package com.netsports.core.sync

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.CommandOutcome
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.ScoreCommand

/**
 * Binary encoding of [Message]s. The layout is specified in
 * `docs/ble-protocol.md`; every platform shares this one implementation.
 *
 * Design notes:
 *  - All integers are big-endian.
 *  - Enums are written as explicit codes, never as ordinals, so reordering a
 *    Kotlin enum cannot silently change the wire format.
 *  - A [Message.Command] is 16 bytes, so it always fits in one Bluetooth
 *    packet even at the minimum packet size.
 *  - Points are packed one bit each, so a full three-set match fits in a few
 *    dozen bytes.
 */
object WireCodec {
    /** Bumped on any incompatible change to the layouts below. */
    const val PROTOCOL_VERSION = 1

    /** Longest device name, in UTF-8 bytes. Longer names are truncated. */
    const val MAX_NAME_BYTES = 24

    /** Largest valid join code. */
    const val MAX_JOIN_CODE = 9999

    private const val TYPE_HELLO = 0x01
    private const val TYPE_COMMAND = 0x02
    private const val TYPE_STATE = 0x10
    private const val TYPE_COMMAND_RESULT = 0x11
    private const val TYPE_JOIN_REJECTED = 0x12
    private const val TYPE_SESSION_ENDED = 0x13

    private const val NO_JOIN_CODE = 0xFFFF

    fun encode(message: Message): ByteArray {
        val out = ByteWriter()
        when (message) {
            is Message.Hello -> {
                val name = truncateUtf8(message.deviceName, MAX_NAME_BYTES)
                out.u8(TYPE_HELLO)
                    .u8(message.protocolVersion)
                    .i64(message.deviceId)
                    .u8(message.deviceKind.code)
                    .u16(message.joinCode ?: NO_JOIN_CODE)
                    .u8(name.size)
                    .bytes(name)
            }
            is Message.Command -> {
                val command = message.command
                out.u8(TYPE_COMMAND)
                    .i64(command.commandId)
                    .u16(command.epoch)
                    .u32(command.baseVersion)
                    .u8(command.action.code)
            }
            is Message.State -> {
                val snapshot = message.snapshot
                out.u8(TYPE_STATE)
                    .i64(snapshot.matchId)
                    .u16(snapshot.epoch)
                    .u32(snapshot.version)
                    .i64(snapshot.lastCommandId)
                    .u8(message.deviceCount)
                writeConfig(out, snapshot.config)
                out.u16(snapshot.points.size).bytes(packPoints(snapshot.points))
            }
            is Message.CommandResult -> {
                out.u8(TYPE_COMMAND_RESULT)
                    .i64(message.commandId)
                    .u8(message.outcome.code)
                    .u32(message.version)
            }
            is Message.JoinRejected -> out.u8(TYPE_JOIN_REJECTED).u8(message.reason.code)
            Message.SessionEnded -> out.u8(TYPE_SESSION_ENDED)
        }
        return out.toByteArray()
    }

    /** @throws ProtocolException if [bytes] is not a valid message. */
    fun decode(bytes: ByteArray): Message {
        val reader = ByteReader(bytes)
        return when (val type = reader.u8()) {
            TYPE_HELLO -> decodeHello(reader)
            TYPE_COMMAND -> {
                val command = ScoreCommand(
                    commandId = reader.i64(),
                    epoch = reader.u16(),
                    baseVersion = reader.u31(),
                    action = actionOf(reader.u8()),
                )
                reader.expectEnd()
                Message.Command(command)
            }
            TYPE_STATE -> decodeState(reader)
            TYPE_COMMAND_RESULT -> {
                val result = Message.CommandResult(
                    commandId = reader.i64(),
                    outcome = outcomeOf(reader.u8()),
                    version = reader.u31(),
                )
                reader.expectEnd()
                result
            }
            TYPE_JOIN_REJECTED -> {
                val rejected = Message.JoinRejected(rejectionOf(reader.u8()))
                reader.expectEnd()
                rejected
            }
            TYPE_SESSION_ENDED -> {
                reader.expectEnd()
                Message.SessionEnded
            }
            else -> throw ProtocolException("unknown message type: $type")
        }
    }

    private fun decodeHello(reader: ByteReader): Message.Hello {
        val protocolVersion = reader.u8()
        // The first two bytes of a hello are fixed for all time. Anything
        // after them may change between versions, so do not try to read the
        // rest of a hello from a different version: the host only needs the
        // version number to turn the device away politely.
        if (protocolVersion != PROTOCOL_VERSION) {
            return Message.Hello(protocolVersion, 0, DeviceKind.PHONE, null, "")
        }
        val deviceId = reader.i64()
        val deviceKind = deviceKindOf(reader.u8())
        val rawCode = reader.u16()
        val joinCode = when {
            rawCode == NO_JOIN_CODE -> null
            rawCode <= MAX_JOIN_CODE -> rawCode
            else -> throw ProtocolException("join code out of range: $rawCode")
        }
        val nameLength = reader.u8()
        if (nameLength > MAX_NAME_BYTES) throw ProtocolException("device name too long: $nameLength")
        val name = reader.bytes(nameLength).decodeToString()
        reader.expectEnd()
        return Message.Hello(protocolVersion, deviceId, deviceKind, joinCode, name)
    }

    private fun decodeState(reader: ByteReader): Message.State {
        val matchId = reader.i64()
        val epoch = reader.u16()
        val version = reader.u31()
        val lastCommandId = reader.i64()
        val deviceCount = reader.u8()
        val config = readConfig(reader)
        val pointCount = reader.u16()
        if (pointCount > MatchSnapshot.MAX_POINTS) throw ProtocolException("too many points: $pointCount")
        val points = unpackPoints(reader.bytes((pointCount + 7) / 8), pointCount)
        reader.expectEnd()
        val snapshot = try {
            MatchSnapshot(matchId, epoch, version, config, points, lastCommandId)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("impossible match state: ${e.message}")
        }
        return Message.State(snapshot, deviceCount)
    }

    // --- Match format: 9 bytes -------------------------------------------

    private fun writeConfig(out: ByteWriter, config: MatchConfig) {
        out.u8(config.sport.code)
            .u8(config.bestOf)
            .u8(config.gamesPerSet)
            .u8(config.deuceRule.code)
            .u8(if (config.setTiebreak) 1 else 0)
            .u8(config.tiebreakPoints)
            .u8(config.finalSetRule.code)
            .u8(config.matchTiebreakPoints)
            .u8(config.firstServer.code)
    }

    private fun readConfig(reader: ByteReader): MatchConfig {
        val sport = sportOf(reader.u8())
        val bestOf = reader.u8()
        val gamesPerSet = reader.u8()
        val deuceRule = deuceRuleOf(reader.u8())
        val setTiebreak = when (val flag = reader.u8()) {
            0 -> false
            1 -> true
            else -> throw ProtocolException("invalid tiebreak flag: $flag")
        }
        val tiebreakPoints = reader.u8()
        val finalSetRule = finalSetRuleOf(reader.u8())
        val matchTiebreakPoints = reader.u8()
        val firstServer = teamOf(reader.u8())
        return try {
            MatchConfig(
                sport = sport,
                bestOf = bestOf,
                gamesPerSet = gamesPerSet,
                deuceRule = deuceRule,
                setTiebreak = setTiebreak,
                tiebreakPoints = tiebreakPoints,
                finalSetRule = finalSetRule,
                matchTiebreakPoints = matchTiebreakPoints,
                firstServer = firstServer,
            )
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("invalid match format: ${e.message}")
        }
    }

    // --- Points: one bit each, least significant bit first; 1 = team B ----

    private fun packPoints(points: List<Team>): ByteArray {
        val packed = ByteArray((points.size + 7) / 8)
        points.forEachIndexed { index, team ->
            if (team == Team.B) packed[index / 8] = (packed[index / 8].toInt() or (1 shl (index % 8))).toByte()
        }
        return packed
    }

    private fun unpackPoints(packed: ByteArray, count: Int): List<Team> = List(count) { index ->
        if ((packed[index / 8].toInt() shr (index % 8)) and 1 == 1) Team.B else Team.A
    }

    /** Cuts [text] to at most [maxBytes] of UTF-8 without splitting a character. */
    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        var end = text.length
        while (true) {
            // Never cut between the two halves of a surrogate pair.
            if (end > 0 && end < text.length && text[end - 1].isHighSurrogate()) end--
            val encoded = text.substring(0, end).encodeToByteArray()
            if (encoded.size <= maxBytes) return encoded
            end--
        }
    }

    // --- Enum codes --------------------------------------------------------

    private val DeviceKind.code: Int
        get() = when (this) {
            DeviceKind.PHONE -> 0
            DeviceKind.WATCH -> 1
        }

    private fun deviceKindOf(code: Int): DeviceKind = when (code) {
        0 -> DeviceKind.PHONE
        1 -> DeviceKind.WATCH
        else -> throw ProtocolException("unknown device kind: $code")
    }

    private val Action.code: Int
        get() = when (this) {
            Action.POINT_A -> 0
            Action.POINT_B -> 1
            Action.UNDO -> 2
        }

    private fun actionOf(code: Int): Action = when (code) {
        0 -> Action.POINT_A
        1 -> Action.POINT_B
        2 -> Action.UNDO
        else -> throw ProtocolException("unknown action: $code")
    }

    private val CommandOutcome.code: Int
        get() = when (this) {
            CommandOutcome.ACCEPTED -> 0
            CommandOutcome.DUPLICATE -> 1
            CommandOutcome.STALE -> 2
            CommandOutcome.MATCH_COMPLETE -> 3
            CommandOutcome.NOTHING_TO_UNDO -> 4
        }

    private fun outcomeOf(code: Int): CommandOutcome = when (code) {
        0 -> CommandOutcome.ACCEPTED
        1 -> CommandOutcome.DUPLICATE
        2 -> CommandOutcome.STALE
        3 -> CommandOutcome.MATCH_COMPLETE
        4 -> CommandOutcome.NOTHING_TO_UNDO
        else -> throw ProtocolException("unknown command outcome: $code")
    }

    private val JoinRejection.code: Int
        get() = when (this) {
            JoinRejection.BAD_CODE -> 0
            JoinRejection.SESSION_FULL -> 1
            JoinRejection.UNSUPPORTED_VERSION -> 2
        }

    private fun rejectionOf(code: Int): JoinRejection = when (code) {
        0 -> JoinRejection.BAD_CODE
        1 -> JoinRejection.SESSION_FULL
        2 -> JoinRejection.UNSUPPORTED_VERSION
        else -> throw ProtocolException("unknown join rejection: $code")
    }

    private val Sport.code: Int
        get() = when (this) {
            Sport.PADEL -> 0
            Sport.TENNIS -> 1
        }

    private fun sportOf(code: Int): Sport = when (code) {
        0 -> Sport.PADEL
        1 -> Sport.TENNIS
        else -> throw ProtocolException("unknown sport: $code")
    }

    private val DeuceRule.code: Int
        get() = when (this) {
            DeuceRule.ADVANTAGE -> 0
            DeuceRule.GOLDEN_POINT -> 1
            DeuceRule.STAR_POINT -> 2
        }

    private fun deuceRuleOf(code: Int): DeuceRule = when (code) {
        0 -> DeuceRule.ADVANTAGE
        1 -> DeuceRule.GOLDEN_POINT
        2 -> DeuceRule.STAR_POINT
        else -> throw ProtocolException("unknown deuce rule: $code")
    }

    private val FinalSetRule.code: Int
        get() = when (this) {
            FinalSetRule.SAME_AS_OTHER_SETS -> 0
            FinalSetRule.ADVANTAGE_SET -> 1
            FinalSetRule.MATCH_TIEBREAK -> 2
        }

    private fun finalSetRuleOf(code: Int): FinalSetRule = when (code) {
        0 -> FinalSetRule.SAME_AS_OTHER_SETS
        1 -> FinalSetRule.ADVANTAGE_SET
        2 -> FinalSetRule.MATCH_TIEBREAK
        else -> throw ProtocolException("unknown final set rule: $code")
    }

    private val Team.code: Int
        get() = when (this) {
            Team.A -> 0
            Team.B -> 1
        }

    private fun teamOf(code: Int): Team = when (code) {
        0 -> Team.A
        1 -> Team.B
        else -> throw ProtocolException("unknown team: $code")
    }
}

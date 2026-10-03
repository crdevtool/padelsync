package com.netsports.core.sync

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.CommandOutcome
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import com.netsports.core.match.ScoreCommand
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WireCodecTest {
    private fun roundTrip(message: Message): Message = WireCodec.decode(WireCodec.encode(message))

    private fun points(count: Int, seed: Int = 7): List<Team> {
        val random = Random(seed)
        return List(count) { if (random.nextBoolean()) Team.A else Team.B }
    }

    /** A long advantage set never completes, so any number of alternating games is valid. */
    private val endless = MatchConfig(Sport.TENNIS, bestOf = 1, setTiebreak = false)

    private fun alternatingGames(count: Int): List<Team> =
        List(count) { index -> if ((index / 4) % 2 == 0) Team.A else Team.B }

    @Test
    fun helloRoundTrips() {
        val hello = Message.Hello(WireCodec.PROTOCOL_VERSION, -123456789012345L, DeviceKind.WATCH, 4821, "Charbel's Watch")
        assertEquals(hello, roundTrip(hello))
        assertEquals(hello.copy(joinCode = null), roundTrip(hello.copy(joinCode = null)))
        assertEquals(hello.copy(joinCode = 0), roundTrip(hello.copy(joinCode = 0)))
    }

    @Test
    fun helloTruncatesLongNamesWithoutSplittingCharacters() {
        val hello = Message.Hello(WireCodec.PROTOCOL_VERSION, 1, DeviceKind.PHONE, null, "Ünïcödé-näme-thät-ïs-wäy-töö-löng 🎾🎾🎾")
        val decoded = roundTrip(hello) as Message.Hello
        assertTrue(decoded.deviceName.encodeToByteArray().size <= WireCodec.MAX_NAME_BYTES)
        assertTrue(hello.deviceName.startsWith(decoded.deviceName))

        val emoji = Message.Hello(WireCodec.PROTOCOL_VERSION, 1, DeviceKind.PHONE, null, "🎾".repeat(10))
        assertEquals("🎾".repeat(6), (roundTrip(emoji) as Message.Hello).deviceName)
    }

    @Test
    fun helloFromAnotherProtocolVersionStillReportsItsVersion() {
        // A future hello with an unknown layout after the version byte.
        val future = byteArrayOf(0x01, 9, 1, 2, 3)
        assertEquals(9, (WireCodec.decode(future) as Message.Hello).protocolVersion)
    }

    @Test
    fun commandRoundTripsAndFitsTheSmallestPacket() {
        for (action in Action.entries) {
            val command = Message.Command(ScoreCommand(Long.MIN_VALUE + 5, 65535, Int.MAX_VALUE, action))
            assertEquals(command, roundTrip(command))
            assertTrue(WireCodec.encode(command).size <= Framing.MIN_PACKET_SIZE - 1)
        }
    }

    @Test
    fun commandResultRoundTrips() {
        for (outcome in CommandOutcome.entries) {
            val result = Message.CommandResult(987654321L, outcome, 250)
            assertEquals(result, roundTrip(result))
        }
    }

    @Test
    fun joinRejectedRoundTrips() {
        for (reason in JoinRejection.entries) {
            assertEquals(Message.JoinRejected(reason), roundTrip(Message.JoinRejected(reason)))
        }
    }

    @Test
    fun sessionEndedRoundTrips() {
        assertEquals(Message.SessionEnded, roundTrip(Message.SessionEnded))
        assertEquals(1, WireCodec.encode(Message.SessionEnded).size)
    }

    @Test
    fun stateRoundTripsForEveryPointCountAroundByteBoundaries() {
        for (count in listOf(0, 1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 300)) {
            val snapshot = MatchSnapshot(77, 3, count + 5, endless, alternatingGames(count), lastCommandId = -9)
            val state = Message.State(snapshot, deviceCount = 8)
            assertEquals(state, roundTrip(state), "count=$count")
        }
    }

    @Test
    fun stateRoundTripsEveryConfigOption() {
        val configs = listOf(
            MatchConfig.padel(),
            MatchConfig.tennis(),
            MatchConfig(
                sport = Sport.PADEL,
                bestOf = 5,
                gamesPerSet = 4,
                deuceRule = DeuceRule.STAR_POINT,
                setTiebreak = false,
                tiebreakPoints = 5,
                finalSetRule = FinalSetRule.MATCH_TIEBREAK,
                matchTiebreakPoints = 7,
                firstServer = Team.B,
            ),
            MatchConfig.tennis().copy(finalSetRule = FinalSetRule.ADVANTAGE_SET, bestOf = 1),
            MatchConfig.tennis().copy(playAllSets = true),
            MatchConfig.tennis().copy(doubles = true),
            MatchConfig.padel().copy(playAllSets = true, doubles = false),
        )
        for (config in configs) {
            val state = Message.State(MatchSnapshot(1, 1, 0, config, emptyList()), deviceCount = 1)
            assertEquals(state, roundTrip(state))
        }
    }

    @Test
    fun stateRoundTripsPlayerNamesServingOrderAndPermission() {
        val rosters = listOf(
            Roster.EMPTY,
            Roster(listOf("Ana"), emptyList()),
            Roster(emptyList(), listOf("Leo", "Mía")),
            Roster(listOf("Ana", "Leo"), listOf("José 🎾", "x".repeat(Roster.MAX_NAME_BYTES))),
        )
        for (roster in rosters) {
            for (bits in 0..7) {
                val snapshot = MatchSnapshot(
                    matchId = 9,
                    epoch = 2,
                    version = 12,
                    config = MatchConfig.padel(),
                    points = alternatingGames(9),
                    lastCommandId = 5,
                    roster = roster,
                    serveFlipA = bits and 1 != 0,
                    serveFlipB = bits and 2 != 0,
                )
                val state = Message.State(snapshot, deviceCount = 4, canScore = bits and 4 != 0)
                assertEquals(state, roundTrip(state), "roster=$roster bits=$bits")
            }
        }
    }

    @Test
    fun everyActionAndOutcomeRoundTrips() {
        for (action in Action.entries) {
            val command = Message.Command(ScoreCommand(3, 1, 7, action))
            assertEquals(command, roundTrip(command))
            assertEquals(16, WireCodec.encode(command).size)
        }
        for (outcome in CommandOutcome.entries) {
            val result = Message.CommandResult(3, outcome, 7)
            assertEquals(result, roundTrip(result))
        }
    }

    @Test
    fun invalidFlagsAndPlayerNamesAreRejected() {
        val named = MatchSnapshot(1, 1, 0, MatchConfig.padel(), emptyList(), roster = Roster(listOf("Ana"), listOf("Leo")))
        val valid = WireCodec.encode(Message.State(named, 1))
        assertEquals(named, (WireCodec.decode(valid) as Message.State).snapshot)

        // A flag bit this version does not know.
        assertFailsWith<ProtocolException> { WireCodec.decode(valid.copyOf().also { it[FLAGS_OFFSET] = 0x08 }) }
        // Play-all-sets must be 0 or 1.
        assertFailsWith<ProtocolException> { WireCodec.decode(valid.copyOf().also { it[CONFIG_OFFSET + 9] = 2 }) }
        // Three players in a team.
        assertFailsWith<ProtocolException> { WireCodec.decode(valid.copyOf().also { it[ROSTER_OFFSET] = 3 }) }
        // A name longer than the limit.
        assertFailsWith<ProtocolException> { WireCodec.decode(valid.copyOf().also { it[ROSTER_OFFSET + 1] = 21 }) }
        // A name made of spaces.
        val blank = valid.copyOf().also { bytes -> for (i in 2..4) bytes[ROSTER_OFFSET + i] = ' '.code.toByte() }
        assertFailsWith<ProtocolException> { WireCodec.decode(blank) }
    }

    @Test
    fun aFullMatchStateIsSmall() {
        // 300 points is a long three-set match.
        val snapshot = MatchSnapshot(1, 1, 300, endless, alternatingGames(300))
        val size = WireCodec.encode(Message.State(snapshot, 8)).size
        assertTrue(size <= 80, "state was $size bytes")
    }

    @Test
    fun truncatedMessagesAreRejected() {
        val messages = listOf(
            Message.Hello(WireCodec.PROTOCOL_VERSION, 1, DeviceKind.PHONE, 1234, "Phone"),
            Message.Command(ScoreCommand(1, 1, 0, Action.POINT_A)),
            Message.State(MatchSnapshot(1, 1, 20, endless, alternatingGames(20)), 2),
            Message.CommandResult(1, CommandOutcome.ACCEPTED, 1),
            Message.JoinRejected(JoinRejection.BAD_CODE),
            Message.SessionEnded,
        )
        for (message in messages) {
            val bytes = WireCodec.encode(message)
            for (length in 0 until bytes.size) {
                // A hello cut to two bytes is still a readable "other version"
                // hello only if the version differs; here it does not.
                assertFailsWith<ProtocolException>("$message cut to $length") {
                    WireCodec.decode(bytes.copyOf(length))
                }
            }
            assertFailsWith<ProtocolException>("$message with trailing byte") {
                WireCodec.decode(bytes + 0)
            }
        }
    }

    @Test
    fun unknownCodesAreRejected() {
        assertFailsWith<ProtocolException> { WireCodec.decode(byteArrayOf(0x7F)) }

        val command = WireCodec.encode(Message.Command(ScoreCommand(1, 1, 0, Action.UNDO)))
        command[command.lastIndex] = 9
        assertFailsWith<ProtocolException> { WireCodec.decode(command) }

        val rejected = WireCodec.encode(Message.JoinRejected(JoinRejection.BAD_CODE))
        rejected[1] = 9
        assertFailsWith<ProtocolException> { WireCodec.decode(rejected) }
    }

    @Test
    fun impossibleStatesAreRejected() {
        val valid = WireCodec.encode(Message.State(MatchSnapshot(1, 1, 0, MatchConfig.padel(), emptyList()), 1))

        // bestOf = 4
        val badConfig = valid.copyOf().also { it[CONFIG_OFFSET + 1] = 4 }
        assertFailsWith<ProtocolException> { WireCodec.decode(badConfig) }

        // epoch = 0
        val badEpoch = valid.copyOf().also { it[9] = 0; it[10] = 0 }
        assertFailsWith<ProtocolException> { WireCodec.decode(badEpoch) }

        // 49 straight points in a match that ends after 48.
        val overrun = WireCodec.encode(
            Message.State(MatchSnapshot(1, 1, 48, MatchConfig.padel(), List(48) { Team.A }), 1),
        )
        overrun[POINT_COUNT_OFFSET + 1] = 49
        assertFailsWith<ProtocolException> { WireCodec.decode(overrun + 0) }
    }

    @Test
    fun randomBytesNeverCrashTheDecoder() {
        val random = Random(2026)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            try {
                WireCodec.decode(bytes)
            } catch (_: ProtocolException) {
                // Expected for almost every input. Anything else fails the test.
            }
        }
    }

    @Test
    fun randomPointsRoundTrip() {
        // Random points in an endless format exercise every bit position.
        val scattered = points(200)
        val snapshot = MatchSnapshot(5, 1, 200, endless.copy(gamesPerSet = 99), scattered)
        assertEquals(scattered, (roundTrip(Message.State(snapshot, 1)) as Message.State).snapshot.points)
    }

    private companion object {
        /** type(1) + matchId(8) + epoch(2) + version(4) + lastCommandId(8) + deviceCount(1) */
        const val FLAGS_OFFSET = 24
        const val CONFIG_OFFSET = FLAGS_OFFSET + 1
        const val ROSTER_OFFSET = CONFIG_OFFSET + 11

        /** With no player names the roster is two zero counts. */
        const val POINT_COUNT_OFFSET = ROSTER_OFFSET + 2
    }
}

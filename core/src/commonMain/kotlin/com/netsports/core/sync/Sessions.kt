package com.netsports.core.sync

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchLog
import com.netsports.core.match.Roster

/**
 * Convenience constructors with no optional or nullable-primitive parameters.
 *
 * Kotlin default arguments and `Int?` do not translate cleanly to Swift, so
 * the Apple apps create everything through this object. It adds no behaviour
 * of its own.
 */
object Sessions {
    /** Value meaning "no join code" wherever a join code is a plain `Int`. */
    const val NO_CODE = -1

    private val ids = RandomIdSource()

    /** A new random, non-zero id for a device or a match. */
    fun createId(): Long = ids.next()

    fun config(
        sport: Sport,
        bestOf: Int,
        deuceRule: DeuceRule,
        finalSetRule: FinalSetRule,
        firstServer: Team,
    ): MatchConfig = MatchConfig(
        sport = sport,
        bestOf = bestOf,
        deuceRule = deuceRule,
        finalSetRule = finalSetRule,
        firstServer = firstServer,
    )

    /** As [config], with the options added for social padel. */
    fun config(
        sport: Sport,
        bestOf: Int,
        deuceRule: DeuceRule,
        finalSetRule: FinalSetRule,
        firstServer: Team,
        playAllSets: Boolean,
        doubles: Boolean,
    ): MatchConfig = MatchConfig(
        sport = sport,
        bestOf = bestOf,
        deuceRule = deuceRule,
        finalSetRule = finalSetRule,
        firstServer = firstServer,
        playAllSets = playAllSets,
        doubles = doubles,
    )

    /**
     * Player names from four form fields. Empty fields are dropped, so pass
     * `""` for a player that was not named or does not exist.
     */
    fun roster(playerA1: String, playerA2: String, playerB1: String, playerB2: String): Roster =
        Roster.of(listOf(playerA1, playerA2), listOf(playerB1, playerB2))

    /** Starts hosting a new match. Pass [NO_CODE] for a court anyone nearby can join. */
    fun host(config: MatchConfig, hostDeviceId: Long, joinCode: Int, nowMillis: Long): HostSession =
        host(config, Roster.EMPTY, hostDeviceId, joinCode, true, nowMillis)

    /**
     * Starts hosting a new match with named players.
     *
     * @param guestsCanScore whether devices that join may change the score.
     */
    fun host(
        config: MatchConfig,
        roster: Roster,
        hostDeviceId: Long,
        joinCode: Int,
        guestsCanScore: Boolean,
        nowMillis: Long,
    ): HostSession = HostSession(
        log = MatchLog.start(ids.next(), config, nowMillis, roster),
        hostDeviceId = hostDeviceId,
        joinCode = codeOrNull(joinCode),
        ids = ids,
        guestsCanScore = guestsCanScore,
    )

    /**
     * Resumes hosting a match saved with [HostSession.savedState].
     * Returns `null` if the saved data cannot be used.
     */
    fun resumeHost(saved: ByteArray, hostDeviceId: Long, joinCode: Int, nowMillis: Long): HostSession? =
        resumeHost(saved, hostDeviceId, joinCode, true, nowMillis)

    /** As [resumeHost], choosing whether devices that join may change the score. */
    fun resumeHost(
        saved: ByteArray,
        hostDeviceId: Long,
        joinCode: Int,
        guestsCanScore: Boolean,
        nowMillis: Long,
    ): HostSession? {
        val snapshot = HostSession.restoreSnapshot(saved) ?: return null
        val log = try {
            MatchLog.takeOver(snapshot, nowMillis)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return HostSession(log, hostDeviceId, codeOrNull(joinCode), ids, guestsCanScore = guestsCanScore)
    }

    /** Creates the guest side of a session. Pass [NO_CODE] if the player entered no code. */
    fun guest(deviceId: Long, deviceName: String, deviceKind: DeviceKind, joinCode: Int): ClientSession =
        ClientSession(deviceId, deviceName, deviceKind, codeOrNull(joinCode), ids)

    private fun codeOrNull(joinCode: Int): Int? = joinCode.takeIf { it in 0..WireCodec.MAX_JOIN_CODE }
}

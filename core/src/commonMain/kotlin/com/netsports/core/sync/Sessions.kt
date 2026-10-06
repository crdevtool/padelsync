package com.netsports.core.sync

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchLog
import com.netsports.core.match.MatchSnapshot
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

    /** A takeover raises the epoch by 1 to this many. */
    private const val TAKEOVER_EPOCH_SPREAD = 64

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
     * As [config], with every choice the setup screen offers.
     *
     * @param setTiebreak whether a set that reaches games-all is settled by a
     * tiebreak; when false it is played on as an advantage set.
     * @param setGamesCap games that win an advantage set outright, or 0 for no limit.
     */
    fun config(
        sport: Sport,
        bestOf: Int,
        gamesPerSet: Int,
        deuceRule: DeuceRule,
        setTiebreak: Boolean,
        setGamesCap: Int,
        finalSetRule: FinalSetRule,
        firstServer: Team,
        playAllSets: Boolean,
        doubles: Boolean,
    ): MatchConfig = MatchConfig(
        sport = sport,
        bestOf = bestOf,
        gamesPerSet = gamesPerSet,
        deuceRule = deuceRule,
        setTiebreak = setTiebreak,
        finalSetRule = finalSetRule,
        firstServer = firstServer,
        playAllSets = playAllSets,
        doubles = doubles,
        setGamesCap = if (setTiebreak) 0 else setGamesCap,
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

    /**
     * Takes over hosting a match from a guest's copy of it, for when the
     * host's device has died or left.
     *
     * The hosting epoch is raised by a random amount rather than by one, so
     * that two players who take over at the same moment almost never end up
     * at the same epoch and their two courts can settle which one continues
     * (see [HostSession.judgeRival]).
     *
     * @param snapshot the guest's last confirmed copy of the match.
     * @param joinCode the code this guest entered to join, reused so that
     * the other guests can follow without typing anything.
     * @return `null` if the match has changed hands too many times to do so again.
     */
    fun takeOver(
        snapshot: MatchSnapshot,
        hostDeviceId: Long,
        joinCode: Int,
        guestsCanScore: Boolean,
        nowMillis: Long,
    ): HostSession? {
        val step = 1 + (ids.next() and Long.MAX_VALUE).mod(TAKEOVER_EPOCH_SPREAD)
        val log = try {
            MatchLog.takeOver(snapshot, nowMillis, step)
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

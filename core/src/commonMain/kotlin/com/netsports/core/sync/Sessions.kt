package com.netsports.core.sync

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchLog

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

    /** Starts hosting a new match. Pass [NO_CODE] for a court anyone nearby can join. */
    fun host(config: MatchConfig, hostDeviceId: Long, joinCode: Int, nowMillis: Long): HostSession =
        HostSession(MatchLog.start(ids.next(), config, nowMillis), hostDeviceId, codeOrNull(joinCode), ids)

    /**
     * Resumes hosting a match saved with [HostSession.savedState].
     * Returns `null` if the saved data cannot be used.
     */
    fun resumeHost(saved: ByteArray, hostDeviceId: Long, joinCode: Int, nowMillis: Long): HostSession? {
        val snapshot = HostSession.restoreSnapshot(saved) ?: return null
        val log = try {
            MatchLog.takeOver(snapshot, nowMillis)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return HostSession(log, hostDeviceId, codeOrNull(joinCode), ids)
    }

    /** Creates the guest side of a session. Pass [NO_CODE] if the player entered no code. */
    fun guest(deviceId: Long, deviceName: String, deviceKind: DeviceKind, joinCode: Int): ClientSession =
        ClientSession(deviceId, deviceName, deviceKind, codeOrNull(joinCode), ids)

    private fun codeOrNull(joinCode: Int): Int? = joinCode.takeIf { it in 0..WireCodec.MAX_JOIN_CODE }
}

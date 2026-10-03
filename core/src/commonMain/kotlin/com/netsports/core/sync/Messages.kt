package com.netsports.core.sync

import com.netsports.core.match.CommandOutcome
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.ScoreCommand

/** The kind of device taking part in a court session. */
enum class DeviceKind { PHONE, WATCH }

/** Why the host refused to let a device join. */
enum class JoinRejection {
    /** The join code was missing or wrong. */
    BAD_CODE,

    /** The session already has the maximum number of devices. */
    SESSION_FULL,

    /** The device runs an incompatible version of the app. */
    UNSUPPORTED_VERSION,
}

/** Everything devices say to each other during a court session. */
sealed class Message {

    /**
     * Guest to host, sent once after connecting.
     *
     * @property joinCode the code the player entered, or `null` if none.
     */
    data class Hello(
        val protocolVersion: Int,
        val deviceId: Long,
        val deviceKind: DeviceKind,
        val joinCode: Int?,
        val deviceName: String,
    ) : Message()

    /** Guest to host: a tap. */
    data class Command(val command: ScoreCommand) : Message()

    /**
     * Host to every guest: the whole match, sent on every change and
     * repeated as a heartbeat. Also acts as the "welcome" after [Hello].
     *
     * @property deviceCount devices in the session, host included.
     * @property canScore whether the receiving device may change the score.
     * The host decides this per device, so two guests can get different values.
     */
    data class State(
        val snapshot: MatchSnapshot,
        val deviceCount: Int,
        val canScore: Boolean = true,
    ) : Message()

    /** Host to the guest that sent a command: what happened to it. */
    data class CommandResult(val commandId: Long, val outcome: CommandOutcome, val version: Int) : Message()

    /** Host to a guest it will not admit. */
    data class JoinRejected(val reason: JoinRejection) : Message()

    /** Host to every guest: the host is closing the court. Do not reconnect. */
    data object SessionEnded : Message()
}

package com.netsports.core.sync

import com.netsports.core.engine.MatchState
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.CommandOutcome
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.ScoreCommand

/** Where a guest stands with the host. */
enum class ClientStatus {
    /** No link to the host. Taps are kept and sent on reconnection. */
    DISCONNECTED,

    /** Linked, hello sent, waiting for the match. */
    JOINING,

    /** In the session and up to date. */
    SYNCED,

    /** The host refused this device; see [ClientSession.rejection]. */
    REJECTED,

    /** The host closed the court. The last score stays on screen. */
    ENDED,
}

/** What became of a tap made on this device. */
enum class TapFeedback {
    /** The host counted it. */
    ACCEPTED,

    /** The score changed before the tap arrived, so it was not counted. */
    SUPERSEDED,

    /** The match was already over. */
    MATCH_COMPLETE,

    /** There was nothing to undo. */
    NOTHING_TO_UNDO,
}

/** What the platform layer must do after calling a [ClientSession] method. */
sealed class ClientEffect {
    /** Write these packets to the host, in order. */
    class Send(val packets: List<ByteArray>) : ClientEffect()

    /** A tap was resolved; use it for haptics or a brief on-screen cue. */
    data class Feedback(val commandId: Long, val feedback: TapFeedback) : ClientEffect()

    /** Drop the link and do not reconnect: the host refused this device or closed the court. */
    data object Disconnect : ClientEffect()
}

/**
 * The guest side of a court session: a phone or watch that scores through
 * the host.
 *
 * Like [HostSession], this class does no I/O. The platform Bluetooth layer
 * reports events and carries out the returned [ClientEffect]s. Call every
 * method from a single thread or serial queue, and refresh the UI from
 * [displayState] and [status] after any call.
 *
 * Taps are optimistic: [displayState] shows a tap immediately, before the
 * host has confirmed it, so the watch never feels laggy. If the host refuses
 * the tap, the display falls back to the host's score and a
 * [ClientEffect.Feedback] says why.
 *
 * Taps are sent to the host one at a time. A second tap made while the first
 * is still unresolved shows on screen at once but is only sent when the
 * first has been accepted. If the first is refused, the second is dropped
 * without ever being sent. This is what guarantees that, for example, a
 * mis-tap followed by a quick undo can never undo another player's point.
 */
class ClientSession(
    private val deviceId: Long,
    private val deviceName: String,
    private val deviceKind: DeviceKind,
    private val joinCode: Int? = null,
    private val ids: IdSource = RandomIdSource(),
) {
    private val reassembler = Reassembler()
    private val pending = ArrayList<ScoreCommand>()
    private var maxPacketSize = Framing.MIN_PACKET_SIZE

    /** Whether the oldest unresolved tap has been sent on the current link. */
    private var headSent = false

    var status: ClientStatus = ClientStatus.DISCONNECTED
        private set

    /** Why the host refused this device, when [status] is [ClientStatus.REJECTED]. */
    var rejection: JoinRejection? = null
        private set

    /** The last match state received from the host, or `null` before the first one. */
    var confirmed: MatchSnapshot? = null
        private set

    /** Devices in the session as last reported by the host, host included. */
    var deviceCount: Int = 0
        private set

    /** Taps made here that the host has not yet resolved. */
    val pendingCount: Int
        get() = pending.size

    /**
     * The score to show: the host's score with this device's unresolved taps
     * applied on top. `null` until the first state arrives.
     */
    val displayState: MatchState?
        get() {
            val snapshot = confirmed ?: return null
            if (pending.isEmpty()) return snapshot.state
            return ScoringEngine.replay(snapshot.config, projectedPoints(snapshot))
        }

    /**
     * The link to the host is up.
     *
     * @param maxPacketSize most bytes one packet to the host may carry.
     */
    fun connected(maxPacketSize: Int): List<ClientEffect> {
        this.maxPacketSize = maxOf(maxPacketSize, Framing.MIN_PACKET_SIZE)
        reassembler.reset()
        status = ClientStatus.JOINING
        rejection = null
        headSent = false
        val hello = Message.Hello(WireCodec.PROTOCOL_VERSION, deviceId, deviceKind, joinCode, deviceName)
        return listOf(send(hello))
    }

    /** The link to the host dropped. Unresolved taps are kept for the next connection. */
    fun disconnected() {
        reassembler.reset()
        headSent = false
        // A refusal or a closed court is final for this link; keep showing it.
        if (status != ClientStatus.REJECTED && status != ClientStatus.ENDED) status = ClientStatus.DISCONNECTED
    }

    /** A packet arrived from the host. */
    fun packetReceived(packet: ByteArray): List<ClientEffect> {
        val bytes = reassembler.accept(packet) ?: return emptyList()
        val message = try {
            WireCodec.decode(bytes)
        } catch (_: ProtocolException) {
            return emptyList()
        }
        return when (message) {
            is Message.State -> onState(message)
            is Message.CommandResult -> onCommandResult(message)
            is Message.JoinRejected -> {
                status = ClientStatus.REJECTED
                rejection = message.reason
                listOf(ClientEffect.Disconnect)
            }
            Message.SessionEnded -> {
                status = ClientStatus.ENDED
                // Nothing still waiting can ever be delivered.
                pending.clear()
                headSent = false
                listOf(ClientEffect.Disconnect)
            }
            // Guest-to-host messages have no meaning when sent to a guest.
            is Message.Hello, is Message.Command -> emptyList()
        }
    }

    /**
     * The player tapped on this device.
     *
     * The tap shows in [displayState] at once. It is sent immediately when
     * synced and no earlier tap is unresolved; otherwise it is held and sent
     * as soon as it can be, including after a reconnection.
     * Taps that cannot apply to the displayed score (a point after match
     * point, undo with nothing scored) are ignored.
     */
    fun submit(action: Action): List<ClientEffect> {
        val snapshot = confirmed ?: return emptyList()
        if (status == ClientStatus.REJECTED || status == ClientStatus.ENDED) return emptyList()

        val points = projectedPoints(snapshot)
        val valid = when (action) {
            Action.UNDO -> points.isNotEmpty()
            else -> points.size < MatchSnapshot.MAX_POINTS && !ScoringEngine.replay(snapshot.config, points).isComplete
        }
        if (!valid) return emptyList()

        // Each queued tap assumes the ones before it were accepted.
        val command = ScoreCommand(ids.next(), snapshot.epoch, snapshot.version + pending.size, action)
        pending += command
        return sendHeadIfDue()
    }

    private fun onState(message: Message.State): List<ClientEffect> {
        val incoming = message.snapshot
        val current = confirmed
        val effects = ArrayList<ClientEffect>()

        val sameLineage = current != null && current.matchId == incoming.matchId && current.epoch == incoming.epoch
        if (current != null && current.matchId == incoming.matchId) {
            // Never go backwards: ignore a former host and out-of-date repeats.
            if (incoming.epoch < current.epoch) return emptyList()
            if (sameLineage && incoming.version < current.version) return emptyList()
        }

        if (sameLineage) {
            // 1. Our oldest unresolved tap is the one that produced this state.
            if (pending.isNotEmpty() && pending.first().commandId == incoming.lastCommandId) {
                effects += ClientEffect.Feedback(pending.removeAt(0).commandId, TapFeedback.ACCEPTED)
                headSent = false
            }
            // 2. Otherwise, if the match moved on without us, every waiting
            //    tap was made against a score that no longer exists. Drop
            //    them all, so that for example a queued undo can never remove
            //    another player's point.
            if (pending.isNotEmpty() && pending.first().baseVersion != incoming.version) {
                effects += dropPending(TapFeedback.SUPERSEDED)
            }
        } else {
            // A different match, or a new host: nothing queued still applies.
            effects += dropPending(TapFeedback.SUPERSEDED)
        }

        confirmed = incoming
        deviceCount = message.deviceCount
        status = ClientStatus.SYNCED

        // Covers the next queued tap after an acceptance, and taps made while
        // offline or cut off mid-flight after a reconnection. If the host had
        // already applied a re-sent tap, it answers DUPLICATE.
        effects += sendHeadIfDue()
        return effects
    }

    /** Sends the oldest unresolved tap if the link is ready and it has not been sent yet. */
    private fun sendHeadIfDue(): List<ClientEffect> {
        if (status != ClientStatus.SYNCED || headSent || pending.isEmpty()) return emptyList()
        headSent = true
        return listOf(send(Message.Command(pending.first())))
    }

    private fun onCommandResult(result: Message.CommandResult): List<ClientEffect> {
        val index = pending.indexOfFirst { it.commandId == result.commandId }
        // Already resolved through a state message: nothing more to do.
        if (index < 0) return emptyList()

        return when (result.outcome) {
            // The state carrying this tap removes it from the queue. If that
            // state was lost in transit the next heartbeat does the same, and
            // keeping the tap until then avoids the score flickering back.
            CommandOutcome.ACCEPTED, CommandOutcome.DUPLICATE -> emptyList()
            CommandOutcome.STALE -> dropPending(TapFeedback.SUPERSEDED)
            CommandOutcome.MATCH_COMPLETE -> dropPending(TapFeedback.MATCH_COMPLETE, result.commandId)
            CommandOutcome.NOTHING_TO_UNDO -> dropPending(TapFeedback.NOTHING_TO_UNDO, result.commandId)
        }
    }

    /**
     * Abandons every unresolved tap. The tap named by [refusedId] is reported
     * with [reason]; the rest depended on it and are reported as superseded.
     */
    private fun dropPending(reason: TapFeedback, refusedId: Long? = null): List<ClientEffect> {
        val effects = pending.map { command ->
            val feedback = if (refusedId == null || command.commandId == refusedId) reason else TapFeedback.SUPERSEDED
            ClientEffect.Feedback(command.commandId, feedback)
        }
        pending.clear()
        headSent = false
        return effects
    }

    /** The host's points with this device's unresolved taps applied. */
    private fun projectedPoints(snapshot: MatchSnapshot): List<Team> {
        if (pending.isEmpty()) return snapshot.points
        val points = snapshot.points.toMutableList()
        for (command in pending) {
            val team = command.action.team
            if (team != null) points += team else if (points.isNotEmpty()) points.removeAt(points.lastIndex)
        }
        return points
    }

    private fun send(message: Message): ClientEffect.Send =
        ClientEffect.Send(Framing.split(WireCodec.encode(message), maxPacketSize))
}

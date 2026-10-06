package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
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

    /**
     * Not counted, because another device had already scored: either the
     * score changed before the tap arrived, or another device scored the same
     * rally moments earlier.
     */
    SUPERSEDED,

    /** The match was already over. */
    MATCH_COMPLETE,

    /** There was nothing to undo. */
    NOTHING_TO_UNDO,

    /** The host has made this device view-only. */
    NOT_ALLOWED,
}

/** What the platform layer must do after calling a [ClientSession] method. */
sealed class ClientEffect {
    /** Write these packets to the host, in order. */
    class Send(val packets: List<ByteArray>) : ClientEffect()

    /** A tap was resolved; use it for haptics or a brief on-screen cue. */
    data class Feedback(val commandId: Long, val feedback: TapFeedback) : ClientEffect()

    /** Drop the link and do not reconnect: the host refused this device or closed the court. */
    data object Disconnect : ClientEffect()

    /**
     * The court found while looking for a lost host is not the one this
     * device was playing on. Drop this link, remember not to try that court
     * again for a while, and keep looking. Only ever raised after
     * [ClientSession.connectedToFoundCourt].
     */
    data object WrongCourt : ClientEffect()
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

    /**
     * True from [connectedToFoundCourt] until the court has proved to be the
     * right one. While it is set, the court's first answer is checked rather
     * than believed.
     */
    private var provingCourt = false

    /**
     * True while proving a court found after the host closed its own. The
     * host that said goodbye has released this device, so the match is
     * accepted from whoever carries it on, at any hosting epoch.
     */
    private var releasedByHost = false

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

    /**
     * Whether the host lets this device change the score. When `false` the
     * device is a scoreboard only, and taps are refused with
     * [TapFeedback.NOT_ALLOWED].
     */
    var canScore: Boolean = true
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
            return projected(snapshot).state(snapshot.config)
        }

    /**
     * Whether [team]'s serving order is shown swapped: the host's value with
     * this device's unresolved swaps applied on top.
     */
    fun displayServeFlip(team: Team): Boolean {
        val snapshot = confirmed ?: return false
        val swap = Action.swapServerFor(team)
        return snapshot.serveFlip(team) != (pending.count { it.action == swap } % 2 == 1)
    }

    /**
     * The link to the host is up.
     *
     * @param maxPacketSize most bytes one packet to the host may carry.
     */
    fun connected(maxPacketSize: Int): List<ClientEffect> {
        provingCourt = false
        releasedByHost = false
        return greet(maxPacketSize)
    }

    /**
     * A link is up to a court that was found by scanning after the host was
     * lost, rather than to the device this session first joined.
     *
     * Phones change their Bluetooth address from time to time, and another
     * player may have taken over as host, so the court that answers to the
     * same name may be the same match on a new address, or somebody else's
     * court altogether. Its first answer decides: it is accepted only if it
     * carries the match this device already holds, at the same hosting epoch
     * or a later one. Anything else (another match, a host left behind by a
     * takeover, a refusal of the join code) yields [ClientEffect.WrongCourt]
     * and leaves this session exactly as it was.
     *
     * A device that has not received a match yet has nothing to compare
     * with, and accepts the court as [connected] would.
     *
     * A device whose host closed the court ([ClientStatus.ENDED]) may look
     * for the match too: another player may have taken it over, or the host
     * may have handed it to another court. Having been released by its host,
     * it accepts the same match at any epoch, and goes back to
     * [ClientStatus.ENDED] if the court turns out to be the wrong one.
     */
    fun connectedToFoundCourt(maxPacketSize: Int): List<ClientEffect> {
        provingCourt = confirmed != null
        releasedByHost = status == ClientStatus.ENDED
        return greet(maxPacketSize)
    }

    private fun greet(maxPacketSize: Int): List<ClientEffect> {
        this.maxPacketSize = maxOf(maxPacketSize, Framing.MIN_PACKET_SIZE)
        reassembler.reset()
        status = ClientStatus.JOINING
        rejection = null
        headSent = false
        val hello = Message.Hello(WireCodec.PROTOCOL_VERSION, deviceId, deviceKind, joinCode, deviceName)
        return listOf(send(hello))
    }

    /**
     * Asks the host to say something, for when it has gone quiet.
     *
     * A live host repeats the match every couple of seconds. Silence means
     * either that its app has stopped while the Bluetooth link stayed up,
     * which the link itself never reports, or that the app is merely
     * suspended (an iPhone in the background), in which case a message from
     * a guest wakes it. So after a few silent seconds the platform layer
     * calls this; if the host still says nothing, it should drop the link
     * and look for the court again.
     *
     * The question is this device's hello sent again, which every host
     * answers with the match. Nothing is sent unless a link is up.
     */
    fun ping(): List<ClientEffect> {
        if (status != ClientStatus.SYNCED && status != ClientStatus.JOINING) return emptyList()
        return listOf(send(Message.Hello(WireCodec.PROTOCOL_VERSION, deviceId, deviceKind, joinCode, deviceName)))
    }

    /** The link to the host dropped. Unresolved taps are kept for the next connection. */
    fun disconnected() {
        reassembler.reset()
        headSent = false
        if (provingCourt && releasedByHost) {
            // The court being tried went away before answering: still "court closed".
            status = ClientStatus.ENDED
        } else if (status != ClientStatus.REJECTED && status != ClientStatus.ENDED) {
            // A refusal or a closed court is final for this link; keep showing it.
            status = ClientStatus.DISCONNECTED
        }
        provingCourt = false
        releasedByHost = false
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
                if (provingCourt) {
                    // A different court that happens to share the name: its
                    // refusal says nothing about the court this device is on.
                    wrongCourt()
                } else {
                    status = ClientStatus.REJECTED
                    rejection = message.reason
                    listOf(ClientEffect.Disconnect)
                }
            }
            Message.SessionEnded -> {
                if (provingCourt) return wrongCourt()
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
     * point, undo with nothing scored) are ignored. On a view-only device
     * nothing is sent and the tap is answered with
     * [TapFeedback.NOT_ALLOWED] straight away.
     */
    fun submit(action: Action): List<ClientEffect> {
        val snapshot = confirmed ?: return emptyList()
        if (status == ClientStatus.REJECTED || status == ClientStatus.ENDED) return emptyList()
        if (!canScore) return listOf(ClientEffect.Feedback(NO_COMMAND, TapFeedback.NOT_ALLOWED))

        val projection = projected(snapshot)
        val points = projection.points
        val over = projection.state(snapshot.config).isComplete
        val valid = when (action) {
            Action.UNDO -> points.isNotEmpty()
            Action.POINT_A, Action.POINT_B -> points.size < MatchSnapshot.MAX_POINTS && !over
            // Only doubles has a serving order to swap.
            Action.SWAP_SERVER_A, Action.SWAP_SERVER_B -> snapshot.config.doubles
            // Only a match with no set end is ended by hand, once something has been played.
            Action.FINISH -> snapshot.config.isOpenEnded && points.isNotEmpty() && !over
        }
        if (!valid) return emptyList()

        // Each queued tap assumes the ones before it were accepted.
        val command = ScoreCommand(ids.next(), snapshot.epoch, snapshot.version + pending.size, action)
        pending += command
        return sendHeadIfDue()
    }

    /** Turns down a court found by scanning, leaving the session as it was before the link came up. */
    private fun wrongCourt(): List<ClientEffect> {
        status = if (releasedByHost) ClientStatus.ENDED else ClientStatus.DISCONNECTED
        provingCourt = false
        releasedByHost = false
        return listOf(ClientEffect.WrongCourt)
    }

    private fun onState(message: Message.State): List<ClientEffect> {
        val incoming = message.snapshot
        val current = confirmed
        val effects = ArrayList<ClientEffect>()

        // A device released by its host takes the match as the new court has
        // it, even from an earlier epoch: there is no host left to prefer.
        var startAfresh = false
        if (provingCourt && current != null) {
            val behind = incoming.epoch < current.epoch ||
                (incoming.epoch == current.epoch && incoming.version < current.version)
            if (incoming.matchId != current.matchId) return wrongCourt()
            if (behind && !releasedByHost) return wrongCourt()
            startAfresh = releasedByHost
            provingCourt = false
            releasedByHost = false
        }

        val sameLineage = !startAfresh &&
            current != null && current.matchId == incoming.matchId && current.epoch == incoming.epoch
        if (!startAfresh && current != null && current.matchId == incoming.matchId) {
            // Never go backwards: ignore a former host and out-of-date repeats.
            if (incoming.epoch < current.epoch) return emptyList()
            if (sameLineage && incoming.version < current.version) return emptyList()
        }

        // The same match at the same point under a later epoch: the host
        // changed (a takeover, a resumed match, a host outranking a rival)
        // but the score this device was looking at did not. Its waiting taps
        // still mean what the player meant, so they are re-addressed to the
        // new epoch instead of being thrown away.
        val onlyTheEpochMoved = !startAfresh && current != null &&
            current.matchId == incoming.matchId && incoming.epoch > current.epoch &&
            incoming.version == current.version && incoming.points == current.points &&
            incoming.serveFlipA == current.serveFlipA && incoming.serveFlipB == current.serveFlipB

        if (onlyTheEpochMoved) {
            // Each goes out as a new command: an answer still on its way to
            // the old one (the host calling it stale) must not be mistaken
            // for an answer to this one.
            for (index in pending.indices) {
                pending[index] = pending[index].copy(commandId = ids.next(), epoch = incoming.epoch)
            }
            headSent = false
        } else if (sameLineage) {
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
        canScore = message.canScore
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
            CommandOutcome.STALE, CommandOutcome.SAME_RALLY -> dropPending(TapFeedback.SUPERSEDED)
            CommandOutcome.MATCH_COMPLETE -> dropPending(TapFeedback.MATCH_COMPLETE, result.commandId)
            CommandOutcome.NOTHING_TO_UNDO -> dropPending(TapFeedback.NOTHING_TO_UNDO, result.commandId)
            // The host withdrew the permission while the tap was on its way.
            CommandOutcome.NOT_ALLOWED -> {
                canScore = false
                dropPending(TapFeedback.NOT_ALLOWED)
            }
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

    /** The match as this device shows it: the points that count and whether it was ended by hand. */
    private class Projection(val points: List<Team>, val finished: Boolean) {
        fun state(config: MatchConfig): MatchState {
            val played = ScoringEngine.replay(config, points)
            return if (finished) ScoringEngine.finish(played) else played
        }
    }

    /** The host's match with this device's unresolved taps applied, as the host would apply them. */
    private fun projected(snapshot: MatchSnapshot): Projection {
        if (pending.isEmpty()) return Projection(snapshot.points, snapshot.finished)
        val points = snapshot.points.toMutableList()
        var finished = snapshot.finished
        for (command in pending) {
            when (command.action) {
                Action.POINT_A -> if (!finished) points += Team.A
                Action.POINT_B -> if (!finished) points += Team.B
                // Undo first takes back the ending of a match ended by hand.
                Action.UNDO -> when {
                    finished -> finished = false
                    points.isNotEmpty() -> points.removeAt(points.lastIndex)
                }
                // Swapping the server does not touch the score.
                Action.SWAP_SERVER_A, Action.SWAP_SERVER_B -> Unit
                Action.FINISH -> if (snapshot.config.isOpenEnded && points.isNotEmpty()) finished = true
            }
        }
        return Projection(points, finished)
    }

    private fun send(message: Message): ClientEffect.Send =
        ClientEffect.Send(Framing.split(WireCodec.encode(message), maxPacketSize))

    companion object {
        /** Command id reported for a tap that was refused without ever being sent. */
        const val NO_COMMAND = 0L
    }
}

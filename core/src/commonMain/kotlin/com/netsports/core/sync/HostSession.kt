package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.MatchState
import com.netsports.core.match.Action
import com.netsports.core.match.CommandOutcome
import com.netsports.core.match.MatchLog
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import com.netsports.core.match.ScoreCommand

/** A device that has joined the session. */
data class PeerInfo(
    /** Transport-level handle of the connection, as given to [HostSession.peerConnected]. */
    val peerId: String,
    val deviceId: Long,
    val deviceKind: DeviceKind,
    val deviceName: String,
    /** Whether the host lets this device change the score. */
    val canScore: Boolean = true,
)

/** Packets the platform Bluetooth layer must deliver to one connected device, in order. */
class Outgoing(val peerId: String, val packets: List<ByteArray>)

/**
 * The host side of a court session: the one device that owns the match and
 * decides which taps count.
 *
 * This class contains no Bluetooth code and does no I/O. The platform layer
 * (Core Bluetooth, Android BLE) tells it what happened (a device connected,
 * a packet arrived, the local player tapped) and it answers with the packets
 * to send. That keeps the whole protocol identical on every platform and
 * testable without radios.
 *
 * Rules of use:
 *  - Call every method from a single thread or serial queue.
 *  - Deliver each [Outgoing.packets] list to its peer in order.
 *  - Call [heartbeat] every couple of seconds while the session is live.
 *  - Refresh the UI from [state] after any call.
 *
 * Input from other devices is never trusted: malformed packets are dropped
 * and cannot make any method throw.
 *
 * @param log the match to host: [MatchLog.start] for a new match, or
 * [MatchLog.takeOver] when taking over from another host.
 * @param hostDeviceId this device's id, recorded against its own taps.
 * @param joinCode code guests must present, or `null` for an open session.
 * @param maxGuests most guests admitted at once. Phones typically sustain
 * about seven Bluetooth LE connections, hence the default.
 * @param guestsCanScore whether a device joining for the first time may
 * change the score. The host can change this later for everyone
 * ([setGuestsCanScore]) or for one device ([setCanScore]).
 */
class HostSession(
    log: MatchLog,
    private val hostDeviceId: Long,
    private val joinCode: Int? = null,
    private val ids: IdSource = RandomIdSource(),
    private val maxGuests: Int = DEFAULT_MAX_GUESTS,
    guestsCanScore: Boolean = true,
) {
    private class Link(var maxPacketSize: Int) {
        val reassembler = Reassembler()
        var info: PeerInfo? = null
    }

    private val links = LinkedHashMap<String, Link>()

    /**
     * The host's explicit choice for individual devices, by device id. It is
     * kept when a device drops off, so a reconnecting watch does not regain a
     * permission the host took away. Devices not listed follow
     * [guestsCanScore].
     */
    private val permissions = HashMap<Long, Boolean>()

    /** Whether devices the host has made no explicit choice for may change the score. */
    var guestsCanScore: Boolean = guestsCanScore
        private set

    /** The authoritative match record. */
    var log: MatchLog = log
        private set

    /** Current score. */
    val state: MatchState
        get() = log.state

    /** Guests admitted to the session, in the order they joined. */
    val guests: List<PeerInfo>
        get() = links.values.mapNotNull { link -> link.info?.let { it.copy(canScore = canScore(it.deviceId)) } }

    /** Whether the device with [deviceId] may currently change the score. */
    fun canScore(deviceId: Long): Boolean = permissions[deviceId] ?: guestsCanScore

    /**
     * Lets one device change the score, or makes it view-only. The choice
     * sticks to the device for the rest of the session.
     */
    fun setCanScore(deviceId: Long, allowed: Boolean): List<Outgoing> {
        permissions[deviceId] = allowed
        return broadcastState()
    }

    /**
     * Lets every guest change the score, or makes them all view-only. This
     * replaces any per-device choices and applies to devices that join later.
     */
    fun setGuestsCanScore(allowed: Boolean): List<Outgoing> {
        guestsCanScore = allowed
        permissions.clear()
        return broadcastState()
    }

    /** Devices in the session, this host included. */
    val deviceCount: Int
        get() = guests.size + 1

    /** What every guest currently sees. */
    fun snapshot(): MatchSnapshot = log.snapshot()

    /**
     * The current match encoded for saving to disk, so a match survives the
     * app being closed. Restore with [restoreSnapshot] and [MatchLog.takeOver].
     */
    fun savedState(): ByteArray = WireCodec.encode(Message.State(log.snapshot(), 1))

    /**
     * A device opened a connection. It is not part of the session until it
     * sends a valid hello.
     *
     * @param maxPacketSize most bytes one packet to this device may carry.
     */
    fun peerConnected(peerId: String, maxPacketSize: Int) {
        links[peerId] = Link(maxOf(maxPacketSize, Framing.MIN_PACKET_SIZE))
    }

    /**
     * The packet size of an existing connection changed, which Android
     * reports separately once the two sides have negotiated it.
     */
    fun peerPacketSizeChanged(peerId: String, maxPacketSize: Int) {
        links[peerId]?.maxPacketSize = maxOf(maxPacketSize, Framing.MIN_PACKET_SIZE)
    }

    /** A device's connection dropped. */
    fun peerDisconnected(peerId: String): List<Outgoing> {
        val link = links.remove(peerId) ?: return emptyList()
        // Tell the others that the device count changed.
        return if (link.info != null) broadcastState() else emptyList()
    }

    /** A packet arrived from a connected device. */
    fun packetReceived(peerId: String, packet: ByteArray, nowMillis: Long): List<Outgoing> {
        val link = links.getOrPut(peerId) { Link(Framing.MIN_PACKET_SIZE) }
        val bytes = link.reassembler.accept(packet) ?: return emptyList()
        val message = try {
            WireCodec.decode(bytes)
        } catch (_: ProtocolException) {
            return emptyList()
        }
        return when (message) {
            is Message.Hello -> onHello(peerId, link, message)
            is Message.Command -> onCommand(peerId, link, message.command, nowMillis)
            // Host-to-guest messages have no meaning when sent to a host.
            is Message.State, is Message.CommandResult, is Message.JoinRejected, Message.SessionEnded -> emptyList()
        }
    }

    /**
     * What became of the most recent [submit]. The host's own tap can be
     * refused too, for example when a guest scored the same rally a moment
     * earlier ([CommandOutcome.SAME_RALLY]).
     */
    var lastSubmitOutcome: CommandOutcome = CommandOutcome.ACCEPTED
        private set

    /** The player holding the host device tapped. The verdict is in [lastSubmitOutcome]. */
    fun submit(action: Action, nowMillis: Long): List<Outgoing> {
        val command = ScoreCommand(ids.next(), log.epoch, log.version, action)
        val result = log.apply(command, hostDeviceId, nowMillis)
        log = result.log
        lastSubmitOutcome = result.outcome
        return if (result.outcome == CommandOutcome.ACCEPTED) broadcastState() else emptyList()
    }

    /**
     * Re-sends the match state to every guest. Call periodically: it repairs
     * anything a guest missed and lets guests notice a silent host.
     */
    fun heartbeat(): List<Outgoing> = broadcastState()

    /**
     * Tells every guest that the court is closing, so they stop trying to
     * reconnect, and forgets them. Send the returned packets before shutting
     * the Bluetooth link down. The match itself is untouched.
     */
    fun endSession(): List<Outgoing> {
        val bytes = WireCodec.encode(Message.SessionEnded)
        val farewell = links.entries
            .filter { it.value.info != null }
            .map { (peerId, link) -> Outgoing(peerId, Framing.split(bytes, link.maxPacketSize)) }
        links.clear()
        return farewell
    }

    /** Replaces the current match with a new one and tells every guest. */
    fun startNewMatch(config: MatchConfig, nowMillis: Long, roster: Roster = Roster.EMPTY): List<Outgoing> {
        log = MatchLog.start(ids.next(), config, nowMillis, roster)
        return broadcastState()
    }

    /** Changes the players' names mid-match and tells every guest. */
    fun updateRoster(roster: Roster): List<Outgoing> {
        log = log.withRoster(roster)
        return broadcastState()
    }

    private fun onHello(peerId: String, link: Link, hello: Message.Hello): List<Outgoing> {
        val rejection = when {
            hello.protocolVersion != WireCodec.PROTOCOL_VERSION -> JoinRejection.UNSUPPORTED_VERSION
            joinCode != null && hello.joinCode != joinCode -> JoinRejection.BAD_CODE
            link.info == null && guests.size >= maxGuests -> JoinRejection.SESSION_FULL
            else -> null
        }
        if (rejection != null) {
            // The guest disconnects itself on receipt. The host does not drop
            // the link, both because iOS cannot and so the reason arrives.
            link.info = null
            return listOf(send(peerId, link, Message.JoinRejected(rejection)))
        }
        link.info = PeerInfo(peerId, hello.deviceId, hello.deviceKind, hello.deviceName)
        // Everyone gets the state: the newcomer needs the match, the rest
        // need the new device count.
        return broadcastState()
    }

    private fun onCommand(peerId: String, link: Link, command: ScoreCommand, nowMillis: Long): List<Outgoing> {
        val info = link.info ?: return emptyList()
        if (!canScore(info.deviceId)) {
            // Refused before the log sees it, so a view-only device cannot
            // even use up a command id.
            return listOf(send(peerId, link, Message.CommandResult(command.commandId, CommandOutcome.NOT_ALLOWED, log.version)))
        }
        val result = log.apply(command, info.deviceId, nowMillis)
        log = result.log
        val reply = send(peerId, link, Message.CommandResult(command.commandId, result.outcome, log.version))
        // State goes out before the result so the sender never sees its tap
        // confirmed while still showing the old score.
        return if (result.outcome == CommandOutcome.ACCEPTED) broadcastState() + reply else listOf(reply)
    }

    private fun broadcastState(): List<Outgoing> {
        val snapshot = log.snapshot()
        val count = deviceCount
        // The message differs between guests only in the permission flag, so
        // it is encoded at most twice however many devices are connected.
        val allowed by lazy { WireCodec.encode(Message.State(snapshot, count, canScore = true)) }
        val viewOnly by lazy { WireCodec.encode(Message.State(snapshot, count, canScore = false)) }
        return links.entries.mapNotNull { (peerId, link) ->
            val info = link.info ?: return@mapNotNull null
            Outgoing(peerId, Framing.split(if (canScore(info.deviceId)) allowed else viewOnly, link.maxPacketSize))
        }
    }

    private fun send(peerId: String, link: Link, message: Message): Outgoing =
        Outgoing(peerId, Framing.split(WireCodec.encode(message), link.maxPacketSize))

    companion object {
        const val DEFAULT_MAX_GUESTS = 7

        /** Decodes [savedState] output, or returns `null` if it is unreadable. */
        fun restoreSnapshot(saved: ByteArray): MatchSnapshot? = try {
            (WireCodec.decode(saved) as? Message.State)?.snapshot
        } catch (_: ProtocolException) {
            null
        }
    }
}

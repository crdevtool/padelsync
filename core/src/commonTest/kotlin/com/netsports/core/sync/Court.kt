package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.match.Action
import com.netsports.core.match.MatchLog

/** Predictable ids, so failures are reproducible. */
class SequentialIds(private var next: Long) : IdSource {
    override fun next(): Long = next++
}

/**
 * A court session with simulated Bluetooth links: one [HostSession], any
 * number of [ClientSession]s, and a queue of packets in flight on each link.
 *
 * Packets on one link arrive in order, as they do over Bluetooth LE, but the
 * test decides when each link delivers. That makes races between devices,
 * lost packets and dropped connections reproducible.
 */
class Court(
    config: MatchConfig = MatchConfig.padel(),
    joinCode: Int? = null,
    maxGuests: Int = HostSession.DEFAULT_MAX_GUESTS,
) {
    var clock = 1_000L
    val host = HostSession(MatchLog.start(1, config, clock), HOST_DEVICE_ID, joinCode, SequentialIds(1_000_000), maxGuests)
    val guests = LinkedHashMap<String, ClientSession>()
    val feedback = HashMap<String, MutableList<TapFeedback>>()

    private val linked = HashSet<String>()
    private val packetSizes = HashMap<String, Int>()
    private val toHost = ArrayDeque<Pair<String, ByteArray>>()
    private val toGuest = HashMap<String, ArrayDeque<ByteArray>>()

    /** Adds a device, connects it and lets the join complete. */
    fun join(
        name: String,
        packetSize: Int = 182,
        kind: DeviceKind = DeviceKind.WATCH,
        code: Int? = null,
    ): ClientSession {
        val number = guests.size + 1
        val session = ClientSession(number.toLong(), name, kind, code, SequentialIds(number * 10_000L))
        guests[name] = session
        packetSizes[name] = packetSize
        feedback[name] = ArrayList()
        toGuest[name] = ArrayDeque()
        connect(name)
        settle()
        return session
    }

    fun isLinked(name: String): Boolean = name in linked

    /** What became of each tap made on [name], oldest first. */
    fun feedbackOf(name: String): List<TapFeedback> = feedback.getValue(name)

    fun connect(name: String) {
        linked += name
        host.peerConnected(name, packetSizes.getValue(name))
        fromGuest(name, guests.getValue(name).connected(packetSizes.getValue(name)))
    }

    /** Drops the link and everything in flight on it. */
    fun disconnect(name: String) {
        if (!linked.remove(name)) return
        toGuest.getValue(name).clear()
        toHost.removeAll { it.first == name }
        guests.getValue(name).disconnected()
        fromHost(host.peerDisconnected(name))
    }

    fun tap(name: String, action: Action) = fromGuest(name, guests.getValue(name).submit(action))

    fun hostTap(action: Action) {
        clock += 1_000
        fromHost(host.submit(action, clock))
    }

    fun heartbeat() = fromHost(host.heartbeat())

    /** The host closes the court. */
    fun endSession() = fromHost(host.endSession())

    fun newMatch(config: MatchConfig) {
        clock += 1_000
        fromHost(host.startNewMatch(config, clock))
    }

    /** Delivers the oldest packet waiting to reach the host. */
    fun deliverOneToHost(): Boolean {
        val (name, packet) = toHost.removeFirstOrNull() ?: return false
        clock += 10
        fromHost(host.packetReceived(name, packet, clock))
        return true
    }

    /** Delivers the oldest packet waiting to reach [name]. */
    fun deliverOneTo(name: String): Boolean {
        val packet = toGuest.getValue(name).removeFirstOrNull() ?: return false
        fromGuest(name, guests.getValue(name).packetReceived(packet))
        return true
    }

    /** Loses the oldest packet waiting to reach [name]. */
    fun dropOneTo(name: String): Boolean = toGuest.getValue(name).removeFirstOrNull() != null

    /** Loses everything waiting to reach [name]. */
    fun dropAllTo(name: String) = toGuest.getValue(name).clear()

    fun pendingToHost(): Int = toHost.size

    /** Delivers packets until every link is idle. */
    fun settle() {
        var progressed = true
        while (progressed) {
            progressed = false
            while (deliverOneToHost()) progressed = true
            for (name in guests.keys.toList()) {
                while (deliverOneTo(name)) progressed = true
            }
        }
    }

    private fun fromHost(outgoing: List<Outgoing>) {
        for (item in outgoing) {
            if (item.peerId in linked) toGuest.getValue(item.peerId).addAll(item.packets)
        }
    }

    private fun fromGuest(name: String, effects: List<ClientEffect>) {
        for (effect in effects) {
            when (effect) {
                is ClientEffect.Send -> if (name in linked) effect.packets.forEach { toHost.addLast(name to it) }
                is ClientEffect.Feedback -> feedback.getValue(name) += effect.feedback
                ClientEffect.Disconnect -> disconnect(name)
            }
        }
    }

    companion object {
        const val HOST_DEVICE_ID = 100L
    }
}

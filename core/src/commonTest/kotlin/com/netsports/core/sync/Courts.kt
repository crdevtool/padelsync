package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.match.Action
import com.netsports.core.match.MatchLog
import com.netsports.core.match.Roster

/**
 * Any number of hosts and guests, with links the test puts up and takes
 * down. Packets are delivered at once: the races between devices are
 * covered by `CourtFuzzTest`; this is about which court a device ends up on.
 */
class Courts {
    var clock = 1_000L
    private val links = HashMap<HostSession, HashMap<String, ClientSession>>()
    private val hostOf = HashMap<ClientSession, HostSession>()
    val effects = HashMap<ClientSession, MutableList<ClientEffect>>()
    private var nextId = 1L

    fun host(
        matchId: Long = 1,
        code: Int? = 1234,
        points: Int = 0,
        deviceId: Long = 100,
    ): HostSession {
        val session = HostSession(
            MatchLog.start(matchId, MatchConfig.padel(), clock, Roster(listOf("Ana", "Leo"), listOf("Mia", "Sam"))),
            deviceId,
            code,
            SequentialIds(deviceId * 1_000_000),
        )
        repeat(points) { tap(session, Action.POINT_A) }
        return session
    }

    fun guest(name: String, code: Int? = 1234): ClientSession {
        val id = nextId++
        return ClientSession(id, name, DeviceKind.WATCH, code, SequentialIds(id * 10_000))
    }

    fun tap(host: HostSession, action: Action) {
        clock += 10_000
        deliver(host, host.submit(action, clock))
    }

    fun tap(guest: ClientSession, action: Action) {
        clock += 10_000
        handle(guest, guest.submit(action))
    }

    /** Links [guest] to [host] as the device it first joined, or one it reconnects to directly. */
    fun join(guest: ClientSession, host: HostSession) = link(guest, host, found = false)

    /** Links [guest] to [host] as a court found by scanning. */
    fun find(guest: ClientSession, host: HostSession) = link(guest, host, found = true)

    private fun link(guest: ClientSession, host: HostSession, found: Boolean) {
        drop(guest)
        val peerId = guest.hashCode().toString()
        links.getOrPut(host) { HashMap() }[peerId] = guest
        hostOf[guest] = host
        host.peerConnected(peerId, 182)
        handle(guest, if (found) guest.connectedToFoundCourt(182) else guest.connected(182))
    }

    /** The link drops, as when a device walks out of range. */
    fun drop(guest: ClientSession) {
        val host = hostOf.remove(guest) ?: return
        val peerId = guest.hashCode().toString()
        links.getValue(host).remove(peerId)
        guest.disconnected()
        deliver(host, host.peerDisconnected(peerId))
    }

    /**
     * The host's device goes out of reach: every link goes with it. If
     * the device is still running, it sees its guests leave.
     */
    fun kill(host: HostSession) {
        for ((peerId, guest) in links.remove(host).orEmpty()) {
            hostOf.remove(guest)
            guest.disconnected()
            host.peerDisconnected(peerId)
        }
    }

    fun isLinked(guest: ClientSession): Boolean = guest in hostOf

    fun effectsOf(guest: ClientSession): List<ClientEffect> = effects[guest].orEmpty()

    private fun deliver(host: HostSession, outgoing: List<Outgoing>) {
        for (item in outgoing) {
            val guest = links[host]?.get(item.peerId) ?: continue
            for (packet in item.packets) handle(guest, guest.packetReceived(packet))
        }
    }

    private fun handle(guest: ClientSession, produced: List<ClientEffect>) {
        for (effect in produced) {
            effects.getOrPut(guest) { ArrayList() } += effect
            when (effect) {
                is ClientEffect.Send -> {
                    val host = hostOf[guest] ?: continue
                    for (packet in effect.packets) {
                        clock += 10
                        deliver(host, host.packetReceived(guest.hashCode().toString(), packet, clock))
                    }
                }
                ClientEffect.WrongCourt, ClientEffect.Disconnect -> drop(guest)
                is ClientEffect.Feedback -> Unit
            }
        }
    }
}

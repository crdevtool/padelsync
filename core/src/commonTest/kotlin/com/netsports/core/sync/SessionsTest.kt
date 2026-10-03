package com.netsports.core.sync

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionsTest {
    /** Passes every packet between one host and one guest until both are quiet. */
    private fun connect(host: HostSession, guest: ClientSession) {
        host.peerConnected("g", 182)
        val queue = ArrayDeque(guest.connected(182))
        while (queue.isNotEmpty()) {
            val effect = queue.removeFirst()
            if (effect !is ClientEffect.Send) continue
            for (packet in effect.packets) {
                for (outgoing in host.packetReceived("g", packet, 0)) {
                    outgoing.packets.forEach { queue.addAll(guest.packetReceived(it)) }
                }
            }
        }
    }

    @Test
    fun configBuildsTheRequestedFormat() {
        val config = Sessions.config(Sport.TENNIS, 5, DeuceRule.STAR_POINT, FinalSetRule.MATCH_TIEBREAK, Team.B)
        assertEquals(
            MatchConfig(Sport.TENNIS, 5, 6, DeuceRule.STAR_POINT, true, 7, FinalSetRule.MATCH_TIEBREAK, 10, Team.B),
            config,
        )
    }

    @Test
    fun aHostWithACodeAdmitsOnlyGuestsWhoKnowIt() {
        val host = Sessions.host(MatchConfig.padel(), 1, joinCode = 4821, nowMillis = 0)

        val right = Sessions.guest(2, "watch", DeviceKind.WATCH, joinCode = 4821)
        connect(host, right)
        assertEquals(ClientStatus.SYNCED, right.status)

        val wrong = Sessions.guest(3, "phone", DeviceKind.PHONE, joinCode = Sessions.NO_CODE)
        connect(host, wrong)
        assertEquals(JoinRejection.BAD_CODE, wrong.rejection)
    }

    @Test
    fun aHostWithoutACodeAdmitsAnyone() {
        val host = Sessions.host(MatchConfig.padel(), 1, Sessions.NO_CODE, 0)
        val guest = Sessions.guest(2, "watch", DeviceKind.WATCH, Sessions.NO_CODE)
        connect(host, guest)
        assertEquals(ClientStatus.SYNCED, guest.status)
    }

    @Test
    fun aSavedMatchResumesAndUnreadableDataDoesNot() {
        val host = Sessions.host(MatchConfig.padel(), 1, 1234, 0)
        repeat(3) { host.submit(Action.POINT_A, 1) }

        val resumed = assertNotNull(Sessions.resumeHost(host.savedState(), 1, 1234, 2))
        assertEquals(host.state, resumed.state)

        assertNull(Sessions.resumeHost(byteArrayOf(9, 9), 1, 1234, 2))
    }

    @Test
    fun idsAreNonZeroAndDistinct() {
        val first = Sessions.createId()
        val second = Sessions.createId()
        assertNotEquals(0L, first)
        assertNotEquals(first, second)
    }
}

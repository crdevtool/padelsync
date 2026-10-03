package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.MatchLog
import com.netsports.core.match.Roster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A guest that has lost its host looks for the court again by name. What it
 * finds may be the same match on a new Bluetooth address, or somebody else's
 * court; these tests cover which it accepts.
 */
class RefindTest {

    @Test
    fun theSameMatchOnANewAddressIsAccepted() {
        val courts = Courts()
        val host = courts.host(points = 3)
        val watch = courts.guest("watch")
        courts.join(watch, host)
        courts.drop(watch)
        // The match moved on while the watch was away.
        courts.tap(host, Action.POINT_B)

        courts.find(watch, host)
        assertEquals(ClientStatus.SYNCED, watch.status)
        assertEquals(host.snapshot(), watch.confirmed)
        assertTrue(courts.isLinked(watch))
        assertFalse(ClientEffect.WrongCourt in courts.effectsOf(watch))

        // And it can score there.
        courts.tap(watch, Action.POINT_A)
        assertEquals(5, host.log.version)
    }

    @Test
    fun aDifferentMatchUnderTheSameNameIsRefused() {
        val courts = Courts()
        val host = courts.host(matchId = 1, points = 3)
        val neighbour = courts.host(matchId = 2, points = 9)
        val watch = courts.guest("watch")
        courts.join(watch, host)
        val held = watch.confirmed
        courts.drop(watch)

        courts.find(watch, neighbour)
        assertEquals(ClientEffect.WrongCourt, courts.effectsOf(watch).last())
        assertFalse(courts.isLinked(watch))
        // Nothing about the watch's own match changed, and it is not "refused":
        // it simply keeps looking.
        assertEquals(held, watch.confirmed)
        assertEquals(ClientStatus.DISCONNECTED, watch.status)
        assertNull(watch.rejection)
        assertEquals(3, watch.displayState?.pointsA)
        // The neighbour admitted it for a moment and then saw it leave.
        assertTrue(neighbour.guests.isEmpty())
    }

    @Test
    fun theSameMatchAtAnEarlierEpochIsRefused() {
        val courts = Courts()
        val stale = courts.host(points = 3)
        // The court the watch is on is a later hosting of the same match.
        val current = HostSession(
            MatchLog.takeOver(stale.snapshot(), courts.clock),
            200,
            1234,
            SequentialIds(200_000_000),
        )
        val watch = courts.guest("watch")
        courts.join(watch, current)
        courts.tap(current, Action.POINT_B)
        val held = watch.confirmed
        courts.drop(watch)

        // The earlier host is still advertising under the same name.
        courts.find(watch, stale)
        assertEquals(ClientEffect.WrongCourt, courts.effectsOf(watch).last())
        assertEquals(held, watch.confirmed)
        assertEquals(2, watch.confirmed?.epoch)
        assertFalse(courts.isLinked(watch))

        courts.find(watch, current)
        assertEquals(ClientStatus.SYNCED, watch.status)
    }

    @Test
    fun aCourtWithADifferentCodeIsNotARefusalOfThisDevice() {
        val courts = Courts()
        val host = courts.host(matchId = 1)
        val neighbour = courts.host(matchId = 2, code = 9999)
        val watch = courts.guest("watch")
        courts.join(watch, host)
        courts.drop(watch)

        courts.find(watch, neighbour)
        assertEquals(ClientEffect.WrongCourt, courts.effectsOf(watch).last())
        assertEquals(ClientStatus.DISCONNECTED, watch.status)
        assertNull(watch.rejection)

        // The right court still takes it back afterwards.
        courts.find(watch, host)
        assertEquals(ClientStatus.SYNCED, watch.status)
    }

    @Test
    fun onTheFirstJoinAWrongCodeIsStillARefusal() {
        val courts = Courts()
        val host = courts.host(code = 1234)
        val watch = courts.guest("watch", code = 1111)
        courts.join(watch, host)
        assertEquals(ClientStatus.REJECTED, watch.status)
        assertEquals(JoinRejection.BAD_CODE, watch.rejection)
    }

    @Test
    fun aDeviceThatNeverGotTheMatchAcceptsTheCourtItFinds() {
        val courts = Courts()
        val host = courts.host(points = 2)
        val watch = courts.guest("watch")
        // Its first connection never completed, so it has nothing to compare with.
        courts.find(watch, host)
        assertEquals(ClientStatus.SYNCED, watch.status)
        assertEquals(host.snapshot(), watch.confirmed)
    }

    @Test
    fun anUnresolvedTapSurvivesAWrongCourtAndReachesTheRightOne() {
        val courts = Courts()
        val host = courts.host(matchId = 1)
        val neighbour = courts.host(matchId = 2)
        val watch = courts.guest("watch")
        courts.join(watch, host)
        courts.drop(watch)
        courts.tap(watch, Action.POINT_B)
        assertEquals(1, watch.pendingCount)

        courts.find(watch, neighbour)
        assertEquals(1, watch.pendingCount)
        assertEquals(0, neighbour.log.version)

        courts.find(watch, host)
        assertEquals(0, watch.pendingCount)
        assertEquals(listOf(Team.B), host.snapshot().points)
    }

    @Test
    fun onceProvedTheCourtMayStartANewMatch() {
        val courts = Courts()
        val host = courts.host(points = 1)
        val watch = courts.guest("watch")
        courts.join(watch, host)
        courts.drop(watch)
        courts.find(watch, host)

        // A new match on an established link is the host's decision, not a wrong court.
        val before = watch.confirmed?.matchId
        host.startNewMatch(MatchConfig.tennis(), courts.clock, Roster.EMPTY)
        host.heartbeat().forEach { item -> item.packets.forEach { watch.packetReceived(it) } }
        assertTrue(watch.confirmed?.matchId != before)
        assertEquals(ClientStatus.SYNCED, watch.status)
    }
}

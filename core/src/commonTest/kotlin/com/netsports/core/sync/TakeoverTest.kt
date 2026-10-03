package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.MatchLog
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A guest taking over as host: the other guests following it, the old host
 * coming back, and two players taking over at the same moment.
 */
class TakeoverTest {

    private fun takeOver(courts: Courts, guest: ClientSession, deviceId: Long, epochStep: Int = 1): HostSession {
        val snapshot = assertNotNull(guest.confirmed)
        return HostSession(
            MatchLog.takeOver(snapshot, courts.clock, epochStep),
            deviceId,
            1234,
            SequentialIds(deviceId * 1_000_000),
        )
    }

    @Test
    fun aGuestTakesOverAndTheMatchCarriesOn() {
        val courts = Courts()
        val host = courts.host(points = 5)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        courts.join(phone, host)
        courts.join(watch, host)
        courts.kill(host)

        val newHost = takeOver(courts, phone, deviceId = 200)
        assertEquals(host.snapshot().points, newHost.snapshot().points)
        assertEquals(host.snapshot().roster, newHost.snapshot().roster)
        assertEquals(host.log.matchId, newHost.log.matchId)
        assertEquals(host.log.epoch + 1, newHost.log.epoch)
        assertEquals(host.log.version, newHost.log.version)

        // The other guest finds the new court under the old name and follows.
        courts.find(watch, newHost)
        assertEquals(ClientStatus.SYNCED, watch.status)
        assertEquals(newHost.snapshot(), watch.confirmed)
        courts.tap(watch, Action.POINT_B)
        courts.tap(newHost, Action.POINT_A)
        assertEquals(newHost.snapshot(), watch.confirmed)
        assertEquals(1, watch.displayState?.pointsB)
    }

    @Test
    fun aTapMadeWhileNobodyWasHostingCountsUnderTheNewHost() {
        val courts = Courts()
        val host = courts.host(points = 2)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        courts.join(phone, host)
        courts.join(watch, host)
        courts.kill(host)
        // Tapped while nobody was hosting.
        courts.tap(watch, Action.POINT_B)

        // The new host carries on from the very score the watch was looking
        // at, so the tap means what the player meant, and counts once.
        val newHost = takeOver(courts, phone, deviceId = 200)
        courts.find(watch, newHost)
        assertEquals(0, watch.pendingCount)
        assertEquals(3, newHost.log.version)
        assertEquals(Team.B, newHost.snapshot().points.last())
        assertEquals(newHost.snapshot(), watch.confirmed)
        assertTrue(courts.effectsOf(watch).any { it is ClientEffect.Feedback && it.feedback == TapFeedback.ACCEPTED })
    }

    @Test
    fun aTapMadeForAScoreTheNewHostHasMovedPastIsDropped() {
        val courts = Courts()
        val host = courts.host(points = 2)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        courts.join(phone, host)
        courts.join(watch, host)
        courts.kill(host)
        courts.tap(watch, Action.POINT_B)

        // The new host scored before the watch found it: the watch's tap was
        // made against a score that no longer exists.
        val newHost = takeOver(courts, phone, deviceId = 200)
        courts.tap(newHost, Action.POINT_A)
        courts.find(watch, newHost)
        assertEquals(0, watch.pendingCount)
        assertEquals(3, newHost.log.version)
        assertEquals(Team.A, newHost.snapshot().points.last())
        assertTrue(courts.effectsOf(watch).any { it is ClientEffect.Feedback && it.feedback == TapFeedback.SUPERSEDED })
    }

    @Test
    fun aTapInFlightWhenTheHostOutranksARivalStillCounts() {
        val courts = Courts()
        val host = courts.host(points = 2)
        val watch = courts.guest("watch")
        courts.join(watch, host)
        val before = host.log.version

        // A new rally, well after the host's last point.
        courts.clock += 10_000
        // The host raises its epoch while the watch's tap is on its way.
        val told = host.outrank(host.log.epoch + 5)
        val inFlight = watch.submit(Action.POINT_A).filterIsInstance<ClientEffect.Send>().flatMap { it.packets }
        // The news of the new epoch reaches the watch first: the tap goes out again, re-addressed.
        told.forEach { item -> item.packets.forEach { courts.deliverTo(watch, it) } }
        assertEquals(before + 1, host.log.version)
        assertEquals(0, watch.pendingCount)
        // Then the original arrives at the host, which calls it stale. That
        // answer must not undo anything or be reported to the player.
        inFlight.forEach { courts.sendToHost(watch, it) }
        assertEquals(before + 1, host.log.version)
        assertEquals(host.snapshot(), watch.confirmed)
        val feedback = courts.effectsOf(watch).filterIsInstance<ClientEffect.Feedback>().map { it.feedback }
        assertEquals(listOf(TapFeedback.ACCEPTED), feedback)
    }

    @Test
    fun aReturningOldHostIsIgnoredByGuestsOfTheNewOne() {
        val courts = Courts()
        val oldHost = courts.host(points = 4)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        courts.join(phone, oldHost)
        courts.join(watch, oldHost)
        courts.kill(oldHost)
        val newHost = takeOver(courts, phone, deviceId = 200)
        courts.find(watch, newHost)
        courts.tap(newHost, Action.POINT_B)
        val current = watch.confirmed

        // The old host is back, advertising under the same name with its old epoch.
        courts.drop(watch)
        courts.find(watch, oldHost)
        assertEquals(ClientEffect.WrongCourt, courts.effectsOf(watch).last())
        assertEquals(current, watch.confirmed)
        assertFalse(courts.isLinked(watch))

        courts.find(watch, newHost)
        assertEquals(ClientStatus.SYNCED, watch.status)
    }

    @Test
    fun aReturningOldHostStandsDownForTheCourtWithThePlayers() {
        val courts = Courts()
        val oldHost = courts.host(points = 4)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        courts.join(phone, oldHost)
        courts.join(watch, oldHost)
        courts.kill(oldHost)
        val newHost = takeOver(courts, phone, deviceId = 200)
        courts.find(watch, newHost)

        // Each host connects to the other for a moment to compare. What it
        // learns is the other's match and device count, the asker included.
        val seenByOld = newHost.snapshot() to newHost.deviceCount + 1
        val seenByNew = oldHost.snapshot() to oldHost.deviceCount + 1
        assertEquals(RivalVerdict.YIELD, oldHost.judgeRival(seenByOld.first, seenByOld.second))
        assertEquals(RivalVerdict.HOLD, newHost.judgeRival(seenByNew.first, seenByNew.second))
        // The new host is already at the later epoch: nothing to raise.
        assertTrue(newHost.outrank(oldHost.log.epoch).isEmpty())

        // The old host then joins as an ordinary guest, with a fresh session.
        val oldHostAsGuest = courts.guest("old host")
        courts.join(oldHostAsGuest, newHost)
        assertEquals(ClientStatus.SYNCED, oldHostAsGuest.status)
        assertEquals(3, newHost.deviceCount)
    }

    @Test
    fun aGuestWhoTookOverByMistakeGivesWayAndNobodyLosesAPoint() {
        val courts = Courts()
        val host = courts.host(points = 4)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        val wanderer = courts.guest("wanderer")
        courts.join(phone, host)
        courts.join(watch, host)
        courts.join(wanderer, host)
        // One player walks out of range and takes over, while the match goes on.
        courts.drop(wanderer)
        val usurper = takeOver(courts, wanderer, deviceId = 300, epochStep = 9)
        courts.tap(host, Action.POINT_A)
        courts.tap(host, Action.POINT_B)

        // The usurper has the later epoch, but the real court has the players.
        assertEquals(RivalVerdict.YIELD, usurper.judgeRival(host.snapshot(), host.deviceCount + 1))
        assertEquals(RivalVerdict.HOLD, host.judgeRival(usurper.snapshot(), usurper.deviceCount + 1))

        // Holding, the real host moves above the usurper's epoch, and its guests follow it there.
        val before = host.snapshot()
        val told = host.outrank(usurper.log.epoch)
        assertEquals(2, told.size)
        assertEquals(usurper.log.epoch + 1, host.log.epoch)
        assertEquals(before.points, host.snapshot().points)
        assertEquals(before.version, host.snapshot().version)
        told.forEach { item ->
            val guest = listOf(phone, watch).first { it.hashCode().toString() == item.peerId }
            item.packets.forEach { guest.packetReceived(it) }
        }
        assertEquals(host.snapshot(), phone.confirmed)
        assertEquals(host.snapshot(), watch.confirmed)
        // Scoring carries on at the new epoch.
        courts.tap(watch, Action.POINT_A)
        assertEquals(before.version + 1, host.log.version)
    }

    @Test
    fun theFollowerOfAHostThatGivesWayIsTakenInByTheCourtThatStays() {
        val courts = Courts()
        val host = courts.host(points = 4)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        val wanderer = courts.guest("wanderer")
        val follower = courts.guest("follower")
        courts.join(phone, host)
        courts.join(watch, host)
        courts.join(wanderer, host)
        courts.join(follower, host)
        // Two players walk off together; one takes over and the other follows.
        courts.drop(wanderer)
        courts.drop(follower)
        val usurper = takeOver(courts, wanderer, deviceId = 300, epochStep = 9)
        courts.find(follower, usurper)
        assertEquals(usurper.log.epoch, follower.confirmed?.epoch)
        courts.tap(host, Action.POINT_A)

        // Back in range, the usurper's court is the smaller one and gives
        // way. It says goodbye, which releases its follower.
        assertEquals(RivalVerdict.YIELD, usurper.judgeRival(host.snapshot(), host.deviceCount + 1))
        courts.close(usurper)
        assertEquals(ClientStatus.ENDED, follower.status)

        // The real host never raised its epoch, and a guest that had only
        // lost its link would refuse it. A released guest takes the match
        // from whoever carries it on.
        assertTrue(host.log.epoch < usurper.log.epoch)
        courts.find(follower, host)
        assertEquals(ClientStatus.SYNCED, follower.status)
        assertEquals(host.snapshot(), follower.confirmed)
        courts.tap(follower, Action.POINT_B)
        assertEquals(host.snapshot(), follower.confirmed)
        assertEquals(Team.B, host.snapshot().points.last())
    }

    @Test
    fun twoPlayersTakingOverAtOnceSettleOnOneCourt() {
        val courts = Courts()
        val host = courts.host(points = 6)
        val guests = List(4) { courts.guest("guest$it") }
        guests.forEach { courts.join(it, host) }
        courts.kill(host)

        // Two of them confirm "host this court" within the same few seconds.
        val first = takeOver(courts, guests[0], deviceId = 200, epochStep = 3)
        val second = takeOver(courts, guests[1], deviceId = 201, epochStep = 7)
        // The other two each find a different one.
        courts.find(guests[2], first)
        courts.find(guests[3], second)

        // Equal numbers: the later epoch keeps the match.
        assertEquals(RivalVerdict.YIELD, first.judgeRival(second.snapshot(), second.deviceCount + 1))
        assertEquals(RivalVerdict.HOLD, second.judgeRival(first.snapshot(), first.deviceCount + 1))

        // The first stands down; its guest loses the link, looks again and is accepted.
        courts.kill(first)
        courts.find(guests[2], second)
        assertEquals(ClientStatus.SYNCED, guests[2].status)
        val firstAsGuest = courts.guest("first as guest")
        courts.join(firstAsGuest, second)
        assertEquals(4, second.deviceCount)
        for (guest in listOf(guests[2], guests[3], firstAsGuest)) {
            assertEquals(second.snapshot(), guest.confirmed)
        }
    }

    @Test
    fun theLargerCourtKeepsTheMatchEvenAtTheEarlierEpoch() {
        val courts = Courts()
        val host = courts.host(points = 6)
        val guests = List(5) { courts.guest("guest$it") }
        guests.forEach { courts.join(it, host) }
        courts.kill(host)
        val first = takeOver(courts, guests[0], deviceId = 200, epochStep = 3)
        val second = takeOver(courts, guests[1], deviceId = 201, epochStep = 7)
        courts.find(guests[2], first)
        courts.find(guests[3], first)
        courts.find(guests[4], second)

        assertEquals(RivalVerdict.HOLD, first.judgeRival(second.snapshot(), second.deviceCount + 1))
        assertEquals(RivalVerdict.YIELD, second.judgeRival(first.snapshot(), first.deviceCount + 1))

        // Without raising its epoch, the larger court would be refused by the
        // smaller court's guest, which has seen a later epoch.
        courts.kill(second)
        courts.find(guests[4], first)
        assertEquals(ClientEffect.WrongCourt, courts.effectsOf(guests[4]).last())

        first.outrank(second.log.epoch)
        courts.find(guests[4], first)
        assertEquals(ClientStatus.SYNCED, guests[4].status)
        assertEquals(first.snapshot(), guests[4].confirmed)
    }

    @Test
    fun equalCourtsAtTheSameEpochAreDecidedByWhoHasRecordedMore() {
        val courts = Courts()
        val host = courts.host(points = 2)
        val a = courts.guest("a")
        val b = courts.guest("b")
        courts.join(a, host)
        courts.join(b, host)
        courts.kill(host)
        val first = takeOver(courts, a, deviceId = 200, epochStep = 5)
        val second = takeOver(courts, b, deviceId = 201, epochStep = 5)

        // Identical in every respect: both keep going until something differs.
        assertEquals(RivalVerdict.HOLD, first.judgeRival(second.snapshot(), second.deviceCount + 1))
        assertEquals(RivalVerdict.HOLD, second.judgeRival(first.snapshot(), first.deviceCount + 1))

        courts.tap(first, Action.POINT_A)
        assertEquals(RivalVerdict.HOLD, first.judgeRival(second.snapshot(), second.deviceCount + 1))
        assertEquals(RivalVerdict.YIELD, second.judgeRival(first.snapshot(), first.deviceCount + 1))
    }

    @Test
    fun anotherMatchUnderTheSameNameIsLeftAlone() {
        val courts = Courts()
        val mine = courts.host(matchId = 1)
        val theirs = courts.host(matchId = 2)
        assertEquals(RivalVerdict.DIFFERENT_MATCH, mine.judgeRival(theirs.snapshot(), 5))
    }

    @Test
    fun takingOverThroughSessionsRaisesTheEpochAndKeepsTheCode() {
        val courts = Courts()
        val host = courts.host(points = 3)
        val phone = courts.guest("phone")
        val watch = courts.guest("watch")
        courts.join(phone, host)
        courts.join(watch, host)
        courts.kill(host)

        val snapshot: MatchSnapshot = assertNotNull(phone.confirmed)
        val steps = HashSet<Int>()
        repeat(50) {
            val taken = assertNotNull(Sessions.takeOver(snapshot, 200, 1234, true, courts.clock))
            val step = taken.log.epoch - snapshot.epoch
            assertTrue(step in 1..64, "step $step")
            steps += step
        }
        // Random, so that two simultaneous takeovers rarely collide.
        assertTrue(steps.size > 10, "only ${steps.size} different steps in 50 takeovers")

        // The join code the guests already entered still opens the court.
        val newHost = assertNotNull(Sessions.takeOver(snapshot, 200, 1234, true, courts.clock))
        courts.find(watch, newHost)
        assertEquals(ClientStatus.SYNCED, watch.status)

        // A match that cannot change hands again says so instead of throwing.
        val exhausted = MatchSnapshot(1, MatchSnapshot.MAX_EPOCH, 0, MatchConfig.padel(), emptyList())
        assertNull(Sessions.takeOver(exhausted, 200, 1234, true, courts.clock))
    }
}

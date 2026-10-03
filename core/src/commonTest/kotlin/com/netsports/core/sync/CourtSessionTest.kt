package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.MatchLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourtSessionTest {

    private fun assertAllInSync(court: Court) {
        val expected = court.host.snapshot()
        for ((name, guest) in court.guests) {
            if (!court.isLinked(name)) continue
            assertEquals(ClientStatus.SYNCED, guest.status, name)
            assertEquals(expected, guest.confirmed, name)
            assertEquals(0, guest.pendingCount, name)
            assertEquals(court.host.state, guest.displayState, name)
        }
    }

    // --- Joining -----------------------------------------------------------

    @Test
    fun aGuestJoinsAndReceivesTheMatchInProgress() {
        val court = Court()
        repeat(5) { court.hostTap(Action.POINT_A) }

        val watch = court.join("watch")
        assertEquals(ClientStatus.SYNCED, watch.status)
        assertEquals(court.host.state, watch.displayState)
        assertEquals(1, watch.displayState?.gamesA)
        assertEquals(2, watch.deviceCount)
        assertEquals("watch", court.host.guests.single().deviceName)
        assertEquals(DeviceKind.WATCH, court.host.guests.single().deviceKind)
    }

    @Test
    fun aGuestShowsNothingBeforeItHasJoined() {
        val guest = ClientSession(1, "watch", DeviceKind.WATCH)
        assertNull(guest.displayState)
        assertEquals(ClientStatus.DISCONNECTED, guest.status)
        assertTrue(guest.submit(Action.POINT_A).isEmpty())
        assertEquals(0, guest.pendingCount)
    }

    @Test
    fun theJoinCodeIsEnforced() {
        val court = Court(joinCode = 4821)

        val wrong = court.join("wrong", code = 1111)
        assertEquals(ClientStatus.REJECTED, wrong.status)
        assertEquals(JoinRejection.BAD_CODE, wrong.rejection)
        assertNull(wrong.displayState)

        val missing = court.join("missing")
        assertEquals(JoinRejection.BAD_CODE, missing.rejection)

        val right = court.join("right", code = 4821)
        assertEquals(ClientStatus.SYNCED, right.status)
        assertEquals(listOf("right"), court.host.guests.map { it.deviceName })
        assertEquals(2, right.deviceCount)
    }

    @Test
    fun aRejectedGuestCannotScore() {
        val court = Court(joinCode = 4821)
        val wrong = court.join("wrong", code = 1111)
        court.tap("wrong", Action.POINT_A)
        court.settle()
        assertEquals(0, court.host.log.version)
        assertEquals(0, wrong.pendingCount)
    }

    @Test
    fun aDeviceThatNeverSaidHelloCannotScore() {
        val court = Court()
        court.host.peerConnected("stranger", 182)
        val command = WireCodec.encode(Message.Command(com.netsports.core.match.ScoreCommand(1, 1, 0, Action.POINT_A)))
        val replies = Framing.split(command, 182).flatMap { court.host.packetReceived("stranger", it, 0) }
        assertTrue(replies.isEmpty())
        assertEquals(0, court.host.log.version)
    }

    @Test
    fun theSessionIsCappedAtSevenGuests() {
        val court = Court()
        repeat(7) { court.join("guest$it") }
        val eighth = court.join("eighth")
        assertEquals(JoinRejection.SESSION_FULL, eighth.rejection)
        assertEquals(8, court.host.deviceCount)

        // A place frees up when someone leaves.
        court.disconnect("guest0")
        court.connect("eighth")
        court.settle()
        assertEquals(ClientStatus.SYNCED, eighth.status)
    }

    @Test
    fun anIncompatibleAppVersionIsTurnedAway() {
        val court = Court()
        court.host.peerConnected("old", 182)
        val replies = court.host.packetReceived("old", Framing.split(byteArrayOf(0x01, 99), 182).single(), 0)
        val reply = WireCodec.decode(Reassembler().accept(replies.single().packets.single())!!)
        assertEquals(Message.JoinRejected(JoinRejection.UNSUPPORTED_VERSION), reply)
        assertTrue(court.host.guests.isEmpty())
    }

    @Test
    fun everyoneLearnsWhenTheDeviceCountChanges() {
        val court = Court()
        val first = court.join("first")
        assertEquals(2, first.deviceCount)
        court.join("second")
        assertEquals(3, first.deviceCount)
        court.disconnect("second")
        court.settle()
        assertEquals(2, first.deviceCount)
    }

    // --- Scoring -----------------------------------------------------------

    @Test
    fun aTapOnAnyDeviceReachesAllEightDevices() {
        val court = Court()
        // Four players, each with a phone and a watch; one phone hosts.
        // Packet sizes cover old and new phones and the Bluetooth minimum.
        court.join("phone2", packetSize = 512, kind = DeviceKind.PHONE)
        court.join("phone3", packetSize = 182, kind = DeviceKind.PHONE)
        court.join("phone4", packetSize = 20, kind = DeviceKind.PHONE)
        court.join("watch1", packetSize = 182)
        court.join("watch2", packetSize = 20)
        court.join("watch3", packetSize = 100)
        court.join("watch4", packetSize = 23)
        assertEquals(8, court.host.deviceCount)

        for ((index, name) in court.guests.keys.withIndex()) {
            court.tap(name, if (index % 2 == 0) Action.POINT_A else Action.POINT_B)
            court.settle()
            assertAllInSync(court)
            assertEquals(index + 1, court.host.state.totalPointsPlayed)
        }
        court.hostTap(Action.POINT_A)
        court.settle()
        assertAllInSync(court)
        assertEquals(8, court.host.state.totalPointsPlayed)
        assertTrue(court.feedback.values.all { it == listOf(TapFeedback.ACCEPTED) })
    }

    @Test
    fun pointsAreAttributedToTheDeviceThatScoredThem() {
        val court = Court()
        court.join("watch")
        court.tap("watch", Action.POINT_B)
        court.settle()
        court.hostTap(Action.POINT_A)
        assertEquals(listOf(1L, Court.HOST_DEVICE_ID), court.host.log.points.map { it.deviceId })
    }

    @Test
    fun aTapShowsImmediatelyBeforeTheHostConfirmsIt() {
        val court = Court()
        val watch = court.join("watch")

        court.tap("watch", Action.POINT_A)
        assertEquals("15", watch.displayState?.pointLabel(Team.A))
        assertEquals(1, watch.pendingCount)
        assertEquals("0", court.host.state.pointLabel(Team.A))

        court.settle()
        assertEquals(0, watch.pendingCount)
        assertEquals(listOf(TapFeedback.ACCEPTED), court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun twoPlayersTappingTheSamePointScoreItOnce() {
        val court = Court()
        val one = court.join("one")
        val two = court.join("two")

        court.tap("one", Action.POINT_A)
        court.tap("two", Action.POINT_A)
        assertEquals(1, one.displayState?.pointsA)
        assertEquals(1, two.displayState?.pointsA)

        court.settle()
        assertEquals(1, court.host.state.pointsA)
        assertEquals(listOf(TapFeedback.ACCEPTED), court.feedbackOf("one"))
        assertEquals(listOf(TapFeedback.SUPERSEDED), court.feedbackOf("two"))
        assertAllInSync(court)
    }

    @Test
    fun hostAndGuestTappingTheSamePointScoreItOnce() {
        val court = Court()
        court.join("watch")
        court.tap("watch", Action.POINT_B)
        court.hostTap(Action.POINT_B)
        court.settle()
        assertEquals(1, court.host.state.pointsB)
        assertEquals(listOf(TapFeedback.SUPERSEDED), court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun aSecondPlayerScoringTheSameRallyMomentsLaterIsRefused() {
        val court = Court()
        court.join("one")
        court.join("two")

        court.tap("one", Action.POINT_A)
        court.settle()
        // "two" has already received the new score, then taps for the same rally.
        court.clock += 1_500
        court.rallyGapMillis = 0
        court.tap("two", Action.POINT_A)
        court.settle()

        assertEquals(1, court.host.state.pointsA, "the rally is counted once")
        assertEquals(listOf(TapFeedback.SUPERSEDED), court.feedbackOf("two"))
        assertAllInSync(court)

        // The host's own late tap for that rally is refused as well.
        court.hostTap(Action.POINT_A)
        assertEquals(1, court.host.state.pointsA)
        assertEquals(com.netsports.core.match.CommandOutcome.SAME_RALLY, court.host.lastSubmitOutcome)

        // A late tap for the other team is refused too: the first report stands.
        court.tap("two", Action.POINT_B)
        court.settle()
        assertEquals(0, court.host.state.pointsB)
    }

    @Test
    fun theNextRallyCountsOnceEnoughTimeHasPassed() {
        val court = Court()
        court.join("one")
        court.join("two")
        court.tap("one", Action.POINT_A)
        court.settle()

        court.clock += com.netsports.core.match.MatchLog.RALLY_WINDOW_MILLIS
        court.rallyGapMillis = 0
        court.tap("two", Action.POINT_A)
        court.settle()
        assertEquals(2, court.host.state.pointsA)
        assertAllInSync(court)
    }

    @Test
    fun onePlayerCanCatchUpSeveralPointsQuickly() {
        val court = Court()
        court.join("one")
        court.rallyGapMillis = 0
        repeat(3) { court.tap("one", Action.POINT_B) }
        court.settle()
        assertEquals(3, court.host.state.pointsB)

        repeat(2) { court.hostTap(Action.POINT_A) }
        court.settle()
        // The host's first tap came right after the guest's and is refused as
        // the same rally; nothing after a refusal slips through either.
        assertEquals(0, court.host.state.pointsA)
        assertAllInSync(court)
    }

    @Test
    fun anotherPlayerCanUndoStraightAway() {
        val court = Court()
        court.join("one")
        court.join("two")
        court.tap("one", Action.POINT_A)
        court.settle()

        court.rallyGapMillis = 0
        court.tap("two", Action.UNDO)
        court.settle()
        assertEquals(0, court.host.state.pointsA)
        assertEquals(listOf(TapFeedback.ACCEPTED), court.feedbackOf("two"))
        assertAllInSync(court)
    }

    @Test
    fun aMisTapAndQuickUndoBothApply() {
        val court = Court()
        val watch = court.join("watch")
        court.hostTap(Action.POINT_B)
        court.settle()

        court.tap("watch", Action.POINT_A)
        court.tap("watch", Action.UNDO)
        assertEquals(0, watch.displayState?.pointsA)
        assertEquals(2, watch.pendingCount)
        assertEquals(1, court.pendingToHost(), "taps are sent one at a time")

        court.settle()
        assertEquals(0, court.host.state.pointsA)
        assertEquals(1, court.host.state.pointsB)
        assertEquals(3, court.host.log.version)
        assertEquals(listOf(TapFeedback.ACCEPTED, TapFeedback.ACCEPTED), court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun aQueuedUndoNeverRemovesAnotherPlayersPoint() {
        val court = Court()
        court.join("one")
        court.join("two")

        // "two" scores the point first; "one" mis-taps the same point and
        // immediately undoes, before hearing about "two".
        court.tap("two", Action.POINT_A)
        court.tap("one", Action.POINT_A)
        court.tap("one", Action.UNDO)

        court.settle()
        assertEquals(1, court.host.state.pointsA, "the legitimate point survives")
        assertEquals(listOf(TapFeedback.SUPERSEDED, TapFeedback.SUPERSEDED), court.feedbackOf("one"))
        assertAllInSync(court)
    }

    @Test
    fun rapidTapsOnOneDeviceAllCount() {
        val court = Court()
        court.join("watch")
        repeat(4) { court.tap("watch", Action.POINT_A) }
        court.settle()
        assertEquals(1, court.host.state.gamesA)
        assertEquals(List(4) { TapFeedback.ACCEPTED }, court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun tapsThatCannotApplyAreIgnoredLocally() {
        val court = Court(MatchConfig.padel().copy(bestOf = 1))
        val watch = court.join("watch")

        court.tap("watch", Action.UNDO)
        assertEquals(0, watch.pendingCount)

        repeat(24) { court.hostTap(Action.POINT_A) }
        court.settle()
        assertEquals(Team.A, watch.displayState?.winner)
        court.tap("watch", Action.POINT_A)
        assertEquals(0, watch.pendingCount)

        // Undo after match point is allowed, to fix a mis-tap.
        court.tap("watch", Action.UNDO)
        court.settle()
        assertNull(court.host.state.winner)
        assertAllInSync(court)
    }

    @Test
    fun aPointRacingTheMatchWinningPointIsRefused() {
        val court = Court(MatchConfig.padel().copy(bestOf = 1))
        court.join("one")
        court.join("two")
        repeat(23) { court.hostTap(Action.POINT_A) }
        court.settle()

        court.tap("one", Action.POINT_A)
        court.tap("two", Action.POINT_A)
        court.settle()
        assertEquals(Team.A, court.host.state.winner)
        assertEquals(listOf(TapFeedback.SUPERSEDED), court.feedbackOf("two"))
        assertAllInSync(court)
    }

    // --- Lost packets and dropped links -----------------------------------

    @Test
    fun aLostStateIsRepairedByTheNextHeartbeat() {
        val court = Court()
        val watch = court.join("watch", packetSize = 20)
        court.hostTap(Action.POINT_A)
        court.dropAllTo("watch")
        assertEquals(0, watch.displayState?.pointsA)

        court.heartbeat()
        court.settle()
        assertAllInSync(court)
    }

    @Test
    fun aPartlyLostStateIsRepairedByTheNextHeartbeat() {
        val court = Court()
        court.join("watch", packetSize = 20)
        court.hostTap(Action.POINT_A)
        court.deliverOneTo("watch")
        court.dropOneTo("watch")
        court.settle()

        court.heartbeat()
        court.settle()
        assertAllInSync(court)
    }

    @Test
    fun anAcceptedTapWhoseConfirmationWasLostDoesNotFlickerOrDouble() {
        val court = Court()
        val watch = court.join("watch")
        court.tap("watch", Action.POINT_A)
        court.deliverOneToHost()
        court.dropAllTo("watch")

        // Still shown optimistically while the confirmation is missing.
        assertEquals(1, watch.displayState?.pointsA)
        assertEquals(1, watch.pendingCount)

        court.heartbeat()
        court.settle()
        assertEquals(1, court.host.state.pointsA)
        assertEquals(listOf(TapFeedback.ACCEPTED), court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun tapsMadeWhileOutOfRangeAreSentOnReconnection() {
        val court = Court()
        val watch = court.join("watch")
        court.disconnect("watch")
        assertEquals(ClientStatus.DISCONNECTED, watch.status)

        court.tap("watch", Action.POINT_A)
        court.tap("watch", Action.POINT_A)
        assertEquals("30", watch.displayState?.pointLabel(Team.A))
        assertEquals(0, court.host.state.pointsA)

        court.connect("watch")
        court.settle()
        assertEquals(2, court.host.state.pointsA)
        assertAllInSync(court)
    }

    @Test
    fun offlineTapsAreDroppedIfTheScoreMovedOnMeanwhile() {
        val court = Court()
        val watch = court.join("watch")
        court.disconnect("watch")

        court.tap("watch", Action.POINT_A)
        court.hostTap(Action.POINT_A)

        court.connect("watch")
        court.settle()
        assertEquals(1, court.host.state.pointsA, "the same point is not counted twice")
        assertEquals(listOf(TapFeedback.SUPERSEDED), court.feedbackOf("watch"))
        assertEquals(0, watch.pendingCount)
        assertAllInSync(court)
    }

    @Test
    fun aTapAppliedJustBeforeTheLinkDroppedIsNotAppliedAgain() {
        val court = Court()
        court.join("watch")
        court.tap("watch", Action.POINT_A)
        court.deliverOneToHost()
        assertEquals(1, court.host.state.pointsA)

        // The link dies before the watch hears back.
        court.disconnect("watch")
        court.connect("watch")
        court.settle()
        assertEquals(1, court.host.state.pointsA)
        assertEquals(listOf(TapFeedback.ACCEPTED), court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun aTapLostWithTheLinkIsSentAgain() {
        val court = Court()
        court.join("watch")
        court.tap("watch", Action.POINT_B)
        // The link dies with the tap still in the air.
        court.disconnect("watch")
        assertEquals(0, court.host.state.pointsB)

        court.connect("watch")
        court.settle()
        assertEquals(1, court.host.state.pointsB)
        assertAllInSync(court)
    }

    @Test
    fun garbageFromADeviceNeverDisturbsTheHost() {
        val court = Court()
        court.join("watch")
        court.hostTap(Action.POINT_A)
        court.settle()
        val before = court.host.snapshot()

        val random = kotlin.random.Random(11)
        repeat(5_000) {
            court.host.packetReceived("watch", random.nextBytes(random.nextInt(0, 40)), 0)
            court.host.packetReceived("nobody", random.nextBytes(random.nextInt(0, 40)), 0)
        }
        // Random bytes can, very rarely, form a valid command. What matters is
        // that nothing throws and the match is still coherent.
        assertTrue(court.host.snapshot().version >= before.version)
        court.heartbeat()
        court.settle()
        assertEquals(court.host.state, court.guests.getValue("watch").displayState)
    }

    @Test
    fun garbageFromTheHostNeverDisturbsAGuest() {
        val court = Court()
        val watch = court.join("watch")
        val random = kotlin.random.Random(12)
        repeat(5_000) { watch.packetReceived(random.nextBytes(random.nextInt(0, 40))) }
        court.heartbeat()
        court.settle()
        assertEquals(ClientStatus.SYNCED, watch.status)
    }

    // --- New match and host handover ---------------------------------------

    @Test
    fun aNewMatchReplacesTheOldOneEverywhere() {
        val court = Court()
        val watch = court.join("watch")
        repeat(3) { court.hostTap(Action.POINT_A) }
        court.settle()

        court.tap("watch", Action.POINT_A)
        court.newMatch(MatchConfig.tennis())
        court.settle()

        assertEquals(MatchConfig.tennis(), watch.displayState?.config)
        assertEquals(0, court.host.state.totalPointsPlayed, "a tap for the old match is not carried over")
        assertEquals(listOf(TapFeedback.SUPERSEDED), court.feedbackOf("watch"))
        assertAllInSync(court)
    }

    @Test
    fun guestsAreToldWhenTheHostClosesTheCourt() {
        val court = Court()
        val one = court.join("one")
        val two = court.join("two")
        court.hostTap(Action.POINT_A)
        court.settle()
        court.tap("one", Action.POINT_B)

        court.endSession()
        court.settle()

        for (guest in listOf(one, two)) {
            assertEquals(ClientStatus.ENDED, guest.status)
            assertEquals(0, guest.pendingCount)
            // The last score stays readable.
            assertEquals(1, guest.displayState?.pointsA)
            assertTrue(guest.submit(Action.POINT_A).isEmpty(), "no scoring after the court has closed")
        }
        assertTrue(court.host.guests.isEmpty())
        assertFalse(court.isLinked("one"), "guests drop the link themselves")
        // The host's own match is untouched and can carry on alone.
        court.hostTap(Action.POINT_A)
        assertEquals(2, court.host.state.pointsA)
    }

    @Test
    fun aGuestCanTakeOverAsHostAndTheOldHostIsIgnored() {
        val court = Court()
        val survivor = court.join("survivor")
        val other = court.join("other")
        repeat(5) { court.hostTap(Action.POINT_A) }
        court.settle()
        val lastFromOldHost = court.host.snapshot()

        // The host's phone dies. "survivor" takes over from its own replica.
        val newHost = HostSession(
            MatchLog.takeOver(survivor.confirmed!!, nowMillis = 9_000),
            hostDeviceId = 1,
            ids = SequentialIds(5_000_000),
        )
        assertEquals(lastFromOldHost.state, newHost.state)
        assertEquals(2, newHost.snapshot().epoch)

        // "other" moves across to the new host.
        other.disconnected()
        newHost.peerConnected("other", 182)
        deliver(other.connected(182), newHost, "other", other)
        assertEquals(newHost.snapshot(), other.confirmed)

        // Scoring carries on.
        deliver(other.submit(Action.POINT_B), newHost, "other", other)
        assertEquals(1, newHost.state.pointsB)
        assertEquals(newHost.state, other.displayState)

        // A late packet from the old host must not roll the score back.
        court.heartbeat()
        val stale = WireCodec.encode(Message.State(lastFromOldHost, 3))
        Framing.split(stale, 182).forEach { other.packetReceived(it) }
        assertEquals(newHost.snapshot(), other.confirmed)
    }

    @Test
    fun aSavedMatchCanBeRestoredAndResumed() {
        val court = Court()
        repeat(6) { court.hostTap(Action.POINT_B) }
        val saved = court.host.savedState()

        val snapshot = HostSession.restoreSnapshot(saved)
        assertEquals(court.host.snapshot(), snapshot)
        val resumed = HostSession(MatchLog.takeOver(snapshot!!, nowMillis = 1), hostDeviceId = 100)
        assertEquals(court.host.state, resumed.state)
        resumed.submit(Action.POINT_B, 2)
        assertEquals(3, resumed.state.pointsB)

        assertNull(HostSession.restoreSnapshot(byteArrayOf(1, 2, 3)))
        assertNull(HostSession.restoreSnapshot(ByteArray(0)))
    }

    @Test
    fun aLargerPacketSizeIsUsedOnceNegotiated() {
        val court = Court()
        court.host.peerConnected("watch", 20)
        court.host.peerPacketSizeChanged("watch", 512)
        val hello = WireCodec.encode(Message.Hello(WireCodec.PROTOCOL_VERSION, 5, DeviceKind.WATCH, null, "watch"))
        val replies = Framing.split(hello, 20).flatMap { court.host.packetReceived("watch", it, 0) }
        assertEquals(1, replies.single().packets.size, "the whole state fits one large packet")
    }

    /** Runs a guest's effects against a host and feeds the replies back, until quiet. */
    private fun deliver(effects: List<ClientEffect>, host: HostSession, peerId: String, guest: ClientSession) {
        val queue = ArrayDeque(effects)
        while (queue.isNotEmpty()) {
            val effect = queue.removeFirst()
            if (effect !is ClientEffect.Send) continue
            for (packet in effect.packets) {
                for (outgoing in host.packetReceived(peerId, packet, 10_000)) {
                    if (outgoing.peerId != peerId) continue
                    outgoing.packets.forEach { queue.addAll(guest.packetReceived(it)) }
                }
            }
        }
    }
}

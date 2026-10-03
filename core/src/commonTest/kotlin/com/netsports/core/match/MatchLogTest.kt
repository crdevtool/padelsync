package com.netsports.core.match

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.PointStake
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatchLogTest {
    private val startedAt = 1_000_000L
    private var sequence = 0L

    private fun newLog(config: MatchConfig = MatchConfig.padel()) = MatchLog.start(42, config, startedAt)

    private fun command(log: MatchLog, action: Action, baseVersion: Int = log.version) =
        ScoreCommand(++sequence, log.epoch, baseVersion, action)

    private fun accept(log: MatchLog, action: Action, deviceId: Long = 7): MatchLog {
        val result = log.apply(command(log, action), deviceId, startedAt + sequence * 1000)
        assertEquals(CommandOutcome.ACCEPTED, result.outcome)
        return result.log
    }

    private fun score(log: MatchLog, action: Action, count: Int): MatchLog =
        (1..count).fold(log) { next, _ -> accept(next, action) }

    @Test
    fun aNewLogIsEmptyAtVersionZero() {
        val log = newLog()
        assertEquals(0, log.version)
        assertEquals(1, log.epoch)
        assertTrue(log.points.isEmpty())
        assertEquals(ScoringEngine.start(MatchConfig.padel()), log.state)
        assertNull(log.completedAtMillis)
        assertNull(log.durationMillis)
    }

    @Test
    fun anAcceptedPointAdvancesTheScoreAndTheVersion() {
        val log = accept(newLog(), Action.POINT_A, deviceId = 9)
        assertEquals(1, log.version)
        assertEquals(Team.A, log.points.single().team)
        assertEquals(9, log.points.single().deviceId)
        assertEquals("15", log.state.pointLabel(Team.A))
    }

    @Test
    fun applyDoesNotMutateTheOriginalLog() {
        val original = newLog()
        accept(original, Action.POINT_A)
        assertEquals(0, original.version)
        assertTrue(original.points.isEmpty())
    }

    @Test
    fun twoDevicesTappingTheSamePointScoreItOnce() {
        val log = newLog()
        val fromPhone = command(log, Action.POINT_A)
        val fromWatch = command(log, Action.POINT_A)

        val afterFirst = log.apply(fromPhone, 1, startedAt).log
        val second = afterFirst.apply(fromWatch, 2, startedAt)

        assertEquals(CommandOutcome.STALE, second.outcome)
        assertSame(afterFirst, second.log)
        assertEquals(1, second.log.state.pointsA)
    }

    @Test
    fun aRetransmittedCommandIsAHarmlessDuplicate() {
        val log = newLog()
        val command = command(log, Action.POINT_B)
        val afterFirst = log.apply(command, 1, startedAt).log

        val again = afterFirst.apply(command, 1, startedAt)
        assertEquals(CommandOutcome.DUPLICATE, again.outcome)
        assertEquals(1, again.log.version)
        assertEquals(1, again.log.state.pointsB)
    }

    @Test
    fun aCommandFromAnotherEpochIsStale() {
        val log = newLog()
        val result = log.apply(ScoreCommand(1, epoch = 2, baseVersion = 0, action = Action.POINT_A), 1, startedAt)
        assertEquals(CommandOutcome.STALE, result.outcome)
    }

    @Test
    fun aPointAfterTheMatchHasEndedIsRefusedNotThrown() {
        val log = score(newLog(MatchConfig.padel().copy(bestOf = 1)), Action.POINT_A, 24)
        assertEquals(Team.A, log.state.winner)
        assertEquals(CommandOutcome.MATCH_COMPLETE, log.apply(command(log, Action.POINT_A), 1, 0).outcome)
    }

    @Test
    fun recordsCompletionTimeAndDuration() {
        val log = score(newLog(MatchConfig.padel().copy(bestOf = 1)), Action.POINT_A, 24)
        assertEquals(startedAt + 24_000, log.completedAtMillis)
        assertEquals(24_000, log.durationMillis)
    }

    @Test
    fun undoRemovesTheLastPointAndStillAdvancesTheVersion() {
        var log = score(newLog(), Action.POINT_A, 2)
        log = accept(log, Action.UNDO)
        assertEquals(3, log.version)
        assertEquals(1, log.points.size)
        assertEquals("15", log.state.pointLabel(Team.A))
    }

    @Test
    fun undoWithNothingToUndoIsRefused() {
        val log = newLog()
        assertEquals(CommandOutcome.NOTHING_TO_UNDO, log.apply(command(log, Action.UNDO), 1, 0).outcome)
    }

    @Test
    fun undoStepsBackAcrossAGameBoundary() {
        var log = score(newLog(), Action.POINT_A, 4)
        assertEquals(1, log.state.gamesA)
        assertEquals(Team.B, log.state.server)

        log = accept(log, Action.UNDO)
        assertEquals(0, log.state.gamesA)
        assertEquals("40", log.state.pointLabel(Team.A))
        assertEquals(Team.A, log.state.server)
    }

    @Test
    fun undoStepsBackAcrossASetBoundary() {
        var log = score(newLog(), Action.POINT_A, 24)
        assertEquals(1, log.state.completedSets.size)

        log = accept(log, Action.UNDO)
        assertTrue(log.state.completedSets.isEmpty())
        assertEquals(5, log.state.gamesA)
        assertEquals("40", log.state.pointLabel(Team.A))
    }

    @Test
    fun undoReopensAFinishedMatch() {
        var log = score(newLog(MatchConfig.padel().copy(bestOf = 1)), Action.POINT_A, 24)
        assertTrue(log.state.isComplete)

        log = accept(log, Action.UNDO)
        assertFalse(log.state.isComplete)
        assertNull(log.completedAtMillis)
        assertEquals(PointStake.MATCH_POINT, ScoringEngine.stakeFor(log.state, Team.A))
    }

    @Test
    fun aStaleUndoIsRefused() {
        val log = score(newLog(), Action.POINT_A, 2)
        val staleUndo = command(log, Action.UNDO)
        val advanced = accept(log, Action.POINT_B)

        val result = advanced.apply(staleUndo, 1, 0)
        assertEquals(CommandOutcome.STALE, result.outcome)
        assertEquals(3, result.log.points.size)
    }

    @Test
    fun snapshotCarriesThePointsVersionAndLastCommand() {
        var log = score(newLog(), Action.POINT_A, 3)
        log = accept(log, Action.POINT_B)
        val snapshot = log.snapshot()
        assertEquals(42, snapshot.matchId)
        assertEquals(1, snapshot.epoch)
        assertEquals(4, snapshot.version)
        assertEquals(listOf(Team.A, Team.A, Team.A, Team.B), snapshot.points)
        assertEquals(sequence, snapshot.lastCommandId)
        assertEquals(log.state, snapshot.state)
    }

    @Test
    fun takeOverRaisesTheEpochAndKeepsTheScoreAndVersion() {
        var log = score(newLog(), Action.POINT_A, 5)
        log = accept(log, Action.UNDO)
        val snapshot = log.snapshot()

        val takenOver = MatchLog.takeOver(snapshot, nowMillis = 5_000_000)
        assertEquals(2, takenOver.epoch)
        assertEquals(snapshot.version, takenOver.version)
        assertEquals(snapshot.state, takenOver.state)
        assertEquals(snapshot.matchId, takenOver.matchId)

        // Commands addressed to the old host are no longer accepted.
        assertEquals(CommandOutcome.STALE, takenOver.apply(command(log, Action.POINT_A), 1, 0).outcome)
        // Commands addressed to the new host are.
        assertEquals(CommandOutcome.ACCEPTED, takenOver.apply(command(takenOver, Action.POINT_A), 1, 0).outcome)
    }

    @Test
    fun snapshotRejectsInconsistentValues() {
        val config = MatchConfig.padel()
        assertFailsWith<IllegalArgumentException> { MatchSnapshot(1, 1, 0, config, listOf(Team.A)) }
        assertFailsWith<IllegalArgumentException> { MatchSnapshot(1, 0, 0, config, emptyList()) }
        assertFailsWith<IllegalArgumentException> { MatchSnapshot(1, 1, 100, config, List(49) { Team.A }) }
    }
}

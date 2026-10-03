package com.netsports.core.match

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.MatchState
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team

/** A point that currently counts towards the score, with who recorded it and when. */
data class PointRecord(
    val team: Team,
    val commandId: Long,
    val deviceId: Long,
    val atMillis: Long,
)

/** Outcome of [MatchLog.apply]. [log] is always the log to use from now on. */
data class ApplyResult(val log: MatchLog, val outcome: CommandOutcome)

/**
 * The host's authoritative record of a match.
 *
 * Only the device hosting the court session owns a [MatchLog]; it is the one
 * place where commands are accepted or refused. Everything other devices need
 * is exported as a [MatchSnapshot]. The log additionally keeps who recorded
 * each point and when, for match history.
 *
 * Instances are immutable; [apply] returns a new log.
 */
class MatchLog private constructor(
    val matchId: Long,
    val epoch: Int,
    val config: MatchConfig,
    val startedAtMillis: Long,
    /** Points that currently count, oldest first. Undone points are removed. */
    val points: List<PointRecord>,
    /** Number of commands accepted so far (points and undos). */
    val version: Int,
    private val appliedCommandIds: Set<Long>,
    private val lastCommandId: Long,
    /** Current score, derived from [points]. */
    val state: MatchState,
) {
    /** When the match was won, or `null` while it is in progress. */
    val completedAtMillis: Long?
        get() = if (state.isComplete) points.last().atMillis else null

    /** Playing time of a finished match, or `null` while it is in progress. */
    val durationMillis: Long?
        get() = completedAtMillis?.let { it - startedAtMillis }

    /** The replicated view of this log, as sent to every other device. */
    fun snapshot(): MatchSnapshot = MatchSnapshot(
        matchId = matchId,
        epoch = epoch,
        version = version,
        config = config,
        points = points.map { it.team },
        lastCommandId = lastCommandId,
    )

    /**
     * Validates [command] and applies it if acceptable. Never throws: every
     * case is reported through [ApplyResult.outcome].
     *
     * @param deviceId device that raised the command, recorded for history.
     * @param atMillis host clock time, recorded for history and duration.
     */
    fun apply(command: ScoreCommand, deviceId: Long, atMillis: Long): ApplyResult {
        // Checked first so a retransmission of an applied command is reported
        // as a harmless duplicate rather than as a stale version.
        if (command.commandId in appliedCommandIds) return ApplyResult(this, CommandOutcome.DUPLICATE)

        if (command.epoch != epoch || command.baseVersion != version) {
            return ApplyResult(this, CommandOutcome.STALE)
        }

        val team = command.action.team
        if (team != null) {
            if (state.isComplete) return ApplyResult(this, CommandOutcome.MATCH_COMPLETE)
            if (isSameRally(deviceId, atMillis)) return ApplyResult(this, CommandOutcome.SAME_RALLY)
            if (points.size >= MatchSnapshot.MAX_POINTS) return ApplyResult(this, CommandOutcome.MATCH_COMPLETE)
            val record = PointRecord(team, command.commandId, deviceId, atMillis)
            return accepted(command, points + record, ScoringEngine.pointWonBy(state, team))
        }

        if (points.isEmpty()) return ApplyResult(this, CommandOutcome.NOTHING_TO_UNDO)
        // Undo is allowed after match point too, to recover from a mis-tap.
        // Replaying from the start is the simplest way to step back across a
        // game or set boundary, and a match is only a few hundred points.
        val remaining = points.dropLast(1)
        return accepted(command, remaining, ScoringEngine.replay(config, remaining.map { it.team }))
    }

    /**
     * Whether a point arriving now from [deviceId] is the previous rally
     * being reported a second time.
     *
     * Players do not tap at the same instant: one scores the point, and a
     * second or two later a partner, who has not looked at their own screen
     * yet, scores it too. By then the second device already holds the new
     * score, so the version check cannot catch it. Two points from different
     * devices cannot genuinely be [RALLY_WINDOW_MILLIS] apart, so the second
     * is refused. Several quick points from one device are allowed: that is
     * a player catching up the score on purpose. Undo is never refused.
     */
    private fun isSameRally(deviceId: Long, atMillis: Long): Boolean {
        val last = points.lastOrNull() ?: return false
        if (last.deviceId == deviceId || last.deviceId == UNKNOWN_DEVICE) return false
        val elapsed = atMillis - last.atMillis
        return elapsed in 0 until RALLY_WINDOW_MILLIS
    }

    private fun accepted(command: ScoreCommand, points: List<PointRecord>, state: MatchState) = ApplyResult(
        MatchLog(
            matchId = matchId,
            epoch = epoch,
            config = config,
            startedAtMillis = startedAtMillis,
            points = points,
            version = version + 1,
            appliedCommandIds = appliedCommandIds + command.commandId,
            lastCommandId = command.commandId,
            state = state,
        ),
        CommandOutcome.ACCEPTED,
    )

    companion object {
        /** Device id recorded for points inherited from a previous host. */
        const val UNKNOWN_DEVICE = 0L

        /**
         * Shortest time that can pass between two real points scored on
         * different devices. Even an ace followed by a quick next serve takes
         * longer than this.
         */
        const val RALLY_WINDOW_MILLIS = 4_000L

        /** Starts an empty log for a new match. */
        fun start(matchId: Long, config: MatchConfig, startedAtMillis: Long): MatchLog = MatchLog(
            matchId = matchId,
            epoch = 1,
            config = config,
            startedAtMillis = startedAtMillis,
            points = emptyList(),
            version = 0,
            appliedCommandIds = emptySet(),
            lastCommandId = 0,
            state = ScoringEngine.start(config),
        )

        /**
         * Continues a match from a replica, when this device takes over as
         * host. The epoch is raised so that every device prefers the new host
         * over the old one, and the version carries on from where it was.
         *
         * Per-point history from the old host is not available to a replica,
         * so inherited points are stamped with [nowMillis] and
         * [UNKNOWN_DEVICE].
         *
         * @throws IllegalArgumentException if the epoch cannot be raised further.
         */
        fun takeOver(snapshot: MatchSnapshot, nowMillis: Long): MatchLog {
            require(snapshot.epoch < MatchSnapshot.MAX_EPOCH) { "hosting epoch exhausted" }
            return MatchLog(
                matchId = snapshot.matchId,
                epoch = snapshot.epoch + 1,
                config = snapshot.config,
                startedAtMillis = nowMillis,
                points = snapshot.points.map { PointRecord(it, 0, UNKNOWN_DEVICE, nowMillis) },
                version = snapshot.version,
                appliedCommandIds = emptySet(),
                lastCommandId = 0,
                state = snapshot.state,
            )
        }
    }
}

package com.netsports.core.match

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.MatchState
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team

/**
 * The complete replicated state of a match: the format plus the ordered list
 * of points that currently count. It is small enough (one bit per point on
 * the wire) that the host sends the whole thing on every change, which makes
 * replication idempotent and self-healing: any device that receives the
 * latest snapshot is fully up to date, whatever it missed before.
 *
 * The score ([state]) is always derived by replaying [points]; it is never
 * transmitted or stored independently.
 *
 * @property matchId Random id of the match.
 * @property epoch Hosting epoch. Starts at 1 and increases each time another
 * device takes over as host, so replicas can ignore a stale former host.
 * @property version Number of commands accepted so far (points and undos).
 * Strictly increasing within an epoch.
 * @property lastCommandId Id of the command that produced this version, or 0.
 * Lets a device recognise that its own in-flight command has landed.
 * @throws IllegalArgumentException if the values are inconsistent.
 */
data class MatchSnapshot(
    val matchId: Long,
    val epoch: Int,
    val version: Int,
    val config: MatchConfig,
    val points: List<Team>,
    val lastCommandId: Long = 0,
) {
    /** Current score. Computed eagerly so an impossible snapshot fails at construction. */
    val state: MatchState = ScoringEngine.replay(config, points)

    init {
        require(epoch in 1..MAX_EPOCH) { "epoch must be in 1..$MAX_EPOCH, was $epoch" }
        require(points.size <= MAX_POINTS) { "too many points: ${points.size}" }
        // Every surviving point needed at least one accepted command.
        require(version >= points.size) { "version $version is lower than the ${points.size} recorded points" }
    }

    companion object {
        const val MAX_EPOCH = 0xFFFF
        const val MAX_POINTS = 4096
    }
}

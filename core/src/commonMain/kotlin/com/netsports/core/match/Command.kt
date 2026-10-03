package com.netsports.core.match

import com.netsports.core.engine.Team

/** What a player can do to the score. */
enum class Action {
    POINT_A,
    POINT_B,
    UNDO;

    /** The team this action scores for, or `null` for [UNDO]. */
    val team: Team?
        get() = when (this) {
            POINT_A -> Team.A
            POINT_B -> Team.B
            UNDO -> null
        }

    companion object {
        fun pointFor(team: Team): Action = if (team == Team.A) POINT_A else POINT_B
    }
}

/**
 * An intent to change the match, raised by any phone or watch on the court.
 *
 * @property commandId Random id, unique per command, so a retransmission is
 * recognised and applied at most once.
 * @property epoch Hosting epoch the sender was synced to (see [MatchLog.epoch]).
 * @property baseVersion Version of the match the sender was looking at. The
 * host accepts a command only if this is its current version, which is what
 * stops two players tapping the same point from scoring it twice.
 */
data class ScoreCommand(
    val commandId: Long,
    val epoch: Int,
    val baseVersion: Int,
    val action: Action,
)

/** The host's verdict on a command. */
enum class CommandOutcome {
    /** Applied. */
    ACCEPTED,

    /** Already applied earlier (a retransmission). Nothing changed. */
    DUPLICATE,

    /** The sender was looking at an outdated score. Nothing changed. */
    STALE,

    /** A point was sent for a match that already has a winner. */
    MATCH_COMPLETE,

    /** Undo was requested with no points recorded. */
    NOTHING_TO_UNDO,

    /**
     * Another device scored a point moments ago, so this point is taken to be
     * the same rally reported twice. Nothing changed.
     */
    SAME_RALLY,
}

package com.netsports.core.match

import com.netsports.core.engine.Team

/** What a player can do to the match. */
enum class Action {
    POINT_A,
    POINT_B,
    UNDO,

    /**
     * Swap which of team A's two players is shown as serving. Players are
     * free to choose their serving order at the start of each set, so the
     * app's guess sometimes needs correcting.
     */
    SWAP_SERVER_A,

    /** As [SWAP_SERVER_A], for team B. */
    SWAP_SERVER_B,

    /**
     * End a match that has no set end (a timed match). The score as it
     * stands becomes the result. [UNDO] takes it back.
     */
    FINISH;

    /** The team this action scores for, or `null` if it is not a point. */
    val team: Team?
        get() = when (this) {
            POINT_A -> Team.A
            POINT_B -> Team.B
            UNDO, SWAP_SERVER_A, SWAP_SERVER_B, FINISH -> null
        }

    val isPoint: Boolean
        get() = this == POINT_A || this == POINT_B

    companion object {
        fun pointFor(team: Team): Action = if (team == Team.A) POINT_A else POINT_B

        fun swapServerFor(team: Team): Action = if (team == Team.A) SWAP_SERVER_A else SWAP_SERVER_B
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

    /** The host has not allowed this device to change the score. Nothing changed. */
    NOT_ALLOWED,
}

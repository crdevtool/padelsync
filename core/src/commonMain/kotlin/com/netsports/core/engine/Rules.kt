package com.netsports.core.engine

/** One side of the net. A "team" is a single player in singles. */
enum class Team {
    A,
    B;

    val opponent: Team
        get() = if (this == A) B else A
}

enum class Sport { PADEL, TENNIS }

/**
 * How a standard game is resolved once it reaches deuce (40-40).
 *
 * @property decidingDeuce 1-based index of the deuce that is settled by a
 * single sudden-death point, or `null` when advantage is played indefinitely.
 */
enum class DeuceRule(val decidingDeuce: Int?) {
    /** Classic advantage: win two consecutive points from deuce. */
    ADVANTAGE(null),

    /** Golden point: the first deuce is decided by a single point. */
    GOLDEN_POINT(1),

    /**
     * Star point: advantage is played at the first two deuces; the third
     * deuce is decided by a single point.
     */
    STAR_POINT(3),
}

/** What happens when the match reaches a deciding (final) set. */
enum class FinalSetRule {
    /** The final set follows the same rules as every other set. */
    SAME_AS_OTHER_SETS,

    /** No tiebreak in the final set: it must be won by two clear games. */
    ADVANTAGE_SET,

    /** The final set is replaced by a single match tiebreak. */
    MATCH_TIEBREAK,

    /**
     * The final set is a full set, but its tiebreak at games-all is played
     * to the match tiebreak's length (10 points), as at the Grand Slams.
     */
    LONG_TIEBREAK,
}

/** The kind of game currently being played. */
enum class GameKind {
    /** 0 / 15 / 30 / 40 game. */
    STANDARD,

    /** Set tiebreak played at games-all. */
    TIEBREAK,

    /** Match tiebreak played instead of a final set. */
    MATCH_TIEBREAK,

    /**
     * A points match (Americano): one run of points from the first rally to
     * the last, with no games or sets.
     */
    POINTS,
}

/**
 * The side of the court the server stands on, from the server's own point of
 * view. Points start from the right ("deuce") side and alternate.
 */
enum class ServeSide { RIGHT, LEFT }

/** What winning the next point would be worth to a team. */
enum class PointStake { NONE, GAME_POINT, SET_POINT, MATCH_POINT }

package com.netsports.core.engine

/**
 * The scoring rules, as a pure reducer.
 *
 * Every function here is deterministic and side-effect free: the same state
 * and input always produce the same output, on any device. That property is
 * what lets several phones and watches replay the same list of points and end
 * up with identical scores.
 *
 * State machine, evaluated on every point:
 *
 * ```
 * point --> game won? --no--> same game, points updated
 *               | yes
 *               v
 *           set won? --no--> next game (standard, or tiebreak at games-all)
 *               | yes
 *               v
 *          match won? --no--> next set (standard, or match tiebreak)
 *               | yes
 *               v
 *            complete
 * ```
 */
object ScoringEngine {

    /** State at the start of a match, before any point is played. */
    fun start(config: MatchConfig): MatchState = MatchState(
        config = config,
        completedSets = emptyList(),
        gamesA = 0,
        gamesB = 0,
        pointsA = 0,
        pointsB = 0,
        gameKind = kindOfNextGame(config, setsA = 0, setsB = 0, gamesA = 0, gamesB = 0),
        gameFirstServer = config.firstServer,
        totalPointsPlayed = 0,
    )

    /**
     * Returns the state after [team] wins a point.
     *
     * @throws IllegalStateException if the match already has a winner.
     */
    fun pointWonBy(state: MatchState, team: Team): MatchState {
        check(!state.isComplete) { "The match is already complete." }

        val config = state.config
        val pointsA = state.pointsA + if (team == Team.A) 1 else 0
        val pointsB = state.pointsB + if (team == Team.B) 1 else 0
        val won = if (team == Team.A) pointsA else pointsB
        val lost = if (team == Team.A) pointsB else pointsA

        val gameWon = when (state.gameKind) {
            GameKind.STANDARD -> standardGameWon(won, lost, config.deuceRule)
            GameKind.TIEBREAK -> {
                val deciding = isDecidingSet(config, state.setsWonBy(Team.A), state.setsWonBy(Team.B))
                if (deciding && config.finalSetRule == FinalSetRule.LONG_TIEBREAK) {
                    won >= config.matchTiebreakPoints && won - lost >= 2
                } else {
                    won >= config.tiebreakPoints && won - lost >= if (config.tiebreakSuddenDeath) 1 else 2
                }
            }
            GameKind.MATCH_TIEBREAK -> won >= config.matchTiebreakPoints && won - lost >= 2
            GameKind.POINTS -> {
                // No games to win: the match simply runs to its last point.
                val counted = state.copy(
                    pointsA = pointsA,
                    pointsB = pointsB,
                    totalPointsPlayed = state.totalPointsPlayed + 1,
                )
                val over = config.pointsTotal != 0 && pointsA + pointsB >= config.pointsTotal
                return if (over) ended(counted) else counted
            }
        }

        if (!gameWon) {
            return state.copy(
                pointsA = pointsA,
                pointsB = pointsB,
                totalPointsPlayed = state.totalPointsPlayed + 1,
            )
        }
        return onGameWon(state, team, pointsA, pointsB)
    }

    /**
     * Ends a points match that has no set end (a timed match): the score as
     * it stands is the result, and level scores are a draw.
     *
     * @throws IllegalArgumentException for any other kind of match.
     */
    fun finish(state: MatchState): MatchState {
        require(state.config.isOpenEnded) { "Only a match with no set end can be ended by hand." }
        return if (state.isComplete) state else ended(state)
    }

    /** [state], a points match, with its result filled in. */
    private fun ended(state: MatchState): MatchState = state.copy(
        winner = when {
            state.pointsA > state.pointsB -> Team.A
            state.pointsB > state.pointsA -> Team.B
            else -> null
        },
        drawn = state.pointsA == state.pointsB,
    )

    /**
     * Replays [points] from the start of a match.
     *
     * @throws IllegalArgumentException if points continue after the match is won.
     */
    fun replay(config: MatchConfig, points: List<Team>): MatchState {
        var state = start(config)
        for (team in points) {
            require(!state.isComplete) { "Points are recorded after the match ended." }
            state = pointWonBy(state, team)
        }
        return state
    }

    /**
     * What the next point is worth to [team]: nothing, the game, the set or
     * the match.
     *
     * Computed by playing the point hypothetically, so it is correct by
     * construction for every format. On a deciding point both teams hold a
     * game point at once.
     */
    fun stakeFor(state: MatchState, team: Team): PointStake {
        if (state.isComplete) return PointStake.NONE
        val next = pointWonBy(state, team)
        return when {
            // The point that decides who wins the match. When every set is
            // played regardless, later sets carry set points only.
            state.decidedWinner == null && next.decidedWinner != null -> PointStake.MATCH_POINT
            next.completedSets.size > state.completedSets.size -> PointStake.SET_POINT
            next.gamesA != state.gamesA || next.gamesB != state.gamesB -> PointStake.GAME_POINT
            else -> PointStake.NONE
        }
    }

    /** True when the receiving team can win a standard game on the next point. */
    fun isBreakPoint(state: MatchState): Boolean =
        state.gameKind == GameKind.STANDARD && stakeFor(state, state.server.opponent) != PointStake.NONE

    /**
     * A standard game is won at 4+ points with a two-point margin, or with a
     * one-point margin when the point just played was a deciding point.
     */
    private fun standardGameWon(won: Int, lost: Int, rule: DeuceRule): Boolean {
        if (won < 4) return false
        if (won - lost >= 2) return true
        val decidingDeuce = rule.decidingDeuce ?: return false
        // Before this point the score was tied at `lost` points each, which is
        // deuce number `lost - 2` (3-3 is the 1st deuce).
        return won - lost == 1 && lost - 2 >= decidingDeuce
    }

    private fun onGameWon(state: MatchState, team: Team, pointsA: Int, pointsB: Int): MatchState {
        val config = state.config
        val gamesA = state.gamesA + if (team == Team.A) 1 else 0
        val gamesB = state.gamesB + if (team == Team.B) 1 else 0
        val won = if (team == Team.A) gamesA else gamesB
        val lost = if (team == Team.A) gamesB else gamesA

        val setWon = when (state.gameKind) {
            GameKind.STANDARD ->
                (won >= config.gamesPerSet && won - lost >= 2) ||
                    // A capped advantage set: reaching the cap wins it, even by one game.
                    (config.setGamesCap != 0 && won >= config.setGamesCap)
            // Winning either kind of tiebreak always wins the set.
            GameKind.TIEBREAK, GameKind.MATCH_TIEBREAK -> true
            GameKind.POINTS -> error("A points match has no games.")
        }

        // Service alternates after every game. A tiebreak counts as one game,
        // so the team that served first in a tiebreak receives first in the
        // next set.
        val nextFirstServer = state.gameFirstServer.opponent
        val totalPointsPlayed = state.totalPointsPlayed + 1

        if (!setWon) {
            return state.copy(
                gamesA = gamesA,
                gamesB = gamesB,
                pointsA = 0,
                pointsB = 0,
                gameKind = kindOfNextGame(
                    config,
                    setsA = state.setsWonBy(Team.A),
                    setsB = state.setsWonBy(Team.B),
                    gamesA = gamesA,
                    gamesB = gamesB,
                ),
                gameFirstServer = nextFirstServer,
                totalPointsPlayed = totalPointsPlayed,
            )
        }

        val wasTiebreak = state.gameKind != GameKind.STANDARD
        val completedSets = state.completedSets + SetScore(
            gamesA = gamesA,
            gamesB = gamesB,
            tiebreakPointsA = if (wasTiebreak) pointsA else null,
            tiebreakPointsB = if (wasTiebreak) pointsB else null,
            isMatchTiebreak = state.gameKind == GameKind.MATCH_TIEBREAK,
        )
        val setsA = completedSets.count { it.winner == Team.A }
        val setsB = completedSets.size - setsA
        // Normally the match ends when a team has enough sets. With
        // playAllSets it ends only when every set has been played.
        val matchWon = if (config.playAllSets) {
            completedSets.size == config.bestOf
        } else {
            maxOf(setsA, setsB) >= config.setsToWin
        }
        val matchWinner = if (setsA > setsB) Team.A else Team.B

        return MatchState(
            config = config,
            completedSets = completedSets,
            gamesA = 0,
            gamesB = 0,
            pointsA = 0,
            pointsB = 0,
            gameKind = if (matchWon) {
                GameKind.STANDARD
            } else {
                kindOfNextGame(config, setsA = setsA, setsB = setsB, gamesA = 0, gamesB = 0)
            },
            gameFirstServer = nextFirstServer,
            totalPointsPlayed = totalPointsPlayed,
            winner = if (matchWon) matchWinner else null,
        )
    }

    /** Decides which kind of game starts at the given set and game score. */
    private fun kindOfNextGame(config: MatchConfig, setsA: Int, setsB: Int, gamesA: Int, gamesB: Int): GameKind {
        if (config.pointsMatch) return GameKind.POINTS
        val isDecidingSet = isDecidingSet(config, setsA, setsB)

        if (isDecidingSet && config.finalSetRule == FinalSetRule.MATCH_TIEBREAK && gamesA == 0 && gamesB == 0) {
            return GameKind.MATCH_TIEBREAK
        }

        val tiebreakAllowed =
            config.setTiebreak && !(isDecidingSet && config.finalSetRule == FinalSetRule.ADVANTAGE_SET)

        return if (tiebreakAllowed && gamesA == config.tiebreakAt && gamesB == config.tiebreakAt) {
            GameKind.TIEBREAK
        } else {
            GameKind.STANDARD
        }
    }

    /** The deciding set is the one where both teams are one set from victory. */
    private fun isDecidingSet(config: MatchConfig, setsA: Int, setsB: Int): Boolean =
        setsA == setsB && setsA == config.setsToWin - 1
}

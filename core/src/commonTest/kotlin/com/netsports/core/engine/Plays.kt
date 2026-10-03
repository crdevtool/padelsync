package com.netsports.core.engine

/** Plays a sequence of points, e.g. `play(state, "AABA")`. */
fun play(state: MatchState, points: String): MatchState =
    points.fold(state) { next, char -> ScoringEngine.pointWonBy(next, if (char == 'A') Team.A else Team.B) }

/** Wins a standard game to love. Only valid at the start of a game. */
fun winGame(state: MatchState, team: Team): MatchState = play(state, (if (team == Team.A) "A" else "B").repeat(4))

fun winGames(state: MatchState, team: Team, count: Int): MatchState =
    (1..count).fold(state) { next, _ -> winGame(next, team) }

/** Wins a six-game set to love. Only valid at the start of a set. */
fun winSet(state: MatchState, team: Team): MatchState = winGames(state, team, 6)

/** Trades games until `games`-all. Only valid at the start of a set. */
fun reachGamesAll(state: MatchState, games: Int = 6): MatchState =
    (1..games).fold(state) { next, _ -> winGame(winGame(next, Team.A), Team.B) }

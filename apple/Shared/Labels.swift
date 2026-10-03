import PadelSyncCore

/// User-facing wording shared by the iPhone and Apple Watch apps. Kept in
/// step with the Android wording in androidkit/Labels.kt.
enum Labels {

    static func team(_ team: Team) -> String {
        team == Team.a ? "Team A" : "Team B"
    }

    /// The call-out for the next point, or nil when there is nothing to call out.
    static func highlight(_ score: ScoreView, config: MatchConfig?) -> String? {
        var who = ""
        if let team = score.highlightTeam {
            who = " · \(Labels.team(team).uppercased())"
        }
        let highlight = score.highlight
        if highlight == Highlight.tiebreak { return "TIEBREAK" }
        if highlight == Highlight.deuce { return "DEUCE" }
        if highlight == Highlight.decidingPoint {
            return config?.deuceRule == DeuceRule.starPoint ? "STAR POINT" : "GOLDEN POINT"
        }
        if highlight == Highlight.gamePoint { return "GAME POINT\(who)" }
        if highlight == Highlight.breakPoint { return "BREAK POINT\(who)" }
        if highlight == Highlight.setPoint { return "SET POINT\(who)" }
        if highlight == Highlight.matchPoint { return "MATCH POINT\(who)" }
        if highlight == Highlight.matchWon, let winner = score.winner {
            return "\(Labels.team(winner).uppercased()) WINS"
        }
        return nil
    }

    /// `highlight` shortened to fit a watch: "MATCH POINT A" instead of "MATCH POINT · TEAM A".
    static func highlightShort(_ score: ScoreView, config: MatchConfig?) -> String? {
        var who = ""
        if let team = score.highlightTeam {
            who = team == Team.a ? " A" : " B"
        }
        let highlight = score.highlight
        if highlight == Highlight.gamePoint { return "GAME POINT\(who)" }
        if highlight == Highlight.breakPoint { return "BREAK POINT\(who)" }
        if highlight == Highlight.setPoint { return "SET POINT\(who)" }
        if highlight == Highlight.matchPoint { return "MATCH POINT\(who)" }
        return Labels.highlight(score, config: config)
    }

    static func sport(_ sport: Sport) -> String {
        sport == Sport.padel ? "Padel" : "Tennis"
    }

    static func deuceRule(_ rule: DeuceRule) -> String {
        if rule == DeuceRule.goldenPoint { return "Golden point" }
        if rule == DeuceRule.starPoint { return "Star point" }
        return "Advantage"
    }

    static func finalSet(_ rule: FinalSetRule) -> String {
        if rule == FinalSetRule.advantageSet { return "No tiebreak" }
        if rule == FinalSetRule.matchTiebreak { return "Match tiebreak" }
        return "Full set"
    }

    static func rejection(_ reason: JoinRejection?) -> String {
        if reason == JoinRejection.badCode {
            return "That code is not right. Check the host's screen and try again."
        }
        if reason == JoinRejection.sessionFull {
            return "This court already has the maximum number of devices."
        }
        if reason == JoinRejection.unsupportedVersion {
            return "This court uses a different version of the app. Update both devices."
        }
        return "The host did not let this device join."
    }

    static func devices(_ count: Int) -> String {
        count == 1 ? "1 device" : "\(count) devices"
    }
}

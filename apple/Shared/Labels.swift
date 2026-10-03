import Foundation
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
            who = " · \(score.nameOf(team: team).uppercased())"
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
            return "\(score.nameOf(team: winner).uppercased()) \(wins(score, winner).uppercased())"
        }
        return nil
    }

    /// A team in at most three characters, for a watch: `A+L` for Ana and
    /// Leo, `ANA` for Ana alone, `A` or `B` when no names were given.
    static func shortName(_ score: ScoreView, _ team: Team) -> String {
        let players = score.playersOf(team: team)
        if players.isEmpty { return team == Team.a ? "A" : "B" }
        if players.count == 1 { return String(players[0].prefix(3)).uppercased() }
        return players.map { String($0.prefix(1)).uppercased() }.joined(separator: "+")
    }

    /// `highlight` shortened to fit a watch: `MP A+L` instead of `MATCH POINT · ANA & LEO`.
    static func highlightShort(_ score: ScoreView, config: MatchConfig?) -> String? {
        var who = ""
        if let team = score.highlightTeam {
            who = " \(shortName(score, team))"
        }
        // About nine characters fit between the two keys on a small watch,
        // so the big points use the abbreviations players write on score
        // sheets.
        let highlight = score.highlight
        if highlight == Highlight.tiebreak { return "TIEBREAK" }
        if highlight == Highlight.deuce { return "DEUCE" }
        if highlight == Highlight.decidingPoint {
            return config?.deuceRule == DeuceRule.starPoint ? "STAR PT" : "GOLDEN PT"
        }
        if highlight == Highlight.gamePoint { return "GP\(who)" }
        if highlight == Highlight.breakPoint { return "BP\(who)" }
        if highlight == Highlight.setPoint { return "SP\(who)" }
        if highlight == Highlight.matchPoint { return "MP\(who)" }
        if highlight == Highlight.matchWon, let winner = score.winner {
            return "\(shortName(score, winner)) \(wins(score, winner).uppercased())"
        }
        return nil
    }

    /// `win` for a pair or an unnamed team, `wins` for one named player.
    static func wins(_ score: ScoreView, _ team: Team) -> String {
        score.playersOf(team: team).count == 1 ? "wins" : "win"
    }

    /// The line over the trophy: `Ana & Leo win!`
    static func winnerHeadline(_ score: ScoreView, _ team: Team) -> String {
        "\(score.nameOf(team: team)) \(wins(score, team))!"
    }

    /// Which side the server stands on, from the server's own point of view.
    static func serveSide(_ side: ServeSide) -> String {
        side == ServeSide.right ? "right side" : "left side"
    }

    /// A duration as `48 min` or `1 h 12 min`.
    static func duration(_ millis: Int64) -> String {
        let minutes = Int(millis / 60_000)
        return minutes < 60 ? "\(minutes) min" : "\(minutes / 60) h \(minutes % 60) min"
    }

    /// A running clock as `7:05` or `1:07:05`.
    static func clock(_ millis: Int64) -> String {
        let seconds = Int(max(millis, 0) / 1000)
        let minutesPart = (seconds / 60) % 60
        let secondsPart = twoDigits(seconds % 60)
        let hours = seconds / 3600
        if hours > 0 { return "\(hours):\(twoDigits(minutesPart)):\(secondsPart)" }
        return "\(minutesPart):\(secondsPart)"
    }

    private static func twoDigits(_ value: Int) -> String {
        value < 10 ? "0\(value)" : "\(value)"
    }

    /// What to tell the player about a tap that did not count, or nil when
    /// there is nothing to say.
    static func tapFeedback(_ feedback: TapFeedback?) -> String? {
        if feedback == TapFeedback.superseded { return "Already scored on another device" }
        if feedback == TapFeedback.matchComplete { return "The match is over" }
        if feedback == TapFeedback.notAllowed { return "View only: the host scores this match" }
        return nil
    }

    /// The result as plain text, for sharing in a chat:
    /// `Ana & Leo beat Mia & Sam 6-4 3-6 7-5 (Padel, 1 h 12 min)`.
    static func shareText(_ score: ScoreView, config: MatchConfig?, durationMillis: Int64?) -> String {
        var text: String
        if let winner = score.winner ?? score.decidedWinner {
            text = "\(score.nameOf(team: winner)) beat \(score.nameOf(team: winner.opponent))"
        } else {
            text = "\(score.nameA) vs \(score.nameB)"
        }
        var details: [String] = []
        if let config { details.append(sport(config.sport)) }
        if let durationMillis, durationMillis >= 60_000 { details.append(duration(durationMillis)) }
        if !score.setSummary.isEmpty { text += " \(score.setSummary)" }
        if !details.isEmpty { text += " (\(details.joined(separator: ", ")))" }
        return text + "\nScored with PadelSync"
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

    /// One line describing a format, for example `Padel · Best of 3 · Golden point`.
    static func format(_ config: MatchConfig) -> String {
        "\(sport(config.sport)) · \(sets(config)) · \(deuceRule(config.deuceRule))"
    }

    /// `1 set`, `Best of 3`, or `3 sets` when every set is played.
    static func sets(_ config: MatchConfig) -> String {
        if config.bestOf == 1 { return "1 set" }
        if config.playAllSets { return "\(config.bestOf) sets" }
        return "Best of \(config.bestOf)"
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

    // Taking over as host. The same wording as on Android.
    static let takeOverButton = "Host this court from this device"
    static let takeOverButtonShort = "Host this court"
    static let takeOverTitle = "Host this court from this device?"
    static let takeOverBody =
        "Do this only if the host's device has left or stopped working, and make sure only one player does it. "
        + "The match carries on from the score on this screen, and the other players' devices follow by themselves."
    static let takeOverConfirm = "Host the court"
    static let hostUnreachable = "The host cannot be reached."
    /// An Apple Watch cannot host a court, so it cannot take one over.
    static let askPhoneToHost = "Ask a player with a phone to host this court from their device."

    static func devices(_ count: Int) -> String {
        count == 1 ? "1 device" : "\(count) devices"
    }
}

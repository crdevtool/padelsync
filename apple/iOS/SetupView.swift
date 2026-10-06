import PadelSyncCore
import SwiftUI

/// Lets the player choose who is playing and the match format.
///
/// `hosting` says whether the match will be shared with other devices, which
/// adds the choice of who may score. `initial` is the last match set up on
/// this device, to start from; it is read once, and the form owns the values
/// from then on.
struct SetupView: View {
    private let title: String
    private let startLabel: String
    private let hosting: Bool
    private let voiceOn: Bool
    private let onVoiceChange: (Bool) -> Void
    private let onStart: (MatchSetup) -> Void
    private let onBack: () -> Void
    @State private var sport: Sport
    @State private var doubles: Bool
    @State private var bestOf: Int
    @State private var playAllSets: Bool
    @State private var deuceRule: DeuceRule
    @State private var gamesPerSet: Int
    @State private var setTiebreak: Bool
    @State private var setGamesCap: Int
    @State private var finalSet: FinalSetRule
    @State private var fast4: Bool
    /// Americano: a match of points, with no games or sets.
    @State private var pointsMatch: Bool
    @State private var pointsTotal: Int
    @State private var firstServer: Team
    @State private var guestsCanScore: Bool
    @State private var a1: String
    @State private var a2: String
    @State private var b1: String
    @State private var b2: String

    init(
        title: String,
        startLabel: String,
        hosting: Bool,
        initial: MatchSetup?,
        voiceOn: Bool,
        onVoiceChange: @escaping (Bool) -> Void,
        onStart: @escaping (MatchSetup) -> Void,
        onBack: @escaping () -> Void
    ) {
        self.title = title
        self.startLabel = startLabel
        self.hosting = hosting
        self.voiceOn = voiceOn
        self.onVoiceChange = onVoiceChange
        self.onStart = onStart
        self.onBack = onBack

        // On first use: club padel, played to the last set as social padel usually is.
        let config = initial?.config
        _sport = State(initialValue: config?.sport ?? Sport.padel)
        _doubles = State(initialValue: config?.doubles ?? true)
        _bestOf = State(initialValue: Int(config?.bestOf ?? 3))
        _playAllSets = State(initialValue: config?.playAllSets ?? true)
        _deuceRule = State(initialValue: config?.deuceRule ?? DeuceRule.advantage)
        _gamesPerSet = State(initialValue: Int(config?.gamesPerSet ?? 6))
        // A one-set match saved with "No tiebreak" as its final set is the
        // same thing as an advantage set, which is now chosen under "At 6-6".
        let startedWithoutTiebreak = config.map { $0.bestOf == 1 && $0.finalSetRule == FinalSetRule.advantageSet } ?? false
        _setTiebreak = State(initialValue: (config?.setTiebreak ?? true) && !startedWithoutTiebreak)
        _setGamesCap = State(initialValue: Int(config?.setGamesCap ?? 0))
        _finalSet = State(
            initialValue: startedWithoutTiebreak
                ? FinalSetRule.sameAsOtherSets
                : (config?.finalSetRule ?? FinalSetRule.sameAsOtherSets)
        )
        _fast4 = State(initialValue: config?.isFast4Tiebreak ?? false)
        let startedWithPoints = config?.pointsMatch ?? false
        _pointsMatch = State(initialValue: startedWithPoints)
        _pointsTotal = State(
            initialValue: startedWithPoints ? Int(config?.pointsTotal ?? 0) : SetupView.defaultPoints
        )
        _firstServer = State(initialValue: config?.firstServer ?? Team.a)
        _guestsCanScore = State(initialValue: initial?.guestsCanScore ?? true)

        let names = initial?.roster
        _a1 = State(initialValue: names?.playerName(team: Team.a, index: 0) ?? "")
        _a2 = State(initialValue: names?.playerName(team: Team.a, index: 1) ?? "")
        _b1 = State(initialValue: names?.playerName(team: Team.b, index: 0) ?? "")
        _b2 = State(initialValue: names?.playerName(team: Team.b, index: 1) ?? "")
    }

    private var roster: Roster {
        rosterOf(doubles: doubles, a1: a1, a2: a2, b1: b1, b2: b2)
    }

    /// The ways a final set can be played, given how the other sets are
    /// settled: "No tiebreak" and "Tiebreak to 10" only mean something when
    /// the other sets end in an ordinary tiebreak.
    private static func finalSetChoices(setTiebreak: Bool, fast4: Bool) -> [FinalSetRule] {
        var choices = [FinalSetRule.sameAsOtherSets]
        if setTiebreak { choices.append(FinalSetRule.advantageSet) }
        choices.append(FinalSetRule.matchTiebreak)
        if setTiebreak && !fast4 { choices.append(FinalSetRule.longTiebreak) }
        return choices
    }

    /// How a set that reaches games-all is settled.
    private enum GamesAll: Equatable {
        case tiebreak, fast4, advantageSet

        var label: String {
            switch self {
            case .tiebreak: return "Tiebreak"
            case .fast4: return "Fast4"
            case .advantageSet: return "Advantage set"
            }
        }
    }

    private var gamesAll: GamesAll {
        if !setTiebreak { return .advantageSet }
        return fast4 ? .fast4 : .tiebreak
    }

    /// Fast4 is offered only where it is played: in sets to four games.
    private var gamesAllOptions: [GamesAll] {
        gamesPerSet == SetupView.fast4SetLength ? [.tiebreak, .fast4, .advantageSet] : [.tiebreak, .advantageSet]
    }

    /// Games that win a set: a short set, the standard one, and the two usual pro sets.
    private static let setLengths = [4, 6, 8, 9]

    /// Fast4 is played in sets to this many games.
    private static let fast4SetLength = 4

    /// The usual lengths of an Americano match, and 0 for one that is timed.
    private static let pointsTotals = [16, 24, 32, 0]
    private static let defaultPoints = 24

    /// The lengths of the two kinds of tiebreak, only used to explain them.
    private static let tiebreakPoints: Int32 = 7
    private static let matchTiebreakPoints: Int32 = 10

    /// Where an advantage set can be made to stop, counted from the set length. 0 is no limit.
    private var capOptions: [Int] {
        [gamesPerSet + 2, gamesPerSet + 3, gamesPerSet + 4, 0]
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: title, onBack: onBack)
                .padding(.horizontal, 20)
                .padding(.top, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    players
                    format
                    switches
                }
                .padding(20)
            }
            .scrollDismissesKeyboard(.interactively)

            // Outside the scrolling area, so it is always within reach.
            BigButton(title: startLabel, filled: true, action: start)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
        }
    }

    @ViewBuilder private var players: some View {
        OptionGroup(
            title: "Sport",
            options: [Sport.padel, Sport.tennis],
            selected: sport,
            label: { Labels.sport($0) },
            onSelect: { choice in
                if choice == sport { return }
                sport = choice
                // Each sport's usual habits; every one can be changed below.
                let padel = choice == Sport.padel
                doubles = padel
                playAllSets = padel
                // Americano is a padel format.
                if !padel { pointsMatch = false }
            }
        )
        if sport == Sport.padel {
            OptionGroup(
                title: "Scoring",
                options: [false, true],
                selected: pointsMatch,
                caption: FormatHelp.shared.scoring(pointsMatch: pointsMatch),
                label: { $0 ? "Americano" : "Sets" },
                onSelect: { pointsMatch = $0 }
            )
        }
        OptionGroup(
            title: "Players",
            options: [true, false],
            selected: doubles,
            label: { $0 ? "Doubles" : "Singles" },
            onSelect: { doubles = $0 }
        )
        VStack(alignment: .leading, spacing: 6) {
            PlayerFields(doubles: doubles, a1: $a1, a2: $a2, b1: $b1, b2: $b2)
            Text("Names are optional. In doubles, player 1 serves first for their team.")
                .font(.footnote)
                .foregroundStyle(Palette.muted)
        }
    }

    @ViewBuilder private var format: some View {
        OptionGroup(
            title: "First to serve",
            options: [Team.a, Team.b],
            selected: firstServer,
            label: { roster.teamName(team: $0) },
            onSelect: { firstServer = $0 }
        )
        if pointsMatch {
            OptionGroup(
                title: "Match length",
                options: SetupView.pointsTotals,
                selected: pointsTotal,
                caption: FormatHelp.shared.matchLength(pointsTotal: Int32(pointsTotal)),
                label: { FormatHelp.shared.matchLengthLabel(pointsTotal: Int32($0)) },
                onSelect: { pointsTotal = $0 }
            )
        } else {
            sets
        }
    }

    @ViewBuilder private var sets: some View {
        OptionGroup(
            title: "Sets",
            options: [1, 3, 5],
            selected: bestOf,
            caption: FormatHelp.shared.sets(bestOf: Int32(bestOf)),
            label: { $0 == 1 ? "1 set" : "Best of \($0)" },
            onSelect: { choice in
                bestOf = choice
                // A one-set match has no final set to play differently.
                if choice == 1 {
                    finalSet = FinalSetRule.sameAsOtherSets
                }
            }
        )
        if bestOf > 1 {
            SwitchRow(
                title: "Play all \(bestOf) sets",
                caption: "Keep playing after a team has already won the match",
                isOn: playAllSets
            ) { playAllSets = $0 }
        }
        OptionGroup(
            title: "Set length",
            options: SetupView.setLengths,
            selected: gamesPerSet,
            caption: FormatHelp.shared.setLength(games: Int32(gamesPerSet)),
            label: { "\($0) games" },
            onSelect: { choice in
                gamesPerSet = choice
                // The cap is counted from the set length, so it moves with it.
                if setGamesCap != 0 {
                    setGamesCap = choice + 2
                }
                // Fast4 is played in sets to four games only.
                if choice != SetupView.fast4SetLength {
                    fast4 = false
                }
            }
        )
        OptionGroup(
            title: "At deuce",
            options: [DeuceRule.advantage, DeuceRule.goldenPoint, DeuceRule.starPoint],
            selected: deuceRule,
            caption: FormatHelp.shared.deuce(rule: deuceRule),
            label: { Labels.deuceRule($0) },
            onSelect: { deuceRule = $0 }
        )
        OptionGroup(
            title: FormatHelp.shared.gamesAllTitle(games: Int32(gamesPerSet)),
            options: gamesAllOptions,
            selected: gamesAll,
            caption: gamesAll == .fast4
                ? FormatHelp.shared.fast4(games: Int32(gamesPerSet))
                : FormatHelp.shared.gamesAll(
                    games: Int32(gamesPerSet),
                    tiebreak: setTiebreak,
                    tiebreakPoints: SetupView.tiebreakPoints
                ),
            label: { $0.label },
            onSelect: { choice in
                if choice == gamesAll { return }
                setTiebreak = choice != .advantageSet
                fast4 = choice == .fast4
                // Most groups that skip the tiebreak still want the set to end.
                setGamesCap = setTiebreak ? 0 : gamesPerSet + 2
                // Some ways of playing the final set need an ordinary tiebreak in the others.
                if !SetupView.finalSetChoices(setTiebreak: setTiebreak, fast4: fast4).contains(finalSet) {
                    finalSet = FinalSetRule.sameAsOtherSets
                }
            }
        )
        if !setTiebreak {
            OptionGroup(
                title: FormatHelp.shared.capTitle(),
                options: capOptions,
                selected: setGamesCap,
                caption: FormatHelp.shared.cap(games: Int32(gamesPerSet), cap: Int32(setGamesCap)),
                label: { FormatHelp.shared.capLabel(cap: Int32($0)) },
                onSelect: { setGamesCap = $0 }
            )
        }
        if bestOf > 1 {
            OptionGroup(
                title: "Final set",
                options: SetupView.finalSetChoices(setTiebreak: setTiebreak, fast4: fast4),
                selected: finalSet,
                caption: FormatHelp.shared.finalSet(
                    rule: finalSet,
                    matchTiebreakPoints: SetupView.matchTiebreakPoints
                ),
                label: { Labels.finalSet($0) },
                onSelect: { finalSet = $0 }
            )
        }
    }

    @ViewBuilder private var switches: some View {
        if hosting {
            SwitchRow(
                title: "Others can score",
                caption: guestsCanScore
                    ? "Every phone and watch that joins can add points"
                    : "Only this phone scores; the others watch. You can allow people one by one later.",
                isOn: guestsCanScore
            ) { guestsCanScore = $0 }
        }
        SwitchRow(
            title: "Call the score out loud",
            caption: "Points, games, who serves. Fine-tune it from the match menu.",
            isOn: voiceOn,
            onChange: onVoiceChange
        )
    }

    private func start() {
        onStart(MatchSetup(config: config, roster: roster, guestsCanScore: guestsCanScore))
    }

    /// The match the form describes.
    private var config: MatchConfig {
        if pointsMatch {
            return Sessions.shared.pointsConfig(
                sport: sport,
                pointsTotal: Int32(pointsTotal),
                firstServer: firstServer,
                doubles: doubles
            )
        }
        // A one-set match has no final set; the core refuses a match tiebreak in one.
        let finalRule = bestOf == 1 ? FinalSetRule.sameAsOtherSets : finalSet
        return Sessions.shared.config(
            sport: sport,
            bestOf: Int32(bestOf),
            gamesPerSet: Int32(gamesPerSet),
            deuceRule: deuceRule,
            setTiebreak: setTiebreak,
            setGamesCap: Int32(setGamesCap),
            fast4Tiebreak: fast4,
            finalSetRule: finalRule,
            firstServer: firstServer,
            playAllSets: playAllSets && bestOf > 1,
            doubles: doubles
        )
    }
}

import PadelSyncCore
import SwiftUI

/// Sets a match up in one short screen: the format as a card that says it in
/// plain words, the players, who serves first, and Start. The choices that
/// make up the format are a second screen behind the card's Change button, so
/// that starting a match in the usual format takes no scrolling and no
/// setting can be passed over unseen.
///
/// `hosting` says whether the match will be shared with other devices, which
/// adds the choice of who may score. `initial` is the players of the last
/// match set up on this device and the format to start from; it is read once,
/// and the form owns the values from then on. `onKeepFormat` is called with
/// the format when a match is started with "Keep as my format" on: new
/// matches then start from it.
struct SetupView: View {
    private let title: String
    private let startLabel: String
    private let hosting: Bool
    private let voiceOn: Bool
    private let onVoiceChange: (Bool) -> Void
    private let onKeepFormat: (MatchConfig) -> Void
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
    /// Whether the format's own screen is showing.
    @State private var editingFormat = false
    /// Whether the format is to be remembered as the player's own once the match starts.
    @State private var keepFormat = true

    init(
        title: String,
        startLabel: String,
        hosting: Bool,
        initial: MatchSetup?,
        voiceOn: Bool,
        onVoiceChange: @escaping (Bool) -> Void,
        onKeepFormat: @escaping (MatchConfig) -> Void,
        onStart: @escaping (MatchSetup) -> Void,
        onBack: @escaping () -> Void
    ) {
        self.title = title
        self.startLabel = startLabel
        self.hosting = hosting
        self.voiceOn = voiceOn
        self.onVoiceChange = onVoiceChange
        self.onKeepFormat = onKeepFormat
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
        // On first use sets are advantage sets with no limit, as in `Sessions.clubPadel()`.
        _setTiebreak = State(initialValue: (config?.setTiebreak ?? false) && !startedWithoutTiebreak)
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
        if editingFormat {
            // Back returns to the match, not to the home screen.
            screen(
                title: "Format",
                onBack: { editingFormat = false },
                action: "Done",
                onAction: { editingFormat = false }
            ) {
                kind
                SwitchRow(
                    title: "Keep as my format",
                    caption: keepFormat
                        ? "New matches start from this format"
                        : "For this match only; the next one starts from your usual format",
                    isOn: keepFormat
                ) { keepFormat = $0 }
            }
        } else {
            screen(title: title, onBack: onBack, action: startLabel, onAction: start) {
                FormatCard(config: config, kept: keepFormat) { editingFormat = true }
                names
                firstServe
                switches
            }
        }
    }

    /// The shape both setup screens share: a title with Back before it, the
    /// content in a scrolling column, and one big button below it.
    private func screen<Content: View>(
        title: String,
        onBack: @escaping () -> Void,
        action: String,
        onAction: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 0) {
            ScreenHeader(title: title, onBack: onBack)
                .padding(.horizontal, 20)
                .padding(.top, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    content()
                }
                .padding(20)
            }
            .scrollDismissesKeyboard(.interactively)

            // Outside the scrolling area, so it is always within reach.
            BigButton(title: action, filled: true, action: onAction)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
        }
    }

    /// The choices that make up the format.
    @ViewBuilder private var kind: some View {
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

    private var names: some View {
        VStack(alignment: .leading, spacing: 6) {
            PlayerFields(doubles: doubles, a1: $a1, a2: $a2, b1: $b1, b2: $b2)
            Text("Names are optional. In doubles, player 1 serves first for their team.")
                .font(.footnote)
                .foregroundStyle(Palette.muted)
        }
    }

    private var firstServe: some View {
        OptionGroup(
            title: "First to serve",
            options: [Team.a, Team.b],
            selected: firstServer,
            label: { roster.teamName(team: $0) },
            onSelect: { firstServer = $0 }
        )
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
                // An advantage set starts with no limit; one is chosen below.
                setGamesCap = 0
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
        let chosen = config
        if keepFormat { onKeepFormat(chosen) }
        onStart(MatchSetup(config: chosen, roster: roster, guestsCanScore: guestsCanScore))
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

/// The format in plain words, one rule to a line, with the way to change it.
private struct FormatCard: View {
    let config: MatchConfig
    /// Whether this format will be remembered as the player's own.
    let kept: Bool
    let onChange: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(kept ? "MY FORMAT" : "THIS MATCH ONLY")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Palette.accent)
                    Text("\(Labels.sport(config.sport)) · \(config.doubles ? "Doubles" : "Singles")")
                        .font(.title2.weight(.bold))
                        .foregroundStyle(.white)
                }
                Spacer(minLength: 8)
                Button(action: onChange) {
                    Text("Change")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .overlay(Capsule().stroke(Palette.muted, lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
            ForEach(FormatHelp.shared.summary(config: config), id: \.self) { line in
                Text(line)
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(Palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Palette.accent, lineWidth: 1.5))
    }
}

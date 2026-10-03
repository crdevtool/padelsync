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
    @State private var finalSet: FinalSetRule
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
        _deuceRule = State(initialValue: config?.deuceRule ?? DeuceRule.goldenPoint)
        _finalSet = State(initialValue: config?.finalSetRule ?? FinalSetRule.sameAsOtherSets)
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

    private var finalSetOptions: [FinalSetRule] {
        // A match tiebreak replaces a deciding set, so it needs more than one set.
        bestOf == 1
            ? [FinalSetRule.sameAsOtherSets, FinalSetRule.advantageSet]
            : [FinalSetRule.sameAsOtherSets, FinalSetRule.advantageSet, FinalSetRule.matchTiebreak]
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
                deuceRule = padel ? DeuceRule.goldenPoint : DeuceRule.advantage
                doubles = padel
                playAllSets = padel
            }
        )
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
        OptionGroup(
            title: "Sets",
            options: [1, 3, 5],
            selected: bestOf,
            label: { $0 == 1 ? "1 set" : "Best of \($0)" },
            onSelect: { choice in
                bestOf = choice
                if choice == 1 && finalSet == FinalSetRule.matchTiebreak {
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
            title: "At deuce",
            options: [DeuceRule.advantage, DeuceRule.goldenPoint, DeuceRule.starPoint],
            selected: deuceRule,
            label: { Labels.deuceRule($0) },
            onSelect: { deuceRule = $0 }
        )
        OptionGroup(
            title: "Final set",
            options: finalSetOptions,
            selected: finalSet,
            label: { Labels.finalSet($0) },
            onSelect: { finalSet = $0 }
        )
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
        // The core refuses a match tiebreak in a one-set match.
        let oneSet = bestOf == 1
        let finalRule = oneSet && finalSet == FinalSetRule.matchTiebreak ? FinalSetRule.sameAsOtherSets : finalSet
        let config = Sessions.shared.config(
            sport: sport,
            bestOf: Int32(bestOf),
            deuceRule: deuceRule,
            finalSetRule: finalRule,
            firstServer: firstServer,
            playAllSets: playAllSets && bestOf > 1,
            doubles: doubles
        )
        onStart(MatchSetup(config: config, roster: roster, guestsCanScore: guestsCanScore))
    }
}

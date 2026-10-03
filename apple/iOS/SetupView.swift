import PadelSyncCore
import SwiftUI

/// Lets the player choose the match format.
struct SetupView: View {
    let onStart: (MatchConfig) -> Void
    let onBack: () -> Void

    @State private var sport: Sport = Sport.padel
    @State private var bestOf = 3
    @State private var deuceRule: DeuceRule = DeuceRule.goldenPoint
    @State private var finalSet: FinalSetRule = FinalSetRule.sameAsOtherSets
    @State private var firstServer: Team = Team.a

    private var finalSetOptions: [FinalSetRule] {
        // A match tiebreak replaces a deciding set, so it needs more than one set.
        bestOf == 1
            ? [FinalSetRule.sameAsOtherSets, FinalSetRule.advantageSet]
            : [FinalSetRule.sameAsOtherSets, FinalSetRule.advantageSet, FinalSetRule.matchTiebreak]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Button("Back", action: onBack)
                    Text("New match")
                        .font(.title.weight(.bold))
                        .foregroundStyle(.white)
                }

                OptionGroup(
                    title: "Sport",
                    options: [Sport.padel, Sport.tennis],
                    selected: sport,
                    label: { Labels.sport($0) },
                    onSelect: { choice in
                        sport = choice
                        // Each sport's usual way of settling deuce.
                        deuceRule = choice == Sport.padel ? DeuceRule.goldenPoint : DeuceRule.advantage
                    }
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
                OptionGroup(
                    title: "First to serve",
                    options: [Team.a, Team.b],
                    selected: firstServer,
                    label: { Labels.team($0) },
                    onSelect: { firstServer = $0 }
                )

                BigButton(title: "Start match", filled: true) {
                    onStart(
                        Sessions.shared.config(
                            sport: sport,
                            bestOf: Int32(bestOf),
                            deuceRule: deuceRule,
                            finalSetRule: finalSet,
                            firstServer: firstServer
                        )
                    )
                }
                .padding(.top, 8)
            }
            .padding(20)
        }
    }
}

/// A titled row of mutually exclusive choices.
private struct OptionGroup<Option: Equatable>: View {
    let title: String
    let options: [Option]
    let selected: Option
    let label: (Option) -> String
    let onSelect: (Option) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.muted)
            HStack(spacing: 8) {
                ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                    let isSelected = option == selected
                    Button {
                        onSelect(option)
                    } label: {
                        Text(label(option))
                            .font(.subheadline.weight(.bold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .padding(.horizontal, 6)
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .foregroundStyle(isSelected ? Palette.onAccent : Color.white)
                            .background(
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(isSelected ? Palette.accent : Palette.surface)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
        }
    }
}

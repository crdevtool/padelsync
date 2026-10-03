import PadelSyncCore
import SwiftUI
import UIKit

/// The live scoreboard, for host and guest alike.
struct MatchView: View {
    @EnvironmentObject private var store: CourtStore
    let onNewMatch: () -> Void

    @State private var confirmLeave = false

    private var hosting: Bool { store.mode == .host }

    var body: some View {
        Group {
            if store.mode == .guest && store.guestRejected {
                NoticeView(
                    title: "Could not join",
                    message: Labels.rejection(store.rejection),
                    button: "Back"
                ) { store.leave() }
            } else if store.mode == .guest && store.guestEnded {
                NoticeView(
                    title: "Court closed",
                    message: "The host ended the match or stopped sharing it.",
                    button: "Back"
                ) { store.leave() }
            } else if let score = store.score {
                scoreboard(score)
            } else {
                NoticeView(
                    title: "Connecting…",
                    message: "Joining \(store.courtName ?? "the court"). Keep this device near the host.",
                    button: "Cancel"
                ) { store.leave() }
            }
        }
        .confirmationDialog(
            hosting ? "End this match?" : "Leave this court?",
            isPresented: $confirmLeave,
            titleVisibility: .visible
        ) {
            Button(hosting ? "End match" : "Leave", role: .destructive) { store.leave() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                hosting
                    ? "The match will end for every device on the court."
                    : "The match carries on for the other players."
            )
        }
        // A gentle buzz when someone else scores, so players know the point is
        // in and do not score it again.
        .onChange(of: store.remoteScoreCount) { _, _ in
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        // Keep the score visible for the length of the match.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func scoreboard(_ score: ScoreView) -> some View {
        let finished = score.winner != nil
        let callout = Labels.highlight(score, config: store.config)
        return VStack(spacing: 6) {
            statusBar

            TeamPanel(
                team: Team.a,
                color: Palette.teamA,
                points: score.pointsA,
                games: Int(score.gamesA),
                sets: Int(score.setsA),
                serving: score.server == Team.a,
                enabled: !finished
            ) { tap(Action.pointA) }

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    if let text = store.note ?? callout {
                        Text(text)
                            .font(.title3.weight(.black))
                            .foregroundStyle(store.note != nil ? Palette.danger : Palette.accent)
                    }
                    if !score.setSummary.isEmpty {
                        Text(score.setSummary)
                            .font(.headline)
                            .foregroundStyle(Palette.muted)
                    }
                }
                Spacer()
                Button("Undo") { tap(Action.undo) }
                    .font(.headline)
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .disabled(!score.canUndo)
            }
            .padding(.horizontal, 16)

            TeamPanel(
                team: Team.b,
                color: Palette.teamB,
                points: score.pointsB,
                games: Int(score.gamesB),
                sets: Int(score.setsB),
                serving: score.server == Team.b,
                enabled: !finished
            ) { tap(Action.pointB) }

            if finished && hosting {
                BigButton(title: "New match", filled: true, action: onNewMatch)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }
        }
    }

    private var statusBar: some View {
        HStack {
            Text(statusText)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(statusColor)
            Spacer()
            Menu {
                if hosting {
                    if store.courtOpen {
                        Button("Stop sharing this court") { store.closeCourt() }
                    } else {
                        Button("Play with others") { store.openCourt() }
                    }
                    Button("New match", action: onNewMatch)
                }
                Button(hosting ? "End match" : "Leave court", role: .destructive) { confirmLeave = true }
            } label: {
                // A touch target of at least 44 points, as Apple recommends.
                Text("Menu")
                    .font(.subheadline.weight(.bold))
                    .frame(minWidth: 64, minHeight: 44, alignment: .trailing)
                    .contentShape(Rectangle())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private var statusText: String {
        // A problem is shown here, where a call-out cannot hide it.
        if let error = store.error { return error }
        if hosting && store.courtOpen {
            return "Court open · Code \(store.joinCode.map { String($0) } ?? "") · \(Labels.devices(store.deviceCount))"
        }
        if hosting { return "This device only" }
        if store.guestSynced { return "\(store.courtName ?? "Court") · \(Labels.devices(store.deviceCount))" }
        return "Reconnecting…"
    }

    private var statusColor: Color {
        if store.error != nil { return Palette.danger }
        if hosting { return store.courtOpen ? Palette.accent : Palette.muted }
        return store.guestSynced ? Palette.accent : Palette.danger
    }

    private func tap(_ action: Action) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        store.tap(action)
    }
}

private struct NoticeView: View {
    let title: String
    let message: String
    let button: String
    let action: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Text(title)
                .font(.title.weight(.bold))
                .foregroundStyle(.white)
            Text(message)
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
            Button(button, action: action)
                .buttonStyle(.bordered)
                .padding(.top, 12)
        }
        .padding(28)
    }
}

/// Half of the screen for one team. The whole panel is the tap target.
private struct TeamPanel: View {
    let team: Team
    let color: Color
    let points: String
    let games: Int
    let sets: Int
    let serving: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 24).fill(color.opacity(0.16))

                Text(points)
                    .font(.system(size: 132, weight: .black, design: .rounded))
                    .minimumScaleFactor(0.4)
                    .lineLimit(1)
                    .foregroundStyle(.white)

                VStack {
                    HStack(spacing: 10) {
                        Text(Labels.team(team).uppercased())
                            .font(.title3.weight(.black))
                            .foregroundStyle(color)
                        if serving {
                            Circle().fill(Palette.accent).frame(width: 14, height: 14)
                            Text("SERVE")
                                .font(.footnote.weight(.bold))
                                .foregroundStyle(Palette.accent)
                        }
                        Spacer()
                    }
                    Spacer()
                    HStack(spacing: 28) {
                        Counter(label: "GAMES", value: games)
                        Counter(label: "SETS", value: sets)
                        Spacer()
                    }
                }
                .padding(20)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
        // Not `.disabled`: that would grey out the final score when the
        // match is over. The panel just stops responding to taps.
        .allowsHitTesting(enabled)
        .padding(.horizontal, 12)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(Labels.team(team)). Points \(points). Games \(games). Sets \(sets)." + (serving ? " Serving." : "")
        )
        .accessibilityHint("Adds a point for \(Labels.team(team))")
        .accessibilityAddTraits(.isButton)
    }
}

private struct Counter: View {
    let label: String
    let value: Int

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 8) {
            Text("\(value)")
                .font(.system(size: 40, weight: .black, design: .rounded))
                .foregroundStyle(.white)
            Text(label)
                .font(.footnote.weight(.bold))
                .foregroundStyle(Palette.muted)
        }
    }
}

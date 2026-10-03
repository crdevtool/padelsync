import PadelSyncCore
import SwiftUI
import WatchKit

/// The watch scoreboard: the top half scores for Team A, the bottom half for
/// Team B, with undo and the menu on the strip between them.
struct WatchMatchView: View {
    @EnvironmentObject private var store: CourtStore
    let score: ScoreView

    @State private var menuOpen = false

    private var offline: Bool { store.mode == .guest && !store.guestSynced }

    private var strip: String {
        if let note = store.note { return note.uppercased() }
        if offline { return "RECONNECTING" }
        return Labels.highlight(score, config: store.config) ?? score.setSummary
    }

    var body: some View {
        let finished = score.winner != nil
        VStack(spacing: 2) {
            WatchHalf(
                team: Team.a,
                color: Palette.teamA,
                points: score.pointsA,
                games: Int(score.gamesA),
                sets: Int(score.setsA),
                serving: score.server == Team.a,
                enabled: !finished
            ) { tap(Action.pointA) }

            HStack(spacing: 4) {
                PillButton(title: "UNDO", enabled: score.canUndo) { tap(Action.undo) }
                Text(strip)
                    .font(.system(size: 10, weight: .black))
                    .foregroundStyle(store.note != nil || offline ? Palette.danger : Palette.accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity)
                PillButton(title: "MENU", enabled: true) { menuOpen = true }
            }
            .padding(.horizontal, 4)

            WatchHalf(
                team: Team.b,
                color: Palette.teamB,
                points: score.pointsB,
                games: Int(score.gamesB),
                sets: Int(score.setsB),
                serving: score.server == Team.b,
                enabled: !finished
            ) { tap(Action.pointB) }
        }
        .ignoresSafeArea(edges: .bottom)
        .onChange(of: store.note) { _, note in
            if note != nil { WKInterfaceDevice.current().play(.failure) }
        }
        .sheet(isPresented: $menuOpen) {
            WatchMenuView(close: { menuOpen = false })
        }
    }

    private func tap(_ action: Action) {
        WKInterfaceDevice.current().play(.click)
        store.tap(action)
    }
}

private struct PillButton: View {
    let title: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(enabled ? Color.white : Palette.muted)
                .frame(width: 44, height: 26)
                .background(Capsule().fill(Palette.surface))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

private struct WatchHalf: View {
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
            HStack(spacing: 8) {
                Text(team == Team.a ? "A" : "B")
                    .font(.system(size: 20, weight: .black, design: .rounded))
                    .foregroundStyle(color)
                VStack(alignment: .trailing, spacing: 0) {
                    Text("G \(games)")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                    Text("S \(sets)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Palette.muted)
                }
                Text(points)
                    .font(.system(size: 44, weight: .black, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .foregroundStyle(.white)
                Circle()
                    .fill(serving ? Palette.accent : Color.clear)
                    .frame(width: 9, height: 9)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 14).fill(color.opacity(0.22)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(Labels.team(team)). Points \(points). Games \(games). Sets \(sets)." + (serving ? " Serving." : "")
        )
        .accessibilityHint("Adds a point for \(Labels.team(team))")
        .accessibilityAddTraits(.isButton)
    }
}

/// Everything that is not scoring: a new match, leaving.
private struct WatchMenuView: View {
    @EnvironmentObject private var store: CourtStore
    let close: () -> Void

    var body: some View {
        let hosting = store.mode == .host
        ScrollView {
            VStack(spacing: 8) {
                if hosting {
                    // An Apple Watch cannot host other devices: watchOS does
                    // not allow the Bluetooth advertising that hosting needs.
                    Text("To play with others, join a court hosted on a phone.")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center)
                    Button("New padel match") {
                        store.startNewMatch(MatchConfig.companion.padel())
                        close()
                    }
                    Button("New tennis match") {
                        store.startNewMatch(MatchConfig.companion.tennis())
                        close()
                    }
                } else {
                    Text("\(store.courtName ?? "Court") · \(Labels.devices(store.deviceCount))")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                }
                Button(hosting ? "End match" : "Leave court", role: .destructive) {
                    close()
                    store.leave()
                }
            }
        }
    }
}

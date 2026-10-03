import PadelSyncCore
import SwiftUI
import WatchKit

/// The watch scoreboard: the top half scores for Team A, the bottom half for
/// Team B, with undo and the menu on the strip between them.
struct WatchMatchView: View {
    @EnvironmentObject private var store: CourtStore
    let score: ScoreView

    @State private var menuOpen = false
    /// The end of the match gets a screen of its own, which can be put away.
    /// A new winner (after an undo, or a new match) brings it back.
    @State private var resultDismissed = false

    private var offline: Bool { store.mode == .guest && !store.guestSynced }

    /// A remark about a tap that did not count, short enough for the strip.
    private var note: String? {
        if store.lastFeedback == TapFeedback.superseded { return "ALREADY IN" }
        if store.lastFeedback == TapFeedback.notAllowed { return "VIEW ONLY" }
        return nil
    }

    /// The strip holds about nine characters on a small watch.
    private var strip: String {
        if let note = note { return note }
        if offline { return "OFFLINE" }
        if let highlight = Labels.highlightShort(score, config: store.config) { return highlight }
        if score.changeEnds { return "SWAP ENDS" }
        if !store.canScore { return "VIEW ONLY" }
        // Decided, with sets still to play.
        if let decided = score.decidedWinner { return "\(Labels.shortName(score, decided)) WON" }
        return serveLine ?? score.setSummary
    }

    /// Who serves next and from which side: `LEO · R`. Only shown when the
    /// player's name is known; the dot beside the score already says which
    /// team serves.
    private var serveLine: String? {
        guard let team = score.server, let side = score.serveSide else { return nil }
        let players = score.playersOf(team: team)
        let index = Int(score.serverPlayerIndex)
        if index >= players.count { return nil }
        let name = String(players[index].prefix(7)).uppercased()
        let letter = side == ServeSide.right ? "R" : "L"
        return "\(name) · \(letter)"
    }

    var body: some View {
        Group {
            if let winner = score.winner, !resultDismissed {
                WatchWinnerView(
                    score: score,
                    winner: winner,
                    // Only a match on this watch alone can be replayed from it.
                    canRematch: store.mode == .host,
                    canUndo: store.canScore,
                    onDismiss: { resultDismissed = true }
                )
            } else {
                scoreboard
            }
        }
        .onChange(of: score.winner) { _, winner in
            resultDismissed = false
            if winner != nil { WKInterfaceDevice.current().play(.success) }
        }
        // `onChange` does not run for the value the screen starts with, so
        // these cannot replay just because the screen was rebuilt.
        .onChange(of: store.feedbackCount) { _, _ in
            if note != nil { WKInterfaceDevice.current().play(.failure) }
        }
        // A distinct tap when someone else scores, so the wearer knows the
        // point is in without looking, and does not score it again.
        .onChange(of: store.remoteScoreCount) { _, _ in
            WKInterfaceDevice.current().play(.directionUp)
        }
        .sheet(isPresented: $menuOpen) {
            WatchMenuView(close: { menuOpen = false })
                .environmentObject(store)
        }
    }

    private var scoreboard: some View {
        let finished = score.winner != nil
        return VStack(spacing: 2) {
            WatchHalf(team: Team.a, score: score, enabled: !finished) { tap(Action.pointA) }

            HStack(spacing: 4) {
                PillButton(title: "UNDO", enabled: score.canUndo && store.canScore) { tap(Action.undo) }
                Text(strip)
                    .font(.system(size: 10, weight: .black))
                    .foregroundStyle(note != nil || offline ? Palette.danger : Palette.accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity)
                PillButton(title: "MENU", enabled: true) { menuOpen = true }
            }
            .padding(.horizontal, 4)

            WatchHalf(team: Team.b, score: score, enabled: !finished) { tap(Action.pointB) }
        }
        .ignoresSafeArea(edges: .bottom)
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
    let score: ScoreView
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        let color = team == Team.a ? Palette.teamA : Palette.teamB
        let label = Labels.shortName(score, team)
        let name = score.nameOf(team: team)
        let points = score.pointsOf(team: team)
        let games = Int(score.gamesOf(team: team))
        let sets = Int(score.setsOf(team: team))
        let serving = score.server == team
        // A three-letter name needs the room that a single letter leaves spare.
        let short = label.count <= 1

        Button(action: action) {
            HStack(spacing: short ? 8 : 5) {
                Text(label)
                    .font(.system(size: short ? 20 : 14, weight: .black, design: .rounded))
                    .foregroundStyle(color)
                    .lineLimit(1)
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
        // Not `.disabled`: that would grey out the final score.
        .allowsHitTesting(enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(name). Points \(points). Games \(games). Sets \(sets)." + (serving ? " Serving." : "")
        )
        .accessibilityHint("Adds a point for \(name)")
        .accessibilityAddTraits(.isButton)
    }
}

/// The end of a match on the watch: who won, the sets, and what to do next.
private struct WatchWinnerView: View {
    @EnvironmentObject private var store: CourtStore
    let score: ScoreView
    let winner: Team
    let canRematch: Bool
    let canUndo: Bool
    let onDismiss: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                Text("🏆")
                    .font(.system(size: 34))
                Text(Labels.winnerHeadline(score, winner))
                    .font(.headline.weight(.black))
                    .foregroundStyle(winner == Team.a ? Palette.teamA : Palette.teamB)
                    .multilineTextAlignment(.center)
                Text(score.setSummary)
                    .font(.system(size: 20, weight: .black, design: .rounded))
                    .multilineTextAlignment(.center)
                if canRematch {
                    Button("Rematch") { store.rematch() }
                        .tint(Palette.accent)
                }
                Button("Scoreboard", action: onDismiss)
                if canUndo {
                    Button("Undo last point") { store.tap(Action.undo) }
                }
            }
        }
    }
}

/// Everything that is not scoring: the serving order, the voice, a new match, leaving.
private struct WatchMenuView: View {
    @EnvironmentObject private var store: CourtStore
    let close: () -> Void

    var body: some View {
        let hosting = store.mode == .host
        ScrollView {
            VStack(spacing: 8) {
                if !hosting {
                    Text("\(store.courtName ?? "Court") · \(Labels.devices(store.deviceCount))")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                }
                if store.canTakeOver {
                    // The match could carry on from another device, but an
                    // Apple Watch cannot host a court.
                    Text("\(Labels.hostUnreachable) \(Labels.askPhoneToHost)")
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                }
                // Players choose their serving order each set; this corrects the app's guess.
                if let score = store.score, score.doubles, let server = score.server, store.canScore {
                    Button("Swap server") {
                        store.tap(server == Team.a ? Action.swapServerA : Action.swapServerB)
                        close()
                    }
                }
                Button(store.speech.enabled ? "Voice: on" : "Voice: off") {
                    store.setSpeech(store.speech.with(enabled: !store.speech.enabled))
                }
                if hosting {
                    // An Apple Watch cannot host other devices: watchOS does
                    // not allow the Bluetooth advertising that hosting needs.
                    Text("To play with others, join a court hosted on a phone.")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center)
                    Button("New padel match") {
                        store.startNewMatch(WatchFormats.padel)
                        close()
                    }
                    Button("New tennis match") {
                        store.startNewMatch(WatchFormats.tennis)
                        close()
                    }
                }
                Button(hosting ? "End match" : "Leave court", role: .destructive) {
                    close()
                    store.leave()
                }
            }
        }
    }
}

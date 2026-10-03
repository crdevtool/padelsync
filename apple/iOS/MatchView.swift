import PadelSyncCore
import SwiftUI
import UIKit

/// The live scoreboard, for host and guest alike.
struct MatchView: View {
    @EnvironmentObject private var store: CourtStore
    let onNewMatch: () -> Void

    @State private var confirmLeave = false
    @State private var confirmTakeOver = false

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
                    message: "The host ended the match or stopped sharing it." + finalSets + problem,
                    button: "Back",
                    action: { store.leave() },
                    // The match need not end with the host: this device holds a full copy.
                    secondButton: store.canTakeOver ? Labels.takeOverButton : nil,
                    secondAction: { confirmTakeOver = true }
                )
            } else if let score = store.score {
                Scoreboard(
                    score: score,
                    onNewMatch: onNewMatch,
                    onLeave: { confirmLeave = true },
                    onTakeOver: { confirmTakeOver = true }
                )
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
        .alert(Labels.takeOverTitle, isPresented: $confirmTakeOver) {
            Button(Labels.takeOverConfirm) { store.takeOverAsHost() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(Labels.takeOverBody)
        }
        // A court in the sun is no place for a screen that dims itself.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private var finalSets: String {
        guard let summary = store.score?.setSummary, !summary.isEmpty else { return "" }
        return " Final sets: \(summary)."
    }

    /// A problem to add to a notice, which has no status line to show it on.
    private var problem: String {
        guard let error = store.error else { return "" }
        return " \(error)"
    }
}

private struct NoticeView: View {
    let title: String
    let message: String
    let button: String
    let action: () -> Void
    /// A second way on from the notice, or nil for none.
    var secondButton: String?
    var secondAction: () -> Void = {}

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
            if let secondButton = secondButton {
                Button(secondButton, action: secondAction)
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Palette.onAccent)
            }
        }
        .padding(28)
    }
}

/// Which of the match sheets is open.
private enum MatchSheet: String, Identifiable {
    case whoCanScore
    case voice
    case players

    var id: String { rawValue }
}

/// The court with both halves, the net strip, the status line and the menu.
private struct Scoreboard: View {
    @EnvironmentObject private var store: CourtStore
    let score: ScoreView
    let onNewMatch: () -> Void
    let onLeave: () -> Void
    let onTakeOver: () -> Void

    @State private var openSheet: MatchSheet?
    /// The winners' screen comes up by itself and can be put away to look at
    /// the scoreboard. A new winner (after an undo, or a new match) brings it back.
    @State private var celebrationDismissed = false
    /// Once the match is decided the result can be opened from the menu, even
    /// if the remaining sets are never played.
    @State private var resultRequested = false
    @State private var event: MatchEvent?
    @State private var eventCount = 0

    private var hosting: Bool { store.mode == .host }
    private var winner: Team? { score.winner }
    private var decided: Team? { score.winner ?? score.decidedWinner }

    private var showResult: Bool {
        if winner != nil { return !celebrationDismissed }
        return decided != nil && resultRequested
    }

    var body: some View {
        VStack(spacing: 0) {
            statusBar
            if store.canTakeOver {
                takeOverOffer
            }
            court
            if winner != nil && celebrationDismissed {
                afterMatchButtons
            }
        }
        .overlay {
            // When this goes away (an undo, a rematch) it fades out as it
            // last was, so the result is not rewritten in mid-air.
            if showResult, let decided = decided {
                resultScreen(decided)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: showResult)
        .onChange(of: winner) { _, _ in celebrationDismissed = false }
        .onChange(of: decided) { _, _ in resultRequested = false }
        .onChange(of: score) { before, after in announceEvent(before: before, after: after) }
        // A gentle buzz when someone else scores, so players know the point is
        // in and do not score it again. `onChange` does not run for the value
        // the screen starts with, so the buzz cannot replay on a rebuild.
        .onChange(of: store.remoteScoreCount) { _, _ in
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        .sheet(item: $openSheet) { which in
            Group {
                switch which {
                case .whoCanScore: WhoCanScoreSheet()
                case .voice: VoiceSheet()
                case .players: PlayersSheet(score: score)
                }
            }
            .environmentObject(store)
        }
    }

    // MARK: Status bar and menu

    private var statusBar: some View {
        HStack {
            Text(statusText)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(statusColor)
                .lineLimit(2)
            Spacer()
            Menu {
                menuItems
            } label: {
                // A touch target of at least 44 points, as Apple recommends.
                Text("Menu")
                    .font(.subheadline.weight(.bold))
                    .frame(minWidth: 64, minHeight: 44, alignment: .trailing)
                    .contentShape(Rectangle())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 2)
    }

    /// The host has been out of reach for a while. The match need not wait
    /// for it: this device holds a full copy.
    private var takeOverOffer: some View {
        HStack(spacing: 8) {
            Text(Labels.hostUnreachable)
                .font(.subheadline)
                .foregroundStyle(Palette.muted)
            Spacer(minLength: 0)
            Button(Labels.takeOverButtonShort, action: onTakeOver)
                .font(.subheadline.weight(.bold))
                .buttonStyle(.borderedProminent)
                .foregroundStyle(Palette.onAccent)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.bottom, 4)
    }

    @ViewBuilder private var menuItems: some View {
        if hosting {
            if store.courtOpen {
                Button("Stop sharing this court") { store.closeCourt() }
            } else {
                Button("Play with others") { store.openCourt() }
            }
            Button("Who can score") { openSheet = .whoCanScore }
            Button("Players") { openSheet = .players }
        }
        // Players choose their serving order each set; this corrects the app's guess.
        if score.doubles, let server = score.server, store.canScore {
            Button(swapLabel(server)) {
                tap(server == Team.a ? Action.swapServerA : Action.swapServerB)
            }
        }
        Button("Voice") { openSheet = .voice }
        if winner == nil && decided != nil {
            Button("Result so far") { resultRequested = true }
        }
        if hosting {
            Button("New match", action: onNewMatch)
        }
        Button(hosting ? "End match" : "Leave court", role: .destructive, action: onLeave)
    }

    /// Names the player who would serve if the team's serving order were swapped now.
    private func swapLabel(_ team: Team) -> String {
        let players = score.playersOf(team: team)
        let other = 1 - Int(score.serverPlayerIndex)
        if other >= 0 && other < players.count { return "Swap server to \(players[other])" }
        return "Swap server"
    }

    private var statusText: String {
        // A problem is shown here, where a call-out cannot hide it.
        if let error = store.error { return error }
        if hosting && store.courtOpen {
            return "Court open · Code \(store.joinCode.map { String($0) } ?? "") · \(Labels.devices(store.deviceCount))"
        }
        if hosting { return "This device only" }
        if !store.guestSynced { return "Reconnecting…" }
        if !store.canScore { return "\(store.courtName ?? "Court") · View only" }
        return "\(store.courtName ?? "Court") · \(Labels.devices(store.deviceCount))"
    }

    private var statusColor: Color {
        if store.error != nil { return Palette.danger }
        if hosting { return store.courtOpen ? Palette.accent : Palette.muted }
        if !store.guestSynced { return Palette.danger }
        return store.canScore ? Palette.accent : Palette.gold
    }

    // MARK: The court

    private var court: some View {
        // A view-only device keeps its halves tappable: the tap is answered
        // with a note saying why it did not count, which is kinder than a
        // dead screen.
        let canTap = winner == nil
        let stats = store.stats
        return ZStack {
            CourtBackground(
                sport: store.config?.sport ?? Sport.padel,
                server: score.server,
                serveSide: score.serveSide
            )
            VStack(spacing: 0) {
                TeamHalf(
                    team: Team.a,
                    atTop: true,
                    score: score,
                    pointsWon: Int(stats?.teamA.points ?? 0),
                    streak: streak(of: Team.a),
                    enabled: canTap
                ) { tap(Action.pointA) }
                NetStrip(score: score) { tap(Action.undo) }
                TeamHalf(
                    team: Team.b,
                    atTop: false,
                    score: score,
                    pointsWon: Int(stats?.teamB.points ?? 0),
                    streak: streak(of: Team.b),
                    enabled: canTap
                ) { tap(Action.pointB) }
            }
            // A set is worth a little confetti of its own.
            if let event = event, event.kind != .game {
                Confetti(pieces: 60, endless: false)
            }
            // In the half of whoever won it, clear of the net strip and its Undo button.
            VStack(spacing: 0) {
                bannerSlot(Team.a)
                bannerSlot(Team.b)
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .animation(.spring(response: 0.35, dampingFraction: 0.6), value: event?.id)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    private func streak(of team: Team) -> Int {
        guard let stats = store.stats, stats.streakTeam == team else { return 0 }
        return Int(stats.streak)
    }

    private func bannerSlot(_ team: Team) -> some View {
        ZStack {
            if let event = event, event.team == team {
                Text(event.text)
                    .font(.system(size: event.kind == .game ? 26 : 30, weight: .black))
                    .foregroundStyle(event.kind == .game ? Palette.accent : Palette.gold)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 22).fill(Color.black.opacity(0.82)))
                    .padding(.horizontal, 24)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var afterMatchButtons: some View {
        HStack(spacing: 10) {
            BigButton(title: "Result", filled: false) { celebrationDismissed = false }
            if hosting {
                BigButton(title: "New match", filled: true, action: onNewMatch)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // MARK: The result

    private func resultScreen(_ decided: Team) -> some View {
        // Rematch and undo only make sense once the last point has been played.
        let over = score.winner != nil
        var onRematch: (() -> Void)?
        var onAnotherMatch: (() -> Void)?
        var onUndo: (() -> Void)?
        if hosting {
            onAnotherMatch = onNewMatch
            if over { onRematch = { store.rematch() } }
        }
        if store.canScore && over { onUndo = { tap(Action.undo) } }
        return CelebrationView(
            score: score,
            winner: decided,
            stats: store.stats,
            durationMillis: store.durationMillis,
            shareText: Labels.shareText(score, config: store.config, durationMillis: store.durationMillis),
            onRematch: onRematch,
            onNewMatch: onAnotherMatch,
            onUndo: onUndo,
            onDismiss: {
                celebrationDismissed = true
                resultRequested = false
            }
        )
    }

    // MARK: Taps and events

    private func tap(_ action: Action) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        store.tap(action)
    }

    /// Reports a game or a set being won, on whichever device it was scored,
    /// for a couple of seconds.
    private func announceEvent(before: ScoreView, after: ScoreView) {
        guard let found = MatchEvent.between(before, after, id: eventCount + 1) else { return }
        eventCount = found.id
        event = found
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.6) {
            // A newer event has its own timer.
            if event?.id == found.id { event = nil }
        }
    }
}

// MARK: The net

/// The band across the middle: what the next point means, the sets so far,
/// the clock, and undo.
private struct NetStrip: View {
    @EnvironmentObject private var store: CourtStore
    let score: ScoreView
    let onUndo: () -> Void

    var body: some View {
        let headline = self.headline
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(headline.text)
                    .font(.system(size: 18, weight: .black))
                    .foregroundStyle(headline.color)
                    .lineLimit(2)
                    .animation(.easeInOut(duration: 0.2), value: headline.text)
                if score.winner == nil, let startedAt = store.startedAtMillis {
                    // Time since the match started on this device, ticking once a second.
                    TimelineView(.periodic(from: Date(), by: 1)) { timeline in
                        let now = Int64(timeline.date.timeIntervalSince1970 * 1000)
                        detailsText(clock: Labels.clock(now - startedAt))
                    }
                } else if !score.setSummary.isEmpty {
                    detailsText(clock: nil)
                }
            }
            Spacer(minLength: 0)
            Button("Undo", action: onUndo)
                .font(.headline)
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(!(score.canUndo && store.canScore))
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .padding(.vertical, 6)
        // Solid, so the net drawn behind it does not strike through the text.
        .background(RoundedRectangle(cornerRadius: 18).fill(Palette.netBand))
        .padding(.horizontal, 10)
    }

    private var headline: (text: String, color: Color) {
        if let note = Labels.tapFeedback(store.lastFeedback) {
            return (note, Palette.danger)
        }
        var callouts: [String] = []
        if score.winner == nil, let highlight = Labels.highlight(score, config: store.config) {
            callouts.append(highlight)
        }
        if score.changeEnds { callouts.append("CHANGE ENDS") }
        if !callouts.isEmpty {
            return (callouts.joined(separator: " · "), Palette.accent)
        }
        if let winner = score.winner {
            return ("\(score.nameOf(team: winner).uppercased()) WON", Palette.gold)
        }
        if let decided = score.decidedWinner {
            return ("\(score.nameOf(team: decided).uppercased()) WON · PLAYING SET \(score.setNumber)", Palette.gold)
        }
        return ("SET \(score.setNumber)", Palette.muted)
    }

    private func detailsText(clock: String?) -> some View {
        var parts: [String] = []
        if !score.setSummary.isEmpty { parts.append(score.setSummary) }
        if let clock = clock { parts.append(clock) }
        return Text(parts.joined(separator: "   "))
            .font(.system(size: 17, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(.white)
            .lineLimit(1)
    }
}

// MARK: Games and sets as they are won

/// Something worth a moment's fanfare, and the team that earned it. `id`
/// makes two identical events in a row distinct.
private struct MatchEvent {
    enum Kind { case game, setWon, decided }

    let kind: Kind
    let team: Team
    let text: String
    let id: Int

    /// What was just won between `before` and `after`, if anything. An undo is never an event.
    static func between(_ before: ScoreView, _ after: ScoreView, id: Int) -> MatchEvent? {
        // The end of the match has a screen of its own.
        if after.winner != nil { return nil }
        if after.completedSets.count == before.completedSets.count + 1 {
            guard let lastSet = after.completedSets.last else { return nil }
            if let decided = after.decidedWinner, before.decidedWinner == nil {
                let text = "🏆 \(after.nameOf(team: decided).uppercased()) WON THE MATCH"
                return MatchEvent(kind: .decided, team: decided, text: text, id: id)
            }
            let text = "SET · \(after.nameOf(team: lastSet.winner).uppercased())"
            return MatchEvent(kind: .setWon, team: lastSet.winner, text: text, id: id)
        }
        if after.completedSets.count != before.completedSets.count { return nil }
        if after.gamesA == before.gamesA + 1 && after.gamesB == before.gamesB {
            return MatchEvent(kind: .game, team: Team.a, text: "GAME · \(after.nameA.uppercased())", id: id)
        }
        if after.gamesB == before.gamesB + 1 && after.gamesA == before.gamesA {
            return MatchEvent(kind: .game, team: Team.b, text: "GAME · \(after.nameB.uppercased())", id: id)
        }
        return nil
    }
}

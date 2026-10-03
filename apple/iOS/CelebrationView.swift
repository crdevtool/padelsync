import PadelSyncCore
import SwiftUI

/// Confetti falling over whatever is behind it.
///
/// With `endless` it keeps falling for as long as it is on screen. Without,
/// every piece falls once, which makes a short burst.
struct Confetti: View {
    /// One piece of confetti. Positions are fractions of the width; speeds are in points per second.
    private struct Piece {
        let x: CGFloat
        let delay: Double
        let fallSpeed: CGFloat
        let sway: CGFloat
        let swayPhase: Double
        let size: CGFloat
        /// Degrees per second.
        let spin: Double
        let color: Color
    }

    private static let colors: [Color] = [
        Palette.accent,
        Palette.teamA,
        Palette.teamB,
        Palette.gold,
        Color(red: 1.0, green: 0x5E / 255, blue: 0x8A / 255),
        Color.white,
    ]

    private let endless: Bool
    @State private var pieces: [Piece]
    @State private var start = Date()

    init(pieces: Int = 110, endless: Bool = true) {
        self.endless = endless
        var made: [Piece] = []
        for _ in 0..<pieces {
            made.append(
                Piece(
                    x: CGFloat.random(in: 0...1),
                    delay: Double.random(in: 0...1) * (endless ? 2.5 : 0.6),
                    // A burst has to be over in a couple of seconds, so it falls faster.
                    fallSpeed: CGFloat.random(in: 170...400) * (endless ? 1 : 1.9),
                    sway: CGFloat.random(in: 8...30),
                    swayPhase: Double.random(in: 0...6.28),
                    size: CGFloat.random(in: 7...15),
                    spin: Double.random(in: -360...360),
                    color: Confetti.colors[Int.random(in: 0..<Confetti.colors.count)]
                )
            )
        }
        _pieces = State(initialValue: made)
    }

    var body: some View {
        // Copies for the renderers, which may run away from the main thread.
        let pieces = self.pieces
        let endless = self.endless
        let start = self.start
        Group {
            if Motion.still {
                // A single frame, part-way down.
                Canvas { context, size in
                    Confetti.draw(&context, size: size, seconds: 1.4, pieces: pieces, endless: endless)
                }
            } else {
                TimelineView(.animation) { timeline in
                    let seconds = timeline.date.timeIntervalSince(start)
                    Canvas { context, size in
                        Confetti.draw(&context, size: size, seconds: seconds, pieces: pieces, endless: endless)
                    }
                }
            }
        }
        // Decoration only: taps go through it, and VoiceOver skips it.
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    nonisolated private static func draw(
        _ context: inout GraphicsContext,
        size: CGSize,
        seconds: Double,
        pieces: [Piece],
        endless: Bool
    ) {
        let margin: CGFloat = 24
        let distance = size.height + 2 * margin
        for piece in pieces {
            let elapsed = seconds - piece.delay
            if elapsed < 0 { continue }
            let fallen = CGFloat(elapsed) * piece.fallSpeed
            if !endless && fallen > distance { continue }
            let y = fallen.truncatingRemainder(dividingBy: distance) - margin
            let swing = CGFloat(sin(elapsed * 2.2 + piece.swayPhase))
            let x = piece.x * size.width + swing * piece.sway
            // A copy, so the turn applies to this piece only.
            var turned = context
            turned.translateBy(x: x, y: y)
            turned.rotate(by: Angle(degrees: elapsed * piece.spin))
            let shape = CGRect(x: -piece.size / 2, y: -piece.size / 4, width: piece.size, height: piece.size / 2)
            turned.fill(Path(shape), with: .color(piece.color))
        }
    }
}

/// The end of a match: confetti, the winners, the score and a few numbers to
/// argue about afterwards.
///
/// `onRematch`, `onNewMatch` and `onUndo` are nil when this device may not do
/// that; the button is then left out.
struct CelebrationView: View {
    let score: ScoreView
    let winner: Team
    let stats: MatchStats?
    /// How long the match took, or nil if unknown.
    let durationMillis: Int64?
    let shareText: String
    let onRematch: (() -> Void)?
    let onNewMatch: (() -> Void)?
    let onUndo: (() -> Void)?
    let onDismiss: () -> Void

    @State private var trophyScale = 0.2

    var body: some View {
        ZStack {
            Color.black.opacity(0.94)
                .ignoresSafeArea()
                // Swallow taps so nothing underneath can be scored by accident.
                .onTapGesture {}
            Confetti()
            GeometryReader { proxy in
                ScrollView {
                    content
                        .padding(.horizontal, 24)
                        .padding(.vertical, 20)
                        .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                }
            }
        }
        .onAppear {
            // The trophy drops in and settles with a bounce.
            withAnimation(.spring(response: 0.6, dampingFraction: 0.4)) { trophyScale = 1 }
        }
    }

    private var content: some View {
        VStack(spacing: 8) {
            headline
            if let stats = stats, stats.totalPoints > 0 {
                StatsTable(score: score, stats: stats)
                    .padding(.top, 10)
            }
            actions
        }
    }

    private var headline: some View {
        VStack(spacing: 8) {
            Text("🏆")
                .font(.system(size: 64))
                .scaleEffect(trophyScale)
                .accessibilityHidden(true)
            Text("CONGRATULATIONS")
                .font(.system(size: 18, weight: .black))
                .foregroundStyle(Palette.gold)
            Text(Labels.winnerHeadline(score, winner))
                .font(.system(size: 36, weight: .black))
                .foregroundStyle(winner == Team.a ? Palette.teamA : Palette.teamB)
                .multilineTextAlignment(.center)
            Text(score.setSummary)
                .font(.system(size: 34, weight: .black, design: .rounded))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
            if let durationMillis = durationMillis, durationMillis >= 60_000 {
                Text("Played in \(Labels.duration(durationMillis))")
                    .font(.callout)
                    .foregroundStyle(Palette.muted)
            }
        }
    }

    private var actions: some View {
        VStack(spacing: 10) {
            ShareLink(item: shareText) {
                BigButtonLabel(title: "Share the result", filled: true)
            }
            .buttonStyle(.plain)
            if let onRematch = onRematch {
                BigButton(title: "Rematch, same players", filled: false, action: onRematch)
            }
            if let onNewMatch = onNewMatch {
                BigButton(title: "New match", filled: false, action: onNewMatch)
            }
            HStack(spacing: 24) {
                Button("Scoreboard", action: onDismiss)
                if let onUndo = onUndo {
                    Button("Undo last point", action: onUndo)
                }
            }
            .font(.callout.weight(.bold))
            .padding(.top, 4)
        }
        .padding(.top, 8)
    }
}

/// Both teams' numbers side by side.
private struct StatsTable: View {
    let score: ScoreView
    let stats: MatchStats

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Text(score.nameA)
                    .foregroundStyle(Palette.teamA)
                Spacer(minLength: 8)
                Text(score.nameB)
                    .foregroundStyle(Palette.teamB)
            }
            .font(.system(size: 15, weight: .black))
            .lineLimit(1)
            row("Points won", Int(stats.teamA.points), Int(stats.teamB.points))
            row("Games won", Int(stats.teamA.games), Int(stats.teamB.games))
            row("Breaks of serve", Int(stats.teamA.breaks), Int(stats.teamB.breaks))
            row("Best run of points", Int(stats.teamA.longestStreak), Int(stats.teamB.longestStreak))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 18).fill(Palette.surface.opacity(0.9)))
    }

    private func row(_ label: String, _ a: Int, _ b: Int) -> some View {
        HStack {
            Text("\(a)")
                .font(.system(size: 22, weight: .black, design: .rounded))
                .foregroundStyle(a > b ? Color.white : Palette.muted)
                .frame(width: 56, alignment: .leading)
            Spacer(minLength: 0)
            Text(label)
                .font(.footnote)
                .foregroundStyle(Palette.muted)
            Spacer(minLength: 0)
            Text("\(b)")
                .font(.system(size: 22, weight: .black, design: .rounded))
                .foregroundStyle(b > a ? Color.white : Palette.muted)
                .frame(width: 56, alignment: .trailing)
        }
    }
}

import PadelSyncCore
import SwiftUI

/// One team's half of the court. The whole half is the button that scores a
/// point for that team.
///
/// The two halves mirror each other about the net: each team's name sits by
/// its own back wall, where its players stand.
struct TeamHalf: View {
    let team: Team
    let atTop: Bool
    let score: ScoreView
    /// The team's points in the whole match, used only to notice that it has just won one.
    let pointsWon: Int
    /// Points this team has now won in a row, or 0.
    let streak: Int
    let enabled: Bool
    let action: () -> Void

    @State private var flash = 0.0

    var body: some View {
        let color = team == Team.a ? Palette.teamA : Palette.teamB
        let name = score.nameOf(team: team)
        let points = score.pointsOf(team: team)
        let games = Int(score.gamesOf(team: team))
        let sets = Int(score.setsOf(team: team))
        let serving = score.server == team

        Button(action: action) {
            ZStack {
                // The half lights up in the team's colour for an instant when it wins a point.
                RoundedRectangle(cornerRadius: 18)
                    .fill(color)
                    .opacity(flash)
                VStack(spacing: 0) {
                    if atTop {
                        NameRow(name: name, color: color, streak: streak)
                        ServeRow(team: team, score: score)
                        PointsArea(points: points, games: games, sets: sets)
                    } else {
                        PointsArea(points: points, games: games, sets: sets)
                        ServeRow(team: team, score: score)
                        NameRow(name: name, color: color, streak: streak)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
        // Not `.disabled`: that would grey out the final score when the
        // match is over. The half just stops responding to taps.
        .allowsHitTesting(enabled)
        .padding(6)
        // The walkthrough test finds the halves by this description; keep
        // the two in step. It is the same wording as on Android.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(name). Points \(points). Games \(games). Sets \(sets)." + (serving ? " Serving." : "")
        )
        .accessibilityHint("Adds a point for \(name)")
        .accessibilityAddTraits(.isButton)
        .onChange(of: pointsWon) { before, after in
            if after > before { flashOnce() }
        }
    }

    private func flashOnce() {
        withAnimation(.easeOut(duration: 0.08)) { flash = 0.42 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
            withAnimation(.easeOut(duration: 0.5)) { flash = 0 }
        }
    }
}

/// Dark outline behind the big numbers, so they read against turf in full sun.
private struct OnCourtShadow: ViewModifier {
    func body(content: Content) -> some View {
        content.shadow(color: Color.black.opacity(0.55), radius: 5, x: 0, y: 3)
    }
}

private struct NameRow: View {
    let name: String
    let color: Color
    let streak: Int

    var body: some View {
        let onARoll = streak >= 3
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 14, height: 14)
            Text(name.uppercased())
                .font(.system(size: 20, weight: .black))
                .foregroundStyle(.white)
                .lineLimit(1)
                .modifier(OnCourtShadow())
            // Three points in a row is a run worth pointing out.
            if onARoll {
                Text("🔥 \(streak) IN A ROW")
                    .font(.system(size: 13, weight: .black))
                    .foregroundStyle(Palette.onAccent)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Palette.gold))
                    .transition(.scale.combined(with: .opacity))
            }
            Spacer(minLength: 0)
        }
        .animation(.easeOut(duration: 0.2), value: onARoll)
    }
}

/// Where the server stands: a ball and the server's name, on the left or the
/// right of the half, sliding across after each point.
private struct ServeRow: View {
    let team: Team
    let score: ScoreView

    var body: some View {
        let side = score.serveSide
        let serving = score.server == team && side != nil
        let onLeft = CourtGeometry.serverOnScreenLeft(server: team, side: side ?? ServeSide.right)

        ZStack {
            if serving {
                HStack(spacing: 8) {
                    BouncingBall()
                    HStack(spacing: 0) {
                        Text(serverLabel)
                            .foregroundStyle(Palette.ball)
                        if let side = side {
                            Text(" · \(Labels.serveSide(side).uppercased())")
                                .foregroundStyle(.white)
                        }
                    }
                    .font(.system(size: 15, weight: .black))
                    .lineLimit(1)
                }
                .padding(.leading, 8)
                .padding(.trailing, 12)
                .padding(.vertical, 5)
                .background(Capsule().fill(Color.black.opacity(0.6)))
                .transition(.opacity)
            }
        }
        .frame(height: 38)
        .frame(maxWidth: .infinity, alignment: onLeft ? .leading : .trailing)
        .animation(.spring(response: 0.45, dampingFraction: 0.75), value: onLeft)
        .animation(.easeInOut(duration: 0.2), value: serving)
    }

    /// Who serves: `ANA`, `PLAYER 2` when the name is not known, or just
    /// `SERVE` in singles. The ball beside it says the rest.
    private var serverLabel: String {
        let players = score.playersOf(team: team)
        let index = Int(score.serverPlayerIndex)
        if index < players.count {
            let name = players[index].uppercased()
            // A long name gives way; the side never does.
            return name.count > 12 ? String(name.prefix(11)) + "…" : name
        }
        return score.doubles ? "PLAYER \(index + 1)" : "SERVE"
    }
}

/// A tennis ball that never quite sits still.
private struct BouncingBall: View {
    @State private var grown = false

    var body: some View {
        ZStack {
            Circle().fill(Palette.ball)
            // The seam: two arcs curving away from each other.
            Circle()
                .stroke(Color.white.opacity(0.9), lineWidth: 1.8)
                .offset(x: -12.4)
            Circle()
                .stroke(Color.white.opacity(0.9), lineWidth: 1.8)
                .offset(x: 12.4)
        }
        .frame(width: 20, height: 20)
        .clipShape(Circle())
        .scaleEffect(Motion.still ? 1 : (grown ? 1.08 : 0.82))
        .onAppear {
            if Motion.still { return }
            withAnimation(.easeInOut(duration: 0.62).repeatForever(autoreverses: true)) { grown = true }
        }
    }
}

/// The big number for the current game, with games and sets beside it.
private struct PointsArea: View {
    let points: String
    let games: Int
    let sets: Int

    var body: some View {
        ZStack {
            Text(points)
                .font(.system(size: 112, weight: .black, design: .rounded))
                .minimumScaleFactor(0.4)
                .lineLimit(1)
                .foregroundStyle(.white)
                .modifier(OnCourtShadow())
                .contentTransition(.numericText())
                .animation(.spring(response: 0.3, dampingFraction: 0.7), value: points)
            HStack {
                Spacer(minLength: 0)
                VStack(alignment: .trailing, spacing: 2) {
                    Counter(label: "GAMES", value: games)
                    Counter(label: "SETS", value: sets)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A small count that swells for a moment when it goes up.
private struct Counter: View {
    let label: String
    let value: Int

    @State private var scale = 1.0

    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text("\(value)")
                .font(.system(size: 34, weight: .black, design: .rounded))
                .foregroundStyle(.white)
                .modifier(OnCourtShadow())
                .scaleEffect(scale, anchor: .trailing)
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(Color.white.opacity(0.85))
        }
        .onChange(of: value) { before, after in
            if after > before { bump() }
        }
    }

    private func bump() {
        withAnimation(.easeOut(duration: 0.1)) { scale = 1.7 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.5)) { scale = 1 }
        }
    }
}

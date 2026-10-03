import PadelSyncCore
import SwiftUI

/// The court seen from above, as the backdrop of the scoreboard: turf, lines,
/// net, and a glow on the box the next serve must land in.
///
/// `server` is the team serving next, or nil when nobody is (the match is
/// over), which switches the glow off.
struct CourtBackground: View {
    let sport: Sport
    let server: Team?
    let serveSide: ServeSide?

    var body: some View {
        let geometry = CourtGeometry.of(sport)
        let padel = sport == Sport.padel
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                Canvas { context, size in
                    CourtBackground.drawSurface(&context, size: size, padel: padel, geometry: geometry)
                }
                // One glow per service box, so it fades out where it was and
                // in where it now is, rather than jumping.
                glow(geometry.targetBox(server: Team.a, side: ServeSide.right), on: serving(Team.a, ServeSide.right), in: proxy.size)
                glow(geometry.targetBox(server: Team.a, side: ServeSide.left), on: serving(Team.a, ServeSide.left), in: proxy.size)
                glow(geometry.targetBox(server: Team.b, side: ServeSide.right), on: serving(Team.b, ServeSide.right), in: proxy.size)
                glow(geometry.targetBox(server: Team.b, side: ServeSide.left), on: serving(Team.b, ServeSide.left), in: proxy.size)
                Canvas { context, size in
                    CourtBackground.drawLines(&context, size: size, padel: padel, geometry: geometry)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func serving(_ team: Team, _ side: ServeSide) -> Bool {
        server == team && serveSide == side
    }

    private func glow(_ box: CourtGeometry.Area, on: Bool, in size: CGSize) -> some View {
        let rect = box.rect(in: size)
        return Rectangle()
            .fill(Palette.ball.opacity(0.20))
            .frame(width: rect.width, height: rect.height)
            .offset(x: rect.minX, y: rect.minY)
            .opacity(on ? 1 : 0)
            .animation(.easeInOut(duration: 0.35), value: on)
    }

    // The two drawing helpers touch nothing of the view, and are marked so:
    // a canvas may call its renderer away from the main thread.
    nonisolated private static func drawSurface(
        _ context: inout GraphicsContext,
        size: CGSize,
        padel: Bool,
        geometry: CourtGeometry
    ) {
        let whole = Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 22)
        let deep = padel ? Palette.padelTurfDeep : Palette.tennisSurroundDeep
        let lit = padel ? Palette.padelTurf : Palette.tennisSurround
        let gradient = Gradient(stops: [
            Gradient.Stop(color: deep, location: 0),
            Gradient.Stop(color: lit, location: 0.5),
            Gradient.Stop(color: deep, location: 1),
        ])
        context.fill(
            whole,
            with: .linearGradient(
                gradient,
                startPoint: CGPoint(x: size.width / 2, y: 0),
                endPoint: CGPoint(x: size.width / 2, y: size.height)
            )
        )
        if !padel {
            context.fill(Path(geometry.bounds.rect(in: size)), with: .color(Palette.tennisCourt))
        }
    }

    nonisolated private static func drawLines(
        _ context: inout GraphicsContext,
        size: CGSize,
        padel: Bool,
        geometry: CourtGeometry
    ) {
        let line = Palette.courtLine.opacity(0.78)
        let width: CGFloat = 2.5
        let court = geometry.bounds.rect(in: size)

        // The outline: glass walls in padel, baselines and sidelines in tennis.
        if padel {
            context.stroke(Path(roundedRect: court, cornerRadius: 12), with: .color(line), lineWidth: width * 1.4)
        } else {
            context.stroke(Path(court), with: .color(line), lineWidth: width)
        }

        for segment in geometry.lines {
            var path = Path()
            path.move(to: CGPoint(x: segment.x1 * size.width, y: segment.y1 * size.height))
            path.addLine(to: CGPoint(x: segment.x2 * size.width, y: segment.y2 * size.height))
            context.stroke(path, with: .color(line), lineWidth: width)
        }

        // The net: a shadow, the tape, and a post at each end.
        let netY = geometry.netY * size.height
        let overhang: CGFloat = 6
        let netStart = CGPoint(x: court.minX - overhang, y: netY)
        let netEnd = CGPoint(x: court.maxX + overhang, y: netY)

        var shadow = Path()
        shadow.move(to: CGPoint(x: netStart.x, y: netY + 4))
        shadow.addLine(to: CGPoint(x: netEnd.x, y: netY + 4))
        context.stroke(shadow, with: .color(Color.black.opacity(0.35)), lineWidth: 7)

        var tape = Path()
        tape.move(to: netStart)
        tape.addLine(to: netEnd)
        context.stroke(tape, with: .color(Palette.courtLine), style: StrokeStyle(lineWidth: 4, lineCap: .round))

        for post in [netStart, netEnd] {
            let dot = CGRect(x: post.x - 5, y: post.y - 5, width: 10, height: 10)
            context.fill(Path(ellipseIn: dot), with: .color(Palette.courtLine))
        }
    }
}

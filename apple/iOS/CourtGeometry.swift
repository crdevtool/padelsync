import CoreGraphics
import PadelSyncCore

/// A court drawn from above with the net running across the middle, in
/// fractions of the space available: (0, 0) is the top-left corner, (1, 1)
/// the bottom-right. Team A plays in the top half and team B in the bottom.
///
/// Pure numbers, no drawing: `CourtBackground` turns them into points. The
/// same figures as the Android app's `CourtGeometry`.
struct CourtGeometry {
    /// A rectangle, as fractions of the canvas.
    struct Area {
        let left: CGFloat
        let top: CGFloat
        let right: CGFloat
        let bottom: CGFloat

        func rect(in size: CGSize) -> CGRect {
            CGRect(
                x: left * size.width,
                y: top * size.height,
                width: (right - left) * size.width,
                height: (bottom - top) * size.height
            )
        }
    }

    /// A straight line, as fractions of the canvas.
    struct Line {
        let x1: CGFloat
        let y1: CGFloat
        let x2: CGFloat
        let y2: CGFloat
    }

    /// The playing area. In padel it is also where the walls stand.
    let bounds: Area
    /// Lines painted on the court, the outline excluded.
    let lines: [Line]
    /// The four boxes a serve must land in.
    let boxTopLeft: Area
    let boxTopRight: Area
    let boxBottomLeft: Area
    let boxBottomRight: Area

    var netY: CGFloat { 0.5 }

    /// The box a serve from `server` on `side` must land in: always the one
    /// diagonally opposite.
    func targetBox(server: Team, side: ServeSide) -> Area {
        let fromScreenLeft = CourtGeometry.serverOnScreenLeft(server: server, side: side)
        if server == Team.a {
            return fromScreenLeft ? boxBottomRight : boxBottomLeft
        }
        return fromScreenLeft ? boxTopRight : boxTopLeft
    }

    /// Whether a server on `side` stands on the left of the screen.
    ///
    /// Sides are named from the server's own point of view. Team A, at the
    /// top, faces down the screen, so its right is the viewer's left.
    static func serverOnScreenLeft(server: Team, side: ServeSide) -> Bool {
        (server == Team.a) == (side == ServeSide.right)
    }

    static func of(_ sport: Sport) -> CourtGeometry {
        sport == Sport.padel ? padel() : tennis()
    }

    /// Padel: 20 m by 10 m inside the walls, service lines 6.95 m from the
    /// net, and a centre line between them.
    private static func padel() -> CourtGeometry {
        let bounds = Area(left: 0.03, top: 0.015, right: 0.97, bottom: 0.985)
        let halfLength = (bounds.bottom - bounds.top) / 2
        let service = halfLength * (6.95 / 10)
        let topService = 0.5 - service
        let bottomService = 0.5 + service
        return CourtGeometry(
            bounds: bounds,
            lines: [
                Line(x1: bounds.left, y1: topService, x2: bounds.right, y2: topService),
                Line(x1: bounds.left, y1: bottomService, x2: bounds.right, y2: bottomService),
                Line(x1: 0.5, y1: topService, x2: 0.5, y2: bottomService),
            ],
            boxTopLeft: Area(left: bounds.left, top: topService, right: 0.5, bottom: 0.5),
            boxTopRight: Area(left: 0.5, top: topService, right: bounds.right, bottom: 0.5),
            boxBottomLeft: Area(left: bounds.left, top: 0.5, right: 0.5, bottom: bottomService),
            boxBottomRight: Area(left: 0.5, top: 0.5, right: bounds.right, bottom: bottomService)
        )
    }

    /// Tennis: 23.77 m by 10.97 m with the doubles alleys, singles lines
    /// 1.37 m in, service lines 6.40 m from the net, and room to run around
    /// the outside.
    private static func tennis() -> CourtGeometry {
        let bounds = Area(left: 0.09, top: 0.07, right: 0.91, bottom: 0.93)
        let width = bounds.right - bounds.left
        let halfLength = (bounds.bottom - bounds.top) / 2
        let alley = width * (1.37 / 10.97)
        let singlesLeft = bounds.left + alley
        let singlesRight = bounds.right - alley
        let service = halfLength * (6.40 / 11.885)
        let topService = 0.5 - service
        let bottomService = 0.5 + service
        let mark: CGFloat = 0.012
        return CourtGeometry(
            bounds: bounds,
            lines: [
                Line(x1: singlesLeft, y1: bounds.top, x2: singlesLeft, y2: bounds.bottom),
                Line(x1: singlesRight, y1: bounds.top, x2: singlesRight, y2: bounds.bottom),
                Line(x1: singlesLeft, y1: topService, x2: singlesRight, y2: topService),
                Line(x1: singlesLeft, y1: bottomService, x2: singlesRight, y2: bottomService),
                Line(x1: 0.5, y1: topService, x2: 0.5, y2: bottomService),
                // Centre marks on the baselines.
                Line(x1: 0.5, y1: bounds.top, x2: 0.5, y2: bounds.top + mark),
                Line(x1: 0.5, y1: bounds.bottom - mark, x2: 0.5, y2: bounds.bottom),
            ],
            boxTopLeft: Area(left: singlesLeft, top: topService, right: 0.5, bottom: 0.5),
            boxTopRight: Area(left: 0.5, top: topService, right: singlesRight, bottom: 0.5),
            boxBottomLeft: Area(left: singlesLeft, top: 0.5, right: 0.5, bottom: bottomService),
            boxBottomRight: Area(left: 0.5, top: 0.5, right: singlesRight, bottom: bottomService)
        )
    }
}

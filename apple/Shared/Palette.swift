import SwiftUI

/// Colours chosen for a court in full sun: pure black behind, pure white for
/// the numbers, and two team colours that stay distinct for colour-blind
/// players (blue and orange). Identical to the Android palette.
enum Palette {
    static let surface = Color(red: 0x23 / 255, green: 0x2A / 255, blue: 0x36 / 255)
    static let muted = Color(red: 0xB4 / 255, green: 0xBC / 255, blue: 0xC8 / 255)
    static let teamA = Color(red: 0x4D / 255, green: 0xA3 / 255, blue: 0xFF / 255)
    static let teamB = Color(red: 0xFF / 255, green: 0x9B / 255, blue: 0x3D / 255)
    static let accent = Color(red: 0xD7 / 255, green: 0xF2 / 255, blue: 0x3C / 255)
    static let onAccent = Color(red: 0x0B / 255, green: 0x1E / 255, blue: 0x3A / 255)
    static let danger = Color(red: 0xFF / 255, green: 0x6B / 255, blue: 0x6B / 255)

    /// A padel court's blue turf, lit at the net and darker towards the back walls.
    static let padelTurf = Color(red: 0x12 / 255, green: 0x57 / 255, blue: 0xC2 / 255)
    static let padelTurfDeep = Color(red: 0x08 / 255, green: 0x2E / 255, blue: 0x73 / 255)

    /// A hard tennis court: green surround, blue playing area.
    static let tennisSurround = Color(red: 0x1F / 255, green: 0x6B / 255, blue: 0x45 / 255)
    static let tennisSurroundDeep = Color(red: 0x0F / 255, green: 0x3D / 255, blue: 0x27 / 255)
    static let tennisCourt = Color(red: 0x1F / 255, green: 0x5F / 255, blue: 0xA8 / 255)

    static let courtLine = Color(red: 0xF4 / 255, green: 0xF7 / 255, blue: 0xFB / 255)

    /// The band across the net that carries the call-outs and Undo.
    static let netBand = Color(red: 0x0C / 255, green: 0x15 / 255, blue: 0x24 / 255)
    static let ball = Color(red: 0xD7 / 255, green: 0xF2 / 255, blue: 0x3C / 255)
    static let gold = Color(red: 0xFF / 255, green: 0xD2 / 255, blue: 0x4A / 255)
}

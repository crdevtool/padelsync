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
}

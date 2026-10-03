import CoreBluetooth

/// Bluetooth identifiers of a court session. These are part of the protocol
/// and must be identical in the Android and Apple apps; see docs/ble-protocol.md.
enum CourtUuids {
    /// The court service a host advertises.
    static let service = CBUUID(string: "5AD31000-7C4E-4B6F-9D2A-8E3F1B0C9A71")

    /// Guest to host: written with response.
    static let toHost = CBUUID(string: "5AD31001-7C4E-4B6F-9D2A-8E3F1B0C9A71")

    /// Host to guest: notifications.
    static let fromHost = CBUUID(string: "5AD31002-7C4E-4B6F-9D2A-8E3F1B0C9A71")

    /// Longest host label that is advertised, in UTF-8 bytes. The same limit
    /// as on Android, where it is all that fits: a court taken over by a
    /// device of the other kind must be able to carry the same name.
    static let maxLabelBytes = 12

    /// A host's name as guests see it: cut to what is advertised, without
    /// splitting a character. A host looking for another court under its own
    /// name has to look for this, not for the full name.
    static func advertisedLabel(_ name: String) -> String {
        var cut = String.UnicodeScalarView()
        var bytes = 0
        for scalar in name.unicodeScalars {
            let size = String(scalar).utf8.count
            if bytes + size > maxLabelBytes { break }
            cut.append(scalar)
            bytes += size
        }
        return String(cut)
    }
}

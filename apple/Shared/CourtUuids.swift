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
}

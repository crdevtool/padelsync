import Foundation
import PadelSyncCore
#if os(watchOS)
import WatchKit
#else
import UIKit
#endif

/// This device's stable id and display name within court sessions.
enum DeviceIdentity {
    private static let idKey = "device_id"

    /// Random id created on first launch. Not tied to any hardware identifier.
    static var deviceId: Int64 {
        let defaults = UserDefaults.standard
        if let stored = defaults.object(forKey: idKey) as? NSNumber, stored.int64Value != 0 {
            return stored.int64Value
        }
        let created = Sessions.shared.createId()
        defaults.set(NSNumber(value: created), forKey: idKey)
        return created
    }

    /// Name shown to other players.
    static var deviceName: String {
        #if os(watchOS)
        return WKInterfaceDevice.current().name
        #else
        return UIDevice.current.name
        #endif
    }

    static var deviceKind: DeviceKind {
        #if os(watchOS)
        return DeviceKind.watch
        #else
        return DeviceKind.phone
        #endif
    }
}

import Foundation
import PadelSyncCore

// The shared core speaks Kotlin byte arrays; Core Bluetooth speaks Data.
// Packets are at most a few hundred bytes, so a plain copy is fine.

extension KotlinByteArray {
    func toData() -> Data {
        var data = Data(count: Int(size))
        for index in 0..<size {
            data[Int(index)] = UInt8(bitPattern: get(index: index))
        }
        return data
    }
}

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

// Kotlin default arguments and `copy` do not reach Swift: every argument has
// to be passed. These two helpers keep that in one place.

extension Roster {
    /// A roster with no names, for a match set up without typing any.
    static var noNames: Roster {
        Sessions.shared.roster(playerA1: "", playerA2: "", playerB1: "", playerB2: "")
    }
}

extension SpeechSettings {
    /// A copy with the given values replaced and the rest kept.
    func with(
        enabled: Bool? = nil,
        points: Bool? = nil,
        games: Bool? = nil,
        stakes: Bool? = nil,
        server: Bool? = nil,
        changeEnds: Bool? = nil,
        reminderMinutes: Int? = nil
    ) -> SpeechSettings {
        // The core refuses a reminder outside 0...60 minutes.
        let minutes = min(max(reminderMinutes ?? Int(self.reminderMinutes), 0), 60)
        return SpeechSettings(
            enabled: enabled ?? self.enabled,
            points: points ?? self.points,
            games: games ?? self.games,
            stakes: stakes ?? self.stakes,
            server: server ?? self.server,
            changeEnds: changeEnds ?? self.changeEnds,
            reminderMinutes: Int32(minutes)
        )
    }
}

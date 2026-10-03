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

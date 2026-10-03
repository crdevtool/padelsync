import CoreBluetooth
import Foundation

/// The Bluetooth side of hosting a court on an iPhone: the service guests
/// connect to, and the advertisement that lets them find it.
///
/// It only moves packets; the shared `HostSession` decides what they mean.
/// Apple Watch cannot do this (watchOS does not allow advertising), which is
/// why this file is in the iOS target only. Everything runs on the main queue.
final class HostTransport: NSObject, CBPeripheralManagerDelegate {

    /// A guest switched notifications on. The value is the most bytes one packet to it may carry.
    var onGuestReady: ((String, Int) -> Void)?
    var onGuestGone: ((String) -> Void)?
    var onPacket: ((String, Data) -> Void)?
    /// Hosting cannot work; the text is fit to show the user.
    var onFailed: ((String) -> Void)?

    private var manager: CBPeripheralManager?
    private var fromHost: CBMutableCharacteristic?
    private var guests: [String: CBCentral] = [:]
    private var outbox: [(peerId: String, packet: Data)] = []
    private var label = "PadelSync"
    private var serviceAdded = false

    /// Opens the court under `label`, cut to the length that is advertised.
    /// Safe to call before Bluetooth is ready.
    func start(label: String) {
        self.label = CourtUuids.advertisedLabel(label)
        if manager == nil {
            // Creating the manager triggers the system's Bluetooth permission prompt.
            manager = CBPeripheralManager(delegate: self, queue: .main)
        } else {
            openIfPossible()
        }
    }

    /// Stops advertising and drops every guest.
    func stop() {
        if let manager, manager.state == .poweredOn {
            manager.stopAdvertising()
            manager.removeAllServices()
        }
        serviceAdded = false
        fromHost = nil
        guests.removeAll()
        outbox.removeAll()
        manager?.delegate = nil
        manager = nil
    }

    /// Queues packets for one guest. Packets for a guest that has gone are dropped.
    func send(peerId: String, packets: [Data]) {
        for packet in packets { outbox.append((peerId, packet)) }
        pump()
    }

    private func openIfPossible() {
        guard let manager, manager.state == .poweredOn, !serviceAdded else { return }
        let write = CBMutableCharacteristic(
            type: CourtUuids.toHost,
            properties: [.write],
            value: nil,
            permissions: [.writeable]
        )
        let notify = CBMutableCharacteristic(
            type: CourtUuids.fromHost,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: CourtUuids.service, primary: true)
        service.characteristics = [write, notify]
        fromHost = notify
        serviceAdded = true
        manager.add(service)
    }

    /// Sends queued notifications until the system's transmit queue is full;
    /// `peripheralManagerIsReady` resumes it.
    private func pump() {
        guard let manager, let fromHost else { return }
        while let next = outbox.first {
            guard let guest = guests[next.peerId] else {
                outbox.removeFirst()
                continue
            }
            if manager.updateValue(next.packet, for: fromHost, onSubscribedCentrals: [guest]) {
                outbox.removeFirst()
            } else {
                return
            }
        }
    }

    // MARK: CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            openIfPossible()
        case .unauthorized:
            onFailed?("Allow Bluetooth for PadelSync in Settings to play with others.")
        case .poweredOff:
            onFailed?("Turn on Bluetooth to play with others.")
        case .unsupported:
            onFailed?("This device cannot host over Bluetooth.")
        default:
            break
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if error != nil {
            onFailed?("Could not start hosting over Bluetooth.")
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CourtUuids.service],
            CBAdvertisementDataLocalNameKey: label,
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if error != nil { onFailed?("Could not start Bluetooth advertising.") }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        guard characteristic.uuid == CourtUuids.fromHost else { return }
        let peerId = central.identifier.uuidString
        guests[peerId] = central
        onGuestReady?(peerId, central.maximumUpdateValueLength)
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        guard characteristic.uuid == CourtUuids.fromHost else { return }
        let peerId = central.identifier.uuidString
        if guests.removeValue(forKey: peerId) != nil { onGuestGone?(peerId) }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        for request in requests {
            guard request.characteristic.uuid == CourtUuids.toHost, request.offset == 0, let value = request.value
            else { continue }
            onPacket?(request.central.identifier.uuidString, value)
        }
        // One response answers the whole batch.
        peripheral.respond(to: first, withResult: .success)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        pump()
    }
}

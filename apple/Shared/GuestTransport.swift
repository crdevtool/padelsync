import CoreBluetooth
import Foundation

/// A court found nearby.
struct NearbyCourt: Identifiable {
    let id: UUID
    /// The host's label.
    let name: String
    /// Signal strength in dBm; closer to zero is nearer.
    let rssi: Int
    fileprivate let peripheral: CBPeripheral
}

/// The Bluetooth side of being a guest: finds courts, connects to one,
/// switches notifications on, moves packets both ways, and keeps trying to
/// reconnect when the link drops until `close()` is called.
///
/// It only moves packets; the shared `ClientSession` decides what they mean.
/// Works on both iPhone and Apple Watch. Everything runs on the main queue.
final class GuestTransport: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    /// Courts found by the current scan, nearest first.
    var onCourts: (([NearbyCourt]) -> Void)?
    /// Notifications are on; packets can flow. The value is the most bytes one packet may carry.
    var onLinkUp: ((Int) -> Void)?
    var onLinkDown: (() -> Void)?
    var onPacket: ((Data) -> Void)?
    /// Bluetooth cannot be used; the text is fit to show the user. Nil when it becomes usable again.
    var onProblem: ((String?) -> Void)?

    private var central: CBCentralManager?
    private var wantScan = false
    private var found: [UUID: NearbyCourt] = [:]

    private var peripheral: CBPeripheral?
    private var toHost: CBCharacteristic?
    private var up = false
    private var outbox: [Data] = []
    private var writing = false

    // MARK: Public

    func startScan() {
        wantScan = true
        found.removeAll()
        onCourts?([])
        scanIfPossible()
    }

    func stopScan() {
        wantScan = false
        if let central, central.state == .poweredOn { central.stopScan() }
    }

    /// Connects to `court` and stays connected, reconnecting as needed, until `close()`.
    func connect(to court: NearbyCourt) {
        close()
        stopScan()
        peripheral = court.peripheral
        court.peripheral.delegate = self
        connectIfPossible()
    }

    /// Queues packets for the host. Dropped if the link is down.
    func send(_ packets: [Data]) {
        guard up else { return }
        outbox.append(contentsOf: packets)
        pump()
    }

    /// Disconnects and stops reconnecting.
    func close() {
        let wasUp = up
        let old = peripheral
        peripheral = nil
        resetLink()
        if let old, let central, central.state == .poweredOn {
            central.cancelPeripheralConnection(old)
        }
        if wasUp { onLinkDown?() }
    }

    // MARK: Internals

    /// Creating the manager is what triggers the system's Bluetooth permission
    /// prompt, so it is created on first use rather than at launch.
    private func manager() -> CBCentralManager {
        if let central { return central }
        let created = CBCentralManager(delegate: self, queue: .main)
        central = created
        return created
    }

    private func scanIfPossible() {
        let central = manager()
        guard wantScan, central.state == .poweredOn else { return }
        central.scanForPeripherals(withServices: [CourtUuids.service], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false,
        ])
    }

    private func connectIfPossible() {
        let central = manager()
        guard let peripheral, central.state == .poweredOn else { return }
        // A connection request never times out: if the host is out of range
        // it completes as soon as the host comes back.
        central.connect(peripheral, options: nil)
    }

    private func resetLink() {
        up = false
        writing = false
        outbox.removeAll()
        toHost = nil
    }

    private func linkLost() {
        let wasUp = up
        resetLink()
        if wasUp { onLinkDown?() }
        connectIfPossible()
    }

    /// Writes one packet at a time, waiting for the host to confirm each.
    private func pump() {
        guard up, !writing, let peripheral, let toHost, !outbox.isEmpty else { return }
        writing = true
        peripheral.writeValue(outbox.removeFirst(), for: toHost, type: .withResponse)
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            onProblem?(nil)
            scanIfPossible()
            connectIfPossible()
        case .unauthorized:
            onProblem?("Allow Bluetooth for PadelSync in Settings to play with others.")
        case .poweredOff:
            linkLost()
            onProblem?("Turn on Bluetooth to play with others.")
        case .unsupported:
            onProblem?("This device does not support Bluetooth LE.")
        default:
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // Android hosts put their label in service data; iPhone hosts can
        // only advertise it as the local name.
        var label: String?
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let data = serviceData[CourtUuids.service], !data.isEmpty {
            label = String(data: data, encoding: .utf8)
        }
        if label == nil {
            label = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        }
        let court = NearbyCourt(
            id: peripheral.identifier,
            name: label ?? peripheral.name ?? "Court",
            rssi: RSSI.intValue,
            peripheral: peripheral
        )
        found[court.id] = court
        onCourts?(found.values.sorted { $0.rssi > $1.rssi })
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === self.peripheral else { return }
        peripheral.discoverServices([CourtUuids.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }
        linkLost()
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral === self.peripheral else { return }
        linkLost()
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === self.peripheral else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == CourtUuids.service }) else {
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([CourtUuids.toHost, CourtUuids.fromHost], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard peripheral === self.peripheral else { return }
        let characteristics = service.characteristics ?? []
        guard error == nil,
              let write = characteristics.first(where: { $0.uuid == CourtUuids.toHost }),
              let notify = characteristics.first(where: { $0.uuid == CourtUuids.fromHost })
        else {
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        toHost = write
        peripheral.setNotifyValue(true, for: notify)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === self.peripheral, characteristic.uuid == CourtUuids.fromHost else { return }
        guard error == nil, characteristic.isNotifying else {
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        up = true
        // Each packet must fit a single Bluetooth write. The "without
        // response" limit is that size; the "with response" limit is larger
        // only because it allows multi-part writes, which the protocol does
        // not use.
        onLinkUp?(peripheral.maximumWriteValueLength(for: .withoutResponse))
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral, up, characteristic.uuid == CourtUuids.fromHost,
              let value = characteristic.value
        else { return }
        onPacket?(value)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral else { return }
        writing = false
        if error != nil {
            // Treat a failed write as a broken link: the session re-sends its
            // unresolved tap after reconnecting.
            central?.cancelPeripheralConnection(peripheral)
        } else {
            pump()
        }
    }
}

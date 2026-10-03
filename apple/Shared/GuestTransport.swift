import CoreBluetooth
import Foundation
import PadelSyncCore

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
/// get the link back when it drops until `close()` is called.
///
/// Getting the link back has two parts, as in the Android app's
/// `GuestConnection`. It first keeps retrying the peripheral it joined.
/// Phones change their Bluetooth address from time to time, so after a long
/// gap that retry can never succeed. If the link has been down for a while,
/// it therefore also scans for a court advertising the name that was joined
/// and tries what it finds, while the direct retry carries on.
///
/// A court found this way is only a candidate: the caller's `ClientSession`
/// checks that it carries the same match, and the caller answers with
/// `courtProved()` or `wrongCourt()`. A wrong court is left alone for a
/// while and the search goes on.
///
/// A host that closes its court is not chased any more (`courtClosed()`),
/// but its court is still looked for by name for a few minutes: another
/// player may carry the match on.
///
/// It only moves packets; the shared `ClientSession` decides what they mean.
/// Works on both iPhone and Apple Watch. Everything runs on the main queue.
final class GuestTransport: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    /// Courts found by the current scan, nearest first.
    var onCourts: (([NearbyCourt]) -> Void)?
    /// Notifications are on; packets can flow. The first value is the most
    /// bytes one packet may carry. The second is true if this is a court
    /// found by scanning that still has to be proved the right one.
    var onLinkUp: ((Int, Bool) -> Void)?
    var onLinkDown: (() -> Void)?
    var onPacket: ((Data) -> Void)?
    /// Bluetooth cannot be used; the text is fit to show the user. Nil when it becomes usable again.
    var onProblem: ((String?) -> Void)?

    /// How long the link is down before looking for the court by name.
    private static let lookAfterSeconds: TimeInterval = 15
    /// How often the search starts afresh while nothing has been found.
    private static let lookAgainSeconds: TimeInterval = 5
    /// A court that turned out to be somebody else's is not tried again for this long.
    private static let wrongCourtRestSeconds: TimeInterval = 120
    /// A found court that could not be connected to is tried again after this long.
    private static let failedRestSeconds: TimeInterval = 10
    /// How long a found court gets to connect, and then again to prove itself.
    private static let candidateSeconds: TimeInterval = 12
    /// After Bluetooth comes back, the direct reconnect gets this long before looking.
    private static let lookAfterBluetoothSeconds: TimeInterval = 5
    /// How long a court that its host closed is still looked for.
    private static let closedLookSeconds: TimeInterval = 180
    /// The pause before asking again for a peripheral that connected and then
    /// had no court to offer, or dropped the link: doubled each time up to
    /// the limit, and back to the start once a link is up.
    private static let firstRetrySeconds: TimeInterval = 0.5
    private static let maxRetrySeconds: TimeInterval = 4

    private var central: CBCentralManager?
    /// The Join screen is listing courts.
    private var wantScan = false
    private var found: [UUID: NearbyCourt] = [:]

    /// The peripheral that was joined, or a found court that has since proved
    /// itself: the one that is retried directly.
    private var primary: CBPeripheral?
    /// A court found by scanning that is being connected to or proved.
    private var candidate: CBPeripheral?
    /// The peripheral packets currently flow on, if any. Only ever one.
    private var active: CBPeripheral?
    /// The name the court advertised when it was joined: what to look for again.
    private var courtLabel: String?
    /// The court to join may have been found by another transport (a host's
    /// lookout for rival courts hands one over). Core Bluetooth only connects
    /// a peripheral through the manager it came from, so this transport's own
    /// object for it is fetched before the first attempt.
    private var primaryUnresolved = false
    /// Whether the joined peripheral is retried directly. Off once the host
    /// has closed the court: a host that said goodbye is not chased, but the
    /// court is still looked for by name.
    private var directRetry = true
    private var retryDelay = GuestTransport.firstRetrySeconds
    private var retryTimer: Timer?
    private var closedLookTimer: Timer?
    /// Bluetooth was switched off, or restarted, since it was last on.
    private var bluetoothWasOff = false

    private var toHost: CBCharacteristic?
    private var outbox: [Data] = []
    private var writing = false

    /// The lost court is being looked for by name.
    private var looking = false
    private var lookTimer: Timer?
    private var candidateTimer: Timer?
    /// Courts seen by the search since it last started afresh.
    private var sighted: [UUID: NearbyCourt] = [:]
    /// Courts not to try again before the given time: wrong ones for a long while, failed ones briefly.
    private var avoidUntil: [UUID: Date] = [:]

    // MARK: Public

    func startScan() {
        wantScan = true
        found.removeAll()
        onCourts?([])
        scanIfPossible()
    }

    func stopScan() {
        wantScan = false
        // The search for a lost court uses the same scan; leave that running.
        if looking { return }
        if let central, central.state == .poweredOn { central.stopScan() }
    }

    /// Connects to `court` and stays connected, reconnecting as needed, until `close()`.
    func connect(to court: NearbyCourt) {
        close()
        stopScan()
        primary = court.peripheral
        primaryUnresolved = true
        courtLabel = court.name
        court.peripheral.delegate = self
        connectPrimaryIfPossible()
        // If even the first connection does not come up, look around as well.
        scheduleLook()
    }

    /// Queues packets for the host. Dropped if the link is down.
    func send(_ packets: [Data]) {
        guard active != nil else { return }
        outbox.append(contentsOf: packets)
        pump()
    }

    /// Whether a link is up, whatever the host is doing with it.
    var isUp: Bool {
        active != nil
    }

    /// The host has stopped answering on a link Bluetooth still calls
    /// connected. Drops the link and goes on as if it had broken, except
    /// that the court is looked for at once: it has been quiet for a while
    /// already.
    func hostSilent() {
        guard let silent = active else { return }
        if silent === candidate {
            avoidUntil[silent.identifier] = Date().addingTimeInterval(GuestTransport.failedRestSeconds)
            dropCandidate()
            return
        }
        active = nil
        resetLink()
        cancel(silent)
        onLinkDown?()
        if primary == nil { return }
        retryPrimary()
        startLooking()
    }

    /// The host has closed the court. Stops chasing that peripheral, but
    /// keeps looking for the court's name for a few minutes: another player
    /// may take the match over, or the host may have handed it to another
    /// court.
    func courtClosed() {
        guard let closedHost = primary else { return }
        directRetry = false
        retryTimer?.invalidate()
        retryTimer = nil
        if let unneeded = candidate {
            candidate = nil
            candidateTimer?.invalidate()
            candidateTimer = nil
            if unneeded !== closedHost { cancel(unneeded) }
        }
        let wasUp = active != nil
        active = nil
        resetLink()
        cancel(closedHost)
        if wasUp { onLinkDown?() }
        // The caller may have closed the transport on hearing that.
        if primary == nil { return }
        startLooking()
        closedLookTimer?.invalidate()
        closedLookTimer = Timer.scheduledTimer(
            withTimeInterval: GuestTransport.closedLookSeconds,
            repeats: false
        ) { [weak self] _ in
            guard let self, self.active == nil, self.candidate == nil else { return }
            self.stopLooking()
        }
    }

    /// Disconnects and stops reconnecting and looking.
    func close() {
        let wasUp = active != nil
        let oldPrimary = primary
        let oldCandidate = candidate
        // Cleared first, so the disconnections below are not taken for lost links.
        primary = nil
        candidate = nil
        active = nil
        courtLabel = nil
        directRetry = true
        retryDelay = GuestTransport.firstRetrySeconds
        retryTimer?.invalidate()
        retryTimer = nil
        closedLookTimer?.invalidate()
        closedLookTimer = nil
        avoidUntil.removeAll()
        candidateTimer?.invalidate()
        candidateTimer = nil
        stopLooking()
        resetLink()
        if let oldPrimary { cancel(oldPrimary) }
        if let oldCandidate { cancel(oldCandidate) }
        if wasUp { onLinkDown?() }
    }

    /// The court found by scanning is the right one: it becomes the
    /// peripheral to reconnect to from now on. Does nothing unless a found
    /// court is waiting to be proved, so it is safe to call on every packet.
    func courtProved() {
        guard let proved = candidate, active === proved else { return }
        let old = primary
        candidate = nil
        candidateTimer?.invalidate()
        candidateTimer = nil
        primary = proved
        // A court that has answered is chased like any other from now on.
        directRetry = true
        closedLookTimer?.invalidate()
        closedLookTimer = nil
        // The old address leads nowhere now; stop asking for it. (A closed
        // court that its host reopened is found as the same peripheral.)
        if let old, old !== proved { cancel(old) }
        stopLooking()
    }

    /// The court found by scanning is somebody else's: drop it, leave it
    /// alone for a while and keep looking.
    func wrongCourt() {
        guard let wrong = candidate else { return }
        avoidUntil[wrong.identifier] = Date().addingTimeInterval(GuestTransport.wrongCourtRestSeconds)
        dropCandidate()
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
        guard wantScan || looking, central.state == .poweredOn else { return }
        central.scanForPeripherals(withServices: [CourtUuids.service], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false,
        ])
    }

    private func connectPrimaryIfPossible() {
        let central = manager()
        guard directRetry, var target = primary, central.state == .poweredOn else { return }
        if primaryUnresolved {
            primaryUnresolved = false
            let known = central.retrievePeripherals(withIdentifiers: [target.identifier])
            if let own = known.first, own !== target {
                own.delegate = self
                primary = own
                target = own
            }
        }
        // A connection request never times out: if the host is out of range
        // it completes as soon as the host comes back.
        central.connect(target, options: nil)
    }

    private func cancel(_ peripheral: CBPeripheral) {
        if let central, central.state == .poweredOn {
            central.cancelPeripheralConnection(peripheral)
        }
    }

    private func isOurs(_ peripheral: CBPeripheral) -> Bool {
        peripheral === primary || peripheral === candidate
    }

    private func resetLink() {
        writing = false
        outbox.removeAll()
        toHost = nil
    }

    /// A connection ended or could not be made.
    private func lost(_ gone: CBPeripheral) {
        if gone === candidate {
            // Could not connect, or the link broke before the court was proved.
            avoidUntil[gone.identifier] = Date().addingTimeInterval(GuestTransport.failedRestSeconds)
            dropCandidate()
        } else if gone === primary {
            if active === gone {
                active = nil
                resetLink()
                onLinkDown?()
                scheduleLook()
            }
            // While a found court is proving itself the direct retry waits;
            // it is resumed if that court is dropped.
            if active == nil { retryPrimary() }
        }
    }

    /// Asks for the joined peripheral again after a pause. A phone that has
    /// stopped hosting still accepts the connection and then has no court to
    /// offer; asked again at once, it would be asked many times a second.
    private func retryPrimary() {
        guard directRetry, primary != nil, retryTimer == nil else { return }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, GuestTransport.maxRetrySeconds)
        retryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.retryTimer = nil
            if self.active == nil { self.connectPrimaryIfPossible() }
        }
    }

    /// Bluetooth went off or is restarting: every connection, pending
    /// request and scan is gone, with no word about each.
    private func allLinksLost() {
        bluetoothWasOff = true
        candidate = nil
        candidateTimer?.invalidate()
        candidateTimer = nil
        retryTimer?.invalidate()
        retryTimer = nil
        sighted.removeAll()
        if active != nil {
            active = nil
            resetLink()
            onLinkDown?()
        }
    }

    /// Writes one packet at a time, waiting for the host to confirm each.
    private func pump() {
        guard !writing, let active, let toHost, !outbox.isEmpty else { return }
        writing = true
        active.writeValue(outbox.removeFirst(), for: toHost, type: .withResponse)
    }

    private func writeCharacteristic(of peripheral: CBPeripheral) -> CBCharacteristic? {
        let service = peripheral.services?.first(where: { $0.uuid == CourtUuids.service })
        return service?.characteristics?.first(where: { $0.uuid == CourtUuids.toHost })
    }

    // MARK: Looking for the court again

    /// Starts the search once the link has been down for a while.
    private func scheduleLook(after seconds: TimeInterval = GuestTransport.lookAfterSeconds) {
        lookTimer?.invalidate()
        lookTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.startLooking()
        }
    }

    private func startLooking() {
        lookTimer?.invalidate()
        lookTimer = nil
        guard primary != nil, active == nil else { return }
        looking = true
        sighted.removeAll()
        // If Bluetooth is off this does nothing; the scan starts when it comes back on.
        scanIfPossible()
        lookTimer = Timer.scheduledTimer(
            withTimeInterval: GuestTransport.lookAgainSeconds,
            repeats: true
        ) { [weak self] _ in
            self?.lookAgain()
        }
    }

    private func stopLooking() {
        lookTimer?.invalidate()
        lookTimer = nil
        sighted.removeAll()
        if !looking { return }
        looking = false
        if !wantScan, let central, central.state == .poweredOn { central.stopScan() }
    }

    /// A scan reports each court once. Starting it afresh brings back courts
    /// whose rest is over and forgets those that have gone.
    private func lookAgain() {
        guard looking, active == nil, candidate == nil, let central, central.state == .poweredOn else { return }
        central.stopScan()
        sighted.removeAll()
        scanIfPossible()
    }

    /// Tries the nearest court seen under the joined name, unless a link is
    /// already up or being made. The peripheral that is retried directly is
    /// never a candidate. Once its host has closed the court it is no longer
    /// retried, and may be found again like any other: the host may reopen.
    private func tryFound() {
        guard looking, active == nil, candidate == nil, let primary, let courtLabel,
              let central, central.state == .poweredOn
        else { return }
        let now = Date()
        let nearestFirst = sighted.values.sorted { $0.rssi > $1.rssi }
        let match = nearestFirst.first { court in
            let rested = (avoidUntil[court.id] ?? Date.distantPast) <= now
            let retried = directRetry && court.id == primary.identifier
            return rested && !retried && CourtName.shared.matches(joined: courtLabel, seen: court.name)
        }
        guard let match else { return }
        let peripheral = match.peripheral
        candidate = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
        startCandidateTimer()
    }

    /// A connection request never fails by itself on Apple devices, so a found
    /// court that does not answer is given up on after a while. The timer is
    /// started again when its link comes up, to cover a court that connects
    /// and then says nothing.
    private func startCandidateTimer() {
        candidateTimer?.invalidate()
        candidateTimer = Timer.scheduledTimer(
            withTimeInterval: GuestTransport.candidateSeconds,
            repeats: false
        ) { [weak self] _ in
            guard let self, let stuck = self.candidate else { return }
            self.avoidUntil[stuck.identifier] = Date().addingTimeInterval(GuestTransport.failedRestSeconds)
            self.dropCandidate()
        }
    }

    /// Lets go of the found court, resumes the direct retry and keeps looking.
    private func dropCandidate() {
        guard let dropped = candidate else { return }
        candidate = nil
        candidateTimer?.invalidate()
        candidateTimer = nil
        let wasActive = active === dropped
        if wasActive {
            active = nil
            resetLink()
        }
        cancel(dropped)
        // The session needs to hear that the link it was proving is gone.
        if wasActive { onLinkDown?() }
        if primary == nil { return }
        connectPrimaryIfPossible()
        tryFound()
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            onProblem?(nil)
            // A search that was under way carries on.
            scanIfPossible()
            if active == nil { connectPrimaryIfPossible() }
            if bluetoothWasOff {
                bluetoothWasOff = false
                // Bluetooth is back: besides reconnecting, look for the court
                // by name as after any loss, a little sooner than usual.
                if primary != nil && active == nil && !looking {
                    scheduleLook(after: GuestTransport.lookAfterBluetoothSeconds)
                }
            }
        case .unauthorized:
            onProblem?("Allow Bluetooth for PadelSync in Settings to play with others.")
        case .poweredOff:
            allLinksLost()
            onProblem?("Turn on Bluetooth to play with others.")
        case .resetting:
            // The system is restarting Bluetooth: as when it is switched off,
            // everything in progress is gone. It reports powered on again by itself.
            allLinksLost()
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
        if wantScan {
            found[court.id] = court
            onCourts?(found.values.sorted { $0.rssi > $1.rssi })
        }
        if looking {
            if directRetry, let primary, court.id == primary.identifier {
                // The court is still where it was joined: no second attempt
                // is needed, only the direct one, asked for again in case
                // the system dropped the request (as it does when Bluetooth
                // is switched off and on).
                if active == nil {
                    if peripheral !== primary {
                        self.primary = peripheral
                        peripheral.delegate = self
                    }
                    connectPrimaryIfPossible()
                }
            } else {
                sighted[court.id] = court
                tryFound()
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard isOurs(peripheral) else { return }
        peripheral.discoverServices([CourtUuids.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        lost(peripheral)
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        lost(peripheral)
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard isOurs(peripheral) else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == CourtUuids.service }) else {
            cancel(peripheral)
            return
        }
        peripheral.discoverCharacteristics([CourtUuids.toHost, CourtUuids.fromHost], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard isOurs(peripheral) else { return }
        let characteristics = service.characteristics ?? []
        guard error == nil,
              characteristics.contains(where: { $0.uuid == CourtUuids.toHost }),
              let notify = characteristics.first(where: { $0.uuid == CourtUuids.fromHost })
        else {
            cancel(peripheral)
            return
        }
        peripheral.setNotifyValue(true, for: notify)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard isOurs(peripheral), characteristic.uuid == CourtUuids.fromHost else { return }
        guard error == nil, characteristic.isNotifying, let write = writeCharacteristic(of: peripheral) else {
            cancel(peripheral)
            return
        }
        // Only one link feeds the session at a time: the first one up.
        if active != nil {
            if peripheral !== active { cancel(peripheral) }
            return
        }
        let foundByScan = peripheral === candidate
        if foundByScan {
            // Stop retrying the old address while this court proves itself.
            if let primary, primary !== peripheral { cancel(primary) }
            startCandidateTimer()
        } else {
            // The peripheral joined in the first place is back: no candidate needed.
            if let unneeded = candidate {
                candidate = nil
                candidateTimer?.invalidate()
                candidateTimer = nil
                cancel(unneeded)
            }
            stopLooking()
        }
        active = peripheral
        toHost = write
        retryDelay = GuestTransport.firstRetrySeconds
        // Each packet must fit a single Bluetooth write. The "without
        // response" limit is that size; the "with response" limit is larger
        // only because it allows multi-part writes, which the protocol does
        // not use.
        onLinkUp?(peripheral.maximumWriteValueLength(for: .withoutResponse), foundByScan)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === active, characteristic.uuid == CourtUuids.fromHost,
              let value = characteristic.value
        else { return }
        onPacket?(value)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === active else { return }
        writing = false
        if error != nil {
            // Treat a failed write as a broken link: the session re-sends its
            // unresolved tap after reconnecting.
            cancel(peripheral)
        } else {
            pump()
        }
    }
}

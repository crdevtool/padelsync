import Foundation
import PadelSyncCore

/// A host's lookout for another court hosting the same match. The Android
/// counterpart is `RivalWatch`.
///
/// Two hosts for one match come about when a guest takes over while the real
/// host is only out of range, when two guests take over at the same moment,
/// or when a host that was taken over from comes back. Guests cannot settle
/// it: each is linked to one host and cannot see the other. So every host
/// looks for a court advertising its own name every so often, joins one it
/// finds for a moment with its own join code, as a guest would, and hands
/// what it learns to `onRival`, which decides which of the two courts
/// continues (`HostSession.judgeRival`).
///
/// It uses a guest transport of its own, so it cannot disturb the Join
/// screen's scan or a guest's search for its host. An iPhone can scan and
/// connect to other devices while it is itself advertising a court.
///
/// Everything runs on the main thread.
final class RivalWatch {
    /// A court for another match, or one that refused the join code, is left alone this long.
    static let otherCourtRestSeconds: TimeInterval = 600
    /// A rival that was judged is looked at again after this long, in case things changed.
    static let rivalRestSeconds: TimeInterval = 45

    private static let firstScanSeconds: TimeInterval = 10
    private static let scanEverySeconds: TimeInterval = 30
    private static let scanJitterSeconds: TimeInterval = 8
    private static let scanForSeconds: TimeInterval = 6
    private static let askTimeoutSeconds: TimeInterval = 12
    private static let failedRestSeconds: TimeInterval = 20

    private let courtName: String
    private let newSession: () -> ClientSession
    private let onRival: (NearbyCourt, MatchSnapshot, Int) -> TimeInterval

    private let transport = GuestTransport()
    private var avoidUntil: [UUID: Date] = [:]
    private var running = false

    // The court being asked, while a question is in progress.
    private var asking: NearbyCourt?
    private var session: ClientSession?

    private var scanTimer: Timer?
    private var askTimer: Timer?

    /// - Parameters:
    ///   - courtName: the name this court advertises, as guests see it.
    ///   - newSession: makes a fresh guest session carrying this court's join code.
    ///   - onRival: called with a rival's court, match and device count;
    ///     returns how many seconds to leave that court alone before looking
    ///     at it again.
    init(
        courtName: String,
        newSession: @escaping () -> ClientSession,
        onRival: @escaping (NearbyCourt, MatchSnapshot, Int) -> TimeInterval
    ) {
        self.courtName = courtName
        self.newSession = newSession
        self.onRival = onRival

        transport.onCourts = { [weak self] courts in self?.consider(courts) }
        transport.onLinkUp = { [weak self] maxPacketSize, _ in
            guard let self, let session = self.session else { return }
            self.carryOut(session.connected(maxPacketSize: Int32(maxPacketSize)))
        }
        transport.onLinkDown = { [weak self] in
            // One attempt only: a court that drops the link is asked again later.
            self?.finish(restSeconds: RivalWatch.failedRestSeconds)
        }
        transport.onPacket = { [weak self] packet in self?.received(packet) }
    }

    func start() {
        if running { return }
        running = true
        schedule(after: RivalWatch.firstScanSeconds) { [weak self] in self?.scan() }
    }

    func stop() {
        running = false
        scanTimer?.invalidate()
        scanTimer = nil
        transport.stopScan()
        finish(restSeconds: 0)
    }

    private func schedule(after seconds: TimeInterval, _ action: @escaping () -> Void) {
        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { _ in action() }
    }

    private func scan() {
        if !running { return }
        // While a court is being asked, this round is skipped.
        if asking == nil { transport.startScan() }
        schedule(after: RivalWatch.scanForSeconds) { [weak self] in self?.rest() }
    }

    private func rest() {
        transport.stopScan()
        if running {
            // A little randomness keeps two hosts from asking each other at
            // the same instant round after round, when each would count the
            // other among its own guests.
            let pause = RivalWatch.scanEverySeconds + TimeInterval.random(in: 0...RivalWatch.scanJitterSeconds)
            schedule(after: pause) { [weak self] in self?.scan() }
        }
    }

    private func consider(_ courts: [NearbyCourt]) {
        if !running || asking != nil { return }
        let now = Date()
        let match = courts.first { court in
            let rested = (avoidUntil[court.id] ?? Date.distantPast) <= now
            return rested && CourtName.shared.matches(joined: courtName, seen: court.name)
        }
        guard let rival = match else { return }
        asking = rival
        session = newSession()
        // Connecting also ends the scan.
        transport.connect(to: rival)
        askTimer?.invalidate()
        askTimer = Timer.scheduledTimer(
            withTimeInterval: RivalWatch.askTimeoutSeconds,
            repeats: false
        ) { [weak self] _ in
            self?.finish(restSeconds: RivalWatch.failedRestSeconds)
        }
    }

    private func received(_ packet: Data) {
        guard let current = session else { return }
        carryOut(current.packetReceived(packet: packet.toKotlinByteArray()))
        // Carrying out the answer may have ended the question.
        guard let court = asking, let snapshot = current.confirmed else { return }
        if current.status == ClientStatus.synced {
            finish(restSeconds: onRival(court, snapshot, Int(current.deviceCount)))
        }
    }

    private func carryOut(_ effects: [ClientEffect]) {
        for effect in effects {
            if let send = effect as? ClientEffect.Send {
                transport.send(send.packets.map { $0.toData() })
            } else if effect is ClientEffect.Disconnect {
                // It refused this court's join code, or is closing: not a rival.
                finish(restSeconds: RivalWatch.otherCourtRestSeconds)
            }
        }
    }

    /// Ends the question in progress, if any, and leaves that court alone for `restSeconds`.
    private func finish(restSeconds: TimeInterval) {
        guard let court = asking else { return }
        askTimer?.invalidate()
        askTimer = nil
        avoidUntil[court.id] = Date().addingTimeInterval(restSeconds)
        asking = nil
        session = nil
        // Closing an open link reports it down, which comes back here and
        // finds no question left to end.
        transport.close()
    }
}

import Combine
import Foundation
import PadelSyncCore

/// The single object the iPhone and Apple Watch UIs talk to.
///
/// It owns the shared-core session (host or guest), wires it to the Bluetooth
/// transports, and publishes everything the views draw. The Android
/// counterpart is `CourtController`; the two are deliberately alike.
///
/// Everything runs on the main thread.
final class CourtStore: ObservableObject {

    enum Mode {
        /// No match.
        case idle
        /// This device owns the match. Other devices may or may not be connected.
        case host
        /// This device is scoring through another device's match.
        case guest
    }

    @Published private(set) var mode: Mode = .idle
    /// The scoreboard, or nil when there is no match to show yet.
    @Published private(set) var score: ScoreView?
    @Published private(set) var config: MatchConfig?
    /// Devices in the session, this one included.
    @Published private(set) var deviceCount = 1
    /// Host only: whether other devices can find and join this court.
    @Published private(set) var courtOpen = false
    /// Host only: the code guests must enter.
    @Published private(set) var joinCode: Int?
    /// Guest only: true once the host has let this device in and the link is up.
    @Published private(set) var guestSynced = false
    /// Guest only: true if the host refused this device; see `rejection`.
    @Published private(set) var guestRejected = false
    /// Guest only: true if the host closed the court.
    @Published private(set) var guestEnded = false
    @Published private(set) var rejection: JoinRejection?
    /// Guest only: the label of the court being joined.
    @Published private(set) var courtName: String?
    /// A problem to show the user, or nil.
    @Published private(set) var error: String?
    /// A short-lived remark about the last tap, such as "already scored".
    @Published private(set) var note: String?
    /// Changes each time another device changes the score, so this one can signal it.
    @Published private(set) var remoteScoreCount = 0
    /// Courts found by the current scan, nearest first.
    @Published private(set) var nearby: [NearbyCourt] = []
    /// Whether a hosted match from an earlier run can be resumed.
    @Published private(set) var hasSavedMatch: Bool
    /// Finished matches this device took part in, newest first.
    @Published private(set) var history: [MatchRecord] = []

    /// Whether this kind of device can host other devices. Apple Watch cannot:
    /// watchOS does not allow Bluetooth advertising.
    static var canOpenCourt: Bool {
        #if os(iOS)
        return true
        #else
        return false
        #endif
    }

    private var host: HostSession?
    private var client: ClientSession?
    private let guestTransport = GuestTransport()
    #if os(iOS)
    private var hostTransport: HostTransport?
    #endif
    private var heartbeat: Timer?
    private var noteTimer: Timer?
    private var savedVersion: Int32 = -1
    private var savedMatchId: Int64 = 0

    private static let savedMatchKey = "hosted_match"
    private static let historyKey = "match_history"

    /// Guest side: when this device first saw each match, for its duration.
    private var firstSeen: [Int64: Int64] = [:]

    /// Matches `Sessions.NO_CODE` in the shared core.
    private static let noCode = -1

    init() {
        hasSavedMatch = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) != nil
        if let saved = UserDefaults.standard.data(forKey: CourtStore.historyKey) {
            history = MatchHistory.shared.decode(bytes: saved.toKotlinByteArray())
        }

        guestTransport.onCourts = { [weak self] courts in self?.nearby = courts }
        guestTransport.onProblem = { [weak self] problem in
            guard let self else { return }
            // Only relevant while searching or playing as a guest.
            if self.mode != .host { self.error = problem }
        }
        guestTransport.onLinkUp = { [weak self] maxPacketSize in
            guard let self, let client = self.client else { return }
            self.handle(client.connected(maxPacketSize: Int32(maxPacketSize)))
            self.publish()
        }
        guestTransport.onLinkDown = { [weak self] in
            guard let self else { return }
            self.client?.disconnected()
            self.publish()
        }
        guestTransport.onPacket = { [weak self] packet in
            guard let self, let client = self.client else { return }
            let before = client.confirmed
            let ownTap = self.handle(client.packetReceived(packet: packet.toKotlinByteArray()))
            if let before, let after = client.confirmed,
               after.version != before.version || after.matchId != before.matchId, !ownTap {
                self.remoteScoreCount += 1
            }
            self.publish()
        }
    }

    // MARK: Hosting

    /// Starts a new match on this device. Nothing is shared until `openCourt()`.
    func startMatch(_ config: MatchConfig) {
        leaveInternal()
        let code = Int.random(in: 1000...9999)
        joinCode = code
        host = Sessions.shared.host(
            config: config,
            hostDeviceId: DeviceIdentity.deviceId,
            joinCode: Int32(code),
            nowMillis: now()
        )
        publish()
    }

    /// Resumes the match this device was hosting when the app last closed.
    func resumeSavedMatch() {
        guard let saved = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) else { return }
        leaveInternal()
        let code = Int.random(in: 1000...9999)
        let resumed = Sessions.shared.resumeHost(
            saved: saved.toKotlinByteArray(),
            hostDeviceId: DeviceIdentity.deviceId,
            joinCode: Int32(code),
            nowMillis: now()
        )
        if let resumed {
            joinCode = code
            host = resumed
        } else {
            UserDefaults.standard.removeObject(forKey: CourtStore.savedMatchKey)
        }
        publish()
    }

    /// Replaces the hosted match with a fresh one, keeping connected devices.
    func startNewMatch(_ config: MatchConfig) {
        guard let host else {
            startMatch(config)
            return
        }
        deliver(host.startNewMatch(config: config, nowMillis: now()))
        publish()
    }

    #if os(iOS)
    /// Lets other devices find and join this court.
    func openCourt() {
        guard host != nil, hostTransport == nil else { return }
        let transport = HostTransport()
        transport.onGuestReady = { [weak self] peerId, maxPacketSize in
            self?.host?.peerConnected(peerId: peerId, maxPacketSize: Int32(maxPacketSize))
        }
        transport.onGuestGone = { [weak self] peerId in
            guard let self, let host = self.host else { return }
            self.deliver(host.peerDisconnected(peerId: peerId))
            self.publish()
        }
        transport.onPacket = { [weak self] peerId, packet in
            guard let self, let host = self.host else { return }
            let before = host.log.version
            self.deliver(host.packetReceived(peerId: peerId, packet: packet.toKotlinByteArray(), nowMillis: self.now()))
            if host.log.version != before { self.remoteScoreCount += 1 }
            self.publish()
        }
        transport.onFailed = { [weak self] reason in
            guard let self else { return }
            self.closeCourt()
            self.error = reason
        }
        hostTransport = transport
        error = nil
        transport.start(label: DeviceIdentity.deviceName)
        heartbeat = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            guard let self, let host = self.host else { return }
            self.deliver(host.heartbeat())
        }
        publish()
    }

    /// Stops sharing the court and disconnects every guest. The match carries on locally.
    func closeCourt() {
        guard hostTransport != nil else { return }
        shutDownHostTransport()
        publish()
    }

    /// Tells the guests the court is closing, then stops the Bluetooth side
    /// once that message has had a moment to go out.
    private func shutDownHostTransport() {
        guard let transport = hostTransport else { return }
        hostTransport = nil
        heartbeat?.invalidate()
        heartbeat = nil
        if let host {
            for item in host.endSession() {
                transport.send(peerId: item.peerId, packets: item.packets.map { $0.toData() })
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { transport.stop() }
    }
    #endif

    private func deliver(_ outgoing: [Outgoing]) {
        #if os(iOS)
        guard let transport = hostTransport else { return }
        for item in outgoing {
            transport.send(peerId: item.peerId, packets: item.packets.map { $0.toData() })
        }
        #endif
    }

    // MARK: Joining

    /// Starts looking for nearby courts. Results arrive in `nearby`.
    func startScan() {
        error = nil
        guestTransport.startScan()
    }

    func stopScan() {
        guestTransport.stopScan()
    }

    /// Joins `court` as a guest. `code` is the join code shown on the host's screen.
    func join(_ court: NearbyCourt, code: Int?) {
        leaveInternal()
        client = Sessions.shared.guest(
            deviceId: DeviceIdentity.deviceId,
            deviceName: DeviceIdentity.deviceName,
            deviceKind: DeviceIdentity.deviceKind,
            joinCode: Int32(code ?? CourtStore.noCode)
        )
        courtName = court.name
        guestTransport.connect(to: court)
        publish()
    }

    /// Carries out a guest session's effects. Returns true if one of them
    /// confirmed a tap made on this device.
    @discardableResult
    private func handle(_ effects: [ClientEffect]) -> Bool {
        var ownTapAccepted = false
        for effect in effects {
            if let send = effect as? ClientEffect.Send {
                guestTransport.send(send.packets.map { $0.toData() })
            } else if let feedback = effect as? ClientEffect.Feedback {
                if feedback.feedback == TapFeedback.accepted {
                    ownTapAccepted = true
                } else if feedback.feedback == TapFeedback.superseded {
                    show(note: "Already scored on another device")
                } else if feedback.feedback == TapFeedback.matchComplete {
                    show(note: "The match is over")
                }
            } else if effect is ClientEffect.Disconnect {
                // The host refused us. Stop the link so it does not retry;
                // the session keeps the reason for the UI.
                guestTransport.close()
            }
        }
        return ownTapAccepted
    }

    // MARK: Scoring

    /// A player tapped on this device.
    func tap(_ action: Action) {
        if let host {
            deliver(host.submit(action: action, nowMillis: now()))
            if host.lastSubmitOutcome == CommandOutcome.sameRally {
                show(note: "Already scored on another device")
            }
        }
        if let client {
            handle(client.submit(action: action))
        }
        publish()
    }

    /// Ends the match (host) or leaves the court (guest) and returns to idle.
    func leave() {
        let wasHost = host != nil
        leaveInternal()
        if wasHost { UserDefaults.standard.removeObject(forKey: CourtStore.savedMatchKey) }
        publish()
    }

    /// Used by automated screenshots only: starts a match and plays a few points.
    func runDemo() {
        startMatch(MatchConfig.companion.padel())
        for _ in 0..<4 { tap(Action.pointA) }
        for _ in 0..<3 { tap(Action.pointB) }
        for _ in 0..<3 { tap(Action.pointA) }
    }

    // MARK: Internals

    private func leaveInternal() {
        #if os(iOS)
        shutDownHostTransport()
        #endif
        heartbeat?.invalidate()
        heartbeat = nil
        host = nil
        joinCode = nil
        savedVersion = -1
        savedMatchId = 0

        guestTransport.close()
        client = nil
        courtName = nil

        error = nil
        note = nil
    }

    private func show(note text: String) {
        note = text
        noteTimer?.invalidate()
        noteTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
            self?.note = nil
        }
    }

    private func publish() {
        if let host {
            // Persist only when the match actually changed.
            let log = host.log
            if log.version != savedVersion || log.matchId != savedMatchId {
                UserDefaults.standard.set(host.savedState().toData(), forKey: CourtStore.savedMatchKey)
                savedVersion = log.version
                savedMatchId = log.matchId
                trackHistory(host.snapshot(), startedAtMillis: log.startedAtMillis)
            }
            mode = .host
            score = ScoreView.companion.of(state: host.state)
            config = host.state.config
            deviceCount = Int(host.deviceCount)
            #if os(iOS)
            courtOpen = hostTransport != nil
            #else
            courtOpen = false
            #endif
            guestSynced = false
            guestRejected = false
            guestEnded = false
            rejection = nil
            hasSavedMatch = true
        } else if let client {
            if let confirmed = client.confirmed {
                let seen = firstSeen[confirmed.matchId] ?? now()
                firstSeen[confirmed.matchId] = seen
                trackHistory(confirmed, startedAtMillis: seen)
            }
            let display = client.displayState
            mode = .guest
            score = display.map { ScoreView.companion.of(state: $0) }
            config = display?.config
            deviceCount = Int(client.deviceCount)
            courtOpen = false
            guestSynced = client.status == ClientStatus.synced
            guestRejected = client.status == ClientStatus.rejected
            guestEnded = client.status == ClientStatus.ended
            rejection = client.rejection
            hasSavedMatch = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) != nil
        } else {
            mode = .idle
            score = nil
            config = nil
            deviceCount = 1
            courtOpen = false
            guestSynced = false
            guestRejected = false
            guestEnded = false
            rejection = nil
            hasSavedMatch = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) != nil
        }
    }

    /// Keeps the history in step with a match: adds it when it is won, and
    /// takes it out again if the winning point is undone.
    private func trackHistory(_ snapshot: MatchSnapshot, startedAtMillis: Int64) {
        let recorded = history.first { $0.matchId == snapshot.matchId }
        let complete = snapshot.state.isComplete
        if complete && recorded?.snapshot.version != snapshot.version {
            let record = MatchRecord(snapshot: snapshot, startedAtMillis: startedAtMillis, finishedAtMillis: now())
            history = MatchHistory.shared.add(records: history, record: record)
        } else if !complete && recorded != nil {
            history = MatchHistory.shared.remove(records: history, matchId: snapshot.matchId)
        } else {
            return
        }
        UserDefaults.standard.set(MatchHistory.shared.encode(records: history).toData(), forKey: CourtStore.historyKey)
    }

    private func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

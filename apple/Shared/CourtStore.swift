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
    /// The outcome of the most recent tap, kept for a moment so the views can
    /// say why a tap did not count. `feedbackCount` changes on every new one.
    @Published private(set) var lastFeedback: TapFeedback?
    @Published private(set) var feedbackCount = 0
    /// Changes each time another device changes the score, so this one can signal it.
    @Published private(set) var remoteScoreCount = 0
    /// Courts found by the current scan, nearest first.
    @Published private(set) var nearby: [NearbyCourt] = []
    /// Whether a hosted match from an earlier run can be resumed.
    @Published private(set) var hasSavedMatch: Bool
    /// Finished matches this device took part in, newest first.
    @Published private(set) var history: [MatchRecord] = []
    /// Whether this device may change the score. Always true for the host.
    @Published private(set) var canScore = true
    /// Host only: the devices that have joined, with what each may do.
    @Published private(set) var guests: [PeerInfo] = []
    /// Host only: whether devices the host has not singled out may score.
    @Published private(set) var guestsCanScore = true
    /// Points, breaks and streaks so far, or nil when there is no match.
    @Published private(set) var stats: MatchStats?
    /// When this device first had the match, for the running clock.
    @Published private(set) var startedAtMillis: Int64?
    /// How long the match took, once it is over.
    @Published private(set) var durationMillis: Int64?
    /// How this device announces the score.
    @Published private(set) var speech: SpeechSettings
    /// False once the device turned out to have no text-to-speech voice.
    @Published private(set) var voiceAvailable = true

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
    private var feedbackTimer: Timer?
    private var errorTimer: Timer?
    private var reminderTimer: Timer?
    private var savedVersion: Int32 = -1
    private var savedMatchId: Int64 = 0

    private static let savedMatchKey = "hosted_match"
    private static let historyKey = "match_history"

    /// Guest side: when this device first saw each match, for its duration.
    private var firstSeen: [Int64: Int64] = [:]

    /// Matches `Sessions.NO_CODE` in the shared core.
    private static let noCode = -1

    private static let heartbeatSeconds: TimeInterval = 2
    /// How long a remark about a tap stays up.
    private static let feedbackSeconds: TimeInterval = 2.5
    /// How long a problem stays on the scoreboard before the status line returns.
    private static let errorSeconds: TimeInterval = 8

    private let settings: SettingsStore
    // A device without a voice simply stays quiet; the voice settings say why.
    private let announcer = Announcer()

    /// The match as last put through the announcer, so each change is spoken once.
    private var announced: MatchSnapshot?

    init() {
        // Only an iPhone calls the score unasked: a watch on a wrist is not a loudspeaker.
        let settings = SettingsStore(voiceOnWhenHosting: CourtStore.canOpenCourt)
        self.settings = settings
        speech = settings.speech(hosting: true)
        hasSavedMatch = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) != nil
        if let saved = UserDefaults.standard.data(forKey: CourtStore.historyKey) {
            history = MatchHistory.shared.decode(bytes: saved.toKotlinByteArray())
        }

        guestTransport.onCourts = { [weak self] courts in self?.nearby = courts }
        guestTransport.onProblem = { [weak self] problem in
            guard let self else { return }
            // Only relevant while searching or playing as a guest.
            if self.mode != .host { self.report(problem) }
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

    /// The match last set up on this device, to pre-fill the setup screen.
    var lastSetup: MatchSetup? {
        settings.lastSetup()
    }

    /// Starts a new match on this device with default options. Nothing is shared until `openCourt()`.
    func startMatch(_ config: MatchConfig) {
        startMatch(MatchSetup(config: config, roster: Roster.noNames, guestsCanScore: true))
    }

    /// Starts a new match on this device. Nothing is shared until `openCourt()`.
    func startMatch(_ setup: MatchSetup) {
        leaveInternal()
        settings.saveSetup(setup)
        let code = Int.random(in: 1000...9999)
        joinCode = code
        host = Sessions.shared.host(
            config: setup.config,
            roster: setup.roster,
            hostDeviceId: DeviceIdentity.deviceId,
            joinCode: Int32(code),
            guestsCanScore: setup.guestsCanScore,
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
            guestsCanScore: settings.lastSetup()?.guestsCanScore ?? true,
            nowMillis: now()
        )
        if let resumed {
            joinCode = code
            host = resumed
            // Picking a match back up is not news: do not read the score out.
            announced = resumed.snapshot()
        } else {
            UserDefaults.standard.removeObject(forKey: CourtStore.savedMatchKey)
        }
        publish()
    }

    /// Replaces the hosted match with a fresh one with default options, keeping connected devices.
    func startNewMatch(_ config: MatchConfig) {
        startNewMatch(
            MatchSetup(config: config, roster: Roster.noNames, guestsCanScore: host?.guestsCanScore ?? true)
        )
    }

    /// Replaces the hosted match with a fresh one, keeping connected devices.
    func startNewMatch(_ setup: MatchSetup) {
        guard let host else {
            startMatch(setup)
            return
        }
        settings.saveSetup(setup)
        deliver(host.startNewMatch(config: setup.config, nowMillis: now(), roster: setup.roster))
        if host.guestsCanScore != setup.guestsCanScore {
            deliver(host.setGuestsCanScore(allowed: setup.guestsCanScore))
        }
        publish()
    }

    /// Host only: plays again with the same format and the same players.
    func rematch() {
        guard let host else { return }
        let snapshot = host.snapshot()
        deliver(host.startNewMatch(config: snapshot.config, nowMillis: now(), roster: snapshot.roster))
        publish()
    }

    /// Host only: changes the players' names mid-match.
    func updateRoster(_ roster: Roster) {
        guard let host else { return }
        deliver(host.updateRoster(roster: roster))
        settings.saveSetup(
            MatchSetup(config: host.state.config, roster: roster, guestsCanScore: host.guestsCanScore)
        )
        // Names do not change the match version, so `publish` would not save them.
        UserDefaults.standard.set(host.savedState().toData(), forKey: CourtStore.savedMatchKey)
        publish()
    }

    /// Host only: lets one joined device score, or makes it view-only.
    func setCanScore(deviceId: Int64, allowed: Bool) {
        guard let host else { return }
        deliver(host.setCanScore(deviceId: deviceId, allowed: allowed))
        publish()
    }

    /// Host only: lets every joined device score, or makes them all view-only.
    func setGuestsCanScore(_ allowed: Bool) {
        guard let host else { return }
        deliver(host.setGuestsCanScore(allowed: allowed))
        let snapshot = host.snapshot()
        settings.saveSetup(MatchSetup(config: snapshot.config, roster: snapshot.roster, guestsCanScore: allowed))
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
            self.report(reason)
        }
        hostTransport = transport
        report(nil)
        transport.start(label: DeviceIdentity.deviceName)
        heartbeat = Timer.scheduledTimer(withTimeInterval: CourtStore.heartbeatSeconds, repeats: true) { [weak self] _ in
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
        report(nil)
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
                if feedback.feedback == TapFeedback.accepted { ownTapAccepted = true }
                show(feedback: feedback.feedback)
            } else if effect is ClientEffect.Disconnect {
                // The court closed: keep the result if there was one.
                recordIfDecided()
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
                show(feedback: TapFeedback.superseded)
            }
        }
        if let client {
            handle(client.submit(action: action))
        }
        publish()
    }

    // MARK: Voice

    /// How this device announces the score in its current role.
    private var currentSpeech: SpeechSettings {
        settings.speech(hosting: client == nil)
    }

    /// Changes how this device announces the score. Remembered between matches.
    func setSpeech(_ value: SpeechSettings) {
        settings.saveSpeech(value, hosting: client == nil)
        // Switching the voice on is a good moment to look again for one
        // that was missing before.
        if value.enabled { announcer.retry() } else { announcer.silence() }
        scheduleReminder()
        publish()
    }

    /// Reads out the whole score now, whatever the settings say.
    func sayScore() {
        announcer.retry()
        if let snapshot = currentSnapshot() {
            announcer.say(ScoreSpeech.shared.reminder(snapshot: snapshot))
        }
        voiceAvailable = !announcer.unavailable
    }

    /// The match as the host has it: this device's own when hosting, the last one received when a guest.
    private func currentSnapshot() -> MatchSnapshot? {
        host?.snapshot() ?? client?.confirmed
    }

    /// Speaks whatever changed since the last call. Only confirmed scores are
    /// announced, never a guest's own unconfirmed tap, so the voice cannot
    /// call a point the host then refuses.
    private func announce(_ snapshot: MatchSnapshot?) {
        guard let snapshot, snapshot != announced else { return }
        let phrases = ScoreSpeech.shared.announce(before: announced, after: snapshot, settings: currentSpeech)
        announced = snapshot
        if !phrases.isEmpty { announcer.say(phrases) }
    }

    private func scheduleReminder() {
        reminderTimer?.invalidate()
        reminderTimer = nil
        let current = currentSpeech
        if !current.enabled || current.reminderMinutes == 0 { return }
        if host == nil && client == nil { return }
        let interval = TimeInterval(current.reminderMinutes) * 60
        reminderTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            self?.remind()
        }
    }

    private func remind() {
        // A guest that has lost the host has only an old score to offer.
        let live = host != nil || client?.status == ClientStatus.synced
        // Nothing to remind anyone of before the first point or after the last.
        if live, let snapshot = currentSnapshot(), !snapshot.points.isEmpty, !snapshot.state.isComplete {
            announcer.say(ScoreSpeech.shared.reminder(snapshot: snapshot))
        }
        scheduleReminder()
    }

    // MARK: Leaving

    /// Ends the match (host) or leaves the court (guest) and returns to idle.
    func leave() {
        let wasHost = host != nil
        recordIfDecided()
        leaveInternal()
        if wasHost { UserDefaults.standard.removeObject(forKey: CourtStore.savedMatchKey) }
        publish()
    }

    func clearError() {
        report(nil)
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

        report(nil)
        feedbackTimer?.invalidate()
        feedbackTimer = nil
        lastFeedback = nil
        announced = nil
        reminderTimer?.invalidate()
        reminderTimer = nil
        announcer.silence()
    }

    /// Shows a problem, or clears it with nil. During a match it makes way
    /// for the normal status line again after a while; on the Join screen it
    /// stays until the player tries again.
    private func report(_ problem: String?) {
        error = problem
        errorTimer?.invalidate()
        errorTimer = nil
        if problem == nil || (host == nil && client == nil) { return }
        errorTimer = Timer.scheduledTimer(withTimeInterval: CourtStore.errorSeconds, repeats: false) { [weak self] _ in
            self?.error = nil
        }
    }

    /// Publishes what became of a tap, then forgets it after a moment so a
    /// remark about it does not outstay its welcome.
    private func show(feedback: TapFeedback) {
        lastFeedback = feedback
        feedbackCount += 1
        feedbackTimer?.invalidate()
        feedbackTimer = Timer.scheduledTimer(withTimeInterval: CourtStore.feedbackSeconds, repeats: false) { [weak self] _ in
            self?.lastFeedback = nil
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
        }
        if let confirmed = client?.confirmed {
            let seen = firstSeen[confirmed.matchId] ?? now()
            firstSeen[confirmed.matchId] = seen
            trackHistory(confirmed, startedAtMillis: seen)
        }

        let wasIdle = mode == .idle
        announce(currentSnapshot())
        speech = currentSpeech
        voiceAvailable = !announcer.unavailable
        let savedMatchExists = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) != nil

        if let host {
            let snapshot = host.snapshot()
            mode = .host
            score = ScoreView.companion.of(snapshot: snapshot)
            config = snapshot.config
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
            canScore = true
            guests = host.guests
            guestsCanScore = host.guestsCanScore
            stats = MatchStats.companion.of(snapshot: snapshot)
            startedAtMillis = host.log.startedAtMillis
            durationMillis = recordedDuration(snapshot)
        } else if let client {
            let display = client.displayState
            let confirmed = client.confirmed
            mode = .guest
            if let display, let confirmed {
                // The host's names, with this device's own unconfirmed taps on top.
                score = ScoreView.companion.of(
                    state: display,
                    roster: confirmed.roster,
                    serveFlipA: client.displayServeFlip(team: Team.a),
                    serveFlipB: client.displayServeFlip(team: Team.b)
                )
            } else {
                score = nil
            }
            config = display?.config
            deviceCount = Int(client.deviceCount)
            courtOpen = false
            guestSynced = client.status == ClientStatus.synced
            guestRejected = client.status == ClientStatus.rejected
            guestEnded = client.status == ClientStatus.ended
            rejection = client.rejection
            hasSavedMatch = savedMatchExists
            canScore = client.canScore
            guests = []
            guestsCanScore = true
            if let confirmed {
                stats = MatchStats.companion.of(snapshot: confirmed)
                startedAtMillis = firstSeen[confirmed.matchId]
                durationMillis = recordedDuration(confirmed)
            } else {
                stats = nil
                startedAtMillis = nil
                durationMillis = nil
            }
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
            hasSavedMatch = savedMatchExists
            canScore = true
            guests = []
            guestsCanScore = true
            stats = nil
            startedAtMillis = nil
            durationMillis = nil
        }
        // A match has just begun on this device: start the reminder clock.
        if wasIdle && mode != .idle { scheduleReminder() }
    }

    /// Keeps the history in step with a match: adds it when it is won, and
    /// takes it out again if the winning point is undone.
    private func trackHistory(_ snapshot: MatchSnapshot, startedAtMillis: Int64) {
        let recorded = history.first { $0.matchId == snapshot.matchId }
        let complete = snapshot.state.isComplete
        if complete && recorded?.snapshot.version != snapshot.version {
            let record = MatchRecord(snapshot: snapshot, startedAtMillis: startedAtMillis, finishedAtMillis: now())
            history = MatchHistory.shared.add(records: history, record: record)
        } else if !complete, let recorded, recorded.snapshot.state.isComplete {
            // A finished match reopened with undo is no longer a result. A
            // match recorded as decided but unfinished stays as it is.
            history = MatchHistory.shared.remove(records: history, matchId: snapshot.matchId)
        } else {
            return
        }
        saveHistory()
    }

    /// How long a finished match took, as recorded in the history when it ended.
    private func recordedDuration(_ snapshot: MatchSnapshot) -> Int64? {
        if !snapshot.state.isComplete { return nil }
        return history.first(where: { $0.matchId == snapshot.matchId })?.durationMillis
    }

    /// Keeps a match that was stopped after it was decided but before every
    /// set was played, which is how most "play all three sets" matches end
    /// when the court time runs out.
    private func recordIfDecided() {
        guard let snapshot = currentSnapshot() else { return }
        if snapshot.state.isComplete || snapshot.state.decidedWinner == nil { return }
        // Already kept, for example when the court closed and the player then tapped Back.
        let kept = history.contains { $0.matchId == snapshot.matchId && $0.snapshot.version == snapshot.version }
        if kept { return }
        let startedAt = host?.log.startedAtMillis ?? firstSeen[snapshot.matchId] ?? now()
        let record = MatchRecord(snapshot: snapshot, startedAtMillis: startedAt, finishedAtMillis: now())
        history = MatchHistory.shared.add(records: history, record: record)
        saveHistory()
    }

    private func saveHistory() {
        UserDefaults.standard.set(MatchHistory.shared.encode(records: history).toData(), forKey: CourtStore.historyKey)
    }

    private func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

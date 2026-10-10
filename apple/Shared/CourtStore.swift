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
    /// Guest only: the host has closed the court or cannot be reached, and
    /// this device holds the match, so the match could carry on from a new
    /// host. Only a device that can open a court can be that host; a watch
    /// tells its wearer to ask a player with a phone.
    @Published private(set) var canTakeOver = false
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
    private var rivalWatch: RivalWatch?
    /// The name the open court is advertised under.
    private var advertisedLabel: String?
    /// Bluetooth going off has closed the open court for now; it reopens by
    /// itself when Bluetooth is back. A court the player closed stays closed.
    private var courtSuspended = false
    #endif
    /// Set when this host gave way to another court; see `yieldTo`.
    private var clearSavedMatchOnceSynced = false
    /// The name this court advertises when it was taken over from another host; nil for this device's own name.
    private var courtLabel: String?
    /// The code the player typed to join, reused if this device takes over as host.
    private var enteredCode: Int?
    /// When the host went out of reach; nil while in touch.
    private var hostLostAt: Date?
    private var offerTimers: [Timer] = []
    /// How many checks in a row have found the host silent on a link that is up.
    private var silentChecks = 0
    private var hostPinged = false
    private var livenessTimer: Timer?
    private var heartbeat: Timer?
    private var feedbackTimer: Timer?
    private var errorTimer: Timer?
    private var reminderTimer: Timer?
    private var savedVersion: Int32 = -1
    private var savedMatchId: Int64 = 0

    private static let savedMatchKey = "hosted_match"
    private static let savedCodeKey = "hosted_code"
    private static let savedLabelKey = "hosted_label"
    private static let savedOpenKey = "hosted_open"
    private static let historyKey = "match_history"

    /// Guest side: when this device first saw each match, for its duration.
    private var firstSeen: [Int64: Int64] = [:]

    /// Matches `Sessions.NO_CODE` in the shared core.
    private static let noCode = -1

    private static let heartbeatSeconds: TimeInterval = 2
    private static let livenessSeconds: TimeInterval = 2
    /// Silent checks, on a link that is up, before the host is asked to
    /// speak: at least 8 seconds, as the first check can come at any moment
    /// after the last packet.
    private static let silentChecksBeforePing = 5
    /// Silent checks before the link is treated as dead: at least 16 seconds.
    private static let silentChecksBeforeDrop = 9
    /// How long the host must be out of reach before a guest is offered its place.
    private static let takeOverAfterSeconds: TimeInterval = 20
    /// How long after the host closed the court a guest is offered its place.
    private static let takeOverAfterCloseSeconds: TimeInterval = 10
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
        let settings = SettingsStore()
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
        guestTransport.onLinkUp = { [weak self] maxPacketSize, foundByScan in
            guard let self, let client = self.client else { return }
            let size = Int32(maxPacketSize)
            self.silentChecks = 0
            self.hostPinged = false
            // A court found by scanning has to show it carries this match
            // before it is believed; the session checks its first answer.
            if foundByScan {
                self.handle(client.connectedToFoundCourt(maxPacketSize: size))
            } else {
                self.handle(client.connected(maxPacketSize: size))
            }
            self.publish()
        }
        guestTransport.onLinkDown = { [weak self] in
            guard let self else { return }
            self.client?.disconnected()
            if self.client != nil && self.hostLostAt == nil {
                self.hostLostAt = Date()
                // Publish again at the two moments the offer to take over can
                // begin: sooner for a closed court, later for a lost host.
                self.cancelOfferTimers()
                for wait in [CourtStore.takeOverAfterCloseSeconds, CourtStore.takeOverAfterSeconds] {
                    let timer = Timer.scheduledTimer(withTimeInterval: wait + 0.1, repeats: false) { [weak self] _ in
                        self?.publish()
                    }
                    self.offerTimers.append(timer)
                }
            }
            // The session's status is read afresh: a closed court stays closed.
            self.publish()
        }
        guestTransport.onPacket = { [weak self] packet in
            guard let self, let client = self.client else { return }
            self.silentChecks = 0
            self.hostPinged = false
            let before = client.confirmed
            let ownTap = self.handle(client.packetReceived(packet: packet.toKotlinByteArray()))
            if let before, let after = client.confirmed,
               after.version != before.version || after.matchId != before.matchId, !ownTap {
                self.remoteScoreCount += 1
            }
            // In step with the host again: if this was a court found by
            // scanning, it has proved itself and is the one to stay with.
            if client.status == ClientStatus.synced {
                self.guestTransport.courtProved()
                self.hostLostAt = nil
                self.cancelOfferTimers()
                if self.clearSavedMatchOnceSynced {
                    self.clearSavedMatchOnceSynced = false
                    self.clearSavedMatch()
                }
            }
            self.publish()
        }
    }

    // MARK: Hosting

    /// What the setup screen starts from: the players of the last match set
    /// up on this device, in the format the player keeps as their own (see
    /// `keepFormat`), or in the last match's format if none was kept. Nil on
    /// first use.
    var lastSetup: MatchSetup? {
        let last = settings.lastSetup()
        guard let mine = settings.myFormat() else { return last }
        return MatchSetup(
            config: mine,
            roster: last?.roster ?? Roster.noNames,
            guestsCanScore: last?.guestsCanScore ?? true
        )
    }

    /// Makes `config` the format every new match is set up in, until another is kept.
    func keepFormat(_ config: MatchConfig) {
        settings.saveMyFormat(config)
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
        saveCode(code)
        courtLabel = nil
        saveLabel(nil)
        saveOpen(false)
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
    /// On an iPhone its court opens again if it was open at the time, so that
    /// guests still looking for it are let back in.
    func resumeSavedMatch() {
        guard let saved = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) else { return }
        #if os(iOS)
        let wasOpen = UserDefaults.standard.bool(forKey: CourtStore.savedOpenKey)
        #endif
        leaveInternal()
        // The same code as before the app closed, so that guests still
        // looking for this court can come back without typing anything.
        let code = savedCode() ?? Int.random(in: 1000...9999)
        let resumed = Sessions.shared.resumeHost(
            saved: saved.toKotlinByteArray(),
            hostDeviceId: DeviceIdentity.deviceId,
            joinCode: Int32(code),
            guestsCanScore: settings.lastSetup()?.guestsCanScore ?? true,
            nowMillis: now()
        )
        if let resumed {
            joinCode = code
            saveCode(code)
            courtLabel = savedLabel()
            host = resumed
            // Picking a match back up is not news: do not read the score out.
            announced = resumed.snapshot()
        } else {
            clearSavedMatch()
        }
        publish()
        #if os(iOS)
        if wasOpen && host != nil { openCourt() }
        #endif
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
        transport.onBluetoothOff = { [weak self] in self?.bluetoothWentOff() }
        transport.onBluetoothBack = { [weak self] in self?.bluetoothCameBack() }
        hostTransport = transport
        courtSuspended = false
        report(nil)
        // This device's own court carries a few characters of its id, because
        // device names are rarely unique at a club. A court taken over from
        // another host keeps that host's name.
        let label = courtLabel ?? CourtName.shared.label(
            deviceName: DeviceIdentity.deviceName,
            deviceId: DeviceIdentity.deviceId,
            maxBytes: CourtUuids.ownLabelBytes
        )
        advertisedLabel = label
        transport.start(label: label)
        saveOpen(true)
        startCourtDuties()
        publish()
    }

    /// What a host does for as long as its court is open: repeats the match
    /// to its guests, and looks out for another court hosting the same match.
    private func startCourtDuties() {
        heartbeat?.invalidate()
        heartbeat = Timer.scheduledTimer(withTimeInterval: CourtStore.heartbeatSeconds, repeats: true) { [weak self] _ in
            guard let self, let host = self.host else { return }
            self.deliverHeartbeat(host.heartbeat())
        }
        let code = joinCode
        rivalWatch?.stop()
        let watch = RivalWatch(
            // The label as it is advertised: what guests, and a rival, go by.
            courtName: advertisedLabel ?? "",
            newSession: {
                Sessions.shared.guest(
                    deviceId: DeviceIdentity.deviceId,
                    deviceName: DeviceIdentity.deviceName,
                    deviceKind: DeviceIdentity.deviceKind,
                    joinCode: Int32(code ?? CourtStore.noCode)
                )
            },
            onRival: { [weak self] court, snapshot, deviceCount in
                self?.onRival(court, snapshot, deviceCount) ?? RivalWatch.rivalRestSeconds
            }
        )
        rivalWatch = watch
        watch.start()
    }

    /// Bluetooth was switched off under the open court. No farewell can be
    /// sent; the guests notice the silence and look for the court, which
    /// reopens under the same name and code when Bluetooth is back.
    private func bluetoothWentOff() {
        guard hostTransport != nil, !courtSuspended else { return }
        courtSuspended = true
        heartbeat?.invalidate()
        heartbeat = nil
        rivalWatch?.stop()
        rivalWatch = nil
        report("Bluetooth is off. The court reopens when it is switched back on.")
        publish()
    }

    private func bluetoothCameBack() {
        guard hostTransport != nil, courtSuspended else { return }
        courtSuspended = false
        report(nil)
        startCourtDuties()
        publish()
    }

    /// The app was in the background with the court open and is back on
    /// screen. Says, for a few seconds, what that cost: iOS hides a court
    /// from Android devices while the app that hosts it is not on screen.
    func courtWasInBackground() {
        guard hostTransport != nil, !courtSuspended else { return }
        report(Labels.wasInBackground)
    }

    /// Stops sharing the court and disconnects every guest. The match carries on locally.
    func closeCourt() {
        guard hostTransport != nil else { return }
        shutDownHostTransport()
        saveOpen(false)
        publish()
    }

    /// Tells the guests the court is closing, then stops the Bluetooth side
    /// once that message has had a moment to go out. The goodbye releases
    /// the guests: they stop chasing this device and look for the court by
    /// name instead.
    private func shutDownHostTransport() {
        guard let transport = hostTransport else { return }
        hostTransport = nil
        courtSuspended = false
        advertisedLabel = nil
        heartbeat?.invalidate()
        heartbeat = nil
        rivalWatch?.stop()
        rivalWatch = nil
        if let host {
            for item in host.endSession() {
                transport.send(peerId: item.peerId, packets: item.packets.map { $0.toData() })
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { transport.stop() }
    }

    /// Another court is hosting this same match. Decides which of the two
    /// carries on; see `HostSession.judgeRival`. Returns how many seconds to
    /// leave that court alone before looking at it again.
    private func onRival(_ court: NearbyCourt, _ snapshot: MatchSnapshot, _ deviceCount: Int) -> TimeInterval {
        guard let host else { return RivalWatch.rivalRestSeconds }
        let verdict = host.judgeRival(rival: snapshot, rivalDeviceCount: Int32(deviceCount))
        if verdict == RivalVerdict.differentMatch {
            return RivalWatch.otherCourtRestSeconds
        }
        if verdict == RivalVerdict.hold {
            // Make sure devices on the other court prefer this one when they find it.
            deliver(host.outrank(rivalEpoch: snapshot.epoch))
            publish()
            return RivalWatch.rivalRestSeconds
        }
        // The remaining verdict is to give way. Not from inside the
        // lookout's own callback: it is about to be shut down.
        DispatchQueue.main.async { [weak self] in self?.yieldTo(court) }
        return RivalWatch.rivalRestSeconds
    }

    /// Stops hosting in favour of `court`, which is hosting the same match, and joins it as a guest.
    private func yieldTo(_ court: NearbyCourt) {
        guard host != nil else { return }
        let code = joinCode
        // Joining says goodbye to this court's guests first, which releases
        // them: they look for the match by name at once and accept the other
        // host whatever its epoch.
        join(court, code: code)
        // The saved match is this device's only copy until the other court
        // has answered; it is dropped once that copy has arrived.
        clearSavedMatchOnceSynced = true
        report("Another device is hosting this match now. This one has joined it.")
        publish()
    }

    // MARK: Taking over as host

    /// Carries the match on as host from this guest's copy of it, for when
    /// the host's device has died or left. The court reopens under the name
    /// and the join code the guests already know, so they follow by
    /// themselves.
    ///
    /// Only one player should do this. If two do, or if the old host is in
    /// fact still playing, the two courts find each other and one of them
    /// gives way (see `onRival`).
    func takeOverAsHost() {
        guard let client, let snapshot = client.confirmed else { return }
        // The offer may have been on screen for a while; the host may be back.
        guard holdsOffer(client) else { return }
        let code = enteredCode
        let label = courtName
        // A court where this device could only watch stays view-only for the others.
        let taken = Sessions.shared.takeOver(
            snapshot: snapshot,
            hostDeviceId: DeviceIdentity.deviceId,
            joinCode: Int32(code ?? CourtStore.noCode),
            guestsCanScore: client.canScore,
            nowMillis: now()
        )
        guard let taken else {
            report("This match has changed hands too many times to be taken over again.")
            return
        }
        leaveInternal()
        host = taken
        joinCode = code
        courtLabel = label
        saveCode(code)
        saveLabel(label)
        // Carrying a match on is not news: do not read the score out.
        announced = taken.snapshot()
        publish()
        openCourt()
    }
    #endif

    /// Sends the regular repeat of the match, except to a guest that has not
    /// taken in what it was already sent: a device on a phone call, say. Its
    /// queue would otherwise fill up with out-of-date copies every 2 seconds,
    /// and the scores behind them would reach it later and later.
    private func deliverHeartbeat(_ outgoing: [Outgoing]) {
        #if os(iOS)
        guard let transport = hostTransport else { return }
        for item in outgoing where !transport.hasPending(peerId: item.peerId) {
            transport.send(peerId: item.peerId, packets: item.packets.map { $0.toData() })
        }
        #endif
    }

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
        enteredCode = code
        guestTransport.connect(to: court)
        livenessTimer = Timer.scheduledTimer(withTimeInterval: CourtStore.livenessSeconds, repeats: true) { [weak self] _ in
            self?.checkHostIsAlive()
        }
        publish()
    }

    /// Notices a host that has gone quiet on a link Bluetooth still calls
    /// connected, which is what happens when the host's app is closed or
    /// crashes: the radio link stays up and nothing reports a problem. A live
    /// host repeats the match every two seconds. After 8 seconds of silence
    /// the host is asked to speak (which also wakes an iPhone host that is
    /// merely suspended); after 16 the link is dropped, and the usual
    /// reconnecting and looking take over.
    ///
    /// Silence is counted in checks rather than read off the clock: while
    /// this app is itself suspended it hears nothing and checks nothing, and
    /// that must not count against the host.
    private func checkHostIsAlive() {
        guard let client, guestTransport.isUp else { return }
        silentChecks += 1
        if silentChecks >= CourtStore.silentChecksBeforeDrop {
            silentChecks = 0
            hostPinged = false
            guestTransport.hostSilent()
        } else if silentChecks >= CourtStore.silentChecksBeforePing && !hostPinged {
            hostPinged = true
            handle(client.ping())
        }
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
            } else if effect is ClientEffect.WrongCourt {
                // Somebody else's court under the same name: let go and keep looking.
                guestTransport.wrongCourt()
            } else if effect is ClientEffect.Disconnect {
                // The court closed: keep the result if there was one.
                recordIfDecided()
                if client?.status == ClientStatus.ended {
                    // The host closed the court. It is not chased, but the
                    // match is still looked for by name for a while: another
                    // player may carry it on, or it may have moved to another court.
                    guestTransport.courtClosed()
                } else {
                    // The host refused us. Stop the link so it does not retry;
                    // the session keeps the reason for the UI.
                    guestTransport.close()
                }
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
        if wasHost { clearSavedMatch() }
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
        clearSavedMatchOnceSynced = false
        heartbeat?.invalidate()
        heartbeat = nil
        host = nil
        joinCode = nil
        courtLabel = nil
        savedVersion = -1
        savedMatchId = 0

        guestTransport.close()
        client = nil
        courtName = nil
        enteredCode = nil
        // After closing the link, which reports it down and would start the offer's clock.
        hostLostAt = nil
        cancelOfferTimers()
        livenessTimer?.invalidate()
        livenessTimer = nil
        silentChecks = 0
        hostPinged = false

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

    /// Sets a published value only when it changed. Every assignment to a
    /// published property redraws the screens that use it, even with the
    /// same value, and a guest republishes on every message from the host,
    /// about every 2 seconds. Kotlin values compare by content here.
    private func assign<Value: Equatable>(_ keyPath: ReferenceWritableKeyPath<CourtStore, Value>, _ value: Value) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    /// The statistics of a match, worked out again only when the match changed.
    private func statsOf(_ snapshot: MatchSnapshot) -> MatchStats {
        if let cached = statsCache, let source = statsSource, source == snapshot { return cached }
        let fresh = MatchStats.companion.of(snapshot: snapshot)
        statsSource = snapshot
        statsCache = fresh
        return fresh
    }

    private var statsSource: MatchSnapshot?
    private var statsCache: MatchStats?

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
        assign(\.speech, currentSpeech)
        assign(\.voiceAvailable, !announcer.unavailable)
        let savedMatchExists = UserDefaults.standard.data(forKey: CourtStore.savedMatchKey) != nil

        if let host {
            let snapshot = host.snapshot()
            assign(\.mode, .host)
            assign(\.score, ScoreView.companion.of(snapshot: snapshot))
            assign(\.config, snapshot.config)
            assign(\.deviceCount, Int(host.deviceCount))
            #if os(iOS)
            assign(\.courtOpen, hostTransport != nil && !courtSuspended)
            #else
            assign(\.courtOpen, false)
            #endif
            assign(\.guestSynced, false)
            assign(\.guestRejected, false)
            assign(\.guestEnded, false)
            assign(\.rejection, nil)
            assign(\.hasSavedMatch, true)
            assign(\.canScore, true)
            assign(\.canTakeOver, false)
            assign(\.guests, host.guests)
            assign(\.guestsCanScore, host.guestsCanScore)
            assign(\.stats, statsOf(snapshot))
            assign(\.startedAtMillis, host.log.startedAtMillis)
            assign(\.durationMillis, recordedDuration(snapshot))
        } else if let client {
            let display = client.displayState
            let confirmed = client.confirmed
            assign(\.mode, .guest)
            if let display, let confirmed {
                // The host's names, with this device's own unconfirmed taps on top.
                assign(\.score, ScoreView.companion.of(
                    state: display,
                    roster: confirmed.roster,
                    serveFlipA: client.displayServeFlip(team: Team.a),
                    serveFlipB: client.displayServeFlip(team: Team.b)
                ))
            } else {
                assign(\.score, nil)
            }
            assign(\.config, display?.config)
            assign(\.deviceCount, Int(client.deviceCount))
            assign(\.courtOpen, false)
            assign(\.guestSynced, client.status == ClientStatus.synced)
            assign(\.guestRejected, client.status == ClientStatus.rejected)
            assign(\.guestEnded, client.status == ClientStatus.ended)
            assign(\.rejection, client.rejection)
            assign(\.hasSavedMatch, savedMatchExists)
            assign(\.canScore, client.canScore)
            assign(\.canTakeOver, holdsOffer(client))
            assign(\.guests, [])
            assign(\.guestsCanScore, true)
            if let confirmed {
                assign(\.stats, statsOf(confirmed))
                assign(\.startedAtMillis, firstSeen[confirmed.matchId])
                assign(\.durationMillis, recordedDuration(confirmed))
            } else {
                assign(\.stats, nil)
                assign(\.startedAtMillis, nil)
                assign(\.durationMillis, nil)
            }
        } else {
            assign(\.mode, .idle)
            assign(\.score, nil)
            assign(\.config, nil)
            assign(\.deviceCount, 1)
            assign(\.courtOpen, false)
            assign(\.guestSynced, false)
            assign(\.guestRejected, false)
            assign(\.guestEnded, false)
            assign(\.rejection, nil)
            assign(\.hasSavedMatch, savedMatchExists)
            assign(\.canScore, true)
            assign(\.canTakeOver, false)
            assign(\.guests, [])
            assign(\.guestsCanScore, true)
            assign(\.stats, nil)
            assign(\.startedAtMillis, nil)
            assign(\.durationMillis, nil)
        }
        // A match has just begun on this device: start the reminder clock.
        if wasIdle && mode != .idle { scheduleReminder() }
    }

    /// Whether to offer this guest's player the host's place: the device
    /// holds the match, and the host has been out of reach for a while. A
    /// closed court is first looked for under its name, as another player
    /// may already be carrying the match on, so that wait is the shorter one.
    private func holdsOffer(_ client: ClientSession) -> Bool {
        if client.confirmed == nil { return false }
        let status = client.status
        if status == ClientStatus.synced || status == ClientStatus.rejected { return false }
        guard let hostLostAt else { return false }
        let lostFor = Date().timeIntervalSince(hostLostAt)
        if status == ClientStatus.ended { return lostFor >= CourtStore.takeOverAfterCloseSeconds }
        return lostFor >= CourtStore.takeOverAfterSeconds
    }

    private func cancelOfferTimers() {
        for timer in offerTimers { timer.invalidate() }
        offerTimers.removeAll()
    }

    // The hosted court's join code and name are kept next to the saved
    // match. A match resumed after the app was closed opens under the same
    // code, so guests still looking for the court are let back in without
    // typing it again; and a court taken over from another host keeps that
    // host's name, which is what the other guests are looking for.

    private func saveCode(_ code: Int?) {
        if let code {
            UserDefaults.standard.set(code, forKey: CourtStore.savedCodeKey)
        } else {
            UserDefaults.standard.removeObject(forKey: CourtStore.savedCodeKey)
        }
    }

    private func savedCode() -> Int? {
        UserDefaults.standard.object(forKey: CourtStore.savedCodeKey) as? Int
    }

    private func saveLabel(_ label: String?) {
        if let label {
            UserDefaults.standard.set(label, forKey: CourtStore.savedLabelKey)
        } else {
            UserDefaults.standard.removeObject(forKey: CourtStore.savedLabelKey)
        }
    }

    private func savedLabel() -> String? {
        UserDefaults.standard.string(forKey: CourtStore.savedLabelKey)
    }

    /// Remembers whether the hosted court was open to others, so that a
    /// resumed match reopens it.
    private func saveOpen(_ open: Bool) {
        UserDefaults.standard.set(open, forKey: CourtStore.savedOpenKey)
    }

    private func clearSavedMatch() {
        UserDefaults.standard.removeObject(forKey: CourtStore.savedMatchKey)
        UserDefaults.standard.removeObject(forKey: CourtStore.savedCodeKey)
        UserDefaults.standard.removeObject(forKey: CourtStore.savedLabelKey)
        UserDefaults.standard.removeObject(forKey: CourtStore.savedOpenKey)
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

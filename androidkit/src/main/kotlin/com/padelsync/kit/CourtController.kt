package com.padelsync.kit

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.history.MatchHistory
import com.netsports.core.history.MatchRecord
import com.netsports.core.match.Action
import com.netsports.core.match.CommandOutcome
import com.netsports.core.match.MatchLog
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import com.netsports.core.sync.ClientEffect
import com.netsports.core.sync.ClientSession
import com.netsports.core.sync.ClientStatus
import com.netsports.core.sync.DeviceKind
import com.netsports.core.sync.HostSession
import com.netsports.core.sync.JoinRejection
import com.netsports.core.sync.Outgoing
import com.netsports.core.sync.PeerInfo
import com.netsports.core.sync.RandomIdSource
import com.netsports.core.sync.TapFeedback
import com.netsports.core.ui.MatchStats
import com.netsports.core.ui.ScoreSpeech
import com.netsports.core.ui.ScoreView
import com.netsports.core.ui.SpeechSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** What this device is doing right now. */
enum class CourtMode {
    /** No match. */
    IDLE,

    /** This device owns the match. Other devices may or may not be connected. */
    HOST,

    /** This device is scoring through another device's match. */
    GUEST,
}

/** Everything the UI needs to draw. Immutable; a new value is published on every change. */
data class CourtUiState(
    val mode: CourtMode = CourtMode.IDLE,
    /** The scoreboard, or `null` when there is no match to show yet. */
    val score: ScoreView? = null,
    val config: MatchConfig? = null,
    /** Devices in the session, this one included. */
    val deviceCount: Int = 1,
    /** Host only: whether other devices can find and join this court. */
    val courtOpen: Boolean = false,
    /** Host only: the code guests must enter. */
    val joinCode: Int? = null,
    /** Guest only. */
    val guestStatus: ClientStatus? = null,
    /** Guest only: why the host refused this device. */
    val rejection: JoinRejection? = null,
    /** Guest only: the label of the court being joined. */
    val courtName: String? = null,
    /** Guest only: taps not yet confirmed by the host. */
    val pendingTaps: Int = 0,
    /** A problem to show the user, or `null`. */
    val error: String? = null,
    /** The outcome of the most recent tap, for haptics. [feedbackCount] changes on every new one. */
    val lastFeedback: TapFeedback? = null,
    val feedbackCount: Int = 0,
    /** Changes each time another device changes the score, so this one can signal it. */
    val remoteScoreCount: Int = 0,
    /** Whether a hosted match from an earlier run can be resumed. */
    val hasSavedMatch: Boolean = false,
    /** Whether this device may change the score. Always true for the host. */
    val canScore: Boolean = true,
    /** Host only: the devices that have joined, with what each may do. */
    val guests: List<PeerInfo> = emptyList(),
    /** Host only: whether devices the host has not singled out may score. */
    val guestsCanScore: Boolean = true,
    /** Points, breaks and streaks so far, or `null` when there is no match. */
    val stats: MatchStats? = null,
    /** When this device first had the match, for the running clock. */
    val startedAtMillis: Long? = null,
    /** How long the match took, once it is over. */
    val durationMillis: Long? = null,
    /** How this device announces the score. */
    val speech: SpeechSettings = SpeechSettings.OFF,
    /** False once the device turned out to have no text-to-speech voice. */
    val voiceAvailable: Boolean = true,
)

/**
 * The single entry point the phone and watch UIs talk to.
 *
 * It owns the shared-core session (host or guest), wires it to the Bluetooth
 * transports, and publishes [ui]. One instance lives for the whole process,
 * so a match carries on when the screen rotates or the activity is recreated.
 *
 * Everything runs on the main thread: call every method from it.
 */
class CourtController private constructor(
    private val app: Context,
    private val kind: DeviceKind,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val identity = DeviceIdentity(app)
    private val store = MatchStore(app)
    private val settings = SettingsStore(app, voiceOnWhenHosting = kind == DeviceKind.PHONE)
    private val ids = RandomIdSource()

    // A device without a voice simply stays quiet; the voice settings say why.
    private val announcer = Announcer(app, handler) { publish() }

    /** The match as last put through the announcer, so each change is spoken once. */
    private var announced: MatchSnapshot? = null

    private val reminder = Runnable { remind() }

    private val _ui = MutableStateFlow(
        CourtUiState(hasSavedMatch = store.load() != null, speech = settings.speech(hosting = true)),
    )
    val ui: StateFlow<CourtUiState> = _ui.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyCourt>>(emptyList())

    /** Courts found by the current scan, nearest first. */
    val nearby: StateFlow<List<NearbyCourt>> = _nearby.asStateFlow()

    private val historyStore = HistoryStore(app)
    private val _history = MutableStateFlow(historyStore.load())

    /** Finished matches this device took part in, newest first. */
    val history: StateFlow<List<MatchRecord>> = _history.asStateFlow()

    /** Guest side: when this device first saw each match, for its duration. */
    private val firstSeen = HashMap<Long, Long>()

    // Host side.
    private var host: HostSession? = null
    private var hostTransport: HostTransport? = null
    private var joinCode: Int? = null
    private var savedVersion = -1
    private var savedMatchId = 0L

    // Guest side.
    private var client: ClientSession? = null
    private var link: GuestLink? = null
    private var courtName: String? = null

    private var scanner: CourtScanner? = null
    private var error: String? = null
    private var lastFeedback: TapFeedback? = null
    private var feedbackCount = 0
    private var remoteScoreCount = 0

    private val heartbeat = object : Runnable {
        override fun run() {
            val session = host ?: return
            if (hostTransport == null) return
            deliver(session.heartbeat())
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** The name other players see for this device. */
    var deviceName: String
        get() = identity.deviceName
        set(value) {
            identity.deviceName = value
        }

    // --- Hosting -----------------------------------------------------------

    /** The match last set up on this device, to pre-fill the setup screen. */
    val lastSetup: MatchSetup?
        get() = settings.lastSetup()

    /** Starts a new match on this device with default options. Nothing is shared until [openCourt]. */
    fun startMatch(config: MatchConfig) = startMatch(MatchSetup(config))

    /** Starts a new match on this device. Nothing is shared until [openCourt]. */
    fun startMatch(setup: MatchSetup) {
        leaveInternal()
        settings.saveSetup(setup)
        joinCode = Random.nextInt(1000, 10000)
        host = HostSession(
            log = MatchLog.start(ids.next(), setup.config, now(), setup.roster),
            hostDeviceId = identity.deviceId,
            joinCode = joinCode,
            ids = ids,
            guestsCanScore = setup.guestsCanScore,
        )
        publish()
    }

    /** Resumes the match this device was hosting when the app last closed. */
    fun resumeSavedMatch() {
        val snapshot = store.load() ?: return
        leaveInternal()
        val log = try {
            MatchLog.takeOver(snapshot, now())
        } catch (e: IllegalArgumentException) {
            store.clear()
            publish()
            return
        }
        joinCode = Random.nextInt(1000, 10000)
        host = HostSession(
            log = log,
            hostDeviceId = identity.deviceId,
            joinCode = joinCode,
            ids = ids,
            guestsCanScore = settings.lastSetup()?.guestsCanScore ?: true,
        )
        // Picking a match back up is not news: do not read the score out.
        announced = host?.snapshot()
        publish()
    }

    /** Replaces the hosted match with a fresh one with default options, keeping connected devices. */
    fun startNewMatch(config: MatchConfig) =
        startNewMatch(MatchSetup(config, guestsCanScore = host?.guestsCanScore ?: true))

    /** Replaces the hosted match with a fresh one, keeping connected devices. */
    fun startNewMatch(setup: MatchSetup) {
        val session = host ?: return startMatch(setup)
        settings.saveSetup(setup)
        deliver(session.startNewMatch(setup.config, now(), setup.roster))
        if (session.guestsCanScore != setup.guestsCanScore) deliver(session.setGuestsCanScore(setup.guestsCanScore))
        publish()
    }

    /** Host only: plays again with the same format and the same players. */
    fun rematch() {
        val session = host ?: return
        val snapshot = session.snapshot()
        deliver(session.startNewMatch(snapshot.config, now(), snapshot.roster))
        publish()
    }

    /** Host only: changes the players' names mid-match. */
    fun updateRoster(roster: Roster) {
        val session = host ?: return
        deliver(session.updateRoster(roster))
        settings.saveSetup(MatchSetup(session.state.config, roster, session.guestsCanScore))
        store.save(session)
        publish()
    }

    /** Host only: lets one joined device score, or makes it view-only. */
    fun setCanScore(deviceId: Long, allowed: Boolean) {
        val session = host ?: return
        deliver(session.setCanScore(deviceId, allowed))
        publish()
    }

    /** Host only: lets every joined device score, or makes them all view-only. */
    fun setGuestsCanScore(allowed: Boolean) {
        val session = host ?: return
        deliver(session.setGuestsCanScore(allowed))
        val snapshot = session.snapshot()
        settings.saveSetup(MatchSetup(snapshot.config, snapshot.roster, allowed))
        publish()
    }

    /** Lets other devices find and join this court. Needs Bluetooth permissions. */
    fun openCourt() {
        if (host == null || hostTransport != null) return
        if (!checkBluetooth()) return

        val transport = HostTransport(app, handler, hostListener)
        if (!transport.start()) {
            error = "Bluetooth is not available on this device."
            publish()
            return
        }
        hostTransport = transport
        error = null
        transport.startAdvertising(identity.deviceName)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        CourtService.start(app)
        publish()
    }

    /** Stops sharing the court and disconnects every guest. The match carries on locally. */
    fun closeCourt() {
        if (hostTransport == null) return
        shutDownHostTransport()
        CourtService.stop(app)
        publish()
    }

    /**
     * Tells the guests the court is closing, then stops the Bluetooth side
     * once that message has had a moment to go out.
     */
    private fun shutDownHostTransport() {
        val transport = hostTransport ?: return
        hostTransport = null
        handler.removeCallbacks(heartbeat)
        host?.let { session ->
            for (item in session.endSession()) transport.send(item.peerId, item.packets)
        }
        transport.stopAdvertising()
        handler.postDelayed({ transport.stop() }, FAREWELL_MS)
    }

    private val hostListener = object : HostTransport.Listener {
        override fun onGuestReady(peerId: String, maxPacketSize: Int) {
            host?.peerConnected(peerId, maxPacketSize)
        }

        override fun onGuestPacketSize(peerId: String, maxPacketSize: Int) {
            host?.peerPacketSizeChanged(peerId, maxPacketSize)
        }

        override fun onGuestGone(peerId: String) {
            val session = host ?: return
            deliver(session.peerDisconnected(peerId))
            publish()
        }

        override fun onPacket(peerId: String, packet: ByteArray) {
            val session = host ?: return
            val before = session.log.version
            deliver(session.packetReceived(peerId, packet, now()))
            if (session.log.version != before) remoteScoreCount++
            publish()
        }

        override fun onAdvertisingFailed(reason: String) {
            error = reason
            closeCourt()
        }
    }

    private fun deliver(outgoing: List<Outgoing>) {
        val transport = hostTransport ?: return
        for (item in outgoing) transport.send(item.peerId, item.packets)
    }

    // --- Joining -----------------------------------------------------------

    /** Starts looking for nearby courts. Results arrive in [nearby]. */
    fun startScan() {
        if (!checkBluetooth()) return
        val active = scanner ?: CourtScanner(app, handler) { _nearby.value = it }.also { scanner = it }
        if (!active.start()) {
            error = "Could not search for courts. Check that Bluetooth is on."
        } else {
            error = null
        }
        publish()
    }

    fun stopScan() {
        scanner?.stop()
    }

    /** Joins [court] as a guest. [code] is the join code shown on the host's screen. */
    fun join(court: NearbyCourt, code: Int?) {
        leaveInternal()
        stopScan()
        val session = ClientSession(identity.deviceId, identity.deviceName, kind, code, ids)
        client = session
        courtName = court.name
        link = GuestLink(app, handler, court.device, guestListener).also { it.connect() }
        CourtService.start(app)
        publish()
    }

    private val guestListener = object : GuestLink.Listener {
        override fun onLinkUp(maxPacketSize: Int) {
            val session = client ?: return
            handle(session.connected(maxPacketSize))
            publish()
        }

        override fun onLinkDown() {
            client?.disconnected()
            publish()
        }

        override fun onPacket(packet: ByteArray) {
            val session = client ?: return
            val before = session.confirmed
            val effects = session.packetReceived(packet)
            handle(effects)
            val after = session.confirmed
            val ownTap = effects.any { it is ClientEffect.Feedback && it.feedback == TapFeedback.ACCEPTED }
            val changed = before != null && after != null &&
                (after.version != before.version || after.matchId != before.matchId)
            if (changed && !ownTap) remoteScoreCount++
            publish()
        }
    }

    private fun handle(effects: List<ClientEffect>) {
        for (effect in effects) {
            when (effect) {
                is ClientEffect.Send -> link?.send(effect.packets)
                is ClientEffect.Feedback -> {
                    lastFeedback = effect.feedback
                    feedbackCount++
                }
                ClientEffect.Disconnect -> {
                    // The court closed: keep the result if there was one.
                    recordIfDecided()
                    // The host refused us. Stop the link so it does not retry;
                    // the session keeps the reason for the UI.
                    link?.close()
                    link = null
                    CourtService.stop(app)
                }
            }
        }
    }

    // --- Scoring -----------------------------------------------------------

    /** A player tapped on this device. */
    fun tap(action: Action) {
        host?.let { session ->
            deliver(session.submit(action, now()))
            if (session.lastSubmitOutcome == CommandOutcome.SAME_RALLY) {
                lastFeedback = TapFeedback.SUPERSEDED
                feedbackCount++
            }
        }
        client?.let { session ->
            handle(session.submit(action))
        }
        publish()
    }

    // --- Voice -------------------------------------------------------------

    /** How this device announces the score in its current role. */
    val speech: SpeechSettings
        get() = settings.speech(hosting = client == null)

    /** Changes how this device announces the score. Remembered between matches. */
    fun setSpeech(value: SpeechSettings) {
        settings.saveSpeech(value, hosting = client == null)
        // Switching the voice on is a good moment to look again for one
        // that was missing before, in case the player has since installed it.
        if (value.enabled) announcer.retry() else announcer.silence()
        scheduleReminder()
        publish()
    }

    /** Reads out the whole score now, whatever the settings say. */
    fun sayScore() {
        announcer.retry()
        currentSnapshot()?.let { announcer.say(ScoreSpeech.reminder(it)) }
    }

    /** The match as the host has it: this device's own when hosting, the last one received when a guest. */
    private fun currentSnapshot(): MatchSnapshot? = host?.snapshot() ?: client?.confirmed

    /**
     * Speaks whatever changed since the last call. Only confirmed scores are
     * announced, never a guest's own unconfirmed tap, so the voice cannot
     * call a point the host then refuses.
     */
    private fun announce(snapshot: MatchSnapshot?) {
        if (snapshot == null || snapshot == announced) return
        val phrases = ScoreSpeech.announce(announced, snapshot, speech)
        announced = snapshot
        if (phrases.isNotEmpty()) announcer.say(phrases)
    }

    private fun scheduleReminder() {
        handler.removeCallbacks(reminder)
        val current = speech
        if (!current.enabled || current.reminderMinutes == 0) return
        if (host == null && client == null) return
        handler.postDelayed(reminder, current.reminderMinutes * 60_000L)
    }

    private fun remind() {
        val snapshot = currentSnapshot()
        // A guest that has lost the host has only an old score to offer.
        val live = host != null || client?.status == ClientStatus.SYNCED
        // Nothing to remind anyone of before the first point or after the last.
        if (live && snapshot != null && snapshot.points.isNotEmpty() && !snapshot.state.isComplete) {
            announcer.say(ScoreSpeech.reminder(snapshot))
        }
        scheduleReminder()
    }

    /** Ends the match (host) or leaves the court (guest) and returns to idle. */
    fun leave() {
        val wasHost = host != null
        recordIfDecided()
        leaveInternal()
        if (wasHost) store.clear()
        publish()
    }

    fun clearError() {
        error = null
        publish()
    }

    // --- Internals ---------------------------------------------------------

    private fun leaveInternal() {
        shutDownHostTransport()
        host = null
        joinCode = null
        savedVersion = -1
        savedMatchId = 0L

        link?.close()
        link = null
        client = null
        courtName = null

        error = null
        lastFeedback = null
        announced = null
        handler.removeCallbacks(reminder)
        announcer.silence()
        CourtService.stop(app)
    }

    private fun checkBluetooth(): Boolean {
        error = when {
            !BlePermissions.granted(app) -> "Allow Bluetooth access to play with others."
            !BlePermissions.bluetoothOn(app) -> "Turn on Bluetooth to play with others."
            else -> null
        }
        if (error != null) publish()
        return error == null
    }

    private fun publish() {
        val hostSession = host
        val guestSession = client

        if (hostSession != null) {
            // Persist only when the match actually changed.
            val log = hostSession.log
            if (log.version != savedVersion || log.matchId != savedMatchId) {
                store.save(hostSession)
                savedVersion = log.version
                savedMatchId = log.matchId
                trackHistory(hostSession.snapshot(), log.startedAtMillis)
            }
        }
        guestSession?.confirmed?.let { snapshot ->
            trackHistory(snapshot, firstSeen.getOrPut(snapshot.matchId) { now() })
        }

        val wasIdle = _ui.value.mode == CourtMode.IDLE
        announce(currentSnapshot())
        val voice = speech
        val voiceAvailable = !announcer.unavailable

        _ui.value = when {
            hostSession != null -> {
                val snapshot = hostSession.snapshot()
                CourtUiState(
                    mode = CourtMode.HOST,
                    score = ScoreView.of(snapshot),
                    config = snapshot.config,
                    deviceCount = hostSession.deviceCount,
                    courtOpen = hostTransport != null,
                    joinCode = joinCode,
                    error = error,
                    lastFeedback = lastFeedback,
                    feedbackCount = feedbackCount,
                    remoteScoreCount = remoteScoreCount,
                    hasSavedMatch = true,
                    canScore = true,
                    guests = hostSession.guests,
                    guestsCanScore = hostSession.guestsCanScore,
                    stats = MatchStats.of(snapshot),
                    startedAtMillis = hostSession.log.startedAtMillis,
                    durationMillis = recordedDuration(snapshot),
                    speech = voice,
                    voiceAvailable = voiceAvailable,
                )
            }
            guestSession != null -> {
                val display = guestSession.displayState
                val confirmed = guestSession.confirmed
                CourtUiState(
                    mode = CourtMode.GUEST,
                    score = if (display != null && confirmed != null) {
                        ScoreView.of(
                            display,
                            confirmed.roster,
                            guestSession.displayServeFlip(Team.A),
                            guestSession.displayServeFlip(Team.B),
                        )
                    } else {
                        null
                    },
                    config = display?.config,
                    deviceCount = guestSession.deviceCount,
                    guestStatus = guestSession.status,
                    rejection = guestSession.rejection,
                    courtName = courtName,
                    pendingTaps = guestSession.pendingCount,
                    error = error,
                    lastFeedback = lastFeedback,
                    feedbackCount = feedbackCount,
                    remoteScoreCount = remoteScoreCount,
                    hasSavedMatch = store.load() != null,
                    canScore = guestSession.canScore,
                    stats = confirmed?.let { MatchStats.of(it) },
                    startedAtMillis = confirmed?.let { firstSeen[it.matchId] },
                    durationMillis = confirmed?.let { recordedDuration(it) },
                    speech = voice,
                    voiceAvailable = voiceAvailable,
                )
            }
            else -> CourtUiState(
                error = error,
                hasSavedMatch = store.load() != null,
                speech = voice,
                voiceAvailable = voiceAvailable,
            )
        }
        // A match has just begun on this device: start the reminder clock.
        if (wasIdle && _ui.value.mode != CourtMode.IDLE) scheduleReminder()
    }

    /**
     * Keeps the history in step with a match: adds it when it is won, and
     * takes it out again if the winning point is undone.
     */
    private fun trackHistory(snapshot: MatchSnapshot, startedAtMillis: Long) {
        val current = _history.value
        val recorded = current.firstOrNull { it.matchId == snapshot.matchId }
        val updated = when {
            snapshot.state.isComplete && recorded?.snapshot?.version != snapshot.version ->
                MatchHistory.add(current, MatchRecord(snapshot, startedAtMillis, now()))
            // A finished match reopened with undo is no longer a result. A
            // match recorded as decided but unfinished stays as it is.
            !snapshot.state.isComplete && recorded != null && recorded.snapshot.state.isComplete ->
                MatchHistory.remove(current, snapshot.matchId)
            else -> return
        }
        _history.value = updated
        historyStore.save(updated)
    }

    /** How long a finished match took, as recorded in the history when it ended. */
    private fun recordedDuration(snapshot: MatchSnapshot): Long? =
        if (snapshot.state.isComplete) {
            _history.value.firstOrNull { it.matchId == snapshot.matchId }?.durationMillis
        } else {
            null
        }

    /**
     * Keeps a match that was stopped after it was decided but before every
     * set was played, which is how most "play all three sets" matches end
     * when the court time runs out.
     */
    private fun recordIfDecided() {
        val snapshot = currentSnapshot() ?: return
        if (snapshot.state.isComplete || snapshot.state.decidedWinner == null) return
        // Already kept, for example when the court closed and the player then tapped Back.
        if (_history.value.any { it.matchId == snapshot.matchId && it.snapshot.version == snapshot.version }) return
        val startedAt = host?.log?.startedAtMillis ?: firstSeen[snapshot.matchId] ?: now()
        val updated = MatchHistory.add(_history.value, MatchRecord(snapshot, startedAt, now()))
        _history.value = updated
        historyStore.save(updated)
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val HEARTBEAT_MS = 2_000L

        /** How long to let the closing message reach guests before the link goes down. */
        private const val FAREWELL_MS = 400L

        @Volatile
        private var instance: CourtController? = null

        /**
         * Returns the process-wide controller, creating it on first use.
         *
         * @param kind whether this app runs on a phone or a watch.
         */
        fun get(context: Context, kind: DeviceKind): CourtController =
            instance ?: synchronized(this) {
                instance ?: CourtController(context.applicationContext, kind).also { instance = it }
            }
    }
}

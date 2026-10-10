package com.padelsync.kit

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
import com.netsports.core.sync.CourtName
import com.netsports.core.sync.DeviceKind
import com.netsports.core.sync.HostSession
import com.netsports.core.sync.JoinRejection
import com.netsports.core.sync.Outgoing
import com.netsports.core.sync.PeerInfo
import com.netsports.core.sync.RandomIdSource
import com.netsports.core.sync.RivalVerdict
import com.netsports.core.sync.Sessions
import com.netsports.core.sync.TapFeedback
import com.netsports.core.ui.MatchStats
import com.netsports.core.ui.ScoreSpeech
import com.netsports.core.ui.ScoreView
import com.netsports.core.ui.SpeechSettings
import androidx.core.content.ContextCompat
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
    /**
     * Guest only: the host has been out of reach long enough, or has closed
     * the court, and this device could carry the match on as host.
     */
    val canTakeOver: Boolean = false,
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
 * Its work runs on a thread of its own (see [handler]): every public method
 * may be called from the main thread and returns at once, and the result
 * shows up in [ui].
 */
class CourtController private constructor(
    private val app: Context,
    private val kind: DeviceKind,
) {
    /**
     * Every piece of the controller's work runs on this one thread, in order:
     * the match, the saved files, and above all the Bluetooth calls. Android's
     * Bluetooth calls can block for seconds while the radio is busy, for
     * example when a phone call reaches a watch; on the main thread that
     * froze the whole screen of a joined Galaxy Watch8 Classic for about two
     * minutes. Here they only delay the Bluetooth work, never the screen.
     */
    private val worker = HandlerThread("padelsync-court").apply { start() }
    private val handler = Handler(worker.looper)

    /** Runs [block] on the controller's thread: now if already on it, otherwise next in line. */
    private fun onWorker(block: () -> Unit) {
        if (Looper.myLooper() == worker.looper) block() else handler.post(block)
    }
    private val identity = DeviceIdentity(app)
    private val store = MatchStore(app)
    private val settings = SettingsStore(app)
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
    private var rivalWatch: RivalWatch? = null
    private var joinCode: Int? = null

    /** The name this court advertises when it was taken over from another host; null for this device's own name. */
    private var courtLabel: String? = null
    private var savedVersion = -1
    private var savedMatchId = 0L

    // Guest side.
    private var client: ClientSession? = null
    private var link: GuestConnection? = null
    private var courtName: String? = null

    /** The code the player typed to join, reused if this device takes over as host. */
    private var enteredCode: Int? = null

    /** When the host went out of reach, by the uptime clock; null while in touch. */
    private var hostLostAt: Long? = null
    private val offerTakeOver = Runnable { publish() }

    /**
     * When the host was last heard from, on the clock that stops while this
     * device sleeps. The check below runs on the same clock, so a device
     * waking from a long sleep does not mistake its own absence for the
     * host's silence.
     */
    private var hostHeardAt = 0L
    private var hostPinged = false

    /**
     * Notices a host that has gone quiet on a link Bluetooth still calls
     * connected, which is what happens when the host's app is closed or
     * crashes: the radio link stays up and nothing reports a problem. A live
     * host repeats the match every two seconds. After [SILENT_PING_MS] of
     * silence the host is asked to speak (which also wakes an iPhone host
     * that is merely suspended); after [SILENT_DROP_MS] the link is dropped,
     * and the usual reconnecting and looking take over.
     */
    private val liveness = object : Runnable {
        override fun run() {
            val session = client ?: return
            val connection = link
            if (connection != null && connection.isUp) {
                val quiet = SystemClock.uptimeMillis() - hostHeardAt
                if (quiet >= SILENT_DROP_MS) {
                    hostPinged = false
                    hostHeardAt = SystemClock.uptimeMillis()
                    connection.hostSilent()
                } else if (quiet >= SILENT_PING_MS && !hostPinged) {
                    hostPinged = true
                    handle(session.ping())
                }
            }
            handler.postDelayed(this, LIVENESS_CHECK_MS)
        }
    }

    /**
     * For the emulator test only: reconnect solely by scanning for the court,
     * never by retrying the address joined. On an emulator the host's address
     * never changes, so without this the scanning path would not be exercised.
     */
    @Volatile
    var scanReconnectOnly = false

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

    /**
     * What the setup screen starts from: the players of the last match set
     * up on this device, in the format the player keeps as their own (see
     * [keepFormat]), or in the last match's format if none was kept. `null`
     * on first use.
     */
    val lastSetup: MatchSetup?
        get() {
            val last = settings.lastSetup()
            val mine = settings.myFormat() ?: return last
            return last?.copy(config = mine) ?: MatchSetup(mine)
        }

    /** Makes [config] the format every new match is set up in, until another is kept. */
    fun keepFormat(config: MatchConfig) = settings.saveMyFormat(config)

    /** Starts a new match on this device with default options. Nothing is shared until [openCourt]. */
    fun startMatch(config: MatchConfig) = startMatch(MatchSetup(config))

    /** Starts a new match on this device. Nothing is shared until [openCourt]. */
    fun startMatch(setup: MatchSetup) = onWorker { startMatchNow(setup) }

    private fun startMatchNow(setup: MatchSetup) {
        leaveInternal()
        settings.saveSetup(setup)
        joinCode = Random.nextInt(1000, 10000)
        store.saveCode(joinCode)
        courtLabel = null
        store.saveLabel(null)
        store.saveOpen(false)
        host = HostSession(
            log = MatchLog.start(ids.next(), setup.config, now(), setup.roster),
            hostDeviceId = identity.deviceId,
            joinCode = joinCode,
            ids = ids,
            guestsCanScore = setup.guestsCanScore,
        )
        publish()
    }

    /**
     * Resumes the match this device was hosting when the app last closed.
     *
     * @return true if its court was open to others at the time. The caller
     * should then call [openCourt] (after any Bluetooth permission prompt),
     * so that guests still looking for the court are let back in.
     */
    fun resumeSavedMatch(): Boolean {
        val wasOpen = store.load() != null && store.loadOpen()
        onWorker { resumeSavedMatchNow() }
        return wasOpen
    }

    private fun resumeSavedMatchNow(): Boolean {
        val snapshot = store.load() ?: return false
        val wasOpen = store.loadOpen()
        leaveInternal()
        val log = try {
            MatchLog.takeOver(snapshot, now())
        } catch (e: IllegalArgumentException) {
            store.clear()
            publish()
            return false
        }
        // The same code as before the app closed, so that guests still
        // looking for this court can come back without typing anything.
        joinCode = store.loadCode() ?: Random.nextInt(1000, 10000)
        store.saveCode(joinCode)
        courtLabel = store.loadLabel()
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
        return wasOpen
    }

    /** Replaces the hosted match with a fresh one with default options, keeping connected devices. */
    fun startNewMatch(config: MatchConfig) = onWorker {
        startNewMatchNow(MatchSetup(config, guestsCanScore = host?.guestsCanScore ?: true))
    }

    /** Replaces the hosted match with a fresh one, keeping connected devices. */
    fun startNewMatch(setup: MatchSetup) = onWorker { startNewMatchNow(setup) }

    private fun startNewMatchNow(setup: MatchSetup) {
        val session = host ?: return startMatchNow(setup)
        settings.saveSetup(setup)
        deliver(session.startNewMatch(setup.config, now(), setup.roster))
        if (session.guestsCanScore != setup.guestsCanScore) deliver(session.setGuestsCanScore(setup.guestsCanScore))
        publish()
    }

    /** Host only: plays again with the same format and the same players. */
    fun rematch() = onWorker { rematchNow() }

    private fun rematchNow() {
        val session = host ?: return
        val snapshot = session.snapshot()
        deliver(session.startNewMatch(snapshot.config, now(), snapshot.roster))
        publish()
    }

    /** Host only: changes the players' names mid-match. */
    fun updateRoster(roster: Roster) = onWorker { updateRosterNow(roster) }

    private fun updateRosterNow(roster: Roster) {
        val session = host ?: return
        deliver(session.updateRoster(roster))
        settings.saveSetup(MatchSetup(session.state.config, roster, session.guestsCanScore))
        store.save(session)
        publish()
    }

    /** Host only: lets one joined device score, or makes it view-only. */
    fun setCanScore(deviceId: Long, allowed: Boolean) = onWorker { setCanScoreNow(deviceId, allowed) }

    private fun setCanScoreNow(deviceId: Long, allowed: Boolean) {
        val session = host ?: return
        deliver(session.setCanScore(deviceId, allowed))
        publish()
    }

    /** Host only: lets every joined device score, or makes them all view-only. */
    fun setGuestsCanScore(allowed: Boolean) = onWorker { setGuestsCanScoreNow(allowed) }

    private fun setGuestsCanScoreNow(allowed: Boolean) {
        val session = host ?: return
        deliver(session.setGuestsCanScore(allowed))
        val snapshot = session.snapshot()
        settings.saveSetup(MatchSetup(snapshot.config, snapshot.roster, allowed))
        publish()
    }

    /** Lets other devices find and join this court. Needs Bluetooth permissions. */
    fun openCourt() = onWorker { openCourtNow() }

    private fun openCourtNow() {
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
        // This device's own court carries a few characters of its id, because
        // device names are rarely unique at a club. A court taken over from
        // another host keeps that host's name.
        val label = courtLabel
            ?: CourtName.label(identity.deviceName, identity.deviceId, CourtUuids.MAX_LABEL_BYTES)
        transport.startAdvertising(label)
        // Advertising can fail on the spot, which closes the court again.
        if (hostTransport !== transport) return
        store.saveOpen(true)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        rivalWatch = RivalWatch(
            context = app,
            handler = handler,
            // Guests see the name cut to what an advertisement can carry.
            courtName = CourtUuids.advertisedLabel(label),
            newSession = { ClientSession(identity.deviceId, identity.deviceName, kind, joinCode, ids) },
            onRival = ::onRival,
        ).also { it.start() }
        CourtService.start(app)
        publish()
    }

    /** Stops sharing the court and disconnects every guest. The match carries on locally. */
    fun closeCourt() = onWorker { closeCourtNow() }

    private fun closeCourtNow() {
        reopenWhenBluetoothReturns = false
        if (hostTransport == null) return
        shutDownHostTransport()
        store.saveOpen(false)
        CourtService.stop(app)
        publish()
    }

    /**
     * Stops the Bluetooth side of hosting.
     *
     * @param farewell tell the guests the court is closing first, so they
     * stop trying to reconnect, and give that message a moment to go out.
     * Without it the guests just lose the link and look for the court again
     * by name, which is what is wanted when another device carries on
     * hosting the same match.
     */
    private fun shutDownHostTransport(farewell: Boolean = true) {
        val transport = hostTransport ?: return
        hostTransport = null
        handler.removeCallbacks(heartbeat)
        rivalWatch?.stop()
        rivalWatch = null
        transport.stopAdvertising()
        if (farewell) {
            host?.let { session ->
                for (item in session.endSession()) transport.send(item.peerId, item.packets)
            }
            handler.postDelayed({ transport.stop() }, FAREWELL_MS)
        } else {
            transport.stop()
        }
    }

    /**
     * Another court is hosting this same match. Decides which of the two
     * carries on; see `HostSession.judgeRival`.
     *
     * @return how long to leave that court alone before looking at it again.
     */
    private fun onRival(court: NearbyCourt, snapshot: MatchSnapshot, deviceCount: Int): Long {
        val session = host ?: return RivalWatch.RIVAL_REST_MS
        return when (session.judgeRival(snapshot, deviceCount)) {
            RivalVerdict.DIFFERENT_MATCH -> RivalWatch.OTHER_COURT_REST_MS
            RivalVerdict.HOLD -> {
                // Make sure devices on the other court prefer this one when they find it.
                deliver(session.outrank(snapshot.epoch))
                publish()
                RivalWatch.RIVAL_REST_MS
            }
            RivalVerdict.YIELD -> {
                // Not from inside the lookout's own callback: it is about to be shut down.
                handler.post { yieldTo(court) }
                RivalWatch.RIVAL_REST_MS
            }
        }
    }

    /** Stops hosting in favour of [court], which is hosting the same match, and joins it as a guest. */
    private fun yieldTo(court: NearbyCourt) {
        if (host == null) return
        val code = joinCode
        // The farewell releases this court's guests: they look for the match
        // by name at once and accept the other host whatever its epoch.
        leaveInternal(keepService = true)
        joinInternal(court, code, keepService = true)
        // The saved match is this device's only copy until the other court
        // has answered; it is dropped once that copy has arrived.
        clearSavedMatchOnceSynced = true
        error = "Another device is hosting this match now. This one has joined it."
        publish()
    }

    // --- Taking over as host -----------------------------------------------

    /**
     * Carries the match on as host from this guest's copy of it, for when the
     * host's device has died or left. The court reopens under the name and
     * the join code the guests already know, so they follow by themselves.
     *
     * Only one player should do this. If two do, or if the old host is in
     * fact still playing, the two courts find each other and one of them
     * gives way (see [onRival]).
     */
    fun takeOverAsHost() = onWorker { takeOverAsHostNow() }

    private fun takeOverAsHostNow() {
        val session = client ?: return
        val snapshot = session.confirmed ?: return
        // The offer may have been on screen for a while; the host may be back.
        if (!canTakeOver(session)) return
        val code = enteredCode
        val label = courtName
        // A court where this device could only watch stays view-only for the others.
        val taken = Sessions.takeOver(snapshot, identity.deviceId, code ?: Sessions.NO_CODE, session.canScore, now())
        if (taken == null) {
            error = "This match has changed hands too many times to be taken over again."
            publish()
            return
        }
        leaveInternal(keepService = true)
        host = taken
        joinCode = code
        courtLabel = label
        store.saveCode(code)
        store.saveLabel(label)
        // Carrying a match on is not news: do not read the score out.
        announced = taken.snapshot()
        publish()
        openCourt()
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
    fun startScan() = onWorker { startScanNow() }

    private fun startScanNow() {
        if (!checkBluetooth()) return
        val active = scanner ?: CourtScanner(app, handler) { _nearby.value = it }.also { scanner = it }
        if (!active.start()) {
            error = "Could not search for courts. Check that Bluetooth is on."
        } else {
            error = null
        }
        publish()
    }

    fun stopScan() = onWorker { scanner?.stop() }

    /** Joins [court] as a guest. [code] is the join code shown on the host's screen. */
    fun join(court: NearbyCourt, code: Int?) = onWorker { joinInternal(court, code, keepService = false) }

    /**
     * @param keepService leave the foreground service running across a
     * change of role. Android may refuse to start it again when the app is
     * not on screen, which is exactly when a host gives way.
     */
    private fun joinInternal(court: NearbyCourt, code: Int?, keepService: Boolean) {
        leaveInternal(keepService = keepService)
        stopScan()
        val session = ClientSession(identity.deviceId, identity.deviceName, kind, code, ids)
        client = session
        courtName = court.name
        enteredCode = code
        link = GuestConnection(app, handler, court, guestListener, scanOnly = scanReconnectOnly)
            .also { it.connect() }
        handler.postDelayed(liveness, LIVENESS_CHECK_MS)
        CourtService.start(app)
        publish()
    }

    private val guestListener = object : GuestConnection.Listener {
        override fun onLinkUp(maxPacketSize: Int, foundByScan: Boolean) {
            val session = client ?: return
            // A court found by scanning has to show it carries this match
            // before it is believed; the session checks its first answer.
            hostHeardAt = SystemClock.uptimeMillis()
            hostPinged = false
            handle(if (foundByScan) session.connectedToFoundCourt(maxPacketSize) else session.connected(maxPacketSize))
            publish()
        }

        override fun onLinkDown() {
            client?.disconnected()
            if (hostLostAt == null) {
                hostLostAt = SystemClock.elapsedRealtime()
                // Publish again when the host has been gone long enough to offer taking over.
                handler.postDelayed(offerTakeOver, TAKE_OVER_AFTER_CLOSE_MS)
                handler.postDelayed(offerTakeOver, TAKE_OVER_AFTER_MS)
            }
            publish()
        }

        override fun onPacket(packet: ByteArray) {
            val session = client ?: return
            hostHeardAt = SystemClock.uptimeMillis()
            hostPinged = false
            val before = session.confirmed
            val effects = session.packetReceived(packet)
            handle(effects)
            val after = session.confirmed
            val ownTap = effects.any { it is ClientEffect.Feedback && it.feedback == TapFeedback.ACCEPTED }
            val changed = before != null && after != null &&
                (after.version != before.version || after.matchId != before.matchId)
            if (changed && !ownTap) remoteScoreCount++
            // In step with the host again: if this was a court found by
            // scanning, it has proved itself and is the one to stay with.
            if (session.status == ClientStatus.SYNCED) {
                link?.courtProved()
                // Back on a court after it was lost or closed.
                if (hostLostAt != null) CourtService.start(app)
                hostLostAt = null
                handler.removeCallbacks(offerTakeOver)
                if (clearSavedMatchOnceSynced) {
                    clearSavedMatchOnceSynced = false
                    store.clear()
                }
            }
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
                // Somebody else's court under the same name: let go and keep looking.
                ClientEffect.WrongCourt -> link?.wrongCourt()
                ClientEffect.Disconnect -> {
                    // The court closed: keep the result if there was one.
                    recordIfDecided()
                    if (client?.status == ClientStatus.ENDED) {
                        // The host closed the court. It is not chased, but the
                        // match is still looked for by name for a while: another
                        // player may carry it on, or it may have moved to another court.
                        link?.courtClosed()
                    } else {
                        // The host refused us. Stop the link so it does not retry;
                        // the session keeps the reason for the UI.
                        link?.close()
                        link = null
                    }
                    CourtService.stop(app)
                }
            }
        }
    }

    // --- Scoring -----------------------------------------------------------

    /** A player tapped on this device. */
    fun tap(action: Action) = onWorker { tapNow(action) }

    private fun tapNow(action: Action) {
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
    fun setSpeech(value: SpeechSettings) = onWorker { setSpeechNow(value) }

    private fun setSpeechNow(value: SpeechSettings) {
        settings.saveSpeech(value, hosting = client == null)
        // Switching the voice on is a good moment to look again for one
        // that was missing before, in case the player has since installed it.
        if (value.enabled) announcer.retry() else announcer.silence()
        scheduleReminder()
        publish()
    }

    /** Reads out the whole score now, whatever the settings say. */
    fun sayScore() = onWorker { sayScoreNow() }

    private fun sayScoreNow() {
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
    fun leave() = onWorker { leaveNow() }

    private fun leaveNow() {
        val wasHost = host != null
        recordIfDecided()
        leaveInternal()
        if (wasHost) store.clear()
        publish()
    }

    fun clearError() = onWorker { clearErrorNow() }

    private fun clearErrorNow() {
        error = null
        publish()
    }

    // --- Internals ---------------------------------------------------------

    private fun leaveInternal(farewell: Boolean = true, keepService: Boolean = false) {
        shutDownHostTransport(farewell)
        reopenWhenBluetoothReturns = false
        clearSavedMatchOnceSynced = false
        host = null
        joinCode = null
        courtLabel = null
        savedVersion = -1
        savedMatchId = 0L

        link?.close()
        link = null
        client = null
        courtName = null
        enteredCode = null
        hostLostAt = null
        handler.removeCallbacks(offerTakeOver)
        handler.removeCallbacks(liveness)

        error = null
        lastFeedback = null
        announced = null
        handler.removeCallbacks(reminder)
        announcer.silence()
        if (!keepService) CourtService.stop(app)
    }

    // --- Bluetooth switched off and on ----------------------------------------

    /** Set when Bluetooth going off closed an open court, which then reopens by itself. */
    private var reopenWhenBluetoothReturns = false

    /** Set when a host gave way to another court; see [yieldTo]. */
    private var clearSavedMatchOnceSynced = false

    /**
     * Android tells nobody that a scan, an advertisement or a pending
     * connection died with the Bluetooth switch, and restarts none of them
     * when it comes back. So both moments are handled here.
     */
    private val bluetoothSwitch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                BluetoothAdapter.STATE_ON -> onBluetoothOn()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            bluetoothSwitch,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            null,
            handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun onBluetoothOff() {
        scanner?.stop()
        link?.bluetoothOff()
        if (hostTransport != null) {
            // No farewell can be sent; the guests notice the silence and look for the court.
            shutDownHostTransport(farewell = false)
            reopenWhenBluetoothReturns = true
            error = "Bluetooth is off. The court reopens when it is switched back on."
        }
        publish()
    }

    private fun onBluetoothOn() {
        link?.bluetoothOn()
        if (reopenWhenBluetoothReturns && host != null) {
            reopenWhenBluetoothReturns = false
            error = null
            openCourt()
        }
        publish()
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
                    canTakeOver = canTakeOver(guestSession),
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

    /**
     * Whether to offer this guest the host's place: it holds the match, and
     * the host has either closed the court or been out of reach for
     * [TAKE_OVER_AFTER_MS].
     */
    private fun canTakeOver(session: ClientSession): Boolean {
        if (session.confirmed == null) return false
        return when (session.status) {
            ClientStatus.SYNCED, ClientStatus.REJECTED -> false
            // A closed court is first looked for under its name: another
            // player may already be carrying the match on.
            ClientStatus.ENDED -> lostFor() >= TAKE_OVER_AFTER_CLOSE_MS
            ClientStatus.DISCONNECTED, ClientStatus.JOINING -> lostFor() >= TAKE_OVER_AFTER_MS
        }
    }

    /** How long the host has been out of reach, in milliseconds; 0 while in touch. */
    private fun lostFor(): Long = hostLostAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L

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

        private const val LIVENESS_CHECK_MS = 2_000L

        /** Silence from the host, on a link that is up, before it is asked to speak. */
        private const val SILENT_PING_MS = 8_000L

        /** Silence from the host before the link is treated as dead. */
        private const val SILENT_DROP_MS = 16_000L

        /** How long the host must be out of reach before a guest is offered its place. */
        private const val TAKE_OVER_AFTER_MS = 20_000L

        /** How long after the host closed the court a guest is offered its place. */
        private const val TAKE_OVER_AFTER_CLOSE_MS = 10_000L

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

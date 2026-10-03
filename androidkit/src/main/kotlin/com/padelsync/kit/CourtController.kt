package com.padelsync.kit

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.netsports.core.engine.MatchConfig
import com.netsports.core.match.Action
import com.netsports.core.match.MatchLog
import com.netsports.core.sync.ClientEffect
import com.netsports.core.sync.ClientSession
import com.netsports.core.sync.ClientStatus
import com.netsports.core.sync.DeviceKind
import com.netsports.core.sync.HostSession
import com.netsports.core.sync.JoinRejection
import com.netsports.core.sync.Outgoing
import com.netsports.core.sync.RandomIdSource
import com.netsports.core.sync.TapFeedback
import com.netsports.core.ui.ScoreView
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
    /** Whether a hosted match from an earlier run can be resumed. */
    val hasSavedMatch: Boolean = false,
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
    private val ids = RandomIdSource()

    private val _ui = MutableStateFlow(CourtUiState(hasSavedMatch = store.load() != null))
    val ui: StateFlow<CourtUiState> = _ui.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyCourt>>(emptyList())

    /** Courts found by the current scan, nearest first. */
    val nearby: StateFlow<List<NearbyCourt>> = _nearby.asStateFlow()

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

    /** Starts a new match on this device. Nothing is shared until [openCourt]. */
    fun startMatch(config: MatchConfig) {
        leaveInternal()
        joinCode = Random.nextInt(1000, 10000)
        host = HostSession(MatchLog.start(ids.next(), config, now()), identity.deviceId, joinCode, ids)
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
        host = HostSession(log, identity.deviceId, joinCode, ids)
        publish()
    }

    /** Replaces the hosted match with a fresh one, keeping connected devices. */
    fun startNewMatch(config: MatchConfig) {
        val session = host ?: return startMatch(config)
        deliver(session.startNewMatch(config, now()))
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
        val transport = hostTransport ?: return
        hostTransport = null
        handler.removeCallbacks(heartbeat)
        transport.stop()
        host?.let { session ->
            for (guest in session.guests) session.peerDisconnected(guest.peerId)
        }
        CourtService.stop(app)
        publish()
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
            deliver(session.packetReceived(peerId, packet, now()))
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
            handle(session.packetReceived(packet))
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
        }
        client?.let { session ->
            handle(session.submit(action))
        }
        publish()
    }

    /** Ends the match (host) or leaves the court (guest) and returns to idle. */
    fun leave() {
        val wasHost = host != null
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
        handler.removeCallbacks(heartbeat)
        hostTransport?.stop()
        hostTransport = null
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
            }
        }

        _ui.value = when {
            hostSession != null -> CourtUiState(
                mode = CourtMode.HOST,
                score = ScoreView.of(hostSession.state),
                config = hostSession.state.config,
                deviceCount = hostSession.deviceCount,
                courtOpen = hostTransport != null,
                joinCode = joinCode,
                error = error,
                hasSavedMatch = true,
            )
            guestSession != null -> {
                val display = guestSession.displayState
                CourtUiState(
                    mode = CourtMode.GUEST,
                    score = display?.let { ScoreView.of(it) },
                    config = display?.config,
                    deviceCount = guestSession.deviceCount,
                    guestStatus = guestSession.status,
                    rejection = guestSession.rejection,
                    courtName = courtName,
                    pendingTaps = guestSession.pendingCount,
                    error = error,
                    lastFeedback = lastFeedback,
                    feedbackCount = feedbackCount,
                    hasSavedMatch = store.load() != null,
                )
            }
            else -> CourtUiState(error = error, hasSavedMatch = store.load() != null)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val HEARTBEAT_MS = 2_000L

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

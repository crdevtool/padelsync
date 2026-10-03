package com.padelsync.kit

import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.sync.ClientEffect
import com.netsports.core.sync.ClientSession
import com.netsports.core.sync.ClientStatus

/**
 * A host's lookout for another court hosting the same match.
 *
 * Two hosts for one match come about when a guest takes over while the real
 * host is only out of range, when two guests take over at the same moment,
 * or when a host that was taken over from comes back. Guests cannot settle
 * it: each is linked to one host and cannot see the other. So every host
 * looks for a court advertising its own name every [SCAN_EVERY_MS], joins
 * one it finds for a moment with its own join code, as a guest would, and
 * hands what it learns to [onRival], which decides which of the two courts
 * continues (`HostSession.judgeRival`).
 *
 * All callbacks arrive on [handler]'s thread.
 *
 * @param courtName the name this court advertises.
 * @param newSession makes a fresh guest session carrying this court's join code.
 * @param onRival called with a rival's match and device count; returns how
 * many milliseconds to leave that court alone before looking at it again.
 */
internal class RivalWatch(
    private val context: Context,
    private val handler: Handler,
    private val courtName: String,
    private val newSession: () -> ClientSession,
    private val onRival: (court: NearbyCourt, snapshot: MatchSnapshot, deviceCount: Int) -> Long,
) {
    private val avoidUntil = HashMap<String, Long>()
    private var running = false

    // The court being asked, while a question is in progress.
    private var asking: NearbyCourt? = null
    private var session: ClientSession? = null
    private var link: GuestLink? = null

    private val scanner = CourtScanner(context, handler, ScanSettings.SCAN_MODE_BALANCED) { consider(it) }
    private val beginScan = Runnable { scan() }
    private val endScan = Runnable { rest() }
    private val timeOut = Runnable { finish(FAILED_REST_MS) }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(beginScan, FIRST_SCAN_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(beginScan)
        handler.removeCallbacks(endScan)
        scanner.stop()
        finish(0)
    }

    private fun scan() {
        if (!running) return
        // A scan that cannot start (Bluetooth busy) is simply tried again next round.
        if (asking == null) scanner.start()
        handler.postDelayed(endScan, SCAN_FOR_MS)
    }

    private fun rest() {
        scanner.stop()
        if (running) handler.postDelayed(beginScan, SCAN_EVERY_MS)
    }

    private fun consider(courts: List<NearbyCourt>) {
        if (!running || asking != null) return
        val now = SystemClock.uptimeMillis()
        val rival = courts.firstOrNull { it.name == courtName && (avoidUntil[it.id] ?: 0L) <= now } ?: return
        asking = rival
        scanner.stop()
        session = newSession()
        link = GuestLink(context, handler, rival.device, listener, keepTrying = false).also { it.connect() }
        handler.postDelayed(timeOut, ASK_TIMEOUT_MS)
    }

    private val listener = object : GuestLink.Listener {
        override fun onLinkUp(maxPacketSize: Int) {
            session?.let { carryOut(it.connected(maxPacketSize)) }
        }

        override fun onLinkDown() = Unit

        override fun onPacket(packet: ByteArray) {
            val current = session ?: return
            carryOut(current.packetReceived(packet))
            val court = asking ?: return
            val snapshot = current.confirmed ?: return
            if (current.status == ClientStatus.SYNCED) finish(onRival(court, snapshot, current.deviceCount))
        }

        override fun onGaveUp() = finish(FAILED_REST_MS)
    }

    private fun carryOut(effects: List<ClientEffect>) {
        for (effect in effects) {
            when (effect) {
                is ClientEffect.Send -> link?.send(effect.packets)
                // It refused this court's join code, or is closing: not a rival.
                ClientEffect.Disconnect -> finish(OTHER_COURT_REST_MS)
                ClientEffect.WrongCourt, is ClientEffect.Feedback -> Unit
            }
        }
    }

    /** Ends the question in progress, if any, and leaves that court alone for [restMs]. */
    private fun finish(restMs: Long) {
        val court = asking ?: return
        handler.removeCallbacks(timeOut)
        avoidUntil[court.id] = SystemClock.uptimeMillis() + restMs
        asking = null
        session = null
        link?.close()
        link = null
    }

    companion object {
        /** A court for another match, or one that refused the join code, is left alone this long. */
        const val OTHER_COURT_REST_MS = 600_000L

        /** A rival that was judged is looked at again after this long, in case things changed. */
        const val RIVAL_REST_MS = 45_000L

        private const val FIRST_SCAN_MS = 10_000L
        private const val SCAN_EVERY_MS = 30_000L
        private const val SCAN_FOR_MS = 6_000L
        private const val ASK_TIMEOUT_MS = 12_000L
        private const val FAILED_REST_MS = 20_000L
    }
}

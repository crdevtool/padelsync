package com.padelsync.kit

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.SystemClock

/**
 * A guest's connection to its court, which survives the host changing its
 * Bluetooth address or another player taking over as host.
 *
 * It first keeps retrying the device it joined, as [GuestLink] does. Phones
 * change their Bluetooth address from time to time, so after a long gap that
 * retry can never succeed. If the link has been down for [LOOK_AFTER_MS], it
 * therefore also scans for a court advertising the name that was joined and
 * tries what it finds, while the direct retry carries on.
 *
 * A court found this way is only a candidate: the caller's `ClientSession`
 * checks that it carries the same match, and answers with [courtProved] or
 * [wrongCourt]. A wrong court is left alone for a while and the search goes on.
 *
 * All [Listener] calls arrive on [handler]'s thread.
 *
 * @param directRetry false only in the emulator test of the scanning path,
 * where the host's address never changes and the direct retry would always
 * win.
 */
internal class GuestConnection(
    private val context: Context,
    private val handler: Handler,
    court: NearbyCourt,
    private val listener: Listener,
    private val directRetry: Boolean = true,
) {
    interface Listener {
        /**
         * Packets can flow.
         *
         * @param foundByScan true if this is a court found by scanning that
         * still has to be proved the right one.
         */
        fun onLinkUp(maxPacketSize: Int, foundByScan: Boolean)

        fun onLinkDown()

        fun onPacket(packet: ByteArray)
    }

    /** The name the court advertised when it was joined: what to look for again. */
    val courtName: String = court.name

    private var primary = newLink(court.device, keepTrying = directRetry)
    private var candidate: GuestLink? = null

    /** The link packets currently flow on, if any. */
    private var active: GuestLink? = null
    private var closed = false

    /** Courts not to try again before the given uptime: wrong ones for a long while, failed ones briefly. */
    private val avoidUntil = HashMap<String, Long>()
    private var lastSeen: List<NearbyCourt> = emptyList()

    private val scanner = CourtScanner(context, handler, ScanSettings.SCAN_MODE_BALANCED) { courts ->
        lastSeen = courts
        tryFound()
    }
    private val look = Runnable { startLooking() }

    fun connect() {
        closed = false
        primary.connect()
        // If even the first connection does not come up, look around as well.
        handler.postDelayed(look, LOOK_AFTER_MS)
    }

    /** Queues [packets] for the host. Dropped if no link is up. */
    fun send(packets: List<ByteArray>) {
        active?.send(packets)
    }

    /** Disconnects and stops reconnecting and looking. */
    fun close() {
        closed = true
        handler.removeCallbacks(look)
        scanner.stop()
        candidate?.close()
        candidate = null
        primary.close()
        active = null
    }

    /** The court found by scanning is the right one: it becomes the device to reconnect to from now on. */
    fun courtProved() {
        val proved = candidate ?: return
        if (active !== proved) return
        candidate = null
        primary.close()
        proved.keepTrying = directRetry
        primary = proved
        stopLooking()
    }

    /** The court found by scanning is somebody else's: drop it and keep looking. */
    fun wrongCourt() {
        val wrong = candidate ?: return
        avoidUntil[wrong.device.address] = SystemClock.uptimeMillis() + WRONG_COURT_REST_MS
        dropCandidate()
    }

    private fun newLink(device: BluetoothDevice, keepTrying: Boolean): GuestLink {
        val events = LinkEvents()
        return GuestLink(context, handler, device, events, keepTrying).also { events.link = it }
    }

    private fun dropCandidate() {
        val dropped = candidate ?: return
        candidate = null
        // Closing an open link reports it down, which is what the session needs to hear.
        dropped.close()
        if (closed) return
        if (directRetry) primary.connect()
        tryFound()
    }

    private fun startLooking() {
        if (closed || active != null) return
        if (!scanner.start()) {
            // Bluetooth is off or busy; the direct retry is still running.
            handler.postDelayed(look, LOOK_RETRY_MS)
        }
    }

    private fun stopLooking() {
        handler.removeCallbacks(look)
        scanner.stop()
    }

    /** Tries the nearest court seen under the joined name, unless a link is already up or being proved. */
    private fun tryFound() {
        if (closed || active != null || candidate != null) return
        val now = SystemClock.uptimeMillis()
        val found = lastSeen.firstOrNull { court ->
            court.name == courtName &&
                (avoidUntil[court.id] ?: 0L) <= now &&
                // The device already being retried directly needs no second attempt.
                !(directRetry && court.device.address == primary.device.address)
        } ?: return
        candidate = newLink(found.device, keepTrying = false).also { it.connect() }
    }

    /**
     * What one link reports. Whether that link is the device joined directly
     * or a court found by scanning is looked up when the event arrives,
     * because a proved candidate changes role.
     */
    private inner class LinkEvents : GuestLink.Listener {
        lateinit var link: GuestLink

        override fun onLinkUp(maxPacketSize: Int) {
            when {
                link === candidate -> {
                    // Stop retrying the old address while this court proves itself.
                    primary.close()
                    active = link
                    listener.onLinkUp(maxPacketSize, foundByScan = true)
                }
                link === primary -> {
                    // The device joined in the first place is back: no candidate needed.
                    candidate?.close()
                    candidate = null
                    stopLooking()
                    active = link
                    listener.onLinkUp(maxPacketSize, foundByScan = false)
                }
            }
        }

        override fun onLinkDown() {
            if (closed || active !== link) return
            active = null
            listener.onLinkDown()
            if (link === primary) {
                handler.removeCallbacks(look)
                handler.postDelayed(look, LOOK_AFTER_MS)
            }
        }

        override fun onPacket(packet: ByteArray) {
            if (active === link) listener.onPacket(packet)
        }

        override fun onGaveUp() {
            if (link !== candidate) return
            // Could not connect, or the link broke before the court was proved.
            avoidUntil[link.device.address] = SystemClock.uptimeMillis() + FAILED_REST_MS
            dropCandidate()
        }
    }

    private companion object {
        /** How long the link is down before looking for the court by name. */
        const val LOOK_AFTER_MS = 15_000L
        const val LOOK_RETRY_MS = 5_000L

        /** A court that turned out to be somebody else's is not tried again for this long. */
        const val WRONG_COURT_REST_MS = 120_000L

        /** A found court that could not be connected to is tried again after this long. */
        const val FAILED_REST_MS = 10_000L
    }
}

package com.padelsync.kit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid

/** A court found nearby. */
class NearbyCourt internal constructor(
    /** Stable key for lists. */
    val id: String,
    /** The host's label. */
    val name: String,
    /** Signal strength in dBm; closer to zero is nearer. */
    val rssi: Int,
    internal val device: BluetoothDevice,
)

/** Finds courts being hosted nearby. All callbacks arrive on [handler]'s thread. */
@SuppressLint("MissingPermission") // Callers check BlePermissions first.
internal class CourtScanner(
    context: Context,
    private val handler: Handler,
    /** How hard to look: the join screen wants results at once, a background watch can take its time. */
    private val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
    private val onChange: (List<NearbyCourt>) -> Unit,
) {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val found = LinkedHashMap<String, NearbyCourt>()
    private var scanning = false

    /** Returns false if scanning could not start. */
    fun start(): Boolean {
        if (scanning) return true
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return false
        found.clear()
        onChange(emptyList())
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CourtUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        return try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: RuntimeException) {
            // Bluetooth was switched off underneath us.
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post
                val record = result.scanRecord
                // Android hosts put their label in service data; iPhone hosts
                // can only advertise it as the local name.
                val label = record?.getServiceData(ParcelUuid(CourtUuids.SERVICE))
                    ?.takeIf { it.isNotEmpty() }
                    ?.toString(Charsets.UTF_8)
                    ?: record?.deviceName
                    ?: "Court"
                val address = result.device.address
                found[address] = NearbyCourt(address, label, result.rssi, result.device)
                onChange(found.values.sortedByDescending { it.rssi })
            }
        }
    }
}

/**
 * One guest's Bluetooth link to a host: connects, switches notifications on,
 * moves packets both ways, and, while [keepTrying] is set, keeps trying to
 * reconnect when the link drops until [close] is called.
 *
 * It only moves packets; the shared `ClientSession` decides what they mean.
 * All [Listener] calls arrive on [handler]'s thread.
 */
@SuppressLint("MissingPermission") // Callers check BlePermissions first.
internal class GuestLink(
    private val context: Context,
    private val handler: Handler,
    val device: BluetoothDevice,
    private val listener: Listener,
    /**
     * Whether to reconnect after a failure. A link to a court that has only
     * just been found, and may not be the right one, gets a single attempt.
     */
    var keepTrying: Boolean = true,
) {
    interface Listener {
        /** Notifications are on; packets can flow. */
        fun onLinkUp(maxPacketSize: Int)

        fun onLinkDown()

        fun onPacket(packet: ByteArray)

        /** A link with [keepTrying] off failed and will not be tried again. */
        fun onGaveUp() = Unit
    }

    private var gatt: BluetoothGatt? = null
    private var toHost: BluetoothGattCharacteristic? = null
    private var mtu = DEFAULT_MTU
    private var up = false
    private var closed = false
    private var retryDelayMs = FIRST_RETRY_MS

    private val outbox = ArrayDeque<ByteArray>()
    private var writing = false

    private val reconnect = Runnable { open() }

    /** Ends a one-attempt link that has not come up in time; the system's own limit is half a minute. */
    private val tooSlow = Runnable { if (!up && !keepTrying) linkLost() }

    fun connect() {
        closed = false
        open()
        if (!keepTrying) handler.postDelayed(tooSlow, ONE_ATTEMPT_LIMIT_MS)
    }

    /** Queues [packets] for the host. Dropped if the link is down. */
    fun send(packets: List<ByteArray>) {
        if (!up) return
        outbox.addAll(packets)
        pump()
    }

    /** Disconnects and stops reconnecting. */
    fun close() {
        closed = true
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(tooSlow)
        teardown()
    }

    /** Whether packets can flow right now. */
    val isUp: Boolean
        get() = up

    /**
     * Treats the link as broken although Bluetooth still reports it up, and
     * starts over. For a host that has stopped answering: its app can die
     * while the radio link stays connected.
     */
    fun reset() {
        if (!closed) linkLost()
    }

    private fun open() {
        if (closed || gatt != null) return
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: RuntimeException) {
            null
        }
        if (gatt == null) linkLost()
    }

    private fun teardown() {
        val wasUp = up
        up = false
        writing = false
        outbox.clear()
        toHost = null
        mtu = DEFAULT_MTU
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: RuntimeException) {
            // Already gone.
        }
        gatt = null
        if (wasUp) listener.onLinkDown()
    }

    private fun scheduleReconnect() {
        if (closed) return
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun linkLost() {
        teardown()
        if (keepTrying) {
            scheduleReconnect()
        } else if (!closed) {
            closed = true
            listener.onGaveUp()
        }
    }

    /** Writes one packet at a time; Android allows a single write in flight. */
    private fun pump() {
        if (writing || !up) return
        val current = gatt ?: return
        val characteristic = toHost ?: return
        val packet = outbox.removeFirstOrNull() ?: return
        if (write(current, characteristic, packet)) {
            writing = true
        } else {
            // The stack refused the write. Treat it as a broken link: the
            // session re-sends its unresolved tap after reconnecting.
            linkLost()
        }
    }

    @Suppress("DEPRECATION")
    private fun write(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, packet: ByteArray): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = packet
                current.writeCharacteristic(characteristic)
            }
        } catch (e: RuntimeException) {
            false
        }

    @Suppress("DEPRECATION")
    private fun enableNotifications(current: BluetoothGatt): Boolean {
        val service = current.getService(CourtUuids.SERVICE) ?: return false
        val write = service.getCharacteristic(CourtUuids.TO_HOST) ?: return false
        val notify = service.getCharacteristic(CourtUuids.FROM_HOST) ?: return false
        val config = notify.getDescriptor(CourtUuids.CLIENT_CONFIG) ?: return false
        toHost = write
        return try {
            if (!current.setCharacteristicNotification(notify, true)) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                config.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                current.writeDescriptor(config)
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // Ask for the largest packets the link allows, then look
                    // for the court service once that is settled.
                    val requested = try {
                        g.requestMtu(REQUESTED_MTU)
                    } catch (e: RuntimeException) {
                        false
                    }
                    if (!requested && !discover(g)) linkLost()
                } else {
                    linkLost()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, negotiated: Int, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) negotiated else DEFAULT_MTU
                if (!discover(g)) linkLost()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || !enableNotifications(g)) linkLost()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt || descriptor.uuid != CourtUuids.CLIENT_CONFIG) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    linkLost()
                    return@post
                }
                up = true
                retryDelayMs = FIRST_RETRY_MS
                listener.onLinkUp(CourtUuids.packetSizeFor(mtu))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post {
                if (g !== gatt) return@post
                writing = false
                if (status == BluetoothGatt.GATT_SUCCESS) pump() else linkLost()
            }
        }

        // Android 13 and newer.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(g, characteristic, value.copyOf())
        }

        // Android 12 and older.
        @Deprecated("Replaced on Android 13 by the overload that carries the value.")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            deliver(g, characteristic, characteristic.value?.copyOf() ?: return)
        }

        private fun deliver(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, packet: ByteArray) {
            if (characteristic.uuid != CourtUuids.FROM_HOST) return
            handler.post {
                if (g === gatt && up) listener.onPacket(packet)
            }
        }
    }

    private fun discover(g: BluetoothGatt): Boolean = try {
        g.discoverServices()
    } catch (e: RuntimeException) {
        false
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 517
        const val ONE_ATTEMPT_LIMIT_MS = 12_000L
        const val FIRST_RETRY_MS = 500L
        const val MAX_RETRY_MS = 4_000L
    }
}

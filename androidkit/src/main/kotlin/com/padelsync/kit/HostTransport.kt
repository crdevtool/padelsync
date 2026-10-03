package com.padelsync.kit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid

/**
 * The Bluetooth side of hosting a court: a GATT server that guests connect
 * to, and the advertisement that lets them find it.
 *
 * It only moves packets. Deciding what the packets mean is the job of the
 * shared `HostSession`.
 *
 * Android delivers Bluetooth callbacks on its own threads. Every callback is
 * re-posted to [handler], so [Listener] methods and all internal state are
 * confined to that handler's thread. Call the public methods from it too.
 */
@SuppressLint("MissingPermission") // Callers check BlePermissions first.
internal class HostTransport(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** A guest switched notifications on and can now be sent packets. */
        fun onGuestReady(peerId: String, maxPacketSize: Int)

        /** The link's packet size was negotiated after [onGuestReady]. */
        fun onGuestPacketSize(peerId: String, maxPacketSize: Int)

        fun onGuestGone(peerId: String)

        fun onPacket(peerId: String, packet: ByteArray)

        /** Advertising could not start; [reason] is fit to show the user. */
        fun onAdvertisingFailed(reason: String)
    }

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var fromHost: BluetoothGattCharacteristic? = null

    /** Guests with notifications switched on, by Bluetooth address. */
    private val ready = HashMap<String, BluetoothDevice>()

    /** Negotiated ATT MTU per connected device. 23 is the Bluetooth default. */
    private val mtus = HashMap<String, Int>()

    private val outbox = ArrayDeque<Pair<String, ByteArray>>()
    private var sending = false
    private var advertising = false

    /** Clears [sending] if Android never reports that a notification went out. */
    private val sendWatchdog = Runnable {
        sending = false
        pump()
    }

    /** Opens the GATT server. Returns false if Bluetooth is unavailable. */
    fun start(): Boolean {
        if (server != null) return true
        val opened = try {
            manager?.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            null
        } ?: return false

        val toHost = BluetoothGattCharacteristic(
            CourtUuids.TO_HOST,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            CourtUuids.FROM_HOST,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                CourtUuids.CLIENT_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val service = BluetoothGattService(CourtUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(toHost)
        service.addCharacteristic(notify)

        fromHost = notify
        server = opened
        opened.addService(service)
        return true
    }

    /** Starts advertising the court under [label], which is truncated to fit. */
    fun startAdvertising(label: String) {
        if (advertising) return
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onAdvertisingFailed("This device cannot host over Bluetooth.")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // A 128-bit service UUID nearly fills the 31-byte advertisement, so
        // the label travels in the scan response as service data.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(CourtUuids.SERVICE))
            .build()
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(CourtUuids.SERVICE), truncateUtf8(label, CourtUuids.MAX_LABEL_BYTES))
            .build()
        try {
            advertiser.startAdvertising(settings, data, response, advertiseCallback)
            advertising = true
        } catch (e: RuntimeException) {
            listener.onAdvertisingFailed("Could not start Bluetooth advertising.")
        }
    }

    fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: RuntimeException) {
            // Bluetooth was switched off underneath us; nothing left to stop.
        }
    }

    /** Queues [packets] for one guest. Packets for a guest that has gone are dropped. */
    fun send(peerId: String, packets: List<ByteArray>) {
        for (packet in packets) outbox.addLast(peerId to packet)
        pump()
    }

    /** Stops advertising, drops every guest and closes the server. */
    fun stop() {
        stopAdvertising()
        handler.removeCallbacks(sendWatchdog)
        outbox.clear()
        sending = false
        ready.clear()
        mtus.clear()
        try {
            server?.close()
        } catch (e: RuntimeException) {
            // Already torn down by the system.
        }
        server = null
        fromHost = null
    }

    /**
     * Sends queued notifications one at a time. Android accepts a new
     * notification only after it has reported the previous one as sent.
     */
    private fun pump() {
        if (sending) return
        val gattServer = server ?: return
        val characteristic = fromHost ?: return
        while (true) {
            val (peerId, packet) = outbox.removeFirstOrNull() ?: return
            val device = ready[peerId] ?: continue
            if (notify(gattServer, device, characteristic, packet)) {
                sending = true
                handler.postDelayed(sendWatchdog, SEND_TIMEOUT_MS)
                return
            }
            // Could not be queued. Drop it: the next state heartbeat repairs
            // whatever this guest missed.
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(
        gattServer: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer.notifyCharacteristicChanged(device, characteristic, false, packet) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = packet
            gattServer.notifyCharacteristicChanged(device, characteristic, false)
        }
    } catch (e: RuntimeException) {
        false
    }

    private fun guestGone(address: String) {
        mtus.remove(address)
        if (ready.remove(address) != null) listener.onGuestGone(address)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            handler.post {
                advertising = false
                listener.onAdvertisingFailed(
                    when (errorCode) {
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This device cannot host over Bluetooth."
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many apps are using Bluetooth. Close some and try again."
                        else -> "Could not start Bluetooth advertising (error $errorCode)."
                    },
                )
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    guestGone(device.address)
                    // A notification in flight to this device will never be
                    // confirmed; do not let it stall the others.
                    handler.removeCallbacks(sendWatchdog)
                    sending = false
                    pump()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                mtus[device.address] = mtu
                if (ready.containsKey(device.address)) {
                    listener.onGuestPacketSize(device.address, CourtUuids.packetSizeFor(mtu))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val packet = value?.copyOf()
            handler.post {
                val accepted = characteristic.uuid == CourtUuids.TO_HOST && !preparedWrite && offset == 0
                if (responseNeeded) {
                    respond(device, requestId, if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE)
                }
                if (accepted && packet != null) listener.onPacket(device.address, packet)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val written = value?.copyOf()
            handler.post {
                val isConfig = descriptor.uuid == CourtUuids.CLIENT_CONFIG
                if (responseNeeded) {
                    respond(device, requestId, if (isConfig) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE)
                }
                if (!isConfig) return@post
                // Bit 0 = notifications, bit 1 = indications.
                val enabled = written != null && written.isNotEmpty() && (written[0].toInt() and 0x03) != 0
                if (enabled) {
                    ready[device.address] = device
                    listener.onGuestReady(device.address, CourtUuids.packetSizeFor(mtus[device.address] ?: DEFAULT_MTU))
                } else {
                    guestGone(device.address)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            handler.post {
                val on = ready.containsKey(device.address)
                val value = if (on) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
                try {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                } catch (e: RuntimeException) {
                    // The device left before we could answer.
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                handler.removeCallbacks(sendWatchdog)
                sending = false
                pump()
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int) {
        try {
            server?.sendResponse(device, requestId, status, 0, null)
        } catch (e: RuntimeException) {
            // The device left before we could answer.
        }
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val SEND_TIMEOUT_MS = 2_000L
    }
}

/** Cuts [text] to at most [maxBytes] of UTF-8 without splitting a character. */
internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
    var end = text.length
    while (true) {
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate()) end--
        val encoded = text.substring(0, end).toByteArray(Charsets.UTF_8)
        if (encoded.size <= maxBytes) return encoded
        end--
    }
}

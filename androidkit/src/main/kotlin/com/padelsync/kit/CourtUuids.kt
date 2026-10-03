package com.padelsync.kit

import java.util.UUID

/**
 * Bluetooth identifiers of a court session. These are part of the protocol
 * and must be identical in the Android and Apple apps; see docs/ble-protocol.md.
 */
object CourtUuids {
    /** The court service a host advertises. */
    val SERVICE: UUID = UUID.fromString("5ad31000-7c4e-4b6f-9d2a-8e3f1b0c9a71")

    /** Guest to host: written with response. */
    val TO_HOST: UUID = UUID.fromString("5ad31001-7c4e-4b6f-9d2a-8e3f1b0c9a71")

    /** Host to guest: notifications. */
    val FROM_HOST: UUID = UUID.fromString("5ad31002-7c4e-4b6f-9d2a-8e3f1b0c9a71")

    /** Standard descriptor a guest writes to switch notifications on. */
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Longest host label that fits in the advertisement, in UTF-8 bytes. */
    const val MAX_LABEL_BYTES = 12

    /**
     * A host's name as guests see it: cut to what the advertisement can
     * carry. A host looking for another court under its own name has to look
     * for this, not for the full name.
     */
    fun advertisedLabel(name: String): String =
        truncateUtf8(name, MAX_LABEL_BYTES).toString(Charsets.UTF_8)

    /**
     * Most payload bytes one packet may carry on a link with the given ATT
     * MTU: the MTU less the 3-byte ATT header, and never more than the 512
     * bytes Bluetooth allows for a single attribute value.
     */
    fun packetSizeFor(mtu: Int): Int = (mtu - 3).coerceIn(20, 512)
}

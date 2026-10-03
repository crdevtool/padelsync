package com.netsports.core.sync

/**
 * Splits messages into Bluetooth-sized packets and puts them back together.
 *
 * A Bluetooth LE packet carries as little as 20 bytes, and a match state can
 * be larger, so every message travels as one or more packets. Each packet
 * starts with a one-byte header:
 *
 * ```
 * bit 7      FIRST  this packet starts a message
 * bit 6      LAST   this packet ends a message
 * bits 5..0  index of the packet within its message, modulo 64
 * ```
 */
object Framing {
    /** Smallest packet any Bluetooth LE link can carry. */
    const val MIN_PACKET_SIZE = 20

    /** Upper bound on a reassembled message; anything larger is discarded. */
    const val MAX_MESSAGE_SIZE = 8 * 1024

    internal const val FLAG_FIRST = 0x80
    internal const val FLAG_LAST = 0x40
    internal const val INDEX_MASK = 0x3F

    /**
     * Splits [message] into packets of at most [maxPacketSize] bytes, header
     * included. Sizes below [MIN_PACKET_SIZE] are raised to it.
     */
    fun split(message: ByteArray, maxPacketSize: Int): List<ByteArray> {
        val chunkSize = maxOf(maxPacketSize, MIN_PACKET_SIZE) - 1
        val chunkCount = maxOf(1, (message.size + chunkSize - 1) / chunkSize)
        return List(chunkCount) { index ->
            val from = index * chunkSize
            val to = minOf(message.size, from + chunkSize)
            var header = index and INDEX_MASK
            if (index == 0) header = header or FLAG_FIRST
            if (index == chunkCount - 1) header = header or FLAG_LAST
            val packet = ByteArray(1 + to - from)
            packet[0] = header.toByte()
            message.copyInto(packet, destinationOffset = 1, startIndex = from, endIndex = to)
            packet
        }
    }
}

/**
 * Rebuilds messages from the packets of one link. Keep one instance per
 * connection and feed it packets in arrival order.
 *
 * A lost or reordered packet makes the reassembler drop the message in
 * progress and wait for the next one. Nothing is requested again: the host
 * repeats the match state regularly, and that repairs any gap.
 */
class Reassembler {
    private var buffer = ByteWriter()
    private var bufferedSize = 0
    private var nextIndex = 0
    private var inProgress = false

    /** Returns a complete message if [packet] finished one, otherwise `null`. */
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null
        val header = packet[0].toInt() and 0xFF

        if (header and Framing.FLAG_FIRST != 0) {
            reset()
            inProgress = true
        } else if (!inProgress) {
            return null
        }

        if (header and Framing.INDEX_MASK != nextIndex and Framing.INDEX_MASK) {
            reset()
            return null
        }

        bufferedSize += packet.size - 1
        if (bufferedSize > Framing.MAX_MESSAGE_SIZE) {
            reset()
            return null
        }
        buffer.bytes(packet.copyOfRange(1, packet.size))
        nextIndex++

        if (header and Framing.FLAG_LAST == 0) return null
        val message = buffer.toByteArray()
        reset()
        return message
    }

    /** Discards any partly received message. Call when the link drops. */
    fun reset() {
        buffer = ByteWriter()
        bufferedSize = 0
        nextIndex = 0
        inProgress = false
    }
}

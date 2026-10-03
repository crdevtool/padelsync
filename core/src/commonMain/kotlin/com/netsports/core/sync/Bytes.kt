package com.netsports.core.sync

/**
 * Thrown when bytes received from another device cannot be decoded. Anything
 * arriving over the air is untrusted, so decoding fails with this single,
 * catchable type rather than an index or cast error.
 */
class ProtocolException(message: String) : Exception(message)

/** Big-endian byte sink that grows as needed. */
internal class ByteWriter(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun u8(value: Int): ByteWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun u16(value: Int): ByteWriter = u8(value ushr 8).u8(value)

    fun u32(value: Int): ByteWriter = u8(value ushr 24).u8(value ushr 16).u8(value ushr 8).u8(value)

    fun i64(value: Long): ByteWriter {
        for (shift in 56 downTo 0 step 8) u8((value ushr shift).toInt())
        return this
    }

    fun bytes(value: ByteArray): ByteWriter {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }
}

/** Big-endian byte source with bounds checking. */
internal class ByteReader(private val bytes: ByteArray) {
    private var position = 0

    val remaining: Int
        get() = bytes.size - position

    fun u8(): Int {
        if (remaining < 1) throw ProtocolException("message truncated")
        return bytes[position++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    /** Reads an unsigned 32-bit value that must fit in a non-negative Int. */
    fun u31(): Int {
        val value = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()
        if (value > Int.MAX_VALUE) throw ProtocolException("value out of range: $value")
        return value.toInt()
    }

    fun i64(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or u8().toLong() }
        return value
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || remaining < count) throw ProtocolException("message truncated")
        val result = bytes.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun expectEnd() {
        if (remaining != 0) throw ProtocolException("$remaining unexpected trailing bytes")
    }
}

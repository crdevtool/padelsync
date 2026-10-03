package com.netsports.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {
    private fun reassemble(packets: List<ByteArray>, reassembler: Reassembler = Reassembler()): ByteArray? {
        var result: ByteArray? = null
        for ((index, packet) in packets.withIndex()) {
            val message = reassembler.accept(packet)
            if (index < packets.lastIndex) assertNull(message, "message completed early") else result = message
        }
        return result
    }

    @Test
    fun everySizeRoundTripsAtEveryPacketSize() {
        val random = Random(1)
        for (packetSize in listOf(20, 23, 100, 182, 512)) {
            for (size in listOf(0, 1, 18, 19, 20, 37, 38, 39, 500, 3000)) {
                val message = random.nextBytes(size)
                val packets = Framing.split(message, packetSize)
                assertTrue(packets.all { it.size <= packetSize }, "packet too large")
                assertContentEquals(message, reassemble(packets), "size=$size packetSize=$packetSize")
            }
        }
    }

    @Test
    fun packetSizesBelowTheMinimumAreRaisedToIt() {
        val packets = Framing.split(ByteArray(40), maxPacketSize = 5)
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.size <= Framing.MIN_PACKET_SIZE })
    }

    @Test
    fun anEmptyMessageIsOnePacket() {
        val packets = Framing.split(ByteArray(0), 20)
        assertEquals(1, packets.size)
        assertContentEquals(ByteArray(0), reassemble(packets))
    }

    @Test
    fun aLostPacketDropsThatMessageOnly() {
        val reassembler = Reassembler()
        val first = Framing.split(ByteArray(100) { 1 }, 20)
        val second = Framing.split(ByteArray(100) { 2 }, 20)

        // Lose a packet from the middle of the first message.
        for (packet in first.filterIndexed { index, _ -> index != 2 }) assertNull(reassembler.accept(packet))
        assertContentEquals(ByteArray(100) { 2 }, reassemble(second, reassembler))
    }

    @Test
    fun aLostFirstPacketDropsThatMessageOnly() {
        val reassembler = Reassembler()
        for (packet in Framing.split(ByteArray(100) { 1 }, 20).drop(1)) assertNull(reassembler.accept(packet))
        assertContentEquals(ByteArray(50) { 2 }, reassemble(Framing.split(ByteArray(50) { 2 }, 20), reassembler))
    }

    @Test
    fun aLostLastPacketDropsThatMessageOnly() {
        val reassembler = Reassembler()
        for (packet in Framing.split(ByteArray(100) { 1 }, 20).dropLast(1)) assertNull(reassembler.accept(packet))
        assertContentEquals(ByteArray(50) { 2 }, reassemble(Framing.split(ByteArray(50) { 2 }, 20), reassembler))
    }

    @Test
    fun oversizedMessagesAreDiscarded() {
        val reassembler = Reassembler()
        val packets = Framing.split(ByteArray(Framing.MAX_MESSAGE_SIZE + 1), 512)
        for (packet in packets) assertNull(reassembler.accept(packet))
        assertContentEquals(ByteArray(10), reassemble(Framing.split(ByteArray(10), 512), reassembler))
    }

    @Test
    fun emptyAndStrayPacketsAreIgnored() {
        val reassembler = Reassembler()
        assertNull(reassembler.accept(ByteArray(0)))
        assertNull(reassembler.accept(byteArrayOf(0x05, 1, 2, 3)))
        assertContentEquals(byteArrayOf(9), reassembler.accept(byteArrayOf(0xC0.toByte(), 9)))
    }

    @Test
    fun randomPacketsNeverCrashTheReassembler() {
        val random = Random(3)
        val reassembler = Reassembler()
        repeat(20_000) { reassembler.accept(random.nextBytes(random.nextInt(0, 30))) }
    }
}

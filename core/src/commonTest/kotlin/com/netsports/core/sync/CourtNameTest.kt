package com.netsports.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourtNameTest {
    @Test
    fun aLabelIsTheDeviceNameWithThreeCharactersOfItsId() {
        assertEquals("iPhone A3F", CourtName.label("iPhone", 0x12345A3F, 12))
        assertEquals("Pixel 8 00B", CourtName.label("Pixel 8", 0x7000B, 12))
        // Long names give way to the suffix, never the other way round.
        assertEquals("Galaxy S A3F", CourtName.label("Galaxy S23 Ultra", 0xA3F, 12))
        assertEquals("iPhone A3F", CourtName.label("  iPhone  ", 0xA3F, 10))
        assertEquals("Charbe A3F", CourtName.label("Charbel's iPhone", 0xA3F, 10))
        // No name at all still gives something to pick.
        assertEquals("A3F", CourtName.label("", 0xA3F, 12))
        // Negative ids are as good as any.
        assertEquals("Mi FFF", CourtName.label("Mi", -1, 12))
    }

    @Test
    fun aLabelNeverExceedsItsRoomOrSplitsACharacter() {
        for (name in listOf("Téléphone de Zoé", "🎾🎾🎾🎾", "شربل", "Player's very long device name")) {
            for (room in 4..14) {
                val label = CourtName.label(name, 0xABC, room)
                assertTrue(label.encodeToByteArray().size <= room, "$name in $room bytes gave $label")
                assertEquals(label, label.encodeToByteArray().decodeToString())
                assertTrue(label.endsWith("ABC"))
            }
        }
        assertFailsWith<IllegalArgumentException> { CourtName.label("x", 1, 3) }
    }

    @Test
    fun twoDevicesWithTheSameNameGetDifferentLabels() {
        assertFalse(CourtName.matches(CourtName.label("iPhone", 0x111, 12), CourtName.label("iPhone", 0x222, 12)))
        // And a label cut shorter by the phone still matches itself.
        val label = CourtName.label("iPhone", 0x111, 10)
        assertTrue(CourtName.matches(label, label.dropLast(2)))
    }

    @Test
    fun equalNamesMatchHoweverShort() {
        assertTrue(CourtName.matches("Leo", "Leo"))
        assertTrue(CourtName.matches("", ""))
        assertTrue(CourtName.matches("Charbel's iP", "Charbel's iP"))
    }

    @Test
    fun aNameCutShorterByThePhoneStillMatches() {
        // Joined under 12 bytes, seen again as the 8 an iPhone had room for, or the other way round.
        assertTrue(CourtName.matches("Charbel's iP", "Charbel'"))
        assertTrue(CourtName.matches("Charbel'", "Charbel's iP"))
        assertTrue(CourtName.matches("Galaxy", "Galaxy S23 U"))
    }

    @Test
    fun aVeryShortBeginningIsNotEnough() {
        assertFalse(CourtName.matches("Pixel", "Pixel 8 Pro"))
        assertFalse(CourtName.matches("Leo", "Leonardo's iPhone"))
        assertFalse(CourtName.matches("", "Charbel's iP"))
    }

    @Test
    fun differentNamesDoNotMatch() {
        assertFalse(CourtName.matches("Charbel's iP", "Charles's iP"))
        assertFalse(CourtName.matches("Galaxy S23 U", "Galaxy S24 U"))
        // The same letters in a different case are a different name.
        assertFalse(CourtName.matches("galaxy s23", "Galaxy S23"))
    }
}

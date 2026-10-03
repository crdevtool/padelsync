package com.netsports.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourtNameTest {
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

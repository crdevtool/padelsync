package com.netsports.core.pro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProAccessTest {
    private val day = 24L * 60 * 60 * 1000
    private val start = 1_760_000_000_000L

    @Test
    fun aNewDeviceMayHostAndIsOfferedTheTrial() {
        val status = ProAccess.status(owned = false, trialStartedAt = null, now = start)
        assertEquals(ProStatus.Kind.TRIAL_NOT_STARTED, status.kind)
        assertTrue(status.canHost)
        assertEquals("14-day free trial", status.homeLine)
    }

    @Test
    fun theTrialCountsDownFourteenDays() {
        assertEquals("Free trial · 14 days left", ProAccess.status(false, start, start).homeLine)
        assertEquals("Free trial · 11 days left", ProAccess.status(false, start, start + 3 * day + 5).homeLine)
        val last = ProAccess.status(false, start, start + 13 * day)
        assertEquals("Free trial · last day", last.homeLine)
        assertTrue(last.canHost)
        val over = ProAccess.status(false, start, start + 14 * day)
        assertEquals(ProStatus.Kind.EXPIRED, over.kind)
        assertFalse(over.canHost)
    }

    @Test
    fun aClockSetBackDoesNotLengthenTheTrial() {
        val status = ProAccess.status(false, start, start - 30 * day)
        assertEquals(ProStatus.Kind.TRIAL, status.kind)
        assertEquals(14, status.daysLeft)
    }

    @Test
    fun aPlanInForceWinsOverAnEndedTrial() {
        val status = ProAccess.status(owned = true, trialStartedAt = start, now = start + 100 * day)
        assertEquals(ProStatus.Kind.OWNED, status.kind)
        assertTrue(status.canHost)
        assertFalse(status.showsProMark)
        assertNull(status.homeLine)
    }

    @Test
    fun theYearlySavingIsShownOnlyWhenItIsReal() {
        assertEquals(58, ProText.yearlySaving(monthlyMicros = 1_990_000, yearlyMicros = 9_990_000))
        assertNull(ProText.yearlySaving(monthlyMicros = 1_000_000, yearlyMicros = 12_000_000))
        assertNull(ProText.yearlySaving(monthlyMicros = 0, yearlyMicros = 9_990_000))
    }
}

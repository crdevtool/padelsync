package com.netsports.core.match

import com.netsports.core.engine.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RosterTest {
    @Test
    fun teamsAreNamedAfterTheirPlayers() {
        val roster = Roster(listOf("Ana", "Leo"), listOf("Mia"))
        assertEquals("Ana & Leo", roster.teamName(Team.A))
        assertEquals("Mia", roster.teamName(Team.B))
        assertEquals("Leo", roster.playerName(Team.A, 1))
        assertNull(roster.playerName(Team.B, 1))
    }

    @Test
    fun withoutNamesTeamsAreAAndB() {
        assertEquals("Team A", Roster.EMPTY.teamName(Team.A))
        assertEquals("Team B", Roster.EMPTY.teamName(Team.B))
        assertNull(Roster.EMPTY.playerName(Team.A, 0))
    }

    @Test
    fun invalidRostersAreRefused() {
        assertFailsWith<IllegalArgumentException> { Roster(listOf("a", "b", "c")) }
        assertFailsWith<IllegalArgumentException> { Roster(listOf(" ")) }
        assertFailsWith<IllegalArgumentException> { Roster(emptyList(), listOf("x".repeat(Roster.MAX_NAME_BYTES + 1))) }
    }

    @Test
    fun formInputIsCleanedUp() {
        val roster = Roster.of(listOf("  Ana  ", ""), listOf("   ", "Sam", "Extra"))
        assertEquals(listOf("Ana"), roster.teamA)
        assertEquals(listOf("Sam", "Extra"), roster.teamB)
        assertEquals(listOf("a", "b"), Roster.of(listOf("a", "b", "c"), emptyList()).teamA)
    }

    @Test
    fun longNamesAreShortenedWithoutBreakingCharacters() {
        val long = Roster.of(listOf("Maximiliano Fernández-Castaño"), listOf("🎾".repeat(8))).let { it.teamA + it.teamB }
        for (name in long) {
            assertTrue(name.encodeToByteArray().size <= Roster.MAX_NAME_BYTES, name)
            assertEquals(name, name.encodeToByteArray().decodeToString())
            assertTrue(name.isNotBlank())
        }
        // "á" takes two bytes, so only 19 characters fit.
        assertEquals("Maximiliano Fernánd", long[0])
        assertEquals("🎾".repeat(5), long[1])
    }
}

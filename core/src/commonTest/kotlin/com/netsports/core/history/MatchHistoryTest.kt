package com.netsports.core.history

import com.netsports.core.match.Roster
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchHistoryTest {
    private fun record(matchId: Long, winner: Team = Team.A, startedAt: Long = 1_000, finishedAt: Long = 61_000) =
        MatchRecord(
            MatchSnapshot(matchId, 1, 48, MatchConfig.padel(), List(48) { winner }),
            startedAt,
            finishedAt,
        )

    @Test
    fun aRecordExposesTheFinalScoreAndDuration() {
        val record = record(1, Team.B)
        assertEquals(Team.B, record.score.winner)
        assertEquals("0-6 0-6", record.score.setSummary)
        assertEquals(60_000, record.durationMillis)
        assertEquals(0, record(2, startedAt = 5_000, finishedAt = 1_000).durationMillis)
    }

    @Test
    fun newestComesFirstAndAMatchAppearsOnce() {
        var records = MatchHistory.add(emptyList(), record(1))
        records = MatchHistory.add(records, record(2))
        records = MatchHistory.add(records, record(1, Team.B))
        assertEquals(listOf(1L, 2L), records.map { it.matchId })
        assertEquals(Team.B, records.first().score.winner)
    }

    @Test
    fun aReopenedMatchCanBeRemoved() {
        val records = MatchHistory.add(MatchHistory.add(emptyList(), record(1)), record(2))
        assertEquals(listOf(2L), MatchHistory.remove(records, 1).map { it.matchId })
        assertEquals(2, MatchHistory.remove(records, 99).size)
    }

    @Test
    fun theListIsCapped() {
        var records = emptyList<MatchRecord>()
        for (id in 1L..(MatchHistory.MAX_RECORDS + 5)) records = MatchHistory.add(records, record(id))
        assertEquals(MatchHistory.MAX_RECORDS, records.size)
        assertEquals((MatchHistory.MAX_RECORDS + 5).toLong(), records.first().matchId)
    }

    @Test
    fun historyRoundTripsThroughStorage() {
        val records = listOf(record(3, Team.B, 10, 20), record(2), record(1))
        val restored = MatchHistory.decode(MatchHistory.encode(records))
        assertEquals(listOf(3L, 2L, 1L), restored.map { it.matchId })
        assertEquals(records.map { it.snapshot }, restored.map { it.snapshot })
        assertEquals(10, restored.first().startedAtMillis)
        assertEquals(20, restored.first().finishedAtMillis)
        assertTrue(MatchHistory.decode(MatchHistory.encode(emptyList())).isEmpty())
    }

    @Test
    fun damagedStorageYieldsWhatCouldBeRead() {
        val encoded = MatchHistory.encode(listOf(record(2), record(1)))
        assertTrue(MatchHistory.decode(ByteArray(0)).isEmpty())
        assertTrue(MatchHistory.decode(byteArrayOf(9, 0, 1)).isEmpty(), "unknown format version")
        // Cut off part-way through the second record: the first survives.
        assertEquals(listOf(2L), MatchHistory.decode(encoded.copyOf(encoded.size - 5)).map { it.matchId })
    }

    @Test
    fun namesAndOptionsSurviveSaving() {
        val roster = Roster(listOf("Ana", "Leo"), listOf("Mia", "Sam"))
        val config = MatchConfig.padel().copy(playAllSets = true)
        val points = List(48) { Team.A } + List(24) { Team.B }
        val saved = MatchRecord(MatchSnapshot(7, 1, 72, config, points, roster = roster), 1_000, 9_000)

        val restored = MatchHistory.decode(MatchHistory.encode(listOf(saved))).single()
        assertEquals(roster, restored.snapshot.roster)
        assertEquals(config, restored.snapshot.config)
        assertEquals("Ana & Leo", restored.score.nameA)
        assertEquals(Team.A, restored.score.winner)
        assertEquals("6-0 6-0 0-6", restored.score.setSummary)
    }

    @Test
    fun historySavedByAnOlderVersionIsIgnoredNotMisread() {
        val bytes = MatchHistory.encode(listOf(record(1)))
        bytes[0] = 1
        assertTrue(MatchHistory.decode(bytes).isEmpty())
    }
}

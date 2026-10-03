package com.netsports.core.sync

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.match.Roster
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Throws a few hundred thousand random events at a full court (taps from
 * every device, packets delivered in every interleaving, lost packets,
 * dropped and restored links, new matches) and checks that the session
 * always ends up coherent.
 */
class CourtFuzzTest {

    @Test
    fun everyDeviceConvergesOnTheHostsScoreWhateverHappens() {
        for (seed in 1..40) runScenario(seed)
    }

    private fun runScenario(seed: Int) {
        val random = Random(seed)
        val court = Court(MatchConfig.padel().copy(bestOf = if (seed % 2 == 0) 3 else 5))
        val names = listOf("phone2", "phone3", "phone4", "watch1", "watch2", "watch3", "watch4")
        val sizes = listOf(20, 23, 100, 182, 512)
        for (name in names) court.join(name, packetSize = sizes.random(random))

        var lastVersion = 0
        var lastMatchId = court.host.log.matchId

        repeat(5_000) {
            val name = names.random(random)
            // Mix taps for the same rally with taps for separate rallies.
            court.rallyGapMillis = if (random.nextBoolean()) 0 else 10_000
            when (random.nextInt(100)) {
                in 0..24 -> court.tap(name, randomAction(random))
                in 25..29 -> court.hostTap(randomAction(random))
                in 30..59 -> court.deliverOneToHost()
                in 60..89 -> court.deliverOneTo(name)
                in 90..91 -> court.dropOneTo(name)
                in 92..93 -> court.disconnect(name)
                in 94..96 -> if (!court.isLinked(name)) court.connect(name)
                97 -> court.heartbeat()
                98 -> when (random.nextInt(4)) {
                    0 -> court.allowEveryone(random.nextBoolean())
                    1 -> court.rename(Roster.of(listOf("Ana", "Leo ${random.nextInt(9)}"), listOf("Mia")))
                    else -> court.allow(name, random.nextBoolean())
                }
                else -> if (court.host.state.isComplete) court.newMatch(MatchConfig.tennis())
            }

            // Invariants that must hold at every step.
            val log = court.host.log
            if (log.matchId == lastMatchId) {
                assertTrue(log.version >= lastVersion, "seed $seed: version went backwards")
            }
            lastVersion = log.version
            lastMatchId = log.matchId
            assertTrue(log.points.size <= log.version, "seed $seed: more points than commands")
            assertEquals(
                ScoringEngine.replay(log.config, log.points.map { it.team }),
                log.state,
                "seed $seed: score does not match the recorded points",
            )
            for (guest in court.guests.values) guest.displayState // must never throw
        }

        // Bring everyone back and let the dust settle.
        for (name in names) if (!court.isLinked(name)) court.connect(name)
        court.settle()
        court.heartbeat()
        court.settle()

        val expected = court.host.snapshot()
        for ((name, guest) in court.guests) {
            assertEquals(ClientStatus.SYNCED, guest.status, "seed $seed: $name")
            assertEquals(expected, guest.confirmed, "seed $seed: $name")
            assertEquals(0, guest.pendingCount, "seed $seed: $name still has unresolved taps")
            assertEquals(court.host.state, guest.displayState, "seed $seed: $name")
            assertEquals(8, guest.deviceCount, "seed $seed: $name")
            assertEquals(court.host.canScore(court.deviceId(name)), guest.canScore, "seed $seed: $name permission")
            for (team in Team.entries) {
                assertEquals(expected.serveFlip(team), guest.displayServeFlip(team), "seed $seed: $name serve order")
            }
        }
    }

    private fun randomAction(random: Random): Action = when (random.nextInt(20)) {
        in 0..8 -> Action.POINT_A
        in 9..16 -> Action.POINT_B
        17 -> Action.SWAP_SERVER_A
        18 -> Action.SWAP_SERVER_B
        else -> Action.UNDO
    }
}

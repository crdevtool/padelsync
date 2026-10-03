package com.netsports.core.history

import com.netsports.core.match.MatchSnapshot
import com.netsports.core.sync.ByteReader
import com.netsports.core.sync.ByteWriter
import com.netsports.core.sync.Message
import com.netsports.core.sync.ProtocolException
import com.netsports.core.sync.WireCodec
import com.netsports.core.ui.ScoreView

/**
 * A finished match as kept in a device's history.
 *
 * It stores the whole match (format and every point), so anything can be
 * derived from it later: the set scores today, point-by-point statistics
 * tomorrow.
 *
 * @property startedAtMillis when this device first saw the match. For a guest
 * that joined late this is later than the real start.
 */
class MatchRecord(
    val snapshot: MatchSnapshot,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
) {
    val matchId: Long
        get() = snapshot.matchId

    /** The final scoreboard. */
    val score: ScoreView
        get() = ScoreView.of(snapshot.state)

    val durationMillis: Long
        get() = (finishedAtMillis - startedAtMillis).coerceAtLeast(0)

    internal fun encode(): ByteArray = ByteWriter()
        .i64(startedAtMillis)
        .i64(finishedAtMillis)
        .bytes(WireCodec.encode(Message.State(snapshot, 1)))
        .toByteArray()

    internal companion object {
        fun decode(bytes: ByteArray): MatchRecord? = try {
            val reader = ByteReader(bytes)
            val startedAt = reader.i64()
            val finishedAt = reader.i64()
            val state = WireCodec.decode(reader.bytes(reader.remaining)) as? Message.State
            state?.let { MatchRecord(it.snapshot, startedAt, finishedAt) }
        } catch (_: ProtocolException) {
            null
        }
    }
}

/** Operations on a device's list of finished matches, newest first. */
object MatchHistory {
    /** Oldest records beyond this many are dropped. */
    const val MAX_RECORDS = 200

    private const val FORMAT_VERSION = 1

    /** Adds [record] at the front, replacing any earlier record of the same match. */
    fun add(records: List<MatchRecord>, record: MatchRecord): List<MatchRecord> =
        (listOf(record) + records.filter { it.matchId != record.matchId }).take(MAX_RECORDS)

    /** Removes the record of [matchId], for a finished match that was reopened with undo. */
    fun remove(records: List<MatchRecord>, matchId: Long): List<MatchRecord> =
        records.filter { it.matchId != matchId }

    /** Encodes [records] for saving to disk. */
    fun encode(records: List<MatchRecord>): ByteArray {
        val out = ByteWriter().u8(FORMAT_VERSION).u16(records.size)
        for (record in records) {
            val bytes = record.encode()
            out.u16(bytes.size).bytes(bytes)
        }
        return out.toByteArray()
    }

    /**
     * Decodes [encode] output. Never throws: damaged or unknown data yields
     * as many leading records as could be read, possibly none.
     */
    fun decode(bytes: ByteArray): List<MatchRecord> {
        val records = ArrayList<MatchRecord>()
        try {
            val reader = ByteReader(bytes)
            if (reader.u8() != FORMAT_VERSION) return records
            repeat(reader.u16()) {
                MatchRecord.decode(reader.bytes(reader.u16()))?.let { records += it }
            }
        } catch (_: ProtocolException) {
            // Keep what was read before the damage.
        }
        return records
    }
}

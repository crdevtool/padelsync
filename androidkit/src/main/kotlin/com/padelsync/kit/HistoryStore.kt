package com.padelsync.kit

import android.content.Context
import com.netsports.core.history.MatchHistory
import com.netsports.core.history.MatchRecord
import java.io.File
import java.io.IOException

/** Keeps this device's finished matches in a small private file. */
internal class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "match_history.bin")

    fun load(): List<MatchRecord> = try {
        if (file.exists()) MatchHistory.decode(file.readBytes()) else emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    fun save(records: List<MatchRecord>) {
        try {
            // Write beside the real file and swap, so a crash mid-write
            // cannot leave a half-written history behind.
            val temp = File(file.path + ".tmp")
            temp.writeBytes(MatchHistory.encode(records))
            if (!temp.renameTo(file)) {
                file.writeBytes(temp.readBytes())
                temp.delete()
            }
        } catch (e: IOException) {
            // History is a convenience; losing a write must not break scoring.
        }
    }
}

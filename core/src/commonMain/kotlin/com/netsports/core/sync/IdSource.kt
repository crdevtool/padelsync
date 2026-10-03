package com.netsports.core.sync

import kotlin.random.Random

/** Supplies random ids for matches and commands. Replaceable in tests. */
fun interface IdSource {
    /** Returns a new non-zero id. */
    fun next(): Long
}

/** Default [IdSource], backed by the platform random generator. */
class RandomIdSource(private val random: Random = Random.Default) : IdSource {
    override fun next(): Long {
        while (true) {
            val id = random.nextLong()
            // Zero is reserved to mean "no command".
            if (id != 0L) return id
        }
    }
}

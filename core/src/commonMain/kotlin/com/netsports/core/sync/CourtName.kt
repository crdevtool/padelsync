package com.netsports.core.sync

/**
 * Compares the names courts advertise.
 *
 * A device looking for its court again goes by the name it joined. That name
 * travels in a Bluetooth advertisement, where space is short, and phones cut
 * it differently: Android carries up to 12 bytes, while an iPhone may shorten
 * its name further when it does not fit beside the service identifier. So the
 * same court can be seen under a slightly shorter name than it was joined
 * under, and an exact comparison would miss it.
 *
 * A match here only decides which courts are worth asking. Whether a court
 * really carries the same match is settled by its answer, so a false match
 * costs one short connection and nothing else.
 */
object CourtName {
    /** A name cut shorter than this says too little to go by. */
    const val MIN_SHARED_LENGTH = 6

    /**
     * Whether [seen] could be the court joined as [joined]: the names are
     * equal, or one is the beginning of the other and at least
     * [MIN_SHARED_LENGTH] characters long.
     */
    fun matches(joined: String, seen: String): Boolean {
        if (joined == seen) return true
        val shorter = if (joined.length <= seen.length) joined else seen
        val longer = if (joined.length <= seen.length) seen else joined
        return shorter.length >= MIN_SHARED_LENGTH && longer.startsWith(shorter)
    }
}

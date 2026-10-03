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
     * The name a device advertises its court under: its own name with three
     * characters taken from its device id, cut to fit [maxBytes] of UTF-8.
     *
     * Device names are rarely unique. Half the phones at a club are called
     * "iPhone" (iOS no longer gives apps the owner's name for the device) or
     * by a model name. The suffix tells their courts apart in the join list,
     * and keeps a device looking for its court from trying every neighbour.
     *
     * @param maxBytes room in the advertisement, at least 4.
     */
    fun label(deviceName: String, deviceId: Long, maxBytes: Int): String {
        require(maxBytes >= SUFFIX_LENGTH + 1) { "maxBytes must be at least ${SUFFIX_LENGTH + 1}, was $maxBytes" }
        val suffix = (deviceId and 0xFFF).toString(16).uppercase().padStart(SUFFIX_LENGTH, '0')
        val base = truncateUtf8(deviceName.trim(), maxBytes - SUFFIX_LENGTH - 1).decodeToString().trim()
        return if (base.isEmpty()) suffix else "$base $suffix"
    }

    private const val SUFFIX_LENGTH = 3

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

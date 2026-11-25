package com.titanejstudios.tuner.playback

/**
 * Smarter buffering. Most channels start as fast as possible (about a second of video). Channels
 * that have stalled or dropped out lately get a bigger cushion before they start playing – a
 * little slower to start, but far fewer freezes. Channels earn it back by playing cleanly.
 *
 * Kept as "count|lastTroubleAt" per channel id; [load]/[save] read and write the stored map.
 */
class StreamCushion(
    private val load: () -> Map<String, String>,
    private val save: (Map<String, String>) -> Unit,
) {
    private var entries: MutableMap<String, Pair<Int, Long>> = HashMap()
    private var loaded = false

    private fun ensure() {
        if (loaded) return
        loaded = true
        entries = load().mapNotNull { (k, v) ->
            val c = v.substringBefore('|').toIntOrNull() ?: return@mapNotNull null
            val at = v.substringAfter('|', "0").toLongOrNull() ?: 0L
            k to (c to at)
        }.toMap(HashMap<String, Pair<Int, Long>>())
    }

    private fun persist() {
        // Keep the 200 most recent.
        val keep = entries.entries.sortedByDescending { it.value.second }.take(200)
        entries = keep.associate { it.key to it.value }.toMutableMap()
        save(entries.mapValues { (_, v) -> "${v.first}|${v.second}" })
    }

    /** Extra milliseconds of video to buffer before [key] starts playing. */
    @Synchronized
    fun cushionMs(key: String, now: Long = System.currentTimeMillis()): Long {
        ensure()
        return cushionFor(entries[key], now)
    }

    /** The channel stalled, reconnected or needed a backup link. */
    @Synchronized
    fun trouble(key: String, now: Long = System.currentTimeMillis()) {
        ensure()
        val c = entries[key]?.first ?: 0
        entries[key] = (c + 1).coerceAtMost(3) to now
        persist()
    }

    /** The channel played cleanly for a long stretch. */
    @Synchronized
    /** Fix-it: forget what's been learned about [key] (start it like a channel that's never had trouble). */
    fun forget(key: String) {
        ensure()
        if (entries.remove(key) != null) persist()
    }

    fun healthy(key: String) {
        ensure()
        val e = entries[key] ?: return
        if (e.first <= 1) entries.remove(key) else entries[key] = (e.first - 1) to e.second
        persist()
    }

    companion object {
        private const val FORGET_AFTER_MS = 14 * 86_400_000L

        /** 0 for healthy channels; 3, 6 or 10 seconds for ones that have had 1, 2 or 3+ problems lately. */
        fun cushionFor(entry: Pair<Int, Long>?, now: Long): Long {
            if (entry == null || now - entry.second > FORGET_AFTER_MS) return 0L
            return when (entry.first) {
                0 -> 0L
                1 -> 3_000L
                2 -> 6_000L
                else -> 10_000L
            }
        }
    }
}

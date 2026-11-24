package com.titanejstudios.tuner.data

/** Backup links for a channel: the other Xtream format, and same-named channels elsewhere in the playlist(s). */
object StreamAlternates {
    private val xtreamLive = Regex("^(https?://[^?#]+/live/[^/]+/[^/]+/[^/.?#]+)\\.(ts|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val quality = Regex("\\b(fhd|uhd|4k|hd|sd|hevc|h265|backup|alt|raw|50fps|60fps)\\b", RegexOption.IGNORE_CASE)

    /** Name key that ignores quality tags, so "Sky Sports Main Event FHD" matches "Sky Sports Main Event HD". */
    fun nameKey(name: String): String = XmltvParser.normalise(name.replace(quality, " "))

    fun otherFormat(url: String): String? {
        val m = xtreamLive.matchEntire(url) ?: return null
        val other = if (m.groupValues[2].equals("ts", true)) "m3u8" else "ts"
        return m.groupValues[1] + "." + other + m.groupValues[3]
    }

    /** Index: name key → channels. Build once per channel list. */
    fun index(channels: List<Channel>): Map<String, List<Channel>> =
        channels.groupBy { nameKey(it.name) }.filter { it.key.length >= 3 && it.value.size > 1 }

    /**
     * Backups in the order to try: the same stream in the other format first (same quality), then
     * the other versions best quality first – so FHD falls back to HD before SD.
     */
    fun forChannel(channel: Channel, index: Map<String, List<Channel>>, max: Int = 4): List<String> {
        val out = LinkedHashSet<String>()
        otherFormat(channel.url)?.let { out += it }
        index[nameKey(channel.name)]
            ?.filter { it.url != channel.url }
            ?.sortedByDescending { ChannelMerge.quality(it.name) }
            ?.forEach { out += it.url }
        out.remove(channel.url)
        return out.take(max)
    }
}

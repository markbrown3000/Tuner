package com.titanejstudios.tuner.data

import java.util.Locale

/** Works out the Home screen's "Sport on now" and "Top picks" rows. Pure, runs off the main thread. */
object HomeFeed {
    class Pick(val channel: Channel, val programme: Programme, val reason: String? = null)

    private val filler = Regex(
        "^(to be announced|tba|tbc|no information|no info|off air|close|closedown|programmes? start.*|" +
            "info(mercial)?s?|teleshopping|regional programmes?|this channel.*|.*not available.*)$",
        RegexOption.IGNORE_CASE,
    )

    fun nowOn(list: List<Programme>?, t: Long): Programme? {
        if (list.isNullOrEmpty()) return null
        for (p in list) {
            if (p.stop <= t) continue
            return if (p.start <= t) p else null
        }
        return null
    }

    fun nextAfter(list: List<Programme>?, t: Long): Programme? = list?.firstOrNull { it.start > t }

    fun sport(channels: List<Channel>, epg: Map<String, List<Programme>>, now: Long, max: Int = 20): List<Channel> {
        val seen = HashSet<String>()
        val out = ArrayList<Channel>()
        for (c in channels) {
            val raw = nowOn(epg[c.id], now) ?: continue
            val p = Sports.normalise(raw, now) ?: continue
            if (p.start > now || p.stop <= now || Sports.classify(p, c.group) == null) continue
            if (!seen.add(p.title.lowercase(Locale.ROOT))) continue
            out += c
            if (out.size >= max) break
        }
        return out
    }

    /**
     * Programmes on now (or starting in the next half hour) from the kinds of channels and
     * programmes you watch most – judged from recently watched and favourites. Channels already
     * in "Continue watching" are left out so the row shows something new.
     */
    fun topPicks(
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        recents: List<String>,
        favourites: List<String>,
        now: Long,
        max: Int = 15,
    ): List<Pick> {
        val byId = channels.associateBy { it.id }
        val groupScore = HashMap<String, Double>()
        val categoryScore = HashMap<String, Double>()
        recents.take(30).forEachIndexed { i, id ->
            val c = byId[id] ?: return@forEachIndexed
            val w = if (i < 5) 3.0 else if (i < 15) 2.0 else 1.0
            groupScore.merge(c.group, w, Double::plus)
            nowOn(epg[id], now)?.category?.lowercase(Locale.ROOT)?.let { categoryScore.merge(it, w, Double::plus) }
        }
        favourites.forEach { id ->
            val c = byId[id] ?: return@forEach
            groupScore.merge(c.group, 1.5, Double::plus)
            nowOn(epg[id], now)?.category?.lowercase(Locale.ROOT)?.let { categoryScore.merge(it, 1.0, Double::plus) }
        }
        val excluded = recents.take(15).toHashSet()
        val haveTaste = groupScore.isNotEmpty()

        val scored = ArrayList<Pair<Double, Pick>>()
        for (c in channels) {
            if (c.id in excluded) continue
            val g = groupScore[c.group] ?: 0.0
            val list = epg[c.id] ?: continue
            val current = nowOn(list, now)
            val p = when {
                current != null && current.stop - now >= 10 * 60_000L -> current
                else -> nextAfter(list, now)?.takeIf { it.start - now <= 30 * 60_000L }
            } ?: continue
            if (p.title.isBlank() || filler.matches(p.title.trim()) || p.title.equals(c.name, ignoreCase = true)) continue
            val cat = categoryScore[p.category?.lowercase(Locale.ROOT)] ?: 0.0
            if (haveTaste && g == 0.0 && cat == 0.0) continue
            var score = g + cat * 2
            if (p.image != null) score += 2.0
            if (!p.desc.isNullOrBlank()) score += 0.5
            if (p.start > now) score += 0.75 // starting soon
            else if (now - p.start < 15 * 60_000L) score += 0.5 // only just started
            if (!haveTaste && p.image == null) continue
            scored += score to Pick(c, p)
        }
        scored.sortByDescending { it.first }
        val titles = HashSet<String>()
        val out = ArrayList<Pick>()
        for ((_, pick) in scored) {
            if (!titles.add(pick.programme.title.lowercase(Locale.ROOT))) continue
            out += pick
            if (out.size >= max) break
        }
        return out
    }
}

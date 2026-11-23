package com.titanejstudios.tuner.data

import java.util.Calendar

/** Recommendations from what a profile has learned to like ([Taste]). */
object Recommend {
    private val filler = Regex(
        "^(to be announced|tba|tbc|no information|no info|off air|close|closedown|teleshopping|regional programmes?|.*not available.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** What's on now (with 10+ minutes left) or starting within half an hour on a channel. */
    private fun candidate(list: List<Programme>?, now: Long): Programme? {
        val cur = HomeFeed.nowOn(list, now)
        if (cur != null && cur.stop - now >= 10 * 60_000L) return cur
        return HomeFeed.nextAfter(list, now)?.takeIf { it.start - now <= 30 * 60_000L }
    }

    /**
     * Top picks ranked by your taste and the time of week, each with a reason. Ends with a
     * couple of well-described things outside your usual mix ("Something different").
     */
    fun topPicks(channels: List<Channel>, epg: Map<String, List<Programme>>, taste: Taste, recents: List<String>, now: Long, max: Int = 15): List<HomeFeed.Pick> {
        val skip = recents.take(10).toHashSet()
        val scored = ArrayList<Triple<Double, HomeFeed.Pick, String?>>()
        val seenTitles = HashSet<String>()
        for (c in channels) {
            if (c.id in skip) continue
            val p = candidate(epg[c.id], now) ?: continue
            if (p.title.isBlank() || filler.matches(p.title.trim()) || p.title.equals(c.name, ignoreCase = true)) continue
            val genre = Genres.of(p.category, p.title, c.group)
            val (s, reason) = taste.score(p.title, genre, now)
            if (s < 0) continue
            var score = s
            if (p.image != null) score += 1.0
            if (!p.desc.isNullOrBlank()) score += 0.3
            scored += Triple(score, HomeFeed.Pick(c, p, reason), genre)
        }
        val best = scored.sortedByDescending { it.first }
            .filter { seenTitles.add(Taste.key(it.second.programme.title)) }
        val main = best.filter { it.first >= 1.5 }.take(max - 2).map { it.second }
        // Something new: well-described programmes in genres you rarely watch.
        val shares = taste.genreShares().toMap()
        val usedIds = main.mapTo(HashSet()) { it.channel.id }
        val different = best.asSequence()
            .filter { it.second.channel.id !in usedIds && it.second.programme.image != null && !it.second.programme.desc.isNullOrBlank() }
            .filter { (shares[it.third] ?: 0.0) < 0.05 }
            .take(2)
            .map { HomeFeed.Pick(it.second.channel, it.second.programme, "Something different") }
            .toList()
        return main + different
    }

    /** Your regular shows on later today (and on now), earliest first – one per show. */
    fun yourShowsTonight(channels: List<Channel>, epg: Map<String, List<Programme>>, taste: Taste, now: Long): List<HomeFeed.Pick> {
        if (taste.isEmpty) return emptyList()
        val end = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val best = HashMap<String, HomeFeed.Pick>()
        for (c in channels) {
            for (p in epg[c.id].orEmpty()) {
                if (p.stop <= now || p.start > end) continue
                if (!taste.isYourShow(p.title)) continue
                val k = Taste.key(p.title)
                val have = best[k]
                if (have == null || p.start < have.programme.start) best[k] = HomeFeed.Pick(c, p, "One of your shows")
            }
        }
        return best.values.sortedBy { it.programme.start }.take(20)
    }
}

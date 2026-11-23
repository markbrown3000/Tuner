package com.titanejstudios.tuner.data

import java.util.Calendar
import java.util.Locale

/** Groups programmes into broad kinds, from the guide's category – or the channel's group when there isn't one. */
object Genres {
    val all = listOf("Sport", "Film", "News", "Kids", "Factual", "Entertainment", "Drama", "Music")

    fun of(category: String?, title: String, channelGroup: String?): String? {
        fromText(category)?.let { return it }
        if (Sports.classify(Programme(0, 0, title, null, category)) != null) return "Sport"
        return fromText(channelGroup)
    }

    private fun fromText(s: String?): String? {
        val c = s?.lowercase(Locale.ROOT) ?: return null
        return when {
            "sport" in c || "football" in c || "soccer" in c || "racing" in c || "golf" in c || "boxing" in c -> "Sport"
            "film" in c || "movie" in c || "cinema" in c -> "Film"
            "news" in c || "weather" in c -> "News"
            "child" in c || "kid" in c || "animation" in c || "cartoon" in c || "junior" in c -> "Kids"
            "doc" in c || "factual" in c || "nature" in c || "history" in c || "science" in c -> "Factual"
            "music" in c -> "Music"
            "comedy" in c || "entertainment" in c || "game show" in c || "reality" in c || "quiz" in c -> "Entertainment"
            "drama" in c || "series" in c || "soap" in c || "crime" in c -> "Drama"
            else -> null
        }
    }
}

/** "What's on": programmes airing at a chosen time (now, tonight at 9…), optionally one kind only. */
object WhatsOn {
    class Slot(val label: String, val at: Long)

    fun slots(now: Long): List<Slot> {
        val out = arrayListOf(Slot("Now", now))
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        for (h in listOf(18, 20, 21, 22)) {
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > now + 30 * 60_000L) out += Slot(if (h == 21) "Tonight 9pm" else "${h - 12}pm", cal.timeInMillis)
        }
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 20)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        out += Slot("Tomorrow 8pm", cal.timeInMillis)
        return out
    }

    fun list(
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        at: Long,
        genre: String?,
        favourites: Set<String>,
        favouritesOnly: Boolean,
        max: Int = 300,
    ): List<HomeFeed.Pick> {
        val favs = ArrayList<HomeFeed.Pick>()
        val rest = ArrayList<HomeFeed.Pick>()
        val seen = HashSet<String>()
        for (c in channels) {
            val isFav = c.id in favourites
            if (favouritesOnly && !isFav) continue
            val p = epg[c.id]?.firstOrNull { it.start <= at && it.stop > at } ?: continue
            if (p.title.isBlank() || p.title.equals(c.name, ignoreCase = true)) continue
            if (genre != null && Genres.of(p.category, p.title, c.group) != genre) continue
            // Same programme on several copies of a channel (HD/FHD) – show it once.
            if (!seen.add(p.title.lowercase(Locale.ROOT) + "|" + p.start)) continue
            (if (isFav) favs else rest) += HomeFeed.Pick(c, p)
            if (favs.size + rest.size >= max) break
        }
        return favs + rest
    }
}

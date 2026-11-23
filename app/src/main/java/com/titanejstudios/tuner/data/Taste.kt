package com.titanejstudios.tuner.data

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * What a profile likes, learned from what they actually watch (and their 👍/👎). Kept on the
 * TV, one per profile, as JSON. Old viewing slowly counts for less, so tastes can change.
 *
 * - genres: "Sport", "Drama"… (minutes watched, fading)
 * - shows: programme titles (minutes, fading) – the ones you come back to
 * - slots: "weekday-evening|Drama" – what you watch at that time of the week
 * - liked / disliked: your 👍/👎 on titles and genres
 */
class Taste(
    val genres: MutableMap<String, Double> = HashMap(),
    val shows: MutableMap<String, Double> = HashMap(),
    val showNames: MutableMap<String, String> = HashMap(),
    val slots: MutableMap<String, Double> = HashMap(),
    val liked: MutableSet<String> = HashSet(),
    val disliked: MutableSet<String> = HashSet(),
    var fadedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = genres.isEmpty() && shows.isEmpty() && liked.isEmpty()

    /** [seconds] of [title] (a [genre] programme) watched at [now]. Short flicks don't count. */
    fun record(title: String, genre: String?, seconds: Long, now: Long) {
        if (seconds < 60) return
        fade(now)
        val minutes = seconds / 60.0
        genre?.let {
            genres.merge(it, minutes, Double::plus)
            slots.merge(slotOf(now) + "|" + it, minutes, Double::plus)
        }
        val key = key(title)
        if (key.length >= 2) {
            shows.merge(key, minutes, Double::plus)
            showNames[key] = title.trim()
        }
        trim()
    }

    fun like(title: String?, genre: String?) {
        title?.let { key(it) }?.let { disliked.remove(it); liked.add(it) }
        genre?.let { disliked.remove("g:$it"); liked.add("g:$it") }
    }

    fun dislike(title: String?, genre: String?) {
        title?.let { key(it) }?.let { liked.remove(it); disliked.add(it) }
        genre?.let { liked.remove("g:$it"); disliked.add("g:$it") }
    }

    /** True when you've watched this show properly before (about 40 minutes, or 👍). */
    fun isYourShow(title: String): Boolean {
        val k = key(title)
        return k in liked || (shows[k] ?: 0.0) >= 40.0
    }

    /** Genres as shares of your viewing, biggest first ("Sport" to 0.35…). */
    fun genreShares(): List<Pair<String, Double>> {
        val total = genres.values.sum()
        if (total <= 0) return emptyList()
        return genres.entries.sortedByDescending { it.value }.map { it.key to it.value / total }
    }

    fun topShows(n: Int): List<String> = shows.entries.sortedByDescending { it.value }.take(n).mapNotNull { showNames[it.key] }

    /**
     * How well [title]/[genre] suits you at [now] (0 = no idea, negative = not for you), with a
     * short reason ("You watch a lot of Sport").
     */
    fun score(title: String, genre: String?, now: Long): Pair<Double, String?> {
        val k = key(title)
        if (k in disliked || (genre != null && "g:$genre" in disliked)) return -10.0 to null
        val total = genres.values.sum().coerceAtLeast(1.0)
        var s = 0.0
        var reason: String? = null
        if (k in liked) {
            s += 8.0
            reason = "You gave this a 👍"
        }
        val show = shows[k] ?: 0.0
        if (show >= 20) {
            s += 4.0 + minOf(show / 60.0, 4.0)
            if (reason == null) reason = "You've been watching this"
        }
        if (genre != null) {
            val share = (genres[genre] ?: 0.0) / total
            s += share * 10.0
            if ("g:$genre" in liked) s += 3.0
            val slotTotal = slots.filterKeys { it.startsWith(slotOf(now) + "|") }.values.sum().coerceAtLeast(1.0)
            val slotShare = (slots[slotOf(now) + "|" + genre] ?: 0.0) / slotTotal
            s += slotShare * 6.0
            if (reason == null) reason = when {
                slotShare >= 0.35 -> "You usually watch ${genre.lowercase(Locale.ROOT)} ${slotLabel(now)}"
                share >= 0.2 -> "You watch a lot of ${genre.lowercase(Locale.ROOT)}"
                else -> null
            }
        }
        return s to reason
    }

    /** Older viewing slowly counts for less (halves in about three weeks). */
    private fun fade(now: Long) {
        if (fadedAt == 0L) {
            fadedAt = now
            return
        }
        val days = ((now - fadedAt) / 86_400_000L).toInt()
        if (days <= 0) return
        val f = Math.pow(0.968, days.toDouble())
        for (m in listOf(genres, shows, slots)) m.replaceAll { _, v -> v * f }
        fadedAt = now
    }

    private fun trim() {
        if (shows.size > 400) {
            val keep = shows.entries.sortedByDescending { it.value }.take(300).map { it.key }.toHashSet()
            shows.keys.retainAll(keep)
            showNames.keys.retainAll(keep)
        }
    }

    fun toJson(): String = JSONObject()
        .put("genres", JSONObject(genres as Map<*, *>))
        .put("shows", JSONObject(shows as Map<*, *>))
        .put("names", JSONObject(showNames as Map<*, *>))
        .put("slots", JSONObject(slots as Map<*, *>))
        .put("liked", org.json.JSONArray(liked.toList()))
        .put("disliked", org.json.JSONArray(disliked.toList()))
        .put("fadedAt", fadedAt)
        .toString()

    companion object {
        fun fromJson(json: String?): Taste {
            if (json.isNullOrBlank()) return Taste()
            return try {
                val o = JSONObject(json)
                fun doubles(name: String): MutableMap<String, Double> {
                    val m = HashMap<String, Double>()
                    o.optJSONObject(name)?.let { j -> j.keys().forEach { k -> m[k] = j.optDouble(k, 0.0) } }
                    return m
                }
                fun strings(name: String): MutableSet<String> {
                    val a = o.optJSONArray(name) ?: return HashSet()
                    return (0 until a.length()).map { a.getString(it) }.toHashSet()
                }
                val names = HashMap<String, String>()
                o.optJSONObject("names")?.let { j -> j.keys().forEach { k -> names[k] = j.optString(k) } }
                Taste(doubles("genres"), doubles("shows"), names, doubles("slots"), strings("liked"), strings("disliked"), o.optLong("fadedAt"))
            } catch (e: Exception) {
                Taste()
            }
        }

        /** "Live: Match of the Day (S2 E3) – New" → "match of the day". */
        fun key(title: String): String = title.lowercase(Locale.ROOT)
            .replace(Regex("^(live|new|premiere)\\s*[:\\-–]\\s*"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("\\b(s\\d+\\s*e\\d+|series \\d+|episode \\d+)\\b.*$"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** "weekday-evening", "weekend-afternoon"… */
        fun slotOf(t: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val day = c.get(Calendar.DAY_OF_WEEK)
            val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
            val part = when (c.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> "morning"
                in 12..17 -> "afternoon"
                in 18..22 -> "evening"
                else -> "night"
            }
            return (if (weekend) "weekend-" else "weekday-") + part
        }

        private fun slotLabel(t: Long): String {
            val s = slotOf(t)
            val part = s.substringAfter('-')
            return if (s.startsWith("weekend")) "on weekend ${part}s" else "on weekday ${part}s"
        }
    }
}

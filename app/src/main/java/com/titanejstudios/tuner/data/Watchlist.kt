package com.titanejstudios.tuner.data

import org.json.JSONArray
import org.json.JSONObject

/** A film or series saved to "My list". Keeps enough to show it and play/open it without reloading. */
data class WatchItem(
    /** VodItem.id or SeriesItem.id. */
    val id: String,
    val isSeries: Boolean,
    val name: String,
    val image: String?,
    val category: String,
    val added: Long,
    // Films
    val url: String? = null,
    val rating: String? = null,
    // Series
    val sourceId: String? = null,
    val seriesId: String? = null,
    val plot: String? = null,
) {
    fun toVod(): VodItem? = url?.let { VodItem(id, name, image, category, it, rating) }
    fun toSeries(): SeriesItem? =
        if (sourceId != null && seriesId != null) SeriesItem(id, sourceId, seriesId, name, image, category, plot, rating) else null

    companion object {
        fun of(m: VodItem, now: Long) = WatchItem(m.id, false, m.name, m.poster, m.category, now, url = m.url, rating = m.rating)
        fun of(s: SeriesItem, now: Long) = WatchItem(
            s.id, true, s.name, s.cover, s.category, now,
            rating = s.rating, sourceId = s.sourceId, seriesId = s.seriesId, plot = s.plot,
        )
    }
}

/** "👪 Recommend to the family": a film or series someone picked for everyone, and who. */
data class FamilyPick(val item: WatchItem, val from: String, val at: Long)

/** A series you follow and its next announced episode ([date] "2026-12-03"). */
data class ComingEpisode(val item: WatchItem, val date: String, val season: Int, val episode: Int, val name: String?)

/** A series you've watched, and how many episodes it had last time we looked. */
data class FollowedSeries(
    val series: WatchItem,
    val episodeCount: Int,
    /** Episodes added since you last opened the series (0 = nothing new). */
    val newEpisodes: Int,
    val lastWatched: Long,
)

object Watchlist {
    /** Most followed series we keep (and check for new episodes). */
    const val MAX_FOLLOWED = 25

    fun encode(list: List<WatchItem>): String {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        return arr.toString()
    }

    fun decode(json: String): List<WatchItem> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun encodeFollowed(list: List<FollowedSeries>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("s", toJson(it.series)).put("n", it.episodeCount)
                    .put("new", it.newEpisodes).put("at", it.lastWatched)
            )
        }
        return arr.toString()
    }

    fun decodeFollowed(json: String): List<FollowedSeries> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val s = o.optJSONObject("s")?.let { fromJson(it) } ?: return@mapNotNull null
            FollowedSeries(s, o.optInt("n"), o.optInt("new"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Adds or removes [item]; newest first. */
    fun toggle(list: List<WatchItem>, item: WatchItem): List<WatchItem> =
        if (list.any { it.id == item.id }) list.filter { it.id != item.id } else listOf(item) + list

    /** Records that you watched an episode of [series] (keeps the newest [MAX_FOLLOWED]). */
    fun watched(list: List<FollowedSeries>, series: WatchItem, episodeCount: Int?, now: Long): List<FollowedSeries> {
        val old = list.firstOrNull { it.series.id == series.id }
        val entry = FollowedSeries(
            series = series,
            episodeCount = episodeCount ?: old?.episodeCount ?: 0,
            newEpisodes = 0,
            lastWatched = now,
        )
        return (listOf(entry) + list.filter { it.series.id != series.id })
            .sortedByDescending { it.lastWatched }
            .take(MAX_FOLLOWED)
    }

    /** Updates a series with a fresh episode count; more than before = new episodes. */
    fun withCount(entry: FollowedSeries, count: Int): FollowedSeries = when {
        // First time we've counted, or the provider removed some: just remember the number.
        entry.episodeCount <= 0 || count < entry.episodeCount -> entry.copy(episodeCount = count, newEpisodes = 0)
        count > entry.episodeCount -> entry.copy(episodeCount = count, newEpisodes = entry.newEpisodes + (count - entry.episodeCount))
        else -> entry
    }

    /** Your own collections: name → films and series, in the order added. */
    fun encodeCollections(map: Map<String, List<WatchItem>>): String {
        val o = JSONObject()
        for ((name, list) in map) o.put(name, JSONArray().apply { list.forEach { put(toJson(it)) } })
        return o.toString()
    }

    fun decodeCollections(json: String?): Map<String, List<WatchItem>> = try {
        val o = JSONObject(json ?: "{}")
        val out = LinkedHashMap<String, List<WatchItem>>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            out[k] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { fromJson(it) } }
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    /** A moment in a film or episode to go back to. */
    class Bookmark(val id: String, val contentId: String, val title: String, val subtitle: String?, val poster: String?, val url: String, val seriesId: String?, val posMs: Long, val at: Long)

    fun encodeBookmarks(list: List<Bookmark>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("id", it.id).put("c", it.contentId).put("t", it.title).put("s", it.subtitle).put("p", it.poster)
                    .put("u", it.url).put("sid", it.seriesId).put("pos", it.posMs).put("at", it.at),
            )
        }
    }.toString()

    fun decodeBookmarks(json: String?): List<Bookmark> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            Bookmark(o.optString("id"), o.optString("c"), o.optString("t"), str("s"), str("p"), o.optString("u"), str("sid"), o.optLong("pos"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Family picks: someone's recommendation for everyone, newest first (30 kept, 45 days). */
    fun encodePicks(list: List<FamilyPick>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("w", toJson(it.item)).put("from", it.from).put("at", it.at)) }
        return arr.toString()
    }

    fun decodePicks(json: String?): List<FamilyPick> = try {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val w = o.optJSONObject("w")?.let { fromJson(it) } ?: return@mapNotNull null
            FamilyPick(w, o.optString("from"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun addPick(list: List<FamilyPick>, pick: FamilyPick, now: Long): List<FamilyPick> =
        (listOf(pick) + list.filter { it.item.id != pick.item.id })
            .filter { now - it.at < 45L * 24 * 3600_000L }
            .take(30)

    private fun toJson(w: WatchItem): JSONObject = JSONObject()
        .put("id", w.id).put("series", w.isSeries).put("name", w.name).put("img", w.image)
        .put("cat", w.category).put("added", w.added).put("url", w.url).put("rating", w.rating)
        .put("src", w.sourceId).put("sid", w.seriesId).put("plot", w.plot)

    private fun fromJson(o: JSONObject): WatchItem? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        return WatchItem(
            id = id,
            isSeries = o.optBoolean("series"),
            name = o.optString("name"),
            image = str("img"),
            category = o.optString("cat"),
            added = o.optLong("added"),
            url = str("url"),
            rating = str("rating"),
            sourceId = str("src"),
            seriesId = str("sid"),
            plot = str("plot"),
        )
    }
}

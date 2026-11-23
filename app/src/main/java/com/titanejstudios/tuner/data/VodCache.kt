package com.titanejstudios.tuner.data

import org.json.JSONArray
import java.io.File

/**
 * Saved copies of the Films and Series lists, so those screens open straight away instead of
 * downloading the whole catalogue every time. Refreshed in the background when over 12 hours old.
 */
class VodCache(filesDir: File) {
    private val dir = File(filesDir, "vod").apply { mkdirs() }

    class Cached<T>(val items: List<T>, val savedAt: Long)

    private fun file(kind: String, sourceId: String) = File(dir, "${kind}_${sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun saveMovies(sourceId: String, list: List<VodItem>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONArray().put(m.id).put(m.name).put(m.poster ?: "").put(m.category).put(m.url).put(m.rating ?: ""))
        write(file("films", sourceId), arr.toString())
    }

    fun loadMovies(sourceId: String): Cached<VodItem>? = read(file("films", sourceId)) { a ->
        VodItem(a.getString(0), a.getString(1), a.optString(2).ifEmpty { null }, a.getString(3), a.getString(4), a.optString(5).ifEmpty { null })
    }

    fun saveSeries(sourceId: String, list: List<SeriesItem>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONArray().put(s.id).put(s.sourceId).put(s.seriesId).put(s.name).put(s.cover ?: "")
                    .put(s.category).put(s.plot ?: "").put(s.rating ?: "")
            )
        }
        write(file("series", sourceId), arr.toString())
    }

    fun loadSeries(sourceId: String): Cached<SeriesItem>? = read(file("series", sourceId)) { a ->
        SeriesItem(
            a.getString(0), a.getString(1), a.getString(2), a.getString(3), a.optString(4).ifEmpty { null },
            a.getString(5), a.optString(6).ifEmpty { null }, a.optString(7).ifEmpty { null },
        )
    }

    /** Forgets one playlist's saved lists, or every one when [sourceId] is null. */
    fun clear(sourceId: String? = null) {
        if (sourceId == null) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            file("films", sourceId).delete()
            file("series", sourceId).delete()
        }
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun <T> read(f: File, row: (JSONArray) -> T): Cached<T>? = try {
        if (!f.exists()) null else {
            val arr = JSONArray(f.readText())
            val out = ArrayList<T>(arr.length())
            for (i in 0 until arr.length()) out += row(arr.getJSONArray(i))
            Cached(out, f.lastModified())
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX_AGE_MS = 12 * 3600_000L
    }
}

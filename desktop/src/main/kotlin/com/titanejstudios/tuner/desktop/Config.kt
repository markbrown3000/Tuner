package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.data.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings for the PC version, kept in Tuner's folder in your user profile. */
class Config {
    private val dir = File(System.getProperty("user.home"), ".tuner").apply { mkdirs() }
    private val file = File(dir, "settings.json")

    var sources: List<Source> = emptyList()
    var englishOnly = false
    var mergeVersions = true
    var favourites: List<String> = emptyList()
    var lastChannel: String? = null
    var volume = 80

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("sources") ?: JSONArray()
            sources = (0 until arr.length()).mapNotNull { i ->
                val s = arr.getJSONObject(i)
                when (s.optString("type")) {
                    "m3u" -> Source.M3u(s.getString("id"), s.optString("name"), s.getString("url"), s.optString("epg").ifBlank { null })
                    "xtream" -> Source.Xtream(
                        s.getString("id"), s.optString("name"), s.getString("server"), s.getString("user"), s.getString("pass"),
                        s.optBoolean("hls"), s.optString("epg").ifBlank { null },
                    )
                    else -> null
                }
            }
            englishOnly = o.optBoolean("englishOnly")
            mergeVersions = o.optBoolean("merge", true)
            favourites = o.optJSONArray("favourites")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            lastChannel = o.optString("last").ifBlank { null }
            volume = o.optInt("volume", 80)
        }
    }

    fun save() {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(
                when (s) {
                    is Source.M3u -> JSONObject().put("type", "m3u").put("id", s.id).put("name", s.name).put("url", s.playlistUrl).put("epg", s.epgUrl ?: "")
                    is Source.Xtream -> JSONObject().put("type", "xtream").put("id", s.id).put("name", s.name).put("server", s.server)
                        .put("user", s.username).put("pass", s.password).put("hls", s.useHls).put("epg", s.epgUrl ?: "")
                }
            )
        }
        val o = JSONObject().put("sources", arr).put("englishOnly", englishOnly).put("merge", mergeVersions)
            .put("favourites", JSONArray(favourites)).put("last", lastChannel ?: "").put("volume", volume)
        file.writeText(o.toString(2))
    }
}

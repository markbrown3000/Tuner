package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.data.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A profile from the TV (who's watching). Kids' profiles see only their channel groups. */
class PcProfile(val id: String, val name: String, val kids: Boolean, val groups: Set<String>)

/** A film or episode part watched (the TV's format, so Continue watching carries across). */
class Resume(
    val id: String, val title: String, val sub: String?, val poster: String?, val url: String,
    val pos: Long, val dur: Long, val at: Long, val series: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("sub", sub).put("poster", poster)
        .put("url", url).put("pos", pos).put("dur", dur).put("at", at).put("series", series)

    companion object {
        fun fromJson(o: JSONObject): Resume? {
            val id = o.optString("id").ifBlank { return null }
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            return Resume(id, o.optString("title"), str("sub"), str("poster"), o.optString("url"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"), str("series"))
        }
    }
}

/** A "remind me" for a programme (while Flicker's open). */
class Reminder(val channelId: String, val channelName: String, val title: String, val start: Long)

/** Settings for the PC version, kept in Flicker's folder in your user profile. */
class Config {
    private val dir = File(System.getProperty("user.home"), ".tuner").apply { mkdirs() }
    private val file = File(dir, "settings.json")

    var deviceId: String = UUID.randomUUID().toString()
    var sources: List<Source> = emptyList()
    var englishOnly = false
    var mergeVersions = true
    var lastChannel: String? = null
    var volume = 80

    // From your TV through Google Drive (see Sync).
    var googleRefresh: String? = null
    var googleEmail: String? = null
    var driveFileId: String? = null
    /** Use the TV's playlists (they come with the sync). */
    var useTvPlaylists = true
    var profiles: List<PcProfile> = emptyList()
    var activeProfile: String? = null
    /** Profile id → favourite channel ids (the TV's format). */
    var favourites: Map<String, List<String>> = emptyMap()
    var favouritesAt = 0L
    var watchlistJson = "[]"
    var watchlistAt = 0L
    var followedJson = "[]"
    var followedAt = 0L
    var resume: Map<String, Resume> = emptyMap()
    /** Finished films and episodes: id → when. */
    var done: Map<String, Long> = emptyMap()
    /** Profile name (lower case) → its YouTube sign-in from the TV. */
    var youtubeTokens: Map<String, String> = emptyMap()
    var lastSync = 0L

    var plexClientId: String = UUID.randomUUID().toString()
    var plexToken: String? = null
    var reminders: List<Reminder> = emptyList()

    init {
        load()
    }

    private fun sourceFromJson(s: JSONObject): Source? = when (s.optString("type")) {
        "m3u" -> Source.M3u(s.getString("id"), s.optString("name"), s.optString("url"), s.optString("epg").ifBlank { null })
        "xtream" -> Source.Xtream(
            s.getString("id"), s.optString("name"), s.optString("server"), s.optString("user"), s.optString("pass"),
            s.optBoolean("hls"), s.optString("epg").ifBlank { null },
        )
        else -> null
    }

    fun sourcesFromJson(arr: JSONArray): List<Source> = (0 until arr.length()).mapNotNull { i ->
        runCatching { sourceFromJson(arr.getJSONObject(i)) }.getOrNull()
    }

    private fun load() {
        if (!file.exists()) {
            save()
            return
        }
        runCatching {
            val o = JSONObject(file.readText())
            deviceId = o.optString("device").ifBlank { deviceId }
            sources = sourcesFromJson(o.optJSONArray("sources") ?: JSONArray())
            englishOnly = o.optBoolean("englishOnly")
            mergeVersions = o.optBoolean("merge", true)
            lastChannel = o.optString("last").ifBlank { null }
            volume = o.optInt("volume", 80)
            googleRefresh = o.optString("gRefresh").ifBlank { null }
            googleEmail = o.optString("gEmail").ifBlank { null }
            driveFileId = o.optString("gFile").ifBlank { null }
            useTvPlaylists = o.optBoolean("tvPlaylists", true)
            profiles = o.optJSONArray("profiles")?.let { profilesFromJson(it) } ?: emptyList()
            activeProfile = o.optString("profile").ifBlank { null }
            favourites = o.optJSONObject("favs")?.let { f ->
                f.keys().asSequence().associateWith { k -> f.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }
            } ?: run {
                // Version 1 kept one list.
                val old = o.optJSONArray("favourites")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                if (old.isEmpty()) emptyMap() else mapOf("default" to old)
            }
            favouritesAt = o.optLong("favsAt")
            watchlistJson = o.optString("watchlist", "[]")
            watchlistAt = o.optLong("watchlistAt")
            followedJson = o.optString("followed", "[]")
            followedAt = o.optLong("followedAt")
            resume = o.optJSONArray("resume")?.let { a -> (0 until a.length()).mapNotNull { Resume.fromJson(a.getJSONObject(it)) } }
                ?.associateBy { it.id } ?: emptyMap()
            done = o.optJSONObject("done")?.let { d -> d.keys().asSequence().associateWith { d.optLong(it) } } ?: emptyMap()
            youtubeTokens = o.optJSONObject("ytTokens")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } } ?: emptyMap()
            lastSync = o.optLong("lastSync")
            plexClientId = o.optString("plexClient").ifBlank { plexClientId }
            plexToken = o.optString("plexToken").ifBlank { null }
            reminders = o.optJSONArray("reminders")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { r -> Reminder(r.optString("c"), r.optString("cn"), r.optString("t"), r.optLong("s")) }
            } ?: emptyList()
        }
    }

    fun profilesFromJson(arr: JSONArray): List<PcProfile> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifBlank { return@mapNotNull null }
        val groups = o.optJSONArray("groups")
        PcProfile(id, o.optString("name"), o.optBoolean("kids"), groups?.let { g -> (0 until g.length()).map { g.getString(it) }.toSet() } ?: emptySet())
    }

    fun sourcesToJson(): JSONArray = JSONArray().apply {
        sources.forEach { s ->
            put(
                when (s) {
                    is Source.M3u -> JSONObject().put("type", "m3u").put("id", s.id).put("name", s.name).put("url", s.playlistUrl).put("epg", s.epgUrl ?: "")
                    is Source.Xtream -> JSONObject().put("type", "xtream").put("id", s.id).put("name", s.name).put("server", s.server)
                        .put("user", s.username).put("pass", s.password).put("hls", s.useHls).put("epg", s.epgUrl ?: "")
                }
            )
        }
    }

    /** The profile whose favourites and lists are shown. */
    val profileKey: String get() = activeProfile ?: profiles.firstOrNull { !it.kids }?.id ?: "default"

    @Synchronized
    fun save() {
        val favs = JSONObject()
        for ((k, v) in favourites) favs.put(k, JSONArray(v))
        val o = JSONObject().put("device", deviceId).put("sources", sourcesToJson()).put("englishOnly", englishOnly).put("merge", mergeVersions)
            .put("last", lastChannel ?: "").put("volume", volume)
            .put("gRefresh", googleRefresh ?: "").put("gEmail", googleEmail ?: "").put("gFile", driveFileId ?: "")
            .put("tvPlaylists", useTvPlaylists)
            .put("profiles", JSONArray().apply {
                profiles.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("kids", p.kids).put("groups", JSONArray(p.groups.toList()))) }
            })
            .put("profile", activeProfile ?: "")
            .put("favs", favs).put("favsAt", favouritesAt)
            .put("watchlist", watchlistJson).put("watchlistAt", watchlistAt)
            .put("followed", followedJson).put("followedAt", followedAt)
            .put("resume", JSONArray().apply { resume.values.forEach { put(it.toJson()) } })
            .put("done", JSONObject(done))
            .put("ytTokens", JSONObject(youtubeTokens))
            .put("lastSync", lastSync)
            .put("plexClient", plexClientId).put("plexToken", plexToken ?: "")
            .put("reminders", JSONArray().apply { reminders.forEach { put(JSONObject().put("c", it.channelId).put("cn", it.channelName).put("t", it.title).put("s", it.start)) } })
        val tmp = File(dir, "settings.json.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

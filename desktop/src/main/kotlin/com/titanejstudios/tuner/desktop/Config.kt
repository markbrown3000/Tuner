package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.data.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A profile from the TV (who's watching). Kids' profiles see only their channel groups. */
class PcProfile(
    val id: String, val name: String, val kids: Boolean, val groups: Set<String>,
    /** Kids: minutes a day (0 = no limit), and no TV after this (minutes after midnight, -1 = none) until 6am. */
    val limitMinutes: Int = 0, val bedtime: Int = -1,
    /** The TV's avatar colour (-1 = automatic) and picture (an emoji, or empty for the first letter). */
    val colour: Int = -1, val icon: String = "",
)

/** A film or episode saved to the PC to watch without the internet. */
class PcDownload(val id: String, val title: String, val sub: String?, val poster: String?, val url: String, var file: String, var state: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("sub", sub).put("p", poster).put("u", url).put("f", file).put("st", state)

    companion object {
        fun fromJson(o: JSONObject) = PcDownload(
            o.optString("id"), o.optString("t"), o.optString("sub").ifBlank { null }?.takeIf { it != "null" },
            o.optString("p").ifBlank { null }?.takeIf { it != "null" }, o.optString("u"), o.optString("f"), o.optString("st", "done"),
        )
    }
}

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
    val file = File(dir, "settings.json")

    var deviceId: String = UUID.randomUUID().toString()
    var sources: List<Source> = emptyList()
    var englishOnly = false
    var mergeVersions = true
    var lastChannel: String? = null
    /** Channels watched lately, newest first. */
    var recents: List<String> = emptyList()
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
    /** The house's linked-devices key (from the TV, through the sync). */
    var linkKey: String? = null
    var recordings: List<PcRecording> = emptyList()
    var lastUpdateCheck = 0L
    /** The TV's parental PIN (through the sync), for kids' limits. */
    var pin: String? = null
    /** Kids' TV time today: profile id → "yyyy-MM-dd:seconds". */
    var usage: Map<String, String> = emptyMap()
    /** Your football teams (from the TV) and their ESPN teams (JSON, by name). */
    var teams: List<String> = emptyList()
    var espnTeams: String = "{}"
    var downloads: List<PcDownload> = emptyList()
    // The window, as you left it.
    var windowX = -1
    var windowY = -1
    var windowW = 1400
    var windowH = 860
    var windowMaximised = false
    /** Closing the window leaves Flicker in the system tray (recordings and reminders carry on). */
    var keepRunning = true
    var startWithWindows = false
    var showNumbers = true
    /** Picture: deinterlace old channels. */
    var deinterlace = true
    /** Evens out loud and quiet channels. */
    var normalise = false
    var theme = "Evening"
    var textScale = 1f
    /** Windows notifications for reminders and finished recordings. */
    var notifications = true
    /** Where each series' intro ends (from the TV): series id → milliseconds. */
    var intros: Map<String, Long> = emptyMap()
    /** The TV's profiles exactly as it sent them (so edits here keep everything the TV knows). */
    var profilesRaw: String = "[]"
    /** When profiles were last changed on this PC (0 = never: the TV's are used). */
    var profilesAt = 0L

    /** Newer settings, kept together. */
    private var more = JSONObject()
    private fun moreStr(k: String, d: String) = more.optString(k, d)
    var familyPicksJson: String get() = moreStr("picks", "[]"); set(v) { more.put("picks", v) }
    var familyPicksAt: Long get() = more.optLong("picksAt"); set(v) { more.put("picksAt", v) }
    var comingSoonJson: String get() = moreStr("coming", "[]"); set(v) { more.put("coming", v) }
    var comingSoonAt: Long get() = more.optLong("comingAt"); set(v) { more.put("comingAt", v) }
    var comingTold: String get() = moreStr("comingTold", ""); set(v) { more.put("comingTold", v) }
    var recaps: Boolean get() = more.optBoolean("recaps", true); set(v) { more.put("recaps", v) }
    var miniOnTop: Boolean get() = more.optBoolean("miniOnTop", true); set(v) { more.put("miniOnTop", v) }
    /** 👍 (1) and 👎 (-1), by title in lower case. */
    var thumbs: Map<String, Int>
        get() = more.optJSONObject("thumbs")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } } ?: emptyMap()
        set(v) { more.put("thumbs", JSONObject(v)) }
    // Channels and categories (Settings › Manage channels).
    private fun moreSet(k: String): Set<String> = more.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    private fun moreList(k: String): List<String> = more.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private fun moreMap(k: String): Map<String, String> = more.optJSONObject(k)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()
    var hiddenGroups: Set<String> get() = moreSet("hiddenGroups"); set(v) { more.put("hiddenGroups", JSONArray(v.toList())) }
    var hiddenChannels: Set<String> get() = moreSet("hiddenChannels"); set(v) { more.put("hiddenChannels", JSONArray(v.toList())) }
    var lockedGroups: Set<String> get() = moreSet("lockedGroups"); set(v) { more.put("lockedGroups", JSONArray(v.toList())) }
    /** Original group name → the name you gave it. */
    var groupNames: Map<String, String> get() = moreMap("groupNames"); set(v) { more.put("groupNames", JSONObject(v)) }
    /** Groups in the order you put them (the rest follow in the playlist's order). */
    var groupOrder: List<String> get() = moreList("groupOrder"); set(v) { more.put("groupOrder", JSONArray(v)) }
    /** Channel id → the channel whose guide it uses (for channels with none). */
    var epgMap: Map<String, String> get() = moreMap("epgMap"); set(v) { more.put("epgMap", JSONObject(v)) }
    /** Channels that didn't answer the last check. */
    var offline: Set<String> get() = moreSet("offline"); set(v) { more.put("offline", JSONArray(v.toList())) }
    var hideOffline: Boolean get() = more.optBoolean("hideOffline"); set(v) { more.put("hideOffline", v) }
    var offlineCheckedAt: Long get() = more.optLong("offlineAt"); set(v) { more.put("offlineAt", v) }

    // Picture and sound.
    var nightMode: Boolean get() = more.optBoolean("nightMode"); set(v) { more.put("nightMode", v) }
    var clearSpeech: Boolean get() = more.optBoolean("clearSpeech"); set(v) { more.put("clearSpeech", v) }
    var subtitleScale: Int get() = more.optInt("subScale", 100); set(v) { more.put("subScale", v) }
    var lipSyncMs: Long get() = more.optLong("lipSync"); set(v) { more.put("lipSync", v) }
    var openSubtitlesKey: String? get() = moreStr("osKey", "").ifBlank { null }; set(v) { more.put("osKey", v ?: "") }
    // Recordings.
    /** Series links: "channelId<TAB>title" – every showing records. */
    var seriesLinks: List<String> get() = moreList("seriesLinks"); set(v) { more.put("seriesLinks", JSONArray(v)) }
    /** Finished recordings older than this many days are deleted (0 = keep them). */
    var keepRecordingsDays: Int get() = more.optInt("keepDays"); set(v) { more.put("keepDays", v) }
    /** Where recordings go (a network drive is fine); blank = Videos\Flicker. */
    var recordFolder: String get() = moreStr("recFolder", ""); set(v) { more.put("recFolder", v) }

    /** Plex libraries kids may see ("machineId/key"), from the TV. */
    var plexKidsSections: Set<String> get() = moreSet("plexKids"); set(v) { more.put("plexKids", JSONArray(v.toList())) }

    /** Artwork screensaver after this many idle minutes with nothing playing (0 = off). */
    var screensaverMin: Int get() = more.optInt("saver", 10); set(v) { more.put("saver", v) }

    // Home and recommendations.
    fun tasteOf(profile: String): String? = moreStr("taste_$profile", "").ifBlank { null }
    fun saveTaste(profile: String, json: String) { more.put("taste_$profile", json) }
    /** Home rows in your order; hidden ones start with "-". Empty = the standard order. */
    var homeRows: List<String> get() = moreList("homeRows"); set(v) { more.put("homeRows", JSONArray(v)) }
    var lastFilm: String get() = moreStr("lastFilm", ""); set(v) { more.put("lastFilm", v) }

    /** Each profile's sound language, subtitle language and audio description: "en|en|1". */
    fun languagesOf(profile: String): Triple<String?, String?, Boolean> {
        val p = moreStr("lang_$profile", "").split('|')
        return Triple(p.getOrNull(0)?.ifBlank { null }, p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2) == "1")
    }
    fun saveLanguages(profile: String, audio: String?, subs: String?, describe: Boolean) {
        more.put("lang_$profile", "${audio ?: ""}|${subs ?: ""}|${if (describe) 1 else 0}")
    }

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

    fun load() {
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
            recents = o.optJSONArray("recents")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
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
            linkKey = o.optString("linkKey").ifBlank { null }
            pin = o.optString("pin").ifBlank { null }
            usage = o.optJSONObject("usage")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } } ?: emptyMap()
            teams = o.optJSONArray("teams")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            espnTeams = o.optString("espnTeams", "{}")
            downloads = o.optJSONArray("downloads")?.let { a -> (0 until a.length()).map { PcDownload.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            o.optJSONObject("window")?.let { w ->
                windowX = w.optInt("x", -1)
                windowY = w.optInt("y", -1)
                windowW = w.optInt("w", 1400)
                windowH = w.optInt("h", 860)
                windowMaximised = w.optBoolean("max")
            }
            keepRunning = o.optBoolean("keepRunning", true)
            startWithWindows = o.optBoolean("startWithWindows")
            showNumbers = o.optBoolean("numbers", true)
            deinterlace = o.optBoolean("deinterlace", true)
            normalise = o.optBoolean("normalise")
            theme = o.optString("theme", "Evening")
            textScale = o.optDouble("textScale", 1.0).toFloat()
            notifications = o.optBoolean("notify", true)
            intros = o.optJSONObject("intros")?.let { d -> d.keys().asSequence().associateWith { d.optLong(it) } } ?: emptyMap()
            profilesRaw = o.optString("profilesRaw", "[]")
            profilesAt = o.optLong("profilesAt")
            more = o.optJSONObject("more") ?: JSONObject()
            recordings = o.optJSONArray("recordings")?.let { a -> (0 until a.length()).map { PcRecording.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            lastUpdateCheck = o.optLong("updateCheck")
            reminders = o.optJSONArray("reminders")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { r -> Reminder(r.optString("c"), r.optString("cn"), r.optString("t"), r.optLong("s")) }
            } ?: emptyList()
        }
    }

    fun profilesFromJson(arr: JSONArray): List<PcProfile> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifBlank { return@mapNotNull null }
        val groups = o.optJSONArray("groups")
        PcProfile(
            id, o.optString("name"), o.optBoolean("kids"), groups?.let { g -> (0 until g.length()).map { g.getString(it) }.toSet() } ?: emptySet(),
            o.optInt("limit"), o.optInt("bedtime", -1), o.optInt("colour", -1), o.optString("icon"),
        )
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
            .put("last", lastChannel ?: "").put("recents", JSONArray(recents)).put("volume", volume)
            .put("gRefresh", googleRefresh ?: "").put("gEmail", googleEmail ?: "").put("gFile", driveFileId ?: "")
            .put("tvPlaylists", useTvPlaylists)
            .put("profiles", JSONArray().apply {
                profiles.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("kids", p.kids).put("groups", JSONArray(p.groups.toList())).put("limit", p.limitMinutes).put("bedtime", p.bedtime).put("colour", p.colour).put("icon", p.icon))
                }
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
            .put("linkKey", linkKey ?: "")
            .put("pin", pin ?: "")
            .put("usage", JSONObject(usage))
            .put("teams", JSONArray(teams)).put("espnTeams", espnTeams)
            .put("downloads", JSONArray().apply { downloads.forEach { put(it.toJson()) } })
            .put("window", JSONObject().put("x", windowX).put("y", windowY).put("w", windowW).put("h", windowH).put("max", windowMaximised))
            .put("keepRunning", keepRunning).put("startWithWindows", startWithWindows).put("numbers", showNumbers).put("deinterlace", deinterlace)
            .put("normalise", normalise).put("theme", theme).put("textScale", textScale.toDouble()).put("notify", notifications)
            .put("intros", JSONObject(intros)).put("profilesRaw", profilesRaw).put("profilesAt", profilesAt).put("more", more)
            .put("recordings", JSONArray().apply { recordings.forEach { put(it.toJson()) } })
            .put("updateCheck", lastUpdateCheck)
            .put("reminders", JSONArray().apply { reminders.forEach { put(JSONObject().put("c", it.channelId).put("cn", it.channelName).put("t", it.title).put("s", it.start)) } })
        val tmp = File(dir, "settings.json.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

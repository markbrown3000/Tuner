package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.integrations.GoogleDrive
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Sync with Flicker on your TV and phone through the small file in your own Google Drive (the
 * same one the TV uses). The PC is one more device in that file: it takes the TV's playlists,
 * profiles, favourites, My list and Continue watching, and adds its own changes back.
 */
class Sync(private val config: Config) {
    val drive = GoogleDrive(Keys.GOOGLE_CLIENT_ID, Keys.GOOGLE_CLIENT_SECRET)
    val configured: Boolean get() = drive.configured
    val signedIn: Boolean get() = config.googleRefresh != null

    private var access: String? = null
    private var accessUntil = 0L

    /** Blocking: a fresh access token. */
    fun accessToken(): String {
        val a = access
        if (a != null && System.currentTimeMillis() < accessUntil - 60_000L) return a
        val refresh = config.googleRefresh ?: throw IllegalStateException("Not signed in")
        val t = drive.refresh(refresh)
        access = t.access
        accessUntil = t.expiresAt
        t.refresh?.let { config.googleRefresh = it }
        return t.access
    }

    /** What changed, so the screens can reload. */
    class Result(val playlistsChanged: Boolean, val listsChanged: Boolean, val devices: List<String>, val linkKeyChanged: Boolean = false)

    private fun deviceName(): String =
        "PC – " + (runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getenv("COMPUTERNAME") ?: "Windows")

    /** Ours, for the TV and phone to pull (no playlists or profiles: those come from the TV). */
    fun snapshot(): JSONObject {
        val favs = JSONObject()
        for ((k, v) in config.favourites) favs.put(k, JSONArray(v))
        val sections = JSONObject()
            .put("favourites", JSONObject().put("at", config.favouritesAt).put("data", favs))
            .put("watchlist", JSONObject().put("at", config.watchlistAt).put("data", config.watchlistJson))
            .put("followed", JSONObject().put("at", config.followedAt).put("data", config.followedJson))
            .put("picks", JSONObject().put("at", config.familyPicksAt).put("data", config.familyPicksJson))
            .put("resume", JSONObject().put("data", JSONArray().apply { config.resume.values.forEach { put(it.toJson()) } }))
            .put("done", JSONObject().apply { config.done.entries.sortedByDescending { it.value }.take(200).forEach { (k, v) -> put(k, v.toString()) } })
        // Profiles changed on this PC go back to the TV (the newest change wins there).
        if (config.profilesAt > 0) {
            val list = runCatching { JSONArray(config.profilesRaw) }.getOrDefault(JSONArray())
            if (list.length() > 0) sections.put("profiles", JSONObject().put("at", config.profilesAt).put("list", list).put("gone", JSONObject()))
        }
        return JSONObject().put("app", "Flicker").put("v", 1).put("sections", sections)
    }

    fun deviceLabel(): String = deviceName()

    /** Read the file, take what's newer from the TV and phone, write ours back. Blocking. */
    fun syncNow(): Result {
        val token = accessToken()
        val found = drive.read(token)
        val root = runCatching { JSONObject(found?.second ?: "{}") }.getOrDefault(JSONObject())
        val all = root.optJSONObject("devices") ?: JSONObject()
        val me = "pc-" + config.deviceId
        var playlistsChanged = false
        var listsChanged = false
        var linkKeyChanged = false
        var teamsAt = -1L
        val names = ArrayList<String>()

        // The TV whose playlists we use: the most recently seen one that has some.
        var bestSources: JSONArray? = null
        var bestSourcesAt = 0L
        var bestProfiles: JSONArray? = null
        var bestProfilesAt = -1L

        for (id in all.keys().asSequence().toList()) {
            if (id == me) continue
            val d = all.optJSONObject(id) ?: continue
            val at = d.optLong("at")
            names += d.optString("name")
            d.optString("pin").takeIf { it.isNotBlank() && it != "null" }?.let { config.pin = it }
            // The house's linked-devices key (phone remote and "Watch on…").
            d.optString("link_key").takeIf { it.isNotBlank() && it != "null" }?.let { k ->
                if (k != config.linkKey) {
                    config.linkKey = k
                    linkKeyChanged = true
                }
            }
            d.optJSONArray("sources")?.let { s ->
                if (s.length() > 0 && !d.optBoolean("phone") && at > bestSourcesAt) {
                    bestSources = s
                    bestSourcesAt = at
                }
            }
            val sections = d.optJSONObject("snapshot")?.optJSONObject("sections") ?: continue

            sections.optJSONObject("intros")?.let { o ->
                val merged = HashMap(config.intros)
                for (k in o.keys()) o.optString(k).toLongOrNull()?.let { merged[k] = it }
                config.intros = merged
            }
            sections.optJSONObject("profiles")?.let { p ->
                p.optJSONArray("plex_kids")?.let { a -> if (a.length() > 0) config.plexKidsSections = (0 until a.length()).map { a.getString(it) }.toSet() }
                val stamp = p.optLong("at")
                if (stamp > bestProfilesAt && stamp >= config.profilesAt) {
                    bestProfilesAt = stamp
                    bestProfiles = p.optJSONArray("list")
                }
            }
            // Favourites, My list and followed series: the newest change wins.
            sections.optJSONObject("favourites")?.let { r ->
                val stamp = r.optLong("at")
                val data = r.optJSONObject("data") ?: JSONObject()
                if (stamp > config.favouritesAt || (config.favouritesAt == 0L && config.favourites.values.all { it.isEmpty() } && data.length() > 0)) {
                    config.favourites = data.keys().asSequence().associateWith { k ->
                        data.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    }
                    config.favouritesAt = stamp
                    listsChanged = true
                }
            }
            sections.optJSONObject("watchlist")?.let { r ->
                val stamp = r.optLong("at")
                val data = r.optString("data", "[]")
                if ((stamp > config.watchlistAt || (config.watchlistAt == 0L && config.watchlistJson == "[]")) && data != config.watchlistJson && data != "[]") {
                    config.watchlistJson = data
                    config.watchlistAt = stamp
                    listsChanged = true
                }
            }
            sections.optJSONObject("picks")?.let { r ->
                val stamp = r.optLong("at")
                val data = r.optString("data", "[]")
                if (stamp > config.familyPicksAt && data != config.familyPicksJson) {
                    config.familyPicksJson = data
                    config.familyPicksAt = stamp
                    listsChanged = true
                }
            }
            sections.optJSONObject("followed")?.let { r ->
                val stamp = r.optLong("at")
                val data = r.optString("data", "[]")
                if ((stamp > config.followedAt || (config.followedAt == 0L && config.followedJson == "[]")) && data != config.followedJson && data != "[]") {
                    config.followedJson = data
                    config.followedAt = stamp
                    listsChanged = true
                }
            }
            // Finished elsewhere, and Continue watching: newest entry for each film or episode.
            sections.optJSONObject("done")?.let { o ->
                val merged = HashMap(config.done)
                for (k in o.keys()) {
                    val t = o.optString(k).toLongOrNull() ?: o.optLong(k)
                    if ((merged[k] ?: 0L) < t) merged[k] = t
                }
                if (merged != config.done) {
                    config.done = merged
                    listsChanged = true
                }
            }
            sections.optJSONObject("resume")?.optJSONArray("data")?.let { a ->
                val merged = HashMap(config.resume)
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i)?.let { Resume.fromJson(it) } ?: continue
                    if ((merged[e.id]?.at ?: 0L) < e.at) merged[e.id] = e
                }
                if (merged.keys != config.resume.keys || merged.any { (k, v) -> config.resume[k]?.at != v.at }) {
                    config.resume = merged
                    listsChanged = true
                }
            }
            // Your football teams: the newest choice wins.
            sections.optJSONObject("teams")?.let { r ->
                val stamp = r.optLong("at")
                if (stamp >= teamsAt) {
                    teamsAt = stamp
                    val names = r.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    if (names.isNotEmpty()) {
                        config.teams = names
                        r.optJSONObject("espn")?.let { config.espnTeams = it.toString() }
                    }
                }
            }
            // Each profile's YouTube sign-in (read-only), by profile name.
            sections.optJSONObject("youtube")?.let { y ->
                val tokens = HashMap(config.youtubeTokens)
                for (k in y.keys()) y.optJSONObject(k)?.optString("tok")?.takeIf { it.isNotBlank() && it != "null" }?.let { tokens[k] = it }
                config.youtubeTokens = tokens
            }
        }
        // Finished somewhere: off Continue watching.
        val done = config.done
        config.resume = config.resume.filter { (id, e) -> (done[id] ?: 0L) < e.at }

        bestProfiles?.let { arr ->
            val list = config.profilesFromJson(arr)
            if (list.isNotEmpty()) {
                config.profiles = list
                config.profilesRaw = arr.toString()
                config.profilesAt = maxOf(config.profilesAt, bestProfilesAt)
                if (config.activeProfile != null && list.none { it.id == config.activeProfile }) config.activeProfile = null
            }
        }
        bestSources?.let { arr ->
            if (config.useTvPlaylists) {
                val theirs = config.sourcesFromJson(arr)
                if (theirs.isNotEmpty() && theirs.map { it.toString() } != config.sources.map { it.toString() }) {
                    config.sources = theirs
                    playlistsChanged = true
                }
            }
        }

        all.put(
            me,
            JSONObject().put("name", deviceName()).put("house", "Home").put("phone", false).put("pc", true)
                .put("at", System.currentTimeMillis()).put("snapshot", snapshot()),
        )
        root.put("app", "Flicker").put("v", 1).put("devices", all)
        config.driveFileId = drive.write(token, found?.first ?: config.driveFileId, root.toString())
        config.lastSync = System.currentTimeMillis()
        config.save()
        return Result(playlistsChanged, listsChanged, names, linkKeyChanged)
    }
}

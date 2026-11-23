package com.titanejstudios.tuner.integrations

import com.titanejstudios.tuner.data.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Plex: link your account with a 4-character code at plex.tv/link, then Tuner shows your
 * "Continue watching" (On Deck) and can play it, telling Plex how far you got.
 * Everything here blocks – call it off the main thread.
 */
class Plex(private val clientId: String) {
    class Pin(val id: Long, val code: String)
    class Server(val name: String, val machineId: String, val uri: String, val token: String)
    class Item(
        val ratingKey: String,
        val title: String,
        val subtitle: String?,
        val image: String?,
        val playUrl: String?,
        val viewOffsetMs: Long,
        val durationMs: Long,
        val server: Server,
    )

    private val PAGE = 200

    private fun open(url: String, method: String = "GET", token: String? = null, timeoutMs: Int = 10_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Client-Identifier", clientId)
            setRequestProperty("X-Plex-Product", "Tuner")
            setRequestProperty("X-Plex-Version", "5.2")
            setRequestProperty("X-Plex-Platform", "Android")
            setRequestProperty("X-Plex-Device", "Android TV")
            setRequestProperty("X-Plex-Device-Name", "Tuner")
            token?.let { setRequestProperty("X-Plex-Token", it) }
        }

    private fun read(conn: HttpURLConnection): String = try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("Plex said $code")
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
        conn.disconnect()
    }

    /** Step 1: get a code to type in at plex.tv/link. */
    fun createPin(): Pin {
        val o = JSONObject(read(open("https://plex.tv/api/v2/pins?strong=false", "POST")))
        return Pin(o.getLong("id"), o.getString("code"))
    }

    /** Step 2: ask until the code has been entered – returns the account token then, otherwise null. */
    fun checkPin(pin: Pin): String? {
        val o = JSONObject(read(open("https://plex.tv/api/v2/pins/${pin.id}")))
        return o.optString("authToken").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Your Plex servers, each with the first address that answers (home network first). */
    fun servers(token: String): List<Server> {
        val arr = JSONArray(read(open("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token = token)))
        val out = ArrayList<Server>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optString("provides").contains("server")) continue
            val serverToken = r.optString("accessToken").ifBlank { token }
            val conns = r.optJSONArray("connections") ?: continue
            val list = (0 until conns.length()).map { conns.getJSONObject(it) }
                .sortedBy { (if (it.optBoolean("local")) 0 else 1) + (if (it.optBoolean("relay")) 2 else 0) }
            for (c in list) {
                val uri = c.optString("uri").trimEnd('/')
                if (uri.isBlank()) continue
                val ok = runCatching {
                    val conn = open("$uri/identity", token = serverToken, timeoutMs = 3_000)
                    try { conn.responseCode in 200..299 } finally { conn.disconnect() }
                }.getOrDefault(false)
                if (ok) {
                    out += Server(r.optString("name", "Plex"), r.optString("clientIdentifier"), uri, serverToken)
                    break
                }
            }
        }
        return out
    }

    /** "Continue watching" from every server, newest first. */
    fun onDeck(servers: List<Server>): List<Item> {
        val out = ArrayList<Triple<Long, String, Item>>()
        for (s in servers) {
            try {
                val root = JSONObject(read(open("${s.uri}/library/onDeck", token = s.token)))
                val md = root.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: continue
                for (i in 0 until md.length()) {
                    val m = md.getJSONObject(i)
                    val episode = m.optString("type") == "episode"
                    val title = if (episode) m.optString("grandparentTitle") else m.optString("title")
                    val sub = if (episode) "S${m.optInt("parentIndex")} E${m.optInt("index")}  ${m.optString("title")}" else m.optString("year").takeIf { it.isNotBlank() && it != "0" }
                    val thumb = m.optString("art").ifBlank { m.optString("thumb") }.ifBlank { m.optString("grandparentThumb") }
                    val image = thumb.takeIf { it.isNotBlank() }?.let {
                        "${s.uri}/photo/:/transcode?width=480&height=270&minSize=1&upscale=1&url=" +
                            URLEncoder.encode(it, "UTF-8") + "&X-Plex-Token=" + s.token
                    }
                    val part = m.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")
                    val playUrl = part?.takeIf { it.isNotBlank() }?.let { "${s.uri}$it?X-Plex-Token=${s.token}" }
                    // The same film can be in several libraries (Films, Films 4K…) or on two servers:
                    // Plex gives them the same guid, so keep just the one watched most recently.
                    val same = m.optString("guid").takeIf { it.isNotBlank() && !it.startsWith("local://") }
                        ?: if (episode) "$title|${m.optInt("parentIndex")}|${m.optInt("index")}".lowercase()
                        else "$title|${m.optString("year")}".lowercase()
                    out += Triple(m.optLong("lastViewedAt"), same, Item(
                        m.optString("ratingKey"), title, sub, image, playUrl, m.optLong("viewOffset"), m.optLong("duration"), s,
                    ))
                }
            } catch (e: Exception) {
                AppLog.warn("Plex: couldn't read Continue watching from ${s.name}", e)
            }
        }
        return out.sortedByDescending { it.first }.distinctBy { it.second }.map { it.third }
    }

    // ---------------- Library browsing ----------------

    /** One Plex library ("Films", "TV Programmes"…) on one server. */
    class Section(val key: String, val title: String, val type: String, val server: Server) {
        val id: String get() = server.machineId + "/" + key
        val isShows: Boolean get() = type == "show"
    }

    /** A film, a show or an episode in a library. */
    class Entry(
        val ratingKey: String,
        val title: String,
        val year: String?,
        /** Poster for films and shows, a still for episodes. */
        val image: String?,
        val type: String,
        val playUrl: String?,
        val viewOffsetMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        val summary: String?,
        val rating: String?,
        val season: Int,
        val episode: Int,
        /** Shows: how many episodes, and how many you've watched. */
        val leafCount: Int,
        val viewedLeafCount: Int,
        val server: Server,
    ) {
        val isShow: Boolean get() = type == "show"

        /** For the player (which plays Plex.Item). */
        fun toItem(showTitle: String? = null): Item = Item(
            ratingKey,
            if (type == "episode" && showTitle != null) showTitle else title,
            if (type == "episode") "S$season E$episode  $title" else year,
            image, playUrl, viewOffsetMs, durationMs, server,
        )
    }

    /** Film and TV libraries on every server (music and photos are left out). */
    fun sections(servers: List<Server>): List<Section> {
        val out = ArrayList<Section>()
        for (s in servers) {
            try {
                val root = JSONObject(read(open("${s.uri}/library/sections", token = s.token)))
                val dirs = root.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: continue
                for (i in 0 until dirs.length()) {
                    val d = dirs.getJSONObject(i)
                    val type = d.optString("type")
                    if (type != "movie" && type != "show") continue
                    out += Section(d.optString("key"), d.optString("title"), type, s)
                }
            } catch (e: Exception) {
                AppLog.warn("Plex: couldn't list the libraries on ${s.name}", e)
            }
        }
        return out
    }

    /**
     * Everything in a library, in Plex's own A–Z order. Fetched [PAGE] at a time: a big library
     * in one go is hundreds of MB of JSON, more than a TV app is allowed. [onPage] gets the list so far.
     */
    fun sectionItems(section: Section, sort: String? = null, title: String? = null, onPage: (List<Entry>) -> Unit = {}): List<Entry> {
        val s = section.server
        val q = listOfNotNull(
            sort?.let { "sort=" + URLEncoder.encode(it, "UTF-8") },
            title?.takeIf { it.isNotBlank() }?.let { "title=" + URLEncoder.encode(it.trim(), "UTF-8") },
        ).joinToString("&")
        val url = "${s.uri}/library/sections/${section.key}/all" + (if (q.isEmpty()) "" else "?$q")
        return paged(url, s, poster = true, onPage = onPage)
    }

    /** Every episode of a show, season by season. */
    fun episodes(show: Entry): List<Entry> {
        val s = show.server
        return paged("${s.uri}/library/metadata/${show.ratingKey}/allLeaves", s, poster = false)
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun paged(base: String, s: Server, poster: Boolean, onPage: (List<Entry>) -> Unit = {}): List<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        while (true) {
            val sep = if ('?' in base) '&' else '?'
            val url = "$base${sep}X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE"
            val conn = open(url, token = s.token, timeoutMs = 30_000).apply {
                setRequestProperty("X-Plex-Container-Start", start.toString())
                setRequestProperty("X-Plex-Container-Size", PAGE.toString())
            }
            val root = JSONObject(read(conn))
            val page = entries(root, s, poster)
            out += page
            onPage(out.toList())
            val total = root.optJSONObject("MediaContainer")?.optInt("totalSize", -1) ?: -1
            start += PAGE
            if (page.isEmpty() || page.size < PAGE && total < 0 || total in 0..start) break
            if (Thread.currentThread().isInterrupted) break
        }
        return out
    }

    private fun entries(root: JSONObject, s: Server, poster: Boolean): List<Entry> {
        val md = root.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        val out = ArrayList<Entry>(md.length())
        for (i in 0 until md.length()) {
            val m = md.getJSONObject(i)
            val thumb = m.optString("thumb").ifBlank { m.optString("parentThumb") }.ifBlank { m.optString("grandparentThumb") }
            val (w, h) = if (poster) 300 to 450 else 480 to 270
            val image = thumb.takeIf { it.isNotBlank() }?.let {
                "${s.uri}/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=" +
                    URLEncoder.encode(it, "UTF-8") + "&X-Plex-Token=" + s.token
            }
            val part = m.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")
            val playUrl = part?.takeIf { it.isNotBlank() }?.let { "${s.uri}$it?X-Plex-Token=${s.token}" }
            val rating = imdbRating(m)?.let { "IMDb " + String.format(java.util.Locale.UK, "%.1f", it) }
                ?: m.optDouble("audienceRating", m.optDouble("rating", 0.0)).takeIf { it > 0.0 }
                    ?.let { "★ " + String.format(java.util.Locale.UK, "%.1f", it) }
            out += Entry(
                ratingKey = m.optString("ratingKey"),
                title = m.optString("title"),
                year = m.optString("year").takeIf { it.isNotBlank() && it != "0" },
                image = image,
                type = m.optString("type"),
                playUrl = playUrl,
                viewOffsetMs = m.optLong("viewOffset"),
                durationMs = m.optLong("duration"),
                watched = m.optInt("viewCount") > 0,
                summary = m.optString("summary").takeIf { it.isNotBlank() }?.take(600),
                rating = rating,
                season = m.optInt("parentIndex"),
                episode = m.optInt("index"),
                leafCount = m.optInt("leafCount"),
                viewedLeafCount = m.optInt("viewedLeafCount"),
                server = s,
            )
        }
        return out
    }

    /**
     * The IMDb score, when Plex has one: either in its list of ratings, or as the main
     * audience rating when the library's ratings source is IMDb.
     */
    private fun imdbRating(m: JSONObject): Double? {
        m.optJSONArray("Rating")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optString("image").startsWith("imdb://")) r.optDouble("value", 0.0).takeIf { it > 0.0 }?.let { return it }
            }
        }
        if (m.optString("audienceRatingImage").startsWith("imdb://")) return m.optDouble("audienceRating", 0.0).takeIf { it > 0.0 }
        if (m.optString("ratingImage").startsWith("imdb://")) return m.optDouble("rating", 0.0).takeIf { it > 0.0 }
        return null
    }

    /** Tells Plex where you are, so Continue watching stays right in the Plex app too. */
    fun timeline(item: Item, state: String, positionMs: Long, durationMs: Long) {
        val url = "${item.server.uri}/:/timeline?ratingKey=${item.ratingKey}" +
            "&key=" + URLEncoder.encode("/library/metadata/${item.ratingKey}", "UTF-8") +
            "&state=$state&time=$positionMs&duration=${if (durationMs > 0) durationMs else item.durationMs}"
        runCatching { read(open(url, token = item.server.token, timeoutMs = 5_000)) }
    }
}

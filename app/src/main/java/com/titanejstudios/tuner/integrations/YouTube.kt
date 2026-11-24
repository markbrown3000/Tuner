package com.titanejstudios.tuner.integrations

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The YouTube Data API: searching, and the latest uploads from the channels you subscribe to.
 * Search uses your API key (or your sign-in); subscriptions need you signed in to YouTube.
 * Videos themselves play in YouTube's own embedded player (see YouTubeScreen).
 */
object YouTube {
    class Video(
        val id: String,
        val title: String,
        val channel: String,
        val thumb: String?,
        val publishedAt: Long,
    )

    const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    const val NO_YOUTUBE = "Google didn't give Flicker the YouTube permission – the YouTube box was probably left unticked. " +
        "Sign in again and tick it (or press Select all) on Google's page."

    private const val API = "https://www.googleapis.com/youtube/v3"

    /** Videos matching [query] (only ones allowed to play in other apps). 100 of the 10,000 daily units. */
    fun search(query: String, apiKey: String?, access: String?, strict: Boolean = false): List<Video> {
        val q = URLEncoder.encode(query, "UTF-8")
        val safe = if (strict) "strict" else "moderate"
        val o = get("$API/search?part=snippet&type=video&videoEmbeddable=true&safeSearch=$safe&maxResults=30&q=$q", apiKey, access)
        val items = o.optJSONArray("items") ?: return emptyList()
        val found = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val id = it.optJSONObject("id")?.optString("videoId")?.takeIf { v -> v.isNotBlank() } ?: return@mapNotNull null
            video(id, it.optJSONObject("snippet"))
        }
        return withoutShorts(found, apiKey, access)
    }

    /**
     * Drops YouTube Shorts: upright videos of 3 minutes or less (and anything titled #shorts).
     * One unit per 50 videos. If YouTube can't say, everything's kept.
     */
    fun withoutShorts(list: List<Video>, apiKey: String?, access: String?): List<Video> {
        val tagged = list.filterNot { "#short" in it.title.lowercase() }
        val shorts = HashSet<String>()
        for (batch in tagged.chunked(50)) {
            val o = runCatching {
                get("$API/videos?part=contentDetails,player&maxWidth=480&id=" + batch.joinToString(",") { it.id }, apiKey, access)
            }.getOrNull() ?: continue
            val items = o.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val v = items.optJSONObject(i) ?: continue
                val seconds = seconds(v.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                val player = v.optJSONObject("player")
                val w = player?.optInt("embedWidth") ?: 0
                val h = player?.optInt("embedHeight") ?: 0
                if (seconds in 1..180 && h > w && w > 0) shorts += v.optString("id")
            }
        }
        return tagged.filterNot { it.id in shorts }
    }

    /** "PT1H2M3S" → 3723. */
    private fun seconds(iso: String): Int {
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(iso) ?: return 0
        val (d, h, mi, s) = m.destructured
        return (d.toIntOrNull() ?: 0) * 86_400 + (h.toIntOrNull() ?: 0) * 3_600 + (mi.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    /** The newest uploads from every channel you subscribe to, newest first. About 1 unit per channel. */
    fun subscriptionsFeed(access: String, perChannel: Int = 3, channels: Int = 40): List<Video> {
        val subs = ArrayList<String>()
        var page: String? = null
        do {
            val o = get("$API/subscriptions?part=snippet&mine=true&maxResults=50&order=relevance" + (page?.let { "&pageToken=$it" } ?: ""), null, access)
            o.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("channelId")
                        ?.takeIf { it.startsWith("UC") }?.let { subs += it }
                }
            }
            page = o.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && subs.size < channels)
        return uploads(subs.take(channels), perChannel, null, access)
    }

    /** A YouTube channel (for a grown-up to approve for a kids' profile). */
    class Channel(val id: String, val title: String, val thumb: String?)

    /** Channels matching [query] (strict SafeSearch). 100 units. */
    fun searchChannels(query: String, apiKey: String?, access: String?): List<Channel> {
        val q = URLEncoder.encode(query, "UTF-8")
        val o = get("$API/search?part=snippet&type=channel&safeSearch=strict&maxResults=20&q=$q", apiKey, access)
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val id = it.optJSONObject("id")?.optString("channelId")?.takeIf { v -> v.startsWith("UC") } ?: return@mapNotNull null
            val sn = it.optJSONObject("snippet")
            val thumbs = sn?.optJSONObject("thumbnails")
            val thumb = listOf("medium", "high", "default").firstNotNullOfOrNull { k -> thumbs?.optJSONObject(k)?.optString("url")?.takeIf { u -> u.isNotBlank() } }
            Channel(id, unescape(sn?.optString("channelTitle").orEmpty().ifBlank { sn?.optString("title").orEmpty() }), thumb)
        }
    }

    /** The newest normal videos (no Shorts) from [channelIds], newest first. About 1 unit per channel. */
    fun uploads(channelIds: List<String>, perChannel: Int, apiKey: String?, access: String?): List<Video> {
        val out = ArrayList<Video>()
        var maybeShorts = false
        for (ch in channelIds) {
            // A channel's uploads playlist is its id with "UC" swapped for "UU"; "UULF" is the same
            // without Shorts (or live streams). Not every channel has the second: then sort them out after.
            val o = runCatching { get("$API/playlistItems?part=snippet&maxResults=$perChannel&playlistId=UULF${ch.drop(2)}", apiKey, access) }.getOrNull()
                ?: runCatching { get("$API/playlistItems?part=snippet&maxResults=$perChannel&playlistId=UU${ch.drop(2)}", apiKey, access) }.getOrNull()
                    ?.also { maybeShorts = true }
                ?: continue
            val items = o.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("videoId")?.takeIf { it.isNotBlank() } ?: continue
                video(id, sn)?.let { out += it }
            }
        }
        val all = out.distinctBy { it.id }.sortedByDescending { it.publishedAt }
        return if (maybeShorts) withoutShorts(all, apiKey, access) else all.filterNot { "#short" in it.title.lowercase() }
    }

    fun toJson(list: List<Video>): String {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("c", it.channel).put("img", it.thumb).put("at", it.publishedAt)) }
        return a.toString()
    }

    fun fromJson(json: String?): List<Video> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Video(o.getString("id"), o.optString("t"), o.optString("c"), o.optString("img").takeIf { it.isNotBlank() && it != "null" }, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun video(id: String, sn: JSONObject?): Video? {
        sn ?: return null
        val title = unescape(sn.optString("title"))
        if (title == "Private video" || title == "Deleted video") return null
        val thumbs = sn.optJSONObject("thumbnails")
        val thumb = listOf("high", "medium", "standard", "default").firstNotNullOfOrNull { k -> thumbs?.optJSONObject(k)?.optString("url")?.takeIf { it.isNotBlank() } }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val at = runCatching { iso.parse(sn.optString("publishedAt").take(19))?.time }.getOrNull() ?: 0L
        return Video(id, title, unescape(sn.optString("channelTitle")), thumb, at)
    }

    /** Search titles come back with &amp; and &#39; in them. */
    private fun unescape(s: String): String = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private fun get(url: String, apiKey: String?, access: String?): JSONObject {
        val full = if (access == null && apiKey != null) "$url&key=$apiKey" else url
        val c = (URL(full).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            access?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code == 401) throw GoogleDrive.Unauthorised()
            if (code == 403 && "insufficient" in text.lowercase()) throw IllegalStateException(NO_YOUTUBE)
            if (code == 403 && "quota" in text.lowercase()) throw IllegalStateException("YouTube's daily limit is used up – it resets overnight (US time)")
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: "YouTube said $code"
                throw IllegalStateException(msg)
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** "3 days ago" for a video's upload time. */
    fun age(t: Long, now: Long = System.currentTimeMillis()): String {
        if (t <= 0) return ""
        val mins = (now - t) / 60_000
        return when {
            mins < 60 -> "${mins.coerceAtLeast(1)} min ago"
            mins < 24 * 60 -> "${mins / 60} hours ago"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)} days ago"
            mins < 60 * 24 * 60 -> "${mins / (7 * 24 * 60)} weeks ago"
            else -> "${mins / (30 * 24 * 60)} months ago"
        }
    }
}

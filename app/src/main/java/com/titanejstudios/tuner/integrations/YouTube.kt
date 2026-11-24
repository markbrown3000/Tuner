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
        return (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val id = it.optJSONObject("id")?.optString("videoId")?.takeIf { v -> v.isNotBlank() } ?: return@mapNotNull null
            video(id, it.optJSONObject("snippet"))
        }
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
        val out = ArrayList<Video>()
        for (ch in subs.take(channels)) {
            // A channel's uploads playlist is its id with "UC" swapped for "UU".
            val o = runCatching { get("$API/playlistItems?part=snippet&maxResults=$perChannel&playlistId=UU${ch.drop(2)}", null, access) }.getOrNull() ?: continue
            val items = o.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("videoId")?.takeIf { it.isNotBlank() } ?: continue
                video(id, sn)?.let { out += it }
            }
        }
        return out.distinctBy { it.id }.sortedByDescending { it.publishedAt }
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

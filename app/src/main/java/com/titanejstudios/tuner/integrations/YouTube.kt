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
        /** The uploader's channel (for "more from channels you watch"). */
        val channelId: String? = null,
        /** Length in seconds (0 = not known yet). */
        val seconds: Int = 0,
        /** "live" or "upcoming" (a premiere or scheduled stream), else null. */
        val live: String? = null,
    ) {
        fun with(seconds: Int, live: String?) = Video(id, title, channel, thumb, publishedAt, channelId, seconds, live)
    }

    /** One of your own playlists. */
    class Playlist(val id: String, val title: String, val thumb: String?, val count: Int)

    /** A chapter in a video (from the timestamps in its description). */
    class Chapter(val start: Float, val title: String)

    /** A part of a video SponsorBlock says can be skipped ("sponsor", "selfpromo", "interaction"). */
    class Segment(val start: Float, val end: Float, val category: String)

    const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    const val NO_YOUTUBE = "Google didn't give Flicker the YouTube permission – the YouTube box was probably left unticked. " +
        "Sign in again and tick it (or press Select all) on Google's page."

    private const val API = "https://www.googleapis.com/youtube/v3"

    /**
     * Videos matching [query] (only ones allowed to play in other apps). 100 of the 10,000 daily units.
     * [filter]: "" any, "long" over 20 minutes, "live" live now, "week" from the last 7 days.
     */
    fun search(query: String, apiKey: String?, access: String?, strict: Boolean = false, filter: String = "", channelId: String? = null, order: String? = null): List<Video> {
        val q = URLEncoder.encode(query, "UTF-8")
        val safe = if (strict) "strict" else "moderate"
        val extra = when (filter) {
            "long" -> "&videoDuration=long"
            "live" -> "&eventType=live"
            "week" -> "&publishedAfter=" + URLEncoder.encode(isoTime(System.currentTimeMillis() - 7 * 86_400_000L), "UTF-8")
            else -> ""
        } + (channelId?.let { "&channelId=$it" } ?: "") + (order?.let { "&order=$it" } ?: "")
        val o = get("$API/search?part=snippet&type=video&videoEmbeddable=true&safeSearch=$safe&maxResults=30&q=$q$extra", apiKey, access)
        val items = o.optJSONArray("items") ?: return emptyList()
        val found = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val id = it.optJSONObject("id")?.optString("videoId")?.takeIf { v -> v.isNotBlank() } ?: return@mapNotNull null
            video(id, it.optJSONObject("snippet"))
        }
        return withoutShorts(found.distinctBy { it.id }, apiKey, access)
    }

    /**
     * Drops YouTube Shorts: upright videos of 3 minutes or less (and anything titled #shorts).
     * One unit per 50 videos. If YouTube can't say, everything's kept.
     */
    fun withoutShorts(list: List<Video>, apiKey: String?, access: String?): List<Video> {
        val tagged = list.filterNot { "#short" in it.title.lowercase() }
        val shorts = HashSet<String>()
        val info = HashMap<String, Pair<Int, String?>>()
        for (batch in tagged.chunked(50)) {
            val o = runCatching {
                get("$API/videos?part=contentDetails,player,snippet&maxWidth=480&id=" + batch.joinToString(",") { it.id }, apiKey, access)
            }.getOrNull() ?: continue
            val items = o.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val v = items.optJSONObject(i) ?: continue
                val seconds = seconds(v.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                val player = v.optJSONObject("player")
                val w = player?.optInt("embedWidth") ?: 0
                val h = player?.optInt("embedHeight") ?: 0
                if (seconds in 1..180 && h > w && w > 0) shorts += v.optString("id")
                val live = v.optJSONObject("snippet")?.optString("liveBroadcastContent")?.takeIf { it == "live" || it == "upcoming" }
                info[v.optString("id")] = seconds to live
            }
        }
        // Lengths and LIVE badges for the tiles, while we're asking.
        return tagged.filterNot { it.id in shorts }.map { v -> info[v.id]?.let { (sec, live) -> v.with(sec, live) } ?: v }
    }

    /** Videos you've liked (signed in). 1 unit. */
    fun liked(access: String): List<Video> {
        val o = get("$API/videos?part=snippet,contentDetails&myRating=like&maxResults=50", null, access)
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val id = it.optString("id").takeIf { v -> v.isNotBlank() } ?: return@mapNotNull null
            video(id, it.optJSONObject("snippet"))?.with(seconds(it.optJSONObject("contentDetails")?.optString("duration").orEmpty()), null)
        }.filterNot { it.seconds in 1..60 }.distinctBy { it.id }
    }

    /** Your own playlists (signed in). 1 unit. */
    fun myPlaylists(access: String): List<Playlist> {
        val o = get("$API/playlists?part=snippet,contentDetails&mine=true&maxResults=50", null, access)
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val sn = it.optJSONObject("snippet") ?: return@mapNotNull null
            val thumbs = sn.optJSONObject("thumbnails")
            val thumb = listOf("high", "medium", "standard", "default").firstNotNullOfOrNull { k -> thumbs?.optJSONObject(k)?.optString("url")?.takeIf { u -> u.isNotBlank() } }
            Playlist(it.optString("id"), unescape(sn.optString("title")), thumb, it.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0)
        }.filter { it.id.isNotBlank() && it.count > 0 }
    }

    /** The videos in a playlist, in its order (up to 50). */
    fun playlistVideos(playlistId: String, apiKey: String?, access: String?): List<Video> {
        val o = get("$API/playlistItems?part=snippet&maxResults=50&playlistId=$playlistId", apiKey, access)
        val items = o.optJSONArray("items") ?: return emptyList()
        val list = (0 until items.length()).mapNotNull { i ->
            val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: return@mapNotNull null
            val id = sn.optJSONObject("resourceId")?.optString("videoId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            video(id, sn)
        }
        return withoutShorts(list.distinctBy { it.id }, apiKey, access)
    }

    /** A channel's videos: newest (about 1 unit) or most popular (a search, 100 units). */
    fun channelVideos(channelId: String, popular: Boolean, apiKey: String?, access: String?, strict: Boolean): List<Video> =
        if (popular) search("", apiKey, access, strict, channelId = channelId, order = "viewCount")
        else uploads(listOf(channelId), 30, apiKey, access)

    /** One video's details: its description (for chapters) and uploader. 1 unit. */
    fun videoInfo(id: String, apiKey: String?, access: String?): Pair<Video?, String> {
        val o = get("$API/videos?part=snippet,contentDetails&id=$id", apiKey, access)
        val it = o.optJSONArray("items")?.optJSONObject(0) ?: return null to ""
        val sn = it.optJSONObject("snippet")
        val v = video(id, sn)?.with(seconds(it.optJSONObject("contentDetails")?.optString("duration").orEmpty()), sn?.optString("liveBroadcastContent")?.takeIf { l -> l == "live" || l == "upcoming" })
        return v to (sn?.optString("description") ?: "")
    }

    private val chapterLine = Regex("^\\s*[\\[(]?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})[\\])]?\\s*[-–—:|]?\\s*(.+)$")

    /** Chapters from the "0:00 Intro / 2:15 …" lines in a description (YouTube's own rules: starts at 0:00, at least 3). */
    fun chapters(description: String): List<Chapter> {
        val out = ArrayList<Chapter>()
        for (line in description.lines()) {
            val m = chapterLine.find(line.trim()) ?: continue
            val parts = m.groupValues[1].split(':').map { it.toIntOrNull() ?: 0 }
            val t = parts.fold(0) { acc, p -> acc * 60 + p }.toFloat()
            val title = m.groupValues[2].trim().trimStart('-', '–', ':', '|').trim()
            if (title.isEmpty()) continue
            if (out.isNotEmpty() && t <= out.last().start) continue
            out += Chapter(t, title)
        }
        return if (out.size >= 3 && out.first().start == 0f) out else emptyList()
    }

    /** SponsorBlock (sponsor.ajay.app, free and public): sponsor reads, self-promotion and "please subscribe" bits. */
    fun sponsorSegments(id: String): List<Segment> {
        val cats = URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"interaction\"]", "UTF-8")
        val c = (URL("https://sponsor.ajay.app/api/skipSegments?videoID=$id&categories=$cats").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
        }
        try {
            if (c.responseCode != 200) return emptyList()
            val a = JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val seg = o.optJSONArray("segment") ?: return@mapNotNull null
                val s = seg.optDouble(0).toFloat()
                val e = seg.optDouble(1).toFloat()
                if (e - s < 1f) null else Segment(s, e, o.optString("category"))
            }.sortedBy { it.start }
        } finally {
            c.disconnect()
        }
    }

    private fun isoTime(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(t))

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
        for (ch in channelIds) {
            // A channel's uploads playlist is its id with "UC" swapped for "UU"; "UULF" is the same
            // without Shorts (or live streams). Not every channel has the second: then sort them out after.
            val o = runCatching { get("$API/playlistItems?part=snippet&maxResults=$perChannel&playlistId=UULF${ch.drop(2)}", apiKey, access) }.getOrNull()
                ?: runCatching { get("$API/playlistItems?part=snippet&maxResults=$perChannel&playlistId=UU${ch.drop(2)}", apiKey, access) }.getOrNull()
                ?: continue
            val items = o.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("videoId")?.takeIf { it.isNotBlank() } ?: continue
                video(id, sn)?.let { out += it }
            }
        }
        val all = out.distinctBy { it.id }.sortedByDescending { it.publishedAt }
        // Always asked now (1 unit per 50): it also gives each video's length and LIVE badge.
        return withoutShorts(all, apiKey, access)
    }

    fun toJson(list: List<Video>): String {
        val a = JSONArray()
        list.forEach { a.put(videoJson(it)) }
        return a.toString()
    }

    fun fromJson(json: String?): List<Video> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            videoFromJson(o)
        }
    }.getOrDefault(emptyList())

    fun videoJson(v: Video): JSONObject =
        JSONObject().put("id", v.id).put("t", v.title).put("c", v.channel).put("img", v.thumb).put("at", v.publishedAt).put("cid", v.channelId)
            .put("s", v.seconds).put("live", v.live)

    fun videoFromJson(o: JSONObject): Video = Video(
        o.getString("id"), o.optString("t"), o.optString("c"),
        o.optString("img").takeIf { it.isNotBlank() && it != "null" }, o.optLong("at"),
        o.optString("cid").takeIf { it.startsWith("UC") },
        o.optInt("s"),
        o.optString("live").takeIf { it == "live" || it == "upcoming" },
    )

    private fun video(id: String, sn: JSONObject?): Video? {
        sn ?: return null
        val title = unescape(sn.optString("title"))
        if (title == "Private video" || title == "Deleted video") return null
        val thumbs = sn.optJSONObject("thumbnails")
        val thumb = listOf("high", "medium", "standard", "default").firstNotNullOfOrNull { k -> thumbs?.optJSONObject(k)?.optString("url")?.takeIf { it.isNotBlank() } }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val at = runCatching { iso.parse(sn.optString("publishedAt").take(19))?.time }.getOrNull() ?: 0L
        val channelId = sn.optString("videoOwnerChannelId").takeIf { it.startsWith("UC") } ?: sn.optString("channelId").takeIf { it.startsWith("UC") }
        val channelName = sn.optString("videoOwnerChannelTitle").ifBlank { sn.optString("channelTitle") }
        return Video(id, title, unescape(channelName), thumb, at, channelId)
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

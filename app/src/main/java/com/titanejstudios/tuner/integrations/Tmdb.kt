package com.titanejstudios.tuner.integrations

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Film and series details from The Movie Database (themoviedb.org) with your own free key:
 * proper posters, plot, cast, rating and a trailer. IPTV providers' own info is often patchy.
 */
object Tmdb {
    class Info(
        val title: String,
        val year: String?,
        val overview: String?,
        val rating: Double?,
        val runtimeMinutes: Int?,
        val poster: String?,
        val backdrop: String?,
        val genres: List<String>,
        val cast: List<String>,
        /** YouTube video id of the trailer, if there is one. */
        val trailerKey: String?,
    )

    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p/"
    private val cache = HashMap<String, Info?>()

    private val bracketTag = Regex("^\\s*[|\\[(][A-Za-z0-9 +]{1,8}[|\\])]\\s*[-:|]?\\s*")
    private val plainTag = Regex("^[A-Z]{2,3}\\s*[-:|]\\s+")
    private val yearInBrackets = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private val yearAtEnd = Regex("\\s[-–]?\\s*((?:19|20)\\d{2})$")
    private val qualityWords = Regex(
        "\\b(4k|uhd|fhd|hd|sd|hdr|hevc|h\\.?265|x265|1080p|720p|2160p|multi[- ]?subs?|multi|vostfr|vost|dubbed|subbed)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val emptyBrackets = Regex("[(\\[]\\s*[)\\]]")

    /** "EN - The Matrix (1999) 4K" → ("The Matrix", 1999). */
    fun cleanTitle(raw: String): Pair<String, Int?> {
        var s = raw.trim()
        var guard = 0
        while (guard++ < 4) {
            val before = s
            s = s.replace(bracketTag, "").replace(plainTag, "")
            if (s == before) break
        }
        var year: Int? = null
        yearInBrackets.find(s)?.let { m ->
            year = m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        s = s.replace(qualityWords, " ").replace(emptyBrackets, " ")
        if (year == null) {
            yearAtEnd.find(s.trim())?.let { m ->
                year = m.groupValues[1].toInt()
                s = s.trim().removeRange(m.range)
            }
        }
        s = s.replace(Regex("\\s+"), " ").trim().trim('-', '–', ':', '|', ' ')
        return s to year
    }

    /** Looks a film or series up by its (messy) IPTV name. Returns null when nothing matches. Blocking. */
    fun lookup(apiKey: String, name: String, isSeries: Boolean): Info? {
        val cacheKey = (if (isSeries) "tv:" else "film:") + name
        synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }
        val (title, year) = cleanTitle(name)
        if (title.isEmpty()) return null
        val kind = if (isSeries) "tv" else "movie"
        val q = URLEncoder.encode(title, "UTF-8")
        val yearParam = year?.let { if (isSeries) "&first_air_date_year=$it" else "&year=$it" } ?: ""
        var results = get(apiKey, "$API/search/$kind?query=$q$yearParam&language=en-GB").optJSONArray("results")
        // A wrong year in the name is common – try again without it.
        if ((results == null || results.length() == 0) && year != null) {
            results = get(apiKey, "$API/search/$kind?query=$q&language=en-GB").optJSONArray("results")
        }
        val id = results?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
        val info = id?.let { details(apiKey, kind, it) }
        synchronized(cache) { cache[cacheKey] = info }
        return info
    }

    private fun details(apiKey: String, kind: String, id: Long): Info {
        val o = get(apiKey, "$API/$kind/$id?append_to_response=credits,videos&language=en-GB")
        val date = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
        val runtime = o.optInt("runtime", 0).takeIf { it > 0 }
            ?: o.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(6, a.length())).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        var trailer: String? = null
        o.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i) ?: continue
                if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                    trailer = v.optString("key").takeIf { it.isNotBlank() }
                    if (trailer != null) break
                }
            }
        }
        return Info(
            title = o.optString("title").ifEmpty { o.optString("name") },
            year = date.take(4).takeIf { it.length == 4 },
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            runtimeMinutes = runtime,
            poster = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { IMAGES + "w500" + it },
            backdrop = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { IMAGES + "w1280" + it },
            genres = genres,
            cast = cast,
            trailerKey = trailer,
        )
    }

    /** Accepts either kind of TMDB key: the short "API key" or the long "read access token". */
    private fun get(apiKey: String, url: String): JSONObject {
        val key = apiKey.trim()
        val bearer = key.length > 40
        val full = if (bearer) url else url + (if ('?' in url) "&" else "?") + "api_key=" + URLEncoder.encode(key, "UTF-8")
        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (bearer) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw IOException("TMDB didn't accept the key – check it in Settings")
            if (code != 200) throw IOException("TMDB said $code")
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    fun ratingText(r: Double?): String? = r?.let { String.format(Locale.UK, "★ %.1f", it) }

    /** One film or show in this week's trending list. */
    class Trend(
        val rank: Int,
        val title: String,
        val year: String?,
        val isTv: Boolean,
        val overview: String?,
        val rating: Double?,
        val backdrop: String?,
        val poster: String?,
    )

    /** What's trending this week in films and TV (TMDB, UK region where it matters), most popular first. */
    fun trending(apiKey: String): List<Trend> {
        val o = get(apiKey, "$API/trending/all/week?language=en-GB")
        val arr = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Trend>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val type = r.optString("media_type")
            if (type != "movie" && type != "tv") continue
            val tv = type == "tv"
            val title = if (tv) r.optString("name") else r.optString("title")
            if (title.isBlank()) continue
            val date = if (tv) r.optString("first_air_date") else r.optString("release_date")
            out += Trend(
                rank = out.size + 1,
                title = title,
                year = date.take(4).takeIf { it.length == 4 },
                isTv = tv,
                overview = r.optString("overview").takeIf { it.isNotBlank() },
                rating = r.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                backdrop = r.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMAGES + "w780" + it },
                poster = r.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMAGES + "w342" + it },
            )
        }
        return out
    }
}

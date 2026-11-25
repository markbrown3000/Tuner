package com.titanejstudios.tuner.integrations

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Trakt (trakt.tv): sign in with a code (like Plex), log films and episodes you finish, and read
 * back what you've watched. Needs a Trakt app's client id and secret (GitHub secrets).
 */
class Trakt(private val clientId: String, private val clientSecret: String) {
    class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int)
    class Tokens(val access: String, val refresh: String, val expiresAt: Long)
    class Watched(val title: String, val year: String?)

    private fun call(path: String, method: String = "GET", body: JSONObject? = null, access: String? = null): Pair<Int, String> {
        val c = URL("https://api.trakt.tv$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("trakt-api-version", "2")
            c.setRequestProperty("trakt-api-key", clientId)
            c.setRequestProperty("User-Agent", "Flicker")
            access?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** Step 1: a code to type in at trakt.tv/activate. */
    fun deviceCode(): DeviceCode {
        val (code, text) = call("/oauth/device/code", "POST", JSONObject().put("client_id", clientId))
        if (code !in 200..299) throw IllegalStateException("Trakt said $code")
        val o = JSONObject(text)
        return DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"), o.optInt("interval", 5), o.optInt("expires_in", 600))
    }

    /** Step 2: the tokens once the code's been used (null while still waiting). */
    fun checkCode(d: DeviceCode): Tokens? {
        val (code, text) = call(
            "/oauth/device/token", "POST",
            JSONObject().put("code", d.deviceCode).put("client_id", clientId).put("client_secret", clientSecret),
        )
        return if (code == 200) tokens(JSONObject(text)) else null
    }

    fun refresh(refreshToken: String): Tokens {
        val (code, text) = call(
            "/oauth/token", "POST",
            JSONObject().put("refresh_token", refreshToken).put("client_id", clientId).put("client_secret", clientSecret)
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"),
        )
        if (code != 200) throw IllegalStateException("Trakt sign-in expired")
        return tokens(JSONObject(text))
    }

    private fun tokens(o: JSONObject) = Tokens(
        o.getString("access_token"), o.getString("refresh_token"),
        (o.optLong("created_at", System.currentTimeMillis() / 1000) + o.optLong("expires_in", 7_776_000)) * 1000L,
    )

    fun username(access: String): String? =
        runCatching { JSONObject(call("/users/settings", access = access).second).optJSONObject("user")?.optString("username") }.getOrNull()

    /** Logs a film as watched (matched by title and year). */
    fun addFilm(access: String, title: String, year: String?): Boolean {
        val movie = JSONObject().put("title", title).put("watched_at", iso(System.currentTimeMillis()))
        year?.toIntOrNull()?.let { movie.put("year", it) }
        return call("/sync/history", "POST", JSONObject().put("movies", JSONArray().put(movie)), access).first in 200..299
    }

    /** Logs an episode as watched (matched by the show's title). */
    fun addEpisode(access: String, show: String, season: Int, episode: Int): Boolean {
        val s = JSONObject().put("title", show).put(
            "seasons", JSONArray().put(
                JSONObject().put("number", season).put("episodes", JSONArray().put(JSONObject().put("number", episode).put("watched_at", iso(System.currentTimeMillis()))))
            )
        )
        return call("/sync/history", "POST", JSONObject().put("shows", JSONArray().put(s)), access).first in 200..299
    }

    /** Every film you've watched, as Trakt knows it. */
    fun watchedFilms(access: String): List<Watched> {
        val (code, text) = call("/sync/watched/movies", access = access)
        if (code != 200) return emptyList()
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("movie") }
            .map { Watched(it.optString("title"), it.optInt("year", 0).takeIf { y -> y > 0 }?.toString()) }
    }

    private fun iso(t: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t))
}

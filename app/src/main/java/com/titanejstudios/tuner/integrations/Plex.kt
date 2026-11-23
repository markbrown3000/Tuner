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
        val out = ArrayList<Pair<Long, Item>>()
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
                    out += m.optLong("lastViewedAt") to Item(
                        m.optString("ratingKey"), title, sub, image, playUrl, m.optLong("viewOffset"), m.optLong("duration"), s,
                    )
                }
            } catch (e: Exception) {
                AppLog.warn("Plex: couldn't read Continue watching from ${s.name}", e)
            }
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** Tells Plex where you are, so Continue watching stays right in the Plex app too. */
    fun timeline(item: Item, state: String, positionMs: Long, durationMs: Long) {
        val url = "${item.server.uri}/:/timeline?ratingKey=${item.ratingKey}" +
            "&key=" + URLEncoder.encode("/library/metadata/${item.ratingKey}", "UTF-8") +
            "&state=$state&time=$positionMs&duration=${if (durationMs > 0) durationMs else item.durationMs}"
        runCatching { read(open(url, token = item.server.token, timeoutMs = 5_000)) }
    }
}

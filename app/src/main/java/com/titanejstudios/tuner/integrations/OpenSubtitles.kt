package com.titanejstudios.tuner.integrations

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Subtitles for films and episodes from opensubtitles.com, with your own free API key
 * (opensubtitles.com › your profile › API consumers). Without logging in, the free plan
 * allows a handful of downloads a day.
 */
object OpenSubtitles {
    class Hit(val fileId: Long, val release: String, val language: String, val downloads: Int)

    private const val API = "https://api.opensubtitles.com/api/v1"
    private const val AGENT = "Tuner v5.4"

    /** "S2 E4  Title" → (2, 4). */
    fun seasonEpisode(subtitle: String?): Pair<Int, Int>? {
        val m = Regex("S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE).find(subtitle ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun search(apiKey: String, query: String, language: String, year: Int?, season: Int?, episode: Int?): List<Hit> {
        val params = StringBuilder("query=").append(URLEncoder.encode(query, "UTF-8"))
            .append("&languages=").append(URLEncoder.encode(language, "UTF-8"))
        year?.let { params.append("&year=").append(it) }
        season?.let { params.append("&season_number=").append(it) }
        episode?.let { params.append("&episode_number=").append(it) }
        val o = JSONObject(request(apiKey, "GET", "$API/subtitles?$params", null))
        val data = o.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            val id = file.optLong("file_id")
            if (id <= 0) continue
            out += Hit(
                fileId = id,
                release = a.optString("release").ifBlank { file.optString("file_name") }.ifBlank { "Subtitles" },
                language = a.optString("language").ifBlank { language },
                downloads = a.optInt("download_count"),
            )
        }
        return out.sortedByDescending { it.downloads }.take(20)
    }

    /** Downloads [hit] as an .srt file into [dir]. */
    fun download(apiKey: String, hit: Hit, dir: File): File {
        val body = JSONObject().put("file_id", hit.fileId).put("sub_format", "srt").toString()
        val o = JSONObject(request(apiKey, "POST", "$API/download", body))
        val link = o.optString("link").takeIf { it.startsWith("http") }
            ?: throw IOException(o.optString("message").ifBlank { "No download link" })
        dir.mkdirs()
        val f = File(dir, "sub_${hit.fileId}.srt")
        (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", AGENT)
        }.let { conn ->
            try {
                if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
                conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
        }
        return f
    }

    private fun request(apiKey: String, method: String, url: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Api-Key", apiKey.trim())
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code == 401 || code == 403 -> throw IOException("OpenSubtitles didn't accept the key – check it in Settings")
                code == 406 || code == 429 -> throw IOException("Today's free subtitle downloads are used up – try again tomorrow")
                code !in 200..299 -> throw IOException("OpenSubtitles said $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

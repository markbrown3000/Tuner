package com.titanejstudios.tuner.integrations

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * IMDb ratings, through OMDb (omdbapi.com – IMDb has no public API of its own). Needs a free
 * OMDb key (1,000 lookups a day); Flicker remembers each rating for a month.
 */
object Omdb {
    /** IMDb's rating for [imdbId] ("7.3"), or null if it hasn't got one. Blocking. */
    fun imdbRating(apiKey: String, imdbId: String): Double? {
        val url = "https://www.omdbapi.com/?i=" + URLEncoder.encode(imdbId, "UTF-8") + "&apikey=" + URLEncoder.encode(apiKey.trim(), "UTF-8")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
        }
        try {
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            if (!o.optString("Response").equals("True", ignoreCase = true)) return null
            return o.optString("imdbRating").toDoubleOrNull()
        } finally {
            c.disconnect()
        }
    }
}

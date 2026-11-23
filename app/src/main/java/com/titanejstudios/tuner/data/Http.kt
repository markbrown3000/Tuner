package com.titanejstudios.tuner.data

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Http {
    const val USER_AGENT = "Tuner/1.0 (Linux; Android TV)"

    /** Opens a URL, following redirects (including http <-> https) and un-gzipping .gz files. */
    fun open(url: String): InputStream = openFinal(url).first

    /** Like [open], but also returns the URL after redirects (needed to resolve relative HLS links). */
    fun openFinal(url: String, connectTimeoutMs: Int = 20_000, readTimeoutMs: Int = 60_000): Pair<InputStream, String> {
        var current = url
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without a location")
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code from ${URL(current).host}")
            }
            return maybeGunzip(BufferedInputStream(conn.inputStream, 64 * 1024)) to current
        }
        throw IOException("Too many redirects")
    }

    fun readText(url: String): String = open(url).bufferedReader().use { it.readText() }

    private fun maybeGunzip(input: BufferedInputStream): InputStream {
        input.mark(4)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024) else input
    }
}

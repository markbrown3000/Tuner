package com.titanejstudios.tuner.data

import java.net.URL

/** Checks whether channels actually stream, so dead ones can be flagged or hidden. */
object HealthCheck {

    /** True if the stream answers and sends real data within a few seconds. */
    fun isAlive(url: String): Boolean = try {
        probe(url, depth = 0)
    } catch (e: Exception) {
        false
    }

    private fun probe(url: String, depth: Int): Boolean {
        val (input, finalUrl) = Http.openFinal(url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
        input.use { s ->
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (total < buf.size) {
                val n = s.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
                if (total >= 2_048) break
            }
            val head = String(buf, 0, minOf(total, 7), Charsets.US_ASCII)
            if (head == "#EXTM3U") {
                if (depth >= 2) return false
                // HLS: read the rest of the playlist and try one level down (variant or segment).
                val text = String(buf, 0, total, Charsets.UTF_8) + s.bufferedReader().readText()
                // Try up to three entries (a variant or segment can be missing while others work).
                val entries = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                val candidates = if (text.contains("#EXT-X-STREAM-INF")) entries.take(3) else entries.takeLast(3)
                return candidates.any { runCatching { probe(URL(URL(finalUrl), it.trim()).toString(), depth + 1) }.getOrDefault(false) }
            }
            // A whole live stream should send 1 KB quickly; an HLS segment can legitimately be small.
            return total >= if (depth > 0) 188 else 1_024
        }
    }

    /**
     * Checks [channels] one at a time (gentle on providers with connection limits).
     * @return ids of channels that didn't respond
     */
    fun run(
        channels: List<Channel>,
        cancelled: () -> Boolean,
        onProgress: (done: Int, total: Int, dead: Int) -> Unit = { _, _, _ -> },
    ): Set<String> {
        val dead = HashSet<String>()
        channels.forEachIndexed { i, c ->
            if (cancelled()) return dead
            // Try twice before calling a channel dead – streams are often slow to start.
            if (!isAlive(c.url)) {
                Thread.sleep(1_000)
                if (!isAlive(c.url)) dead += c.id
            }
            onProgress(i + 1, channels.size, dead.size)
            Thread.sleep(300)
        }
        return dead
    }
}

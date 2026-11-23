package com.titanejstudios.tuner.recording

import com.titanejstudios.tuner.data.Http
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL

/**
 * Saves a live stream to a .ts file until a given time. Handles plain MPEG-TS streams and
 * HLS playlists (TS segments), reconnecting if the stream drops.
 */
object Recorder {

    class Progress(@Volatile var bytes: Long = 0L)

    /** Blocks until [until] (epoch ms), [cancelled] returns true, or an HLS stream ends. */
    fun record(url: String, file: File, until: Long, cancelled: () -> Boolean, progress: Progress) {
        file.parentFile?.mkdirs()
        var failures = 0
        FileOutputStream(file, true).use { out ->
            while (System.currentTimeMillis() < until && !cancelled()) {
                try {
                    val finished = recordOnce(url, out, until, cancelled, progress)
                    if (finished) return
                    failures = 0
                } catch (e: IOException) {
                    failures++
                    if (failures > 30 && progress.bytes == 0L) throw e
                    Thread.sleep(minOf(2_000L * failures, 15_000L))
                }
            }
        }
    }

    /** @return true when the stream itself finished (HLS #EXT-X-ENDLIST). */
    private fun recordOnce(url: String, out: FileOutputStream, until: Long, cancelled: () -> Boolean, progress: Progress): Boolean {
        val (raw, finalUrl) = Http.openFinal(url)
        val input = BufferedInputStream(raw, 64 * 1024)
        input.use {
            input.mark(16)
            val head = ByteArray(7)
            val n = input.read(head)
            input.reset()
            if (n == 7 && String(head, Charsets.US_ASCII) == "#EXTM3U") {
                val text = input.bufferedReader().readText()
                return recordHls(finalUrl, text, out, until, cancelled, progress)
            }
            copy(input, out, until, cancelled, progress)
            return false
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream, until: Long, cancelled: () -> Boolean, progress: Progress) {
        val buf = ByteArray(64 * 1024)
        while (System.currentTimeMillis() < until && !cancelled()) {
            val n = input.read(buf)
            if (n < 0) throw IOException("Stream ended")
            out.write(buf, 0, n)
            progress.bytes += n
        }
    }

    private fun resolve(base: String, ref: String): String = URL(URL(base), ref.trim()).toString()

    private fun recordHls(
        playlistUrl: String,
        firstText: String,
        out: FileOutputStream,
        until: Long,
        cancelled: () -> Boolean,
        progress: Progress,
    ): Boolean {
        var mediaUrl = playlistUrl
        var text = firstText

        // Master playlist: pick the highest-bandwidth variant.
        if (text.contains("#EXT-X-STREAM-INF")) {
            var best: String? = null
            var bestBw = -1L
            val lines = text.lines()
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > bestBw) {
                    bestBw = bw
                    best = uri
                }
            }
            mediaUrl = resolve(playlistUrl, best ?: throw IOException("No variants in HLS playlist"))
            val (s, finalUrl) = Http.openFinal(mediaUrl)
            text = s.bufferedReader().use { it.readText() }
            mediaUrl = finalUrl
        }

        if (text.contains("METHOD=AES-128") || text.contains("METHOD=SAMPLE-AES")) {
            throw IOException("This channel's stream is encrypted and can't be recorded")
        }

        val seen = LinkedHashSet<String>()
        var first = true
        while (System.currentTimeMillis() < until && !cancelled()) {
            val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
            var segments = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { resolve(mediaUrl, it) }
            // On a live playlist start near the live edge rather than minutes back, and remember
            // the skipped ones so they're never written later (out of order).
            if (first && !text.contains("#EXT-X-ENDLIST")) {
                seen.addAll(segments.dropLast(3))
                segments = segments.takeLast(3)
            }
            first = false
            for (seg in segments) {
                if (cancelled() || System.currentTimeMillis() >= until) return false
                if (!seen.add(seg)) continue
                Http.open(seg).use { input -> copySegment(input, out, progress) }
            }
            while (seen.size > 600) seen.remove(seen.first())
            if (text.contains("#EXT-X-ENDLIST")) return true
            Thread.sleep((target * 1000 / 2).coerceIn(1_000L, 10_000L))
            val (s, finalUrl) = Http.openFinal(mediaUrl)
            text = s.bufferedReader().use { it.readText() }
            mediaUrl = finalUrl
        }
        return false
    }

    private fun copySegment(input: InputStream, out: FileOutputStream, progress: Progress) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            progress.bytes += n
        }
    }
}

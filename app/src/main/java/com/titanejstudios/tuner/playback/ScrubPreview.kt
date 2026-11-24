package com.titanejstudios.tuner.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.titanejstudios.tuner.data.Http

/**
 * Little pictures from a film while you scrub through it. Frames are read with Android's
 * MediaMetadataRetriever (a second, separate read of the file), cached in 10-second steps.
 *
 * Reading frames opens a second connection, which many IPTV providers don't allow while you're
 * watching – so for IPTV it's a setting, and it's always on for Plex, downloads and recordings.
 */
class ScrubPreview(private val url: String) {
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private val cache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean = size > 40
    }

    /** A frame near [ms], or null if this stream can't give pictures. Slow – call off the main thread. */
    @Synchronized
    fun frameAt(ms: Long): Bitmap? {
        if (failed) return null
        val bucket = ms / 10_000L * 10_000L
        cache[bucket]?.let { return it }
        val r = retriever ?: try {
            MediaMetadataRetriever().also { mr ->
                if (url.startsWith("file:") || url.startsWith("/")) mr.setDataSource(Uri.parse(url).path ?: url)
                else mr.setDataSource(url, mapOf("User-Agent" to Http.USER_AGENT))
                retriever = mr
            }
        } catch (e: Exception) {
            failed = true
            return null
        }
        val frame = try {
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(bucket * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                r.getFrameAtTime(bucket * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 320, 180, true) }
            }
        } catch (e: Exception) {
            null
        } ?: return null
        cache[bucket] = frame
        return frame
    }

    @Synchronized
    fun release() {
        runCatching { retriever?.release() }
        retriever = null
    }

    companion object {
        /** Can we read pictures from this without upsetting an IPTV provider? */
        fun safeFor(url: String, contentId: String?): Boolean {
            if (url.startsWith("file:") || url.startsWith("/")) return true
            if (contentId?.startsWith("plex:") == true) return true
            val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
            // Your own network (a Plex server or network drive at home).
            return host.startsWith("192.168.") || host.startsWith("10.") || host.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*")) || host.endsWith(".plex.direct")
        }
    }
}

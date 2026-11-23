package com.titanejstudios.tuner.data

import android.content.Context
import com.titanejstudios.tuner.recording.RecordingScheduler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where recordings go, shared by the app and the overnight worker. */
object RecordingPaths {
    fun defaultDir(context: Context): File =
        context.getExternalFilesDirs("Recordings").firstOrNull { it != null } ?: File(context.filesDir, "Recordings")

    fun dir(context: Context, store: Store): String =
        store.recordingDir?.takeIf { File(it).let { f -> f.exists() || f.mkdirs() } }
            ?: defaultDir(context).also { it.mkdirs() }.path

    private val unsafe = Regex("[^A-Za-z0-9 _-]")
    fun safeName(s: String) = s.replace(unsafe, "").trim().replace(' ', '_').take(60).ifEmpty { "Recording" }

    fun newRecording(
        context: Context,
        store: Store,
        channel: Channel,
        title: String,
        start: Long,
        stop: Long,
        category: String?,
    ): Recording {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(start))
        return Recording(
            id = "r" + System.nanoTime().toString(36),
            channelId = channel.id,
            channelName = channel.name,
            title = title,
            start = start,
            stop = stop,
            url = channel.url,
            path = File(dir(context, store), "${safeName(title)}_$stamp.ts").path,
            status = RecStatus.SCHEDULED,
            category = category,
        )
    }
}

/**
 * Series link: "record every episode of this programme on this channel". Checked whenever the
 * guide loads or refreshes; any matching programme in the next ~36 hours is set to record.
 */
object SeriesLinks {
    private val prefix = Regex("^(new|live|premiere)\\s*[:!-]\\s*")
    private val episodeTail = Regex("\\s*[(\\[].*?[)\\]]\\s*$")       // "Title (S2 E4)", "Title [HD]"
    private val seasonTail = Regex("\\s*[-:,]?\\s*(s\\d+\\s*e\\d+|series\\s+\\d+.*|season\\s+\\d+.*|episode\\s+\\d+.*)$")
    private val nonAlnum = Regex("[^a-z0-9]")

    /** "New: Doctor Who (S2 E4)" and "Doctor Who" give the same key. */
    fun key(title: String): String {
        var t = title.lowercase(Locale.ROOT).trim()
        t = t.replace(prefix, "")
        repeat(2) { t = t.replace(episodeTail, "") }
        t = t.replace(seasonTail, "")
        return t.replace(nonAlnum, "")
    }

    fun matches(link: SeriesLink, channelId: String, p: Programme): Boolean =
        link.channelId == channelId && key(p.title) == key(link.title) && key(link.title).isNotEmpty()

    /** Programmes the links want recorded that aren't already. Pure – easy to test. */
    fun planned(
        links: List<SeriesLink>,
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        existing: List<Recording>,
        now: Long,
        horizonMs: Long = 36 * 3600_000L,
    ): List<Pair<Channel, Programme>> {
        if (links.isEmpty()) return emptyList()
        val byId = channels.associateBy { it.id }
        val out = ArrayList<Pair<Channel, Programme>>()
        for (link in links) {
            val ch = byId[link.channelId] ?: continue
            for (p in epg[ch.id].orEmpty()) {
                if (p.stop <= now + 60_000L || p.start > now + horizonMs) continue
                if (!matches(link, ch.id, p)) continue
                val already = existing.any {
                    it.channelId == ch.id && it.start < p.stop && it.stop > p.start &&
                        it.status != RecStatus.FAILED
                } || out.any { it.first.id == ch.id && it.second.start == p.start }
                if (!already) out += ch to p
            }
        }
        return out
    }

    /** Sets up the recordings. Returns how many were added. */
    fun apply(context: Context, store: Store, channels: List<Channel>, epg: Map<String, List<Programme>>): Int {
        val todo = planned(store.seriesLinks, channels, epg, store.recordings, System.currentTimeMillis())
        if (todo.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val recs = todo.map { (ch, p) ->
            RecordingPaths.newRecording(context, store, ch, p.title, maxOf(p.start - 60_000L, now), p.stop + 120_000L, p.category)
        }
        store.updateRecordings { it + recs }
        recs.forEach { RecordingScheduler.schedule(context, it) }
        AppLog.info("Series link: set ${recs.size} recording(s)")
        return recs.size
    }
}

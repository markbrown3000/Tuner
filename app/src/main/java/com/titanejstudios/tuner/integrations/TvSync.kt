package com.titanejstudios.tuner.integrations

import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.recording.RecordingTidy
import jcifs.smb.SmbFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps TVs in step through one small file on the network drive (tuner-sync.json):
 * favourites, My list, followed series and where you got to in films and episodes.
 *
 * Each list carries the time it was last changed; the newest copy wins. Resume points are
 * merged one by one, so carrying on a film on another TV picks up where you stopped.
 * The first TV to switch sync on puts its lists there; others then take those.
 */
class TvSync(private val store: Store) {

    class Outcome(val changedHere: Set<String>, val uploaded: Boolean)

    companion object {
        const val FAVOURITES = "favourites"
        const val WATCHLIST = "watchlist"
        const val FOLLOWED = "followed"
        const val RESUME = "resume"
        private const val FILE = "tuner-sync.json"

        /** Newest entry wins, item by item. */
        fun mergeResume(a: Map<String, ResumeEntry>, b: Map<String, ResumeEntry>): Map<String, ResumeEntry> {
            val out = HashMap(a)
            for ((id, e) in b) {
                val mine = out[id]
                if (mine == null || e.updatedAt > mine.updatedAt) out[id] = e
            }
            // Same 50-entry limit as the TV's own list.
            return out.values.sortedByDescending { it.updatedAt }.take(50).associateBy { it.contentId }
        }

        /** "here" (keep and upload ours), "there" (take theirs) or "same". */
        fun decide(localStamp: Long, remoteStamp: Long, remoteHasIt: Boolean): String = when {
            !remoteHasIt -> "here"
            remoteStamp > localStamp -> "there"
            localStamp > remoteStamp -> "here"
            else -> "same"
        }

        fun resumeToJson(e: ResumeEntry): JSONObject = JSONObject()
            .put("id", e.contentId).put("title", e.title).put("sub", e.subtitle).put("poster", e.poster)
            .put("url", e.url).put("pos", e.positionMs).put("dur", e.durationMs).put("at", e.updatedAt).put("series", e.seriesId)

        fun resumeFromJson(o: JSONObject): ResumeEntry? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            return ResumeEntry(
                contentId = id,
                title = o.optString("title"),
                subtitle = str("sub"),
                poster = str("poster"),
                url = o.optString("url"),
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                updatedAt = o.optLong("at"),
                seriesId = str("series"),
            )
        }
    }

    private fun file(): SmbFile? {
        val nas = store.nasUrl?.takeIf { it.isNotBlank() } ?: return null
        return SmbFile(RecordingTidy.folderUrl(nas) + FILE, RecordingTidy.context(store))
    }

    /** For the self-test: can we see the drive, and has a TV written the sync file yet? Throws if unreachable. */
    fun check(): String {
        val f = file() ?: return "No network drive set"
        val exists = f.use { it.exists() }
        val last = store.syncedAt
        return (if (exists) "Sync file found" else "Drive reached – the sync file is made on the first sync") +
            (if (last > 0) " · last synced " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(last)) else "")
    }

    /** One round of syncing. Blocking – run on a background thread. Throws if the drive can't be reached. */
    fun sync(): Outcome {
        val f = file() ?: return Outcome(emptySet(), false)
        val remote = f.use { sf ->
            if (sf.exists()) JSONObject(sf.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }) else JSONObject()
        }
        val sections = remote.optJSONObject("sections") ?: JSONObject()
        val changed = HashSet<String>()
        var dirty = false

        // Favourites (every profile's list together).
        run {
            val r = sections.optJSONObject(FAVOURITES)
            when (decide(store.syncStamp(FAVOURITES), r?.optLong("at") ?: 0L, r != null)) {
                "there" -> {
                    val data = r!!.optJSONObject("data") ?: JSONObject()
                    for (pid in data.keys()) {
                        val arr = data.optJSONArray(pid) ?: continue
                        store.setFavourites(pid, (0 until arr.length()).map { arr.getString(it) })
                    }
                    store.setSyncStamp(FAVOURITES, r.optLong("at"))
                    changed += FAVOURITES
                }
                "here" -> {
                    val data = JSONObject()
                    for (p in store.profiles) data.put(p.id, JSONArray(store.favourites(p.id)))
                    sections.put(FAVOURITES, JSONObject().put("at", stampOrNow(FAVOURITES)).put("data", data))
                    dirty = true
                }
            }
        }

        // My list and followed series are stored as JSON text already.
        for ((section, read, write) in listOf(
            Triple(WATCHLIST, { store.watchlistJson }, { v: String -> store.watchlistJson = v }),
            Triple(FOLLOWED, { store.followedSeriesJson }, { v: String -> store.followedSeriesJson = v }),
        )) {
            val r = sections.optJSONObject(section)
            when (decide(store.syncStamp(section), r?.optLong("at") ?: 0L, r != null)) {
                "there" -> {
                    write(r!!.optString("data", "[]"))
                    store.setSyncStamp(section, r.optLong("at"))
                    changed += section
                }
                "here" -> {
                    sections.put(section, JSONObject().put("at", stampOrNow(section)).put("data", read()))
                    dirty = true
                }
            }
        }

        // Resume points: merged entry by entry.
        run {
            val theirs = HashMap<String, ResumeEntry>()
            sections.optJSONObject(RESUME)?.optJSONArray("data")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { resumeFromJson(it) }?.let { theirs[it.contentId] = it }
            }
            val mine = store.resume
            val merged = mergeResume(mine, theirs)
            if (merged != mine) {
                store.resume = merged
                changed += RESUME
            }
            if (merged != theirs) {
                val arr = JSONArray()
                merged.values.forEach { arr.put(resumeToJson(it)) }
                sections.put(RESUME, JSONObject().put("at", System.currentTimeMillis()).put("data", arr))
                dirty = true
            }
        }

        if (dirty) {
            remote.put("app", "Tuner").put("v", 1).put("sections", sections).put("updated", System.currentTimeMillis())
            file()?.use { sf -> sf.openOutputStream().use { it.write(remote.toString().toByteArray()) } }
        }
        store.syncedAt = System.currentTimeMillis()
        return Outcome(changed, dirty)
    }

    private fun stampOrNow(section: String): Long =
        store.syncStamp(section).takeIf { it > 0 } ?: System.currentTimeMillis().also { store.setSyncStamp(section, it) }
}

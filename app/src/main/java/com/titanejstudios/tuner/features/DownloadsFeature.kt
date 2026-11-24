package com.titanejstudios.tuner.features

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Http
import com.titanejstudios.tuner.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A film or episode saved on this phone. */
data class DownloadEntry(
    val contentId: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val seriesId: String?,
    /** Where it came from (sent to the TV instead of the phone's own copy). */
    val sourceUrl: String,
    val file: String,
    val managerId: Long,
    val addedAt: Long,
)

/**
 * Downloads for watching offline (phones): films and episodes saved into Flicker's own folder,
 * using Android's download manager (it carries on in the background and shows progress).
 * Live channels and streams split into pieces (HLS) can't be saved.
 */
class DownloadsFeature(private val context: Context, private val store: Store) {
    class Progress(val done: Boolean, val failed: Boolean, val bytes: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    var items by mutableStateOf(load()); private set
    var progress by mutableStateOf<Map<String, Progress>>(emptyMap()); private set
    var wifiOnly by mutableStateOf(store.downloadWifiOnly); private set

    private val manager get() = context.getSystemService(DownloadManager::class.java)

    fun canDownload(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("http") && ".m3u8" !in u && "/transcode/" !in u && "/live/" !in u
    }

    fun entryFor(contentId: String): DownloadEntry? = items.firstOrNull { it.contentId == contentId }

    fun isReady(contentId: String): Boolean {
        val e = entryFor(contentId) ?: return false
        return progress[contentId]?.done == true || (progress[contentId] == null && File(e.file).length() > 0 && !inProgress(e))
    }

    /** Starts downloading. Returns a message to show. */
    fun start(contentId: String, title: String, subtitle: String?, poster: String?, seriesId: String?, url: String): String {
        if (entryFor(contentId) != null) return "Already downloaded or downloading"
        if (!canDownload(url)) return "This one can't be downloaded – it's streamed in pieces"
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return "No storage available"
        dir.mkdirs()
        val ext = Regex("\\.(mp4|mkv|avi|mov|m4v|ts)(\\?|$)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase() ?: "mp4"
        val base = (title + (subtitle?.let { " $it" } ?: "")).replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifBlank { "video" }
        var file = File(dir, "$base.$ext")
        var n = 2
        while (file.exists()) file = File(dir, "$base ($n).$ext").also { n++ }
        return try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription(subtitle ?: "Flicker download")
                .addRequestHeader("User-Agent", Http.USER_AGENT)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
            val id = manager.enqueue(request)
            items = items + DownloadEntry(contentId, title, subtitle, poster, seriesId, url, file.absolutePath, id, System.currentTimeMillis())
            save()
            if (wifiOnly) "⬇ Downloading – it waits for Wi-Fi if you're on mobile data" else "⬇ Downloading"
        } catch (e: Exception) {
            AppLog.warn("Couldn't start a download", e)
            "Couldn't start the download: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun remove(contentId: String) {
        val e = entryFor(contentId) ?: return
        runCatching { manager.remove(e.managerId) }
        runCatching { File(e.file).delete() }
        items = items.filter { it.contentId != contentId }
        progress = progress - contentId
        save()
    }

    fun toggleWifiOnly() {
        wifiOnly = !wifiOnly
        store.downloadWifiOnly = wifiOnly
    }

    /** Space used by finished downloads. */
    fun usedBytes(): Long = items.sumOf { File(it.file).length() }

    /** Reads progress from Android's download manager (call every second or so while showing it). */
    fun refresh() {
        if (items.isEmpty()) return
        val out = HashMap<String, Progress>()
        runCatching {
            val byId = items.associateBy { it.managerId }
            manager.query(DownloadManager.Query().setFilterById(*items.map { it.managerId }.toLongArray())).use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    val e = byId[c.getLong(idCol)] ?: continue
                    val st = c.getInt(statusCol)
                    out[e.contentId] = Progress(
                        done = st == DownloadManager.STATUS_SUCCESSFUL,
                        failed = st == DownloadManager.STATUS_FAILED,
                        bytes = c.getLong(doneCol),
                        total = c.getLong(totalCol),
                    )
                }
            }
        }
        // Downloads Android has forgotten about but whose file is complete still count as done.
        for (e in items) if (e.contentId !in out && File(e.file).length() > 0) out[e.contentId] = Progress(true, false, File(e.file).length(), File(e.file).length())
        progress = out
    }

    private fun inProgress(e: DownloadEntry): Boolean = progress[e.contentId]?.let { !it.done && !it.failed } ?: false

    private fun load(): List<DownloadEntry> = try {
        val arr = JSONArray(store.downloadsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            DownloadEntry(
                contentId = str("id") ?: return@mapNotNull null,
                title = o.optString("title"),
                subtitle = str("sub"),
                poster = str("poster"),
                seriesId = str("series"),
                sourceUrl = o.optString("url"),
                file = o.optString("file"),
                managerId = o.optLong("dm"),
                addedAt = o.optLong("at"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("id", it.contentId).put("title", it.title).put("sub", it.subtitle).put("poster", it.poster)
                    .put("series", it.seriesId).put("url", it.sourceUrl).put("file", it.file).put("dm", it.managerId).put("at", it.addedAt)
            )
        }
        store.downloadsJson = arr.toString()
    }
}

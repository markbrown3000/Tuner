package com.titanejstudios.tuner.integrations

import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.recording.RecordingTidy
import jcifs.smb.SmbFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nightly copy of Tuner's settings (playlists, profiles, favourites, PIN, reminders…) to the
 * network drive: "Tuner backups/tuner-backup-<TV>-<date>.json", keeping the last 7 for each TV.
 * Restore one from your phone (Set up from your phone › Restore) after a factory reset.
 */
object NasBackup {
    private const val KEEP = 7
    private const val FOLDER = "Tuner backups/"

    fun safeDeviceName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(40).ifEmpty { "TV" }

    /** Writes today's backup and removes this TV's older ones beyond the last [KEEP]. Returns the file name. */
    fun save(store: Store, deviceName: String, now: Long = System.currentTimeMillis()): String? {
        val nas = store.nasUrl?.takeIf { it.isNotBlank() } ?: return null
        val ctx = RecordingTidy.context(store)
        val dirUrl = RecordingTidy.folderUrl(nas) + FOLDER
        SmbFile(dirUrl, ctx).use { if (!it.exists()) it.mkdirs() }
        val device = safeDeviceName(deviceName)
        val name = "tuner-backup-$device-" + SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date(now)) + ".json"
        SmbFile(dirUrl + name, ctx).use { f -> f.openOutputStream().use { it.write(store.exportBackup().toByteArray()) } }
        // Tidy: keep the newest few for this TV.
        SmbFile(dirUrl, ctx).use { dir ->
            val mine = dir.listFiles().filter { it.name.startsWith("tuner-backup-$device-") && it.name.endsWith(".json") }
            mine.sortedByDescending { it.name }.drop(KEEP).forEach { old -> runCatching { old.delete() } }
            mine.forEach { runCatching { it.close() } }
        }
        store.nasBackupAt = now
        return name
    }
}

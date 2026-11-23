package com.titanejstudios.tuner.recording

import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.Store
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.File
import java.util.Properties

/** Deleting old recordings, and copying finished ones to a network drive. */
object RecordingTidy {

    /** Deletes finished recordings older than the chosen number of days (kept ones stay). Returns how many. */
    fun cleanUp(store: Store, now: Long = System.currentTimeMillis()): Int {
        val days = store.recordingAutoDelete
        if (days <= 0) return 0
        val kept = store.keptRecordings
        val cutoff = now - days * 86_400_000L
        val old = store.recordings.filter {
            (it.status == RecStatus.DONE || it.status == RecStatus.FAILED) && it.stop < cutoff && it.id !in kept
        }
        if (old.isEmpty()) return 0
        old.forEach { r -> if (!r.path.startsWith("smb://")) runCatching { File(r.path).delete() } }
        val ids = old.map { it.id }.toHashSet()
        store.updateRecordings { list -> list.filter { it.id !in ids } }
        AppLog.info("Deleted ${old.size} recording(s) older than $days days")
        return old.size
    }

    /** SMB connection settings with the saved user name and password (also used by TV sync and NAS backups). */
    fun context(store: Store): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val user = store.nasUser?.trim().orEmpty()
        if (user.isEmpty()) return base.withAnonymousCredentials()
        // "DOMAIN\\user" is allowed.
        val domain = user.substringBefore('\\', "").ifEmpty { null }
        return base.withCredentials(NtlmPasswordAuthenticator(domain, user.substringAfter('\\'), store.nasPassword.orEmpty()))
    }

    /** smb://host/share/folder/ (always ending in a slash). */
    fun folderUrl(raw: String): String {
        var u = raw.trim().replace('\\', '/')
        if (u.startsWith("//")) u = "smb:$u"
        if (!u.startsWith("smb://", ignoreCase = true)) u = "smb://" + u.trimStart('/')
        if (!u.endsWith("/")) u += "/"
        return u
    }

    /** Checks the drive can be reached and written to. Returns null when fine, or the problem. */
    fun test(store: Store): String? {
        val url = store.nasUrl?.takeIf { it.isNotBlank() } ?: return "No network drive set"
        return try {
            val ctx = context(store)
            SmbFile(folderUrl(url), ctx).use { dir -> if (!dir.exists()) dir.mkdirs() }
            SmbFile(folderUrl(url) + ".tuner-test", ctx).use { f ->
                f.openOutputStream().use { it.write("ok".toByteArray()) }
                f.delete()
            }
            null
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Copies a finished recording to the network drive. If you've chosen not to keep a copy on the
     * TV, the local file is removed and the recording points at the drive instead.
     */
    fun upload(store: Store, rec: Recording) {
        val base = store.nasUrl?.takeIf { it.isNotBlank() } ?: return
        val local = File(rec.path)
        if (!local.exists() || local.length() == 0L) return
        try {
            val ctx = context(store)
            val dir = folderUrl(base)
            SmbFile(dir, ctx).use { if (!it.exists()) it.mkdirs() }
            val target = dir + local.name
            SmbFile(target, ctx).use { f ->
                f.openOutputStream().use { out -> local.inputStream().use { it.copyTo(out, 1 shl 16) } }
            }
            AppLog.info("Copied \"${rec.title}\" to the network drive")
            if (!store.nasKeepLocal) {
                local.delete()
                store.updateRecordings { list -> list.map { if (it.id == rec.id) it.copy(path = target) else it } }
            }
        } catch (e: Throwable) {
            AppLog.warn("Couldn't copy \"${rec.title}\" to the network drive", e)
        }
    }
}

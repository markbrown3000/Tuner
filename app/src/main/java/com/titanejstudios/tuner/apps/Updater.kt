package com.titanejstudios.tuner.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.titanejstudios.tuner.data.AppLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installing a new version of Tuner without sideloading by hand:
 *  - upload the APK from your PC's browser (phone remote › /update), or
 *  - put it on a GitHub release and let Tuner fetch it.
 * Android always asks you to confirm, and the first time it asks you to allow Tuner to install apps.
 */
object Updater {
    class Release(val version: String, val apkUrl: String, val notes: String?)

    /** Fired with a message for the user ("Installing…", "Update failed: …"). */
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** "5.10" > "5.9". */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().trimStart('v', 'V').split('.', '-', ' ').mapNotNull { it.toIntOrNull() }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Latest GitHub release with an .apk attached ("owner/repo"). Blocking. */
    fun latest(repo: String): Release? {
        val conn = (URL("https://api.github.com/repos/${repo.trim().trim('/')}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IllegalStateException("GitHub said ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(o.optString("tag_name").ifBlank { o.optString("name") }, a.getString("browser_download_url"), o.optString("body").takeIf { it.isNotBlank() })
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String, to: File) {
        var u = URL(url)
        repeat(5) {
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    u = URL(u, conn.getHeaderField("Location"))
                    return@repeat
                }
                if (code != 200) throw IllegalStateException("Download failed ($code)")
                conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    /** Checks the file is a Tuner APK, then hands it to Android's installer. */
    /** When the last install was handed to Android (pressing Update again straight away doesn't start another). */
    @Volatile private var installingAt = 0L

    fun install(context: Context, apk: File) {
        if (System.currentTimeMillis() - installingAt < 90_000L) {
            onStatus?.invoke("Already installing – confirm on screen. Flicker closes for a moment while it updates.")
            return
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IllegalArgumentException("That file isn't an Android app (APK)")
        if (info.packageName != context.packageName) throw IllegalArgumentException("That APK is a different app (${info.packageName})")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("tuner.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
        installingAt = System.currentTimeMillis()
        AppLog.info("Installing Flicker ${info.versionName}")
        onStatus?.invoke("Installing Flicker ${info.versionName}… confirm on screen. Flicker closes for a moment while it updates, then opens again.")
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Do you want to update this app?" screen.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.info("Update installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val friendly = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "it was built with a different signing key – build it on the same computer as before"
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "it was cancelled"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "the TV is out of space"
                    else -> msg.ifBlank { "error $status" }
                }
                AppLog.warn("Update failed: $friendly ($msg)")
                Updater.onStatus?.invoke("Update failed: $friendly")
            }
        }
    }
}


/**
 * Android closes an app while it replaces it with the new version (that's the "crash" you see
 * after Update). This runs in the new version straight afterwards: it notes the update and
 * opens Flicker again (where the TV allows it).
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.info("Updated to Flicker ${Updater.installedVersion(context)} – opening it again")
        runCatching {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { context.startActivity(it) }
        }.onFailure { AppLog.warn("Couldn't reopen Flicker after the update", it) }
    }
}

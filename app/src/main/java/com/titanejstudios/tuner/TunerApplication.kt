package com.titanejstudios.tuner

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.titanejstudios.tuner.data.AppLog

class TunerApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Safe mode must know about earlier crashes before anything else starts.
        com.titanejstudios.tuner.data.SafeMode.onAppStart(this)
        AppLog.init(this)
        if (com.titanejstudios.tuner.data.SafeMode.active) AppLog.info("Started in safe mode")
        logHowItLastClosed()
        // Older Android (Fire OS 6/7) keeps no record of why: Flicker's own note tells us.
        com.titanejstudios.tuner.data.SafeMode.endedWhileOpen()?.let { what ->
            AppLog.warn(what)
            com.titanejstudios.tuner.data.SafeMode.noteExit("Closed while you were using it – most likely the TV ran out of memory", System.currentTimeMillis() - 1)
        }
    }

    private var lastTrimLogged = 0L

    /** Android asking Flicker to use less memory: logged (it often comes just before Android closes an app), and images let go. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_UI_HIDDEN) return
        val rt = Runtime.getRuntime()
        val now = System.currentTimeMillis()
        if (now - lastTrimLogged > 60_000L || level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            lastTrimLogged = now
            val what = when (level) {
                TRIM_MEMORY_RUNNING_MODERATE -> "the TV is getting low on memory"
                TRIM_MEMORY_RUNNING_LOW -> "the TV is low on memory"
                TRIM_MEMORY_RUNNING_CRITICAL -> "the TV is very low on memory – Android may close apps"
                else -> "memory level $level"
            }
            AppLog.warn("Android: $what (Flicker using ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB + ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB native, while: ${com.titanejstudios.tuner.data.SafeMode.doing})")
        }
        // Pictures are the easiest thing to give back; they reload when needed.
        if (level >= TRIM_MEMORY_RUNNING_LOW) runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    /**
     * Android 11+ keeps why an app last stopped – including the ones Flicker can't log itself:
     * the TV running out of memory, a freeze ("not responding"), or a crash inside the video decoder.
     * Writes any new ones to the problem log (and makes them the "closed unexpectedly" notice).
     */
    private fun logHowItLastClosed() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = getSharedPreferences("exit_reasons", MODE_PRIVATE)
            val seen = prefs.getLong("seen_at", 0L)
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 5).filter { it.timestamp > seen }
            if (exits.isEmpty()) return
            prefs.edit().putLong("seen_at", exits.maxOf { it.timestamp }).apply()
            val fmt = java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.UK)
            for (e in exits.sortedBy { it.timestamp }) {
                val why = when (e.reason) {
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "the TV ran out of memory"
                    android.app.ApplicationExitInfo.REASON_ANR -> "it froze (Android said \"not responding\")"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "a crash inside the video or sound decoder"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "a crash"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "Android stopped it (signal ${e.status})"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "it used too much of the TV's power"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED, android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "you closed it"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "it closed itself"
                    android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "it was updated"
                    android.app.ApplicationExitInfo.REASON_OTHER -> "Android closed it (other)"
                    else -> "reason ${e.reason}"
                }
                val memory = if (e.pss > 0) " · it was using ${e.pss / 1024} MB" else ""
                val unexpected = e.reason in setOf(
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_ANR,
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_SIGNALED,
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                )
                val line = "Last closed ${fmt.format(java.util.Date(e.timestamp))}: $why$memory" + (e.description?.let { " ($it)" } ?: "")
                if (unexpected) {
                    // The first lines of Android's report say where it was stuck or crashed.
                    val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(40).joinToString("\n") } }.getOrNull()
                    AppLog.warn(line + (trace?.let { "\n$it" } ?: ""))
                    com.titanejstudios.tuner.data.SafeMode.noteExit("${why.replaceFirstChar { it.uppercase() }}$memory", e.timestamp)
                } else AppLog.info(line)
            }
        }.onFailure { AppLog.warn("Couldn't read how Flicker last closed", it) }
    }

    /** Logos and programme pictures are cached on disk so they appear instantly next time. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                // Posters and logos kept between starts (Home appears with its pictures straight away).
                .maxSizeBytes(if ((cacheDir.usableSpace) > 4L * 1024 * 1024 * 1024) 400L * 1024 * 1024 else 150L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false) // many IPTV logo hosts send "no-cache"
        .crossfade(false)
        .build()
}

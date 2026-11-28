package com.titanejstudios.tuner.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Safe mode: if Tuner crashes twice in a row soon after starting, the next start switches off the
 * newer, riskier extras (night mode, frame-rate matching, lip sync, passthrough, fast switching,
 * Live tab, Plex, football…) and opens on Home, so the TV isn't stuck in a crash loop.
 *
 * Kept in its own small preferences file (written with commit()) so the crash handler can update
 * it while the app is going down. Nothing here is copied to another TV.
 */
object SafeMode {
    private const val PREFS = "tuner_safe"
    /** A crash this soon after the process starts counts as a start-up crash. */
    private const val STARTUP_WINDOW_MS = 60_000L

    @Volatile
    var active = false
        private set

    /** One line describing the last crash, e.g. "Night mode sound – IllegalArgumentException: …". */
    var lastCrash: String? = null
        private set
    var lastCrashAt: Long = 0L
        private set

    private var prefs: SharedPreferences? = null
    private var processStart = 0L

    /** First thing in Application.onCreate. */
    fun onAppStart(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        processStart = System.currentTimeMillis()
        active = p.getBoolean("forced", false) || p.getInt("startup_crashes", 0) >= 2
        lastCrash = p.getString("last_crash", null)
        lastCrashAt = p.getLong("last_crash_at", 0L)
    }

    // ---------------- Closing while open (works on every Android, including Fire OS 6) ----------------
    // Android can end Flicker without telling it (out of memory, a decoder crash). So while Flicker is
    // on screen it notes, every few seconds, that it's still running and what it's doing; a clean
    // close (going to the home screen) clears that. If the next start finds the note still there,
    // Flicker was ended while you were using it – and the note says when, doing what, and how much
    // memory was left.

    /** What's happening now, e.g. "Watching South Park S29 E3 on Plex (0:15)". */
    @Volatile var doing: String = "Browsing"

    fun heartbeat(context: Context) {
        val p = prefs ?: return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
        val memory = "Flicker used ${javaMb} MB (+${nativeMb} MB native) of ${rt.maxMemory() / 1_048_576} MB; " +
            "the TV had ${mi.availMem / 1_048_576} MB free" + if (mi.lowMemory) " (low on memory)" else ""
        p.edit().putLong("hb_at", System.currentTimeMillis()).putString("hb_doing", doing).putString("hb_memory", memory).apply()
    }

    /** Left the screen normally (home button, another app): not a surprise if Android ends it later. */
    fun cleanStop() {
        prefs?.edit()?.remove("hb_at")?.apply()
    }

    /** At start: was Flicker ended while it was on screen last time? Returns a description, once. */
    fun endedWhileOpen(updatedAt: Long = 0L): String? {
        val p = prefs ?: return null
        val at = p.getLong("hb_at", 0L)
        if (at == 0L) return null
        // Installing an update ends the old Flicker while it's on screen: that's expected, not a crash.
        if (wasUpdate(at, updatedAt)) {
            p.edit().remove("hb_at").apply()
            return null
        }
        val doing = p.getString("hb_doing", null) ?: "using Flicker"
        val memory = p.getString("hb_memory", null).orEmpty()
        p.edit().remove("hb_at").apply()
        val time = java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.UK).format(java.util.Date(at))
        return "Flicker closed unexpectedly around $time while: $doing. Last memory check: $memory"
    }

    /** A closing Flicker couldn't catch itself (out of memory, frozen, decoder crash): shown once at start. */
    fun noteExit(summary: String, at: Long) {
        val p = prefs ?: return
        if (at <= lastCrashAt) return
        lastCrash = summary
        lastCrashAt = at
        p.edit().putString("last_crash", summary).putLong("last_crash_at", at).apply()
    }

    /** Called by the crash handler (AppLog). */
    fun onCrash(error: Throwable) {
        val p = prefs ?: return
        val summary = describe(error)
        val now = System.currentTimeMillis()
        val e = p.edit().putString("last_crash", summary).putLong("last_crash_at", now)
        if (now - processStart < STARTUP_WINDOW_MS) e.putInt("startup_crashes", p.getInt("startup_crashes", 0) + 1)
        e.commit()
    }

    /** Tuner has been open for a while without crashing: start-up crashes no longer count. */
    fun markStable() {
        val p = prefs ?: return
        // While safe mode is on, keep it on until it's turned off from the banner or Settings.
        if (active) return
        if (p.getInt("startup_crashes", 0) != 0) p.edit().putInt("startup_crashes", 0).apply()
    }

    /** Back to normal from the next start (Settings or the Home banner). */
    fun leave() {
        prefs?.edit()?.putInt("startup_crashes", 0)?.putBoolean("forced", false)?.commit()
        active = false
    }

    /** Start in safe mode next time (Settings › Help). */
    fun forceNextStart() {
        prefs?.edit()?.putBoolean("forced", true)?.commit()
    }

    fun clearCrash() {
        lastCrash = null
        lastCrashAt = 0L
        prefs?.edit()?.remove("last_crash")?.remove("last_crash_at")?.apply()
    }

    /** "Feature – Exception: message" using the first Tuner class in the stack. */
    fun describe(error: Throwable): String {
        var root: Throwable = error
        var guard = 0
        while (root.cause != null && root.cause !== root && guard++ < 6) root = root.cause!!
        val frame = (error.stackTrace + root.stackTrace).firstOrNull { it.className.startsWith("com.titanejstudios.tuner") }
        val where = frame?.let { featureFor(it.className) ?: it.className.substringAfterLast('.').substringBefore('$') }
        val what = root.javaClass.simpleName + (root.message?.let { ": " + it.take(120) } ?: "")
        return if (where != null) "$where – $what" else what
    }

    /** Plain-English name for the part of Tuner a class belongs to. */
    fun featureFor(className: String): String? {
        val c = className.substringAfterLast('.')
        return when {
            c.startsWith("NightMode") -> "Night mode sound"
            c.startsWith("FrameRate") -> "Frame-rate matching"
            c.startsWith("LipSync") || c.startsWith("TunerRenderers") -> "Sound / lip sync"
            c.startsWith("TunerInputService") || c.startsWith("TunerSession") || c.startsWith("LiveChannelSync") || c.startsWith("LiveSetup") -> "TV's Live tab"
            c.startsWith("TunerDream") -> "Screensaver"
            c.startsWith("Plex") -> "Plex"
            c.startsWith("Football") -> "Football scores"
            c.startsWith("RecordingTidy") -> "Network drive"
            c.startsWith("Recorder") || c.startsWith("Recording") -> "Recording"
            c.startsWith("MultiView") -> "Multi-view"
            c.startsWith("Timeshift") || c.startsWith("GrowingFile") -> "Pause / rewind live TV"
            c.startsWith("PlaybackController") || c.startsWith("PlayerFactory") -> "Video player"
            c.startsWith("PhoneSetupServer") -> "Phone remote"
            c.startsWith("TvShare") -> "Copy to another TV"
            c.startsWith("Updater") -> "App updates"
            c.startsWith("XmltvParser") || c.startsWith("Repository") || c.startsWith("Database") -> "Loading the TV guide"
            c.startsWith("M3uParser") || c.startsWith("XtreamClient") -> "Loading channels"
            else -> null
        }
    }

    /** Flicker was installed (updated) after it last said it was running, or within a few minutes of it closing. */
    fun wasUpdate(closedAt: Long, updatedAt: Long): Boolean =
        updatedAt > 0 && closedAt > 0 && updatedAt >= closedAt - 5 * 60_000L && updatedAt <= closedAt + 10 * 60_000L
}

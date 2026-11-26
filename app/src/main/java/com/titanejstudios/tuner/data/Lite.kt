package com.titanejstudios.tuner.data

import android.app.ActivityManager
import android.content.Context

/**
 * Lite mode: for older sticks with little memory (the first Fire TV Stick 4K has 1.5 GB, shared
 * with Fire OS). Flicker keeps fewer pictures in memory, reads the TV guide a few hours at a time,
 * shortens Home's rows and leaves out the extras that cost memory (background pictures taken from
 * posters, the guide's live picture, trailers that play by themselves).
 *
 * Read once at start-up (the picture cache is set up before anything else), so changing it takes
 * full effect the next time Flicker starts.
 */
object Lite {
    /** "auto" (on for TVs with little memory), "on" or "off". */
    @Volatile var choice: String = "auto"; private set
    /** Whether this TV counts as a low-memory one. */
    @Volatile var lowMemoryDevice: Boolean = false; private set
    /** Lite mode is on. */
    @Volatile var on: Boolean = false; private set

    private const val PREFS = "lite"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        choice = prefs.getString("mode", "auto") ?: "auto"
        lowMemoryDevice = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            isLowMemory(am.isLowRamDevice, am.memoryClass, info.totalMem)
        }.getOrDefault(false)
        on = decide(choice, lowMemoryDevice)
    }

    fun set(context: Context, mode: String) {
        choice = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
        on = decide(mode, lowMemoryDevice)
    }

    /** Android's own low-RAM flag, a small per-app allowance, or under about 1.7 GB in the whole TV (2 GB TVs report a little less). */
    fun isLowMemory(lowRamFlag: Boolean, memoryClassMb: Int, totalBytes: Long): Boolean =
        lowRamFlag || (memoryClassMb in 1..128) || (totalBytes in 1 until 1_700_000_000L)

    fun decide(mode: String, lowMemory: Boolean): Boolean = when (mode) {
        "on" -> true
        "off" -> false
        else -> lowMemory
    }

    /** How much TV guide is read at a time (more is read as you scroll the guide). */
    val guideHours: Int get() = if (on) 12 else 36

    /** Days of catch-up history kept in memory. */
    val catchupDays: Int get() = if (on) 2 else 7

    /** The most tiles in one Home row. */
    val rowLimit: Int get() = if (on) 12 else 40

    val label: String
        get() = when (choice) {
            "on" -> "On"
            "off" -> "Off"
            else -> if (lowMemoryDevice) "Automatic (on for this TV)" else "Automatic (off for this TV)"
        }

    fun next(mode: String): String = when (mode) {
        "auto" -> "on"
        "on" -> "off"
        else -> "auto"
    }
}

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

    /** What Flicker is running on ("Fire TV Stick 4K (1st gen)"), its memory, and why lite mode is or isn't on. */
    @Volatile var deviceName: String = ""; private set
    @Volatile var memoryGb: Double = 0.0; private set
    @Volatile var reason: String = ""; private set

    private const val PREFS = "lite"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        choice = prefs.getString("mode", "auto") ?: "auto"
        deviceName = runCatching { niceName(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.MODEL.orEmpty()) }.getOrDefault("This device")
        lowMemoryDevice = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            memoryGb = roundedGb(info.totalMem)
            reason = why(am.isLowRamDevice, am.memoryClass, info.totalMem)
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

    /** Why this device does or doesn't count as low-memory, in plain words. */
    fun why(lowRamFlag: Boolean, memoryClassMb: Int, totalBytes: Long): String = when {
        lowRamFlag -> "the TV says it's low on memory"
        totalBytes in 1 until 1_700_000_000L -> "under 2 GB of memory"
        memoryClassMb in 1..128 -> "only ${memoryClassMb} MB allowed for each app"
        else -> "enough memory"
    }

    /** Memory as sold: what Android reports is a little under, so round up to the nearest half GB. */
    fun roundedGb(totalBytes: Long): Double =
        if (totalBytes <= 0) 0.0 else Math.ceil(totalBytes / 500_000_000.0) / 2.0

    /** Amazon only gives a code ("AFTMM"), so the well-known ones get their real names. */
    private val amazon = mapOf(
        "AFTMM" to "Fire TV Stick 4K (1st gen)",
        "AFTKA" to "Fire TV Stick 4K Max (1st gen)",
        "AFTKM" to "Fire TV Stick 4K (2nd gen)",
        "AFTKRT" to "Fire TV Stick 4K Max (2nd gen)",
        "AFTSSS" to "Fire TV Stick (3rd gen)",
        "AFTSS" to "Fire TV Stick Lite",
        "AFTT" to "Fire TV Stick (2nd gen)",
        "AFTMA" to "Fire TV Cube (1st gen)",
        "AFTR" to "Fire TV Cube (2nd gen)",
        "AFTGAZL" to "Fire TV Cube (3rd gen)",
    )

    fun niceName(manufacturer: String, model: String): String {
        val m = model.trim()
        amazon[m.uppercase()]?.let { return it }
        if (m.startsWith("AFT", ignoreCase = true)) return "Amazon Fire TV ($m)"
        val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
        return when {
            m.isEmpty() -> maker.ifEmpty { "This device" }
            maker.isEmpty() || m.startsWith(maker, ignoreCase = true) -> m
            else -> "$maker $m"
        }
    }

    /** "Fire TV Stick 4K (1st gen) · 1.5 GB · Lite on (automatic)". */
    fun summary(mode: String = choice): String {
        val isOn = decide(mode, lowMemoryDevice)
        val how = when (mode) {
            "on" -> "Lite on (you chose)"
            "off" -> "Lite off (you chose)"
            else -> if (isOn) "Lite on (automatic – $reason)" else "Lite off (automatic – $reason)"
        }
        val gb = if (memoryGb > 0) (if (memoryGb % 1.0 == 0.0) "${memoryGb.toInt()} GB" else "$memoryGb GB") else null
        return listOfNotNull(deviceName.ifEmpty { null }, gb, how).joinToString(" · ")
    }

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

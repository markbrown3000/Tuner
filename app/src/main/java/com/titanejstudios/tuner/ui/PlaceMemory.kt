package com.titanejstudios.tuner.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Remembers which tile, row or button you were on in each screen of the back stack, so Back
 * (or a programme ending) puts the highlight back where you left it instead of on the first item.
 *
 * Screens mark their items with FocusSurface(memoryKey = …). Scroll positions and the screen's own
 * choices (category, channel) come back through MainActivity's saved state for each screen.
 */
class PlaceMemory(private val entry: () -> String) {
    /** Last highlighted item of each screen in the back stack ("3/Catchup" → "p|bbc1|1732…"). */
    private val focus = HashMap<String, String>()
    private var pendingEntry: String? = null
    private var pendingKey: String? = null
    private var pendingAt = 0L

    /** An item was highlighted in the current screen. */
    fun note(key: String) {
        focus[entry()] = key
    }

    /** Sets what [entryKey] should highlight when you come back to it (e.g. the episode that autoplayed). */
    fun set(entryKey: String, key: String) {
        focus[entryKey] = key
    }

    /** You've come back to [entryKey]: its last highlighted item gets the highlight again. */
    fun returnedTo(entryKey: String) {
        pendingEntry = entryKey
        pendingKey = focus[entryKey]
        pendingAt = System.currentTimeMillis()
    }

    /** Whether the item [key] should take the highlight now (once, soon after coming back). */
    fun shouldRestore(key: String): Boolean =
        pendingKey == key && pendingEntry == entry() && System.currentTimeMillis() - pendingAt < 2_500

    fun restored() {
        pendingEntry = null
        pendingKey = null
    }

    /** Forgets screens that are no longer in the back stack. */
    fun keepOnly(entries: Collection<String>) {
        focus.keys.retainAll(entries.toSet())
        if (pendingEntry != null && pendingEntry !in entries) restored()
    }
}

/** The place memory for the screens being drawn (null in previews and dialogs outside the app). */
val LocalPlaceMemory = staticCompositionLocalOf<PlaceMemory?> { null }

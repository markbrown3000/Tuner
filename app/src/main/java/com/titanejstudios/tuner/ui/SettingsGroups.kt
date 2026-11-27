package com.titanejstudios.tuner.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Settings in groups: a short menu on the left (Channels & guide, Watching, Look…) and only that
 * group's settings on the right, instead of one very long list. Search shows matching rows from
 * every group. Rarely-used rows sit behind "Advanced" at the end of their section.
 */
object SettingsGroups {
    class Group(val key: String, val label: String, val sections: Set<String>)

    val all = listOf(
        Group("channels", "📺  Channels & guide", setOf("Playlists", "Your usual channel", "Channel health", "Channels")),
        Group("watching", "▶  Watching", setOf("Playback", "Skipping & previews", "Sound & subtitles", "Languages")),
        Group("look", "🎨  Look & Home", setOf("Look", "Easier to see and hear", "Home & recommendations")),
        Group("profiles", "👤  Profiles & parental controls", setOf("Profiles", "Parental controls")),
        Group("films", "🎬  Films, series & Plex", setOf("Films & series", "Plex & scores")),
        Group("recordings", "⏺  Recordings & reminders", setOf("Recordings", "Reminders")),
        Group("connected", "🔗  Connected", setOf("Devices & sync", "Google TV", "Phone remote", "Apps", "VPN", "Home Assistant")),
        Group("thistv", "ℹ  This TV, updates & help", setOf("Start-up", "When the TV turns on", "This TV", "Help", "Backup", "Updates", "About")),
    )

    fun groupOf(section: String): String = all.firstOrNull { section in it.sections }?.key ?: "thistv"
}

/** Words being searched for in Settings ("" = not searching): rows that don't match hide themselves. */
val LocalSettingsQuery = compositionLocalOf { "" }

/** Whether a row with this text matches the search. */
fun settingsMatch(query: String, vararg text: String?): Boolean {
    val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val all = text.filterNotNull().joinToString(" ").lowercase()
    return words.all { it in all }
}

/**
 * The Settings list, filtered: [section] starts a section (its title shows only in its own group),
 * [advanced] marks the rest of the section as rarely used. Items outside the chosen group are
 * left out entirely, so the list is short.
 */
class SettingsScope(
    private val inner: LazyListScope,
    private val group: String,
    private val query: String,
    private val showAdvanced: Boolean,
    private val onShowAdvanced: () -> Unit,
) : LazyListScope by inner {
    private var current = "Playlists"
    private var inAdvanced = false
    private val searching get() = query.isNotBlank()

    private val visible: Boolean
        get() = searching || (SettingsGroups.groupOf(current) == group && (!inAdvanced || showAdvanced))

    fun section(title: String) {
        current = title
        inAdvanced = false
        if (!searching && SettingsGroups.groupOf(title) == group) inner.item(key = "section|$title") { SectionTitle(title) }
    }

    /** The rows after this in the section are tucked behind "⚙ Advanced". */
    fun advanced() {
        if (!searching && !showAdvanced && SettingsGroups.groupOf(current) == group) {
            val name = current
            val show = onShowAdvanced
            inner.item(key = "advanced|$name") {
                SettingRow("⚙ Advanced", subtitle = "More $name settings you'll rarely need", onClick = show)
            }
        }
        inAdvanced = true
    }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        if (visible) inner.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        if (visible) inner.items(count, key, contentType, itemContent)
    }
}

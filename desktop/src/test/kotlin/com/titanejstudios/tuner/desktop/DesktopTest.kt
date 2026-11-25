package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.data.FilmName
import com.titanejstudios.tuner.data.Fuzzy
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the PC app's own rules (run on every GitHub build, before the installer's made). */
class DesktopTest {
    @Test
    fun updatesCompareVersionsProperly() {
        assertTrue(Updater.newer("6.5.10", "6.5.9"))
        assertTrue(Updater.newer("v7.0.0", "6.9.9"))
        assertFalse(Updater.newer("6.5.7", "6.5.7"))
        assertFalse(Updater.newer("6.5.6", "6.5.7"))
        assertTrue(Updater.newer("6.6", "6.5.9"))
    }

    @Test
    fun searchForgivesSmallTypos() {
        assertTrue(Fuzzy.matches("strictley", "Strictly Come Dancing"))
        assertTrue(Fuzzy.matches("eastenders", "EastEnders"))
        assertTrue(Fuzzy.matches("lord rings", "The Lord of the Rings: The Two Towers"))
        assertTrue(Fuzzy.matches("bbc one", "BBC One HD"))
        assertFalse(Fuzzy.matches("football", "Gardeners' World"))
        assertFalse(Fuzzy.matches("", "Anything"))
        // Closer matches score higher.
        assertTrue(Fuzzy.score("news", "BBC News") > Fuzzy.score("nevs", "BBC News"))
    }

    @Test
    fun collectionsAndBookmarksSurviveSaving() {
        val film = WatchItem("f1", false, "Elf (2003)", "http://x/elf.jpg", "Christmas", 1L, url = "http://x/elf.mp4")
        val map = mapOf("Christmas films" to listOf(film))
        val back = Watchlist.decodeCollections(Watchlist.encodeCollections(map))
        assertEquals("Elf (2003)", back["Christmas films"]?.single()?.name)
        val b = Watchlist.Bookmark("b1", "f1", "Elf", null, null, "http://x/elf.mp4", null, 61_000L, 5L)
        val bb = Watchlist.decodeBookmarks(Watchlist.encodeBookmarks(listOf(b))).single()
        assertEquals(61_000L, bb.posMs)
        assertEquals(null, bb.subtitle)
        assertEquals(emptyMap(), Watchlist.decodeCollections("not json"))
    }

    @Test
    fun filmNamesLoseTheirExtras() {
        assertEquals("Elf" to "2003", FilmName.parse("Elf (2003)"))
    }
}

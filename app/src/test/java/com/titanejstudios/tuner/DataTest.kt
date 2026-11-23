package com.titanejstudios.tuner

import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.integrations.TvSync
import org.junit.Assert.assertEquals
import org.junit.Test

/** My list, followed series and TV sync merging. */
class DataTest {

    @Test
    fun watchlistRoundTrip() {
        val film = WatchItem.of(VodItem("s1/m1", "Paddington", "http://p.jpg", "Family", "http://x/1.mp4", "7.8"), 1L)
        val list = Watchlist.toggle(emptyList(), film)
        val back = Watchlist.decode(Watchlist.encode(list))
        assertEquals(list, back)
        assertEquals(emptyList<WatchItem>(), Watchlist.toggle(back, film))
    }

    @Test
    fun newEpisodesAreCounted() {
        val s = WatchItem("s1/s9", true, "Show", null, "Drama", 0L, sourceId = "s1", seriesId = "9")
        val f = Watchlist.watched(emptyList(), s, 10, 100L).single()
        assertEquals(0, Watchlist.withCount(f, 10).newEpisodes)
        assertEquals(2, Watchlist.withCount(f, 12).newEpisodes)
        // Provider removed some: just remember the new number.
        assertEquals(8, Watchlist.withCount(f, 8).episodeCount)
        assertEquals(f.copy(), Watchlist.decodeFollowed(Watchlist.encodeFollowed(listOf(f))).single())
    }

    private fun entry(id: String, at: Long, pos: Long) = ResumeEntry(id, id, null, null, "http://x/$id", pos, 100_000L, at, null)

    @Test
    fun resumePointsMergeNewestFirst() {
        val here = mapOf("a" to entry("a", 10, 1_000), "b" to entry("b", 50, 2_000))
        val there = mapOf("a" to entry("a", 20, 9_000), "c" to entry("c", 5, 3_000))
        val merged = TvSync.mergeResume(here, there)
        assertEquals(9_000L, merged["a"]?.positionMs)
        assertEquals(setOf("a", "b", "c"), merged.keys)
    }

    @Test
    fun whoseListWins() {
        assertEquals("here", TvSync.decide(5, 0, remoteHasIt = false))
        assertEquals("there", TvSync.decide(5, 9, remoteHasIt = true))
        assertEquals("here", TvSync.decide(9, 5, remoteHasIt = true))
        assertEquals("same", TvSync.decide(5, 5, remoteHasIt = true))
    }
}

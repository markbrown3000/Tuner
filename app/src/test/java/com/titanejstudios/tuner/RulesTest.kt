package com.titanejstudios.tuner

import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.RecordingPlanner
import com.titanejstudios.tuner.data.SafeMode
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.integrations.CinemaLights
import com.titanejstudios.tuner.playback.StreamCushion
import com.titanejstudios.tuner.playback.VolumeLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bedtime, clashes, buffering, lights, sport and other rules. */
class RulesTest {
    private val hour = 3_600_000L

    @Test
    fun bedtime() {
        val seven = 19 * 60
        assertFalse(KidsRules.isBedtime(seven, 18 * 60))
        assertTrue(KidsRules.isBedtime(seven, 19 * 60))
        assertTrue(KidsRules.isBedtime(seven, 2 * 60))      // 2am
        assertFalse(KidsRules.isBedtime(seven, 7 * 60))     // after 6am
        assertFalse(KidsRules.isBedtime(-1, 23 * 60))       // no bedtime set
        assertEquals(5, KidsRules.minutesToBedtime(seven, 18 * 60 + 55))
        assertNull(KidsRules.minutesToBedtime(seven, 20 * 60))
    }

    @Test
    fun timeLeftWarnings() {
        assertEquals(5, KidsRules.warningMinutes(290, null))
        assertEquals(1, KidsRules.warningMinutes(50, null))
        assertNull(KidsRules.warningMinutes(600, null))
        assertEquals(5, KidsRules.warningMinutes(Long.MAX_VALUE, 5))
        assertNull(KidsRules.warningMinutes(Long.MAX_VALUE, null))
    }

    private fun rec(id: String, ch: String, start: Long, stop: Long) =
        Recording(id, ch, ch, id, start, stop, "http://x/$ch", "/tmp/$id.ts", RecStatus.SCHEDULED)

    @Test
    fun clashesOnlyBetweenDifferentChannels() {
        val now = 0L
        val a = rec("a", "bbc1", 10 * hour, 11 * hour)
        val b = rec("b", "itv", 10 * hour + 30 * 60_000L, 12 * hour)
        val c = rec("c", "bbc1", 10 * hour + 30 * 60_000L, 11 * hour)
        val d = rec("d", "ch4", 13 * hour, 14 * hour)
        val clashes = RecordingPlanner.clashes(listOf(a, b, c, d), now)
        assertEquals(setOf("a", "c"), clashes["b"].orEmpty().map { it.id }.toSet())
        assertTrue(clashes["a"].orEmpty().any { it.id == "b" })
        assertFalse(clashes["a"].orEmpty().any { it.id == "c" })   // same channel = one stream
        assertNull(clashes["d"])
        assertEquals(2, RecordingPlanner.peakStreams(listOf(a, b, c)))
    }

    @Test
    fun cushionGrowsAndFades() {
        val now = 1_000_000_000L
        assertEquals(0L, StreamCushion.cushionFor(null, now))
        assertEquals(3_000L, StreamCushion.cushionFor(1 to now, now))
        assertEquals(10_000L, StreamCushion.cushionFor(3 to now, now))
        assertEquals(0L, StreamCushion.cushionFor(3 to now, now + 15 * 24 * hour))

        var saved: Map<String, String> = emptyMap()
        val c = StreamCushion({ saved }, { saved = it })
        c.trouble("ch1", now)
        c.trouble("ch1", now)
        assertEquals(6_000L, c.cushionMs("ch1", now))
        c.healthy("ch1")
        assertEquals(3_000L, c.cushionMs("ch1", now))
        assertEquals("1|$now", saved["ch1"])
    }

    @Test
    fun cinemaLights() {
        assertTrue(CinemaLights.shouldDim("films", "film", fullScreen = true, paused = false))
        assertFalse(CinemaLights.shouldDim("films", "film", fullScreen = true, paused = true))
        assertFalse(CinemaLights.shouldDim("films", "episode", fullScreen = true, paused = false))
        assertTrue(CinemaLights.shouldDim("films_series", "episode", fullScreen = true, paused = false))
        assertFalse(CinemaLights.shouldDim("everything", "live", fullScreen = false, paused = false))
        assertFalse(CinemaLights.shouldDim("off", "film", fullScreen = true, paused = false))
    }

    @Test
    fun volumeLevels() {
        assertEquals(1f, VolumeLevels.linear(0))
        assertEquals(1f, VolumeLevels.linear(6))
        assertTrue(VolumeLevels.linear(-6) in 0.49f..0.51f)
        assertEquals(600, VolumeLevels.boostMb(6))
        assertEquals(0, VolumeLevels.boostMb(-3))
        assertEquals(3, VolumeLevels.next(0))
        assertEquals(-9, VolumeLevels.next(9))
    }

    @Test
    fun crashesAreNamedInPlainEnglish() {
        assertEquals("Night mode sound", SafeMode.featureFor("com.titanejstudios.tuner.playback.NightMode"))
        assertEquals("TV's Live tab", SafeMode.featureFor("com.titanejstudios.tuner.tv.LiveChannelSync"))
        assertNull(SafeMode.featureFor("com.titanejstudios.tuner.ui.HomeScreenKt"))
    }

    @Test
    fun followedTeamsInTheGuide() {
        assertTrue(SportFollow.mentions("Newcastle v Tottenham", "Newcastle"))
        assertFalse(SportFollow.mentions("Newcastleton Show", "Newcastle"))
        val now = 100 * hour
        val ch1 = Channel("s/1", "Sky Sports", "Sport", null, "http://x/1", null)
        val ch2 = Channel("s/2", "Sky Sports HD", "Sport", null, "http://x/2", null)
        val match = Programme(now + 2 * hour, now + 4 * hour, "Newcastle v Tottenham", null, "Sport")
        val other = Programme(now + 5 * hour, now + 7 * hour, "Arsenal v Chelsea", null, "Sport")
        assertTrue(Sports.classify(match) != null)
        val epg = mapOf("s/1" to listOf(match, other), "s/2" to listOf(match))
        val found = SportFollow.find(setOf("Newcastle"), listOf(ch1, ch2), epg, favourites = setOf("s/2"), now = now)
        assertEquals(1, found.size)                 // same match on two channels counts once
        assertEquals("s/2", found[0].channel.id)    // favourite channel preferred
    }
}

class ChannelMergeTest {
    private fun ch(id: String, name: String, group: String = "UK", number: Int = 0) =
        com.titanejstudios.tuner.data.Channel(id, name, group, null, "http://x/$id", null, number = number)

    @Test
    fun copiesShowOnceBestFirst() {
        val list = listOf(
            ch("1", "BBC One FHD (1080p)", number = 513),
            ch("2", "BBC One HD (720p)", number = 514),
            ch("3", "BBC One SD (576p)", number = 515),
            ch("4", "BBC Two HD (720p)", number = 517),
            ch("5", "BBC One FHD (1080p)", group = "Other"),
        )
        val r = com.titanejstudios.tuner.data.ChannelMerge.merge(list, emptyMap())
        assertEquals(listOf("1", "4", "5"), r.channels.map { it.id })
        assertEquals(listOf("1", "2", "3"), r.versions["1"]?.map { it.id })
        // Picking the 720p version keeps BBC One's place and number.
        val key = com.titanejstudios.tuner.data.ChannelMerge.key("BBC One SD")
        val r2 = com.titanejstudios.tuner.data.ChannelMerge.merge(list, mapOf(key to "2"))
        assertEquals("2", r2.channels[0].id)
        assertEquals(513, r2.channels[0].number)
        assertEquals("FHD 1080p", com.titanejstudios.tuner.data.ChannelMerge.versionLabel(list[0]))
    }

    @Test
    fun cricketIsNotFootball() {
        fun sport(title: String) = Sports.classify(Programme(0, 3_600_000, title, null, "Sport"))
        assertEquals("Cricket", sport("Live 2nd Test Day 1: SA v BAN"))
        assertEquals("Cricket", sport("Men's Asiacup Risingstars SI V Ban Live"))
        assertEquals("Cricket", sport("Live: 2026 ACC Men's Asia Cup Rising Stars"))
        assertEquals("Cricket", sport("ICC T20 World Cup: England v India"))
        assertEquals("Rugby", sport("Rugby World Cup: Wales v Fiji"))
        assertEquals("Football", sport("Newcastle v Tottenham"))
        assertEquals("Football", sport("AFC Asian Cup: Japan v Iraq"))
    }

    @Test
    fun filmNamesAreTidiedForPlex() {
        val f = com.titanejstudios.tuner.data.FilmName
        assertEquals("Home Alone" to "1990", f.parse("EN - Home Alone (1990) 4K"))
        assertEquals("Home Alone" to "1990", f.parse("|UK| Home Alone 1990"))
        assertEquals("Blade Runner 2049" to "2017", f.parse("Blade Runner 2049 (2017)"))
        assertEquals("1917" to null, f.parse("1917"))
        assertEquals("Trolls" to null, f.parse("[EN] Trolls HD"))
    }

    @Test
    fun broadcasterAppsMatchTheirChannels() {
        val c = com.titanejstudios.tuner.data.CatchupApps
        assertEquals("BBC iPlayer", c.forChannel("UK: BBC One HD")?.name)
        assertEquals("BBC iPlayer", c.forChannel("CBeebies")?.name)
        assertEquals("ITVX", c.forChannel("ITV1 London FHD")?.name)
        assertEquals("Channel 4", c.forChannel("UK| E4 HD")?.name)
        assertEquals("Channel 4", c.forChannel("Film4")?.name)
        assertEquals("My5", c.forChannel("5Star")?.name)
        assertEquals("My5", c.forChannel("Channel 5 HD")?.name)
        assertNull(c.forChannel("Sky Sports Main Event"))
    }

    @Test
    fun countryTagsAreFound() {
        val l = com.titanejstudios.tuner.data.Languages
        assertEquals("UK", l.tagOf("UK | Sports")?.code)
        assertEquals("AR", l.tagOf("|AR| MBC 1")?.code)
        assertEquals("FR", l.tagOf("[FR] Films")?.code)
        assertEquals("UK", l.tagOf("VIP | UK Sport")?.code)
        assertEquals("AR", l.tagOf("Arabic Movies")?.code)
        assertEquals("US", l.tagOf("USA: NBC")?.code)
        assertNull(l.tagOf("Sky Sports Main Event"))
        assertNull(l.tagOf("BBC One HD"))
        assertNull(l.tagOf("4K | Movies"))
    }

    @Test
    fun tasteLearnsWhatYouWatch() {
        val t = com.titanejstudios.tuner.data.Taste()
        val now = 1_700_000_000_000L
        t.record("Live: Match of the Day", "Sport", 3600, now)
        t.record("Pointless", "Entertainment", 30, now)
        assertTrue(t.isYourShow("Match of the Day"))
        assertFalse(t.isYourShow("Pointless"))
        assertTrue(t.score("Match of the Day 2", "Sport", now).first > t.score("Bake Off", "Entertainment", now).first)
        t.dislike("Bake Off", null)
        assertTrue(t.score("Bake Off", "Entertainment", now).first < 0)
        val back = com.titanejstudios.tuner.data.Taste.fromJson(t.toJson())
        assertTrue(back.isYourShow("match of the day"))
    }

    @Test
    fun usualChannelNeedsAClearHabit() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 23, 18, 10, 0) } // a Monday
        val six = cal.timeInMillis
        var json = "{}"
        json = com.titanejstudios.tuner.data.Habits.add(json, six, "bbc1", 3_000)
        json = com.titanejstudios.tuner.data.Habits.add(json, six, "itv", 600)
        assertEquals("bbc1", com.titanejstudios.tuner.data.Habits.usual(json, six))
        // Nothing usual at 3 in the morning, or on a Saturday at 6.
        cal.set(java.util.Calendar.HOUR_OF_DAY, 3)
        assertEquals(null, com.titanejstudios.tuner.data.Habits.usual(json, cal.timeInMillis))
        cal.set(2026, java.util.Calendar.NOVEMBER, 28, 18, 10, 0)
        assertEquals(null, com.titanejstudios.tuner.data.Habits.usual(json, cal.timeInMillis))
    }
}

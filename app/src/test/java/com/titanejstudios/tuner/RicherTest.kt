package com.titanejstudios.tuner

import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.Lite
import com.titanejstudios.tuner.data.Moods
import com.titanejstudios.tuner.data.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RicherTest {
    @Test
    fun voiceCommands() {
        assertEquals("eastenders", (VoiceCommand.parse("Record EastEnders") as VoiceCommand.Record).what)
        assertEquals("match of the day", (VoiceCommand.parse("remind me about Match of the Day please") as VoiceCommand.Remind).what)
        assertTrue(VoiceCommand.parse("play the next episode") === VoiceCommand.NextEpisode)
        assertTrue(VoiceCommand.parse("Next episode") === VoiceCommand.NextEpisode)
        val later = VoiceCommand.parse("What's on BBC One later?") as VoiceCommand.WhatsOn
        assertEquals("bbc one", later.channel)
        assertTrue(later.later)
        val now = VoiceCommand.parse("whats on itv") as VoiceCommand.WhatsOn
        assertEquals("itv", now.channel)
        assertFalse(now.later)
        assertEquals("whatson", (VoiceCommand.parse("what's on tonight") as VoiceCommand.Go).place)
        assertEquals("dune", (VoiceCommand.parse("Where can I watch Dune?") as VoiceCommand.WhereToWatch).what)
        assertEquals("guide", (VoiceCommand.parse("go to the TV guide") as VoiceCommand.Go).place)
        assertEquals("funny", (VoiceCommand.parse("something funny") as VoiceCommand.Mood).key)
        assertTrue(VoiceCommand.parse("surprise me") === VoiceCommand.Surprise)
        // Channels and plain searches are left alone.
        assertNull(VoiceCommand.parse("BBC One"))
        assertNull(VoiceCommand.parse("watch Sky Sports Main Event"))
        assertNull(VoiceCommand.parse("family guy"))
    }

    @Test
    fun moodsFitByCategoryGenreAndLength() {
        val funny = Moods.byKey("funny")!!
        assertTrue(Moods.fits(funny, listOf("EN | Comedy"), null))
        assertFalse(Moods.fits(funny, listOf("EN | Horror"), null))
        val feelgood = Moods.byKey("feelgood")!!
        assertFalse(Moods.fits(feelgood, listOf("Comedy", "Horror"), null))
        val short = Moods.byKey("short")!!
        assertTrue(Moods.fits(short, emptyList(), 88))
        assertFalse(Moods.fits(short, emptyList(), 150))
        assertFalse(Moods.fits(short, emptyList(), null))
        assertEquals("scary", Moods.fromSpeech("i want something scary")?.key)
        assertNull(Moods.fromSpeech("horror"))
    }

    @Test
    fun liteModeChoice() {
        assertTrue(Lite.isLowMemory(false, 0, 1_450_000_000L)) // Fire TV Stick 4K (1.5 GB)
        assertFalse(Lite.isLowMemory(false, 192, 1_950_000_000L)) // a 2 GB Google TV
        assertTrue(Lite.isLowMemory(true, 0, 0L))
        assertTrue(Lite.decide("auto", true))
        assertFalse(Lite.decide("auto", false))
        assertTrue(Lite.decide("on", false))
        assertFalse(Lite.decide("off", true))
        assertEquals("on", Lite.next("auto"))
    }

    @Test
    fun liteModeKnowsTheDevice() {
        assertEquals("Fire TV Stick 4K (1st gen)", Lite.niceName("Amazon", "AFTMM"))
        assertEquals("Amazon Fire TV (AFTXYZ)", Lite.niceName("Amazon", "AFTXYZ"))
        assertEquals("Google Chromecast", Lite.niceName("Google", "Chromecast"))
        assertEquals("NVIDIA SHIELD Android TV", Lite.niceName("NVIDIA", "SHIELD Android TV"))
        assertEquals(1.5, Lite.roundedGb(1_430_000_000L), 0.001)
        assertEquals(2.0, Lite.roundedGb(1_950_000_000L), 0.001)
        assertEquals("under 2 GB of memory", Lite.why(false, 196, 1_430_000_000L))
        assertEquals("enough memory", Lite.why(false, 192, 1_950_000_000L))
    }

    @Test
    fun bedtimeHeadsUpAtTenMinutes() {
        assertTrue(KidsRules.bedtimeHeadsUp(10))
        assertTrue(KidsRules.bedtimeHeadsUp(9))
        assertFalse(KidsRules.bedtimeHeadsUp(5))
        assertFalse(KidsRules.bedtimeHeadsUp(null))
    }
}

package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.ChannelMerge
import com.titanejstudios.tuner.data.Http
import com.titanejstudios.tuner.data.Languages
import com.titanejstudios.tuner.data.M3uParser
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.XmltvParser
import com.titanejstudios.tuner.data.XtreamClient

/** Loads channels and the TV guide from your playlists – the same code the TV app uses. */
object Library {
    class Loaded(val channels: List<Channel>, val guide: Map<String, List<Programme>>, val problems: List<String>)

    fun load(sources: List<Source>, englishOnly: Boolean, merge: Boolean, onStatus: (String) -> Unit): Loaded {
        val all = ArrayList<Channel>()
        val problems = ArrayList<String>()
        val guideUrls = ArrayList<Pair<String, String>>()
        for (s in sources) {
            onStatus("Loading ${s.name}…")
            try {
                when (s) {
                    is Source.M3u -> {
                        val r = Http.open(s.playlistUrl.trim()).use { M3uParser.parse(it, s.id) }
                        all += r.channels
                        (s.epgUrl ?: r.epgUrl)?.let { guideUrls += s.id to it }
                    }
                    is Source.Xtream -> {
                        all += XtreamClient.load(s)
                        guideUrls += s.id to (s.epgUrl ?: XtreamClient.epgUrl(s))
                    }
                }
            } catch (e: Exception) {
                problems += "${s.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        var channels: List<Channel> = all
        if (englishOnly) {
            val hidden = Languages.nonEnglish
            channels = channels.filter { (Languages.tagOf(it)?.code ?: Languages.NONE) !in hidden }
        }
        if (merge) channels = ChannelMerge.merge(channels, emptyMap()).channels

        // The guide: from 3 hours ago to a day and a half ahead (plus catch-up days for channels that have it).
        val guide = HashMap<String, ArrayList<Programme>>()
        val now = System.currentTimeMillis()
        for ((sourceId, url) in guideUrls) {
            onStatus("Loading the TV guide…")
            val mine = all.filter { it.sourceId == sourceId }
            val byId = HashMap<String, MutableList<String>>()
            val byName = HashMap<String, MutableList<String>>()
            val catchupFrom = HashMap<String, Long>()
            for (c in mine) {
                c.epgId?.let { byId.getOrPut(it.lowercase()) { ArrayList() }.add(c.id) }
                byName.getOrPut(XmltvParser.normalise(c.name)) { ArrayList() }.add(c.id)
                if (c.hasCatchup) catchupFrom[c.id] = now - c.catchupDays.coerceAtMost(7) * 86_400_000L
            }
            try {
                Http.open(url.trim()).use { input ->
                    XmltvParser.parse(input, byId, byName, now - 3 * 3_600_000L, now + 36 * 3_600_000L, catchupFrom) { id, p ->
                        guide.getOrPut(id) { ArrayList() }.add(p)
                    }
                }
            } catch (e: Exception) {
                problems += "TV guide: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val sorted = guide.mapValues { (_, l) -> l.sortedBy { it.start }.distinctBy { it.start } }
        return Loaded(channels, sorted, problems)
    }
}

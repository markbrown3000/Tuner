package com.titanejstudios.tuner.data

import android.content.Context
import java.io.IOException

/** Downloads playlists and guides into the database. Shared by the app and the overnight refresh. */
class Repository(context: Context, private val store: Store) {
    private val db = Database.get(context)

    class Refreshed(val channels: List<Channel>, val epgError: String?)

    /** Downloads one playlist + its guide and saves both. Throws if the channels fail. */
    fun refreshSource(src: Source): Refreshed {
        val (list, playlistEpg) = refreshChannels(src)
        return Refreshed(list, refreshGuide(src, list, playlistEpg))
    }

    /** Step 1 (quick): download and save the channel list. Returns the channels and any guide URL in the playlist. */
    fun refreshChannels(src: Source): Pair<List<Channel>, String?> {
        val (list, playlistEpg) = loadChannels(src)
        if (list.isEmpty()) throw IOException("the playlist has no channels")
        db.replaceChannels(src.id, list)
        store.setLastChannels(src.id, System.currentTimeMillis())
        return list to playlistEpg
    }

    /** Step 2 (slow): download and save the TV guide. Returns an error message, or null if it worked. */
    fun refreshGuide(src: Source, list: List<Channel>, playlistEpg: String?): String? {
        val epgUrl = when (src) {
            is Source.M3u -> src.epgUrl?.takeIf { it.isNotBlank() } ?: playlistEpg
            is Source.Xtream -> src.epgUrl?.takeIf { it.isNotBlank() } ?: XtreamClient.epgUrl(src)
        }
        var epgError: String? = null
        if (epgUrl != null) {
            val started = System.currentTimeMillis()
            val bytes = Http.received.get()
            try {
                loadEpg(src.id, epgUrl, list)
                val secs = (System.currentTimeMillis() - started) / 1000
                AppLog.info("TV guide updated for ${src.name} in ${secs}s (" + String.format(java.util.Locale.UK, "%.1f MB", (Http.received.get() - bytes) / 1_000_000.0) + ")")
            } catch (e: Exception) {
                epgError = e.message ?: e.javaClass.simpleName
            }
        }
        // Extra guides fill what your provider's guide doesn't have (more days, missing channels).
        for (extra in store.extraGuides) {
            val started = System.currentTimeMillis()
            runCatching { addExtraEpg(src.id, extra, list) }
                .onSuccess { n -> AppLog.info("Extra TV guide: added $n programmes for ${src.name} in ${(System.currentTimeMillis() - started) / 1000}s") }
                .onFailure { AppLog.warn("Extra TV guide failed ($extra)", it) }
        }
        val t = System.currentTimeMillis()
        store.setLastGuide(src.id, t)
        store.setLastRefresh(src.id, t)
        return epgError
    }

    /** How far ahead the guide is saved: a week, so it only needs fetching once a day (or less). */
    private val guideDays = 7

    /** Which of your channels each guide id and name belongs to. */
    private fun matchers(list: List<Channel>): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val overrides = store.epgOverrides
        val byId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        for (c in list) {
            val manual = overrides[c.id]
            if (manual != null) byId.getOrPut(manual.lowercase()) { ArrayList() }.add(c.id)
            else {
                c.epgId?.let { byId.getOrPut(it.lowercase()) { ArrayList() }.add(c.id) }
                byName.getOrPut(XmltvParser.normalise(c.name)) { ArrayList() }.add(c.id)
            }
        }
        return byId to byName
    }

    /**
     * An extra guide: for each channel, only programmes after what's already saved (so your
     * provider's own guide wins where it has listings). Returns how many were added.
     */
    private fun addExtraEpg(sourceId: String, url: String, list: List<Channel>): Int {
        val (byId, byName) = matchers(list)
        val now = System.currentTimeMillis()
        val have = db.lastStops(sourceId)
        return Http.open(url.trim()).use { input ->
            db.addProgrammes(sourceId) { add ->
                XmltvParser.parse(
                    input, byId, byName,
                    from = now - 3 * 3600_000L,
                    to = now + guideDays * 86_400_000L,
                    onProgramme = { channelId, p ->
                        if (p.start >= (have[channelId] ?: 0L)) add(channelId, p)
                    },
                )
            }
        }
    }

    private fun loadChannels(src: Source): Pair<List<Channel>, String?> = when (src) {
        is Source.M3u -> {
            val r = Http.open(src.playlistUrl.trim()).use { M3uParser.parse(it, src.id) }
            r.channels to r.epgUrl
        }
        is Source.Xtream -> XtreamClient.load(src) to null
    }

    private fun loadEpg(sourceId: String, url: String, list: List<Channel>) {
        val overrides = store.epgOverrides
        val byId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        val catchupFrom = HashMap<String, Long>()
        val now = System.currentTimeMillis()
        for (c in list) {
            val manual = overrides[c.id]
            if (manual != null) {
                // A guide match picked by hand wins over tvg-id and name matching.
                byId.getOrPut(manual.lowercase()) { ArrayList() }.add(c.id)
            } else {
                c.epgId?.let { byId.getOrPut(it.lowercase()) { ArrayList() }.add(c.id) }
                byName.getOrPut(XmltvParser.normalise(c.name)) { ArrayList() }.add(c.id)
            }
            if (c.hasCatchup) catchupFrom[c.id] = now - c.catchupDays.coerceAtMost(7) * 86_400_000L
        }
        val missingLogo = list.filter { it.logo.isNullOrBlank() }.map { it.id }.toHashSet()
        val guideLogos = HashMap<String, String>()
        Http.open(url.trim()).use { input ->
            db.replaceEpg(sourceId) { addProgramme, addXmlChannel ->
                XmltvParser.parse(
                    input, byId, byName,
                    from = now - 3 * 3600_000L,
                    to = now + guideDays * 86_400_000L,
                    catchupFrom = catchupFrom,
                    onChannel = addXmlChannel,
                    onChannelLogo = { ids, logo -> for (id in ids) if (id in missingLogo) guideLogos.putIfAbsent(id, logo) },
                    onProgramme = addProgramme,
                )
            }
        }
        // Channels with no logo in the playlist borrow the guide's.
        if (guideLogos.isNotEmpty()) db.fillMissingLogos(sourceId, guideLogos)
    }
}

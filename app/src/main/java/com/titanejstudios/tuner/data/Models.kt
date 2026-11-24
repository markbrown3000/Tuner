package com.titanejstudios.tuner.data

data class Channel(
    /** Stable key ("<sourceId>/…") used for favourites, recents, hiding and EPG lookup. */
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    /** tvg-id / epg_channel_id used to match XMLTV guide data. */
    val epgId: String?,
    val sourceId: String = "",
    /** How many days back catch-up is available (0 = none). */
    val catchupDays: Int = 0,
    /** Catch-up URL template with {utc}/{duration}/{Y}… placeholders – see [Catchup]. */
    val catchupTemplate: String? = null,
    /** Time zone for the template's date fields (Xtream panels use the server's zone). */
    val catchupTz: String? = null,
    /** The provider's own channel number (Xtream "num" / M3U tvg-chno); 0 = none. */
    val number: Int = 0,
    /** Position of this channel's category in the provider's category order. */
    val groupPos: Int = 0,
) {
    val hasCatchup: Boolean get() = catchupDays > 0 && catchupTemplate != null
}

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
    /** XMLTV <category>, e.g. "Sport", "Film", "News". */
    val category: String? = null,
    /** XMLTV <icon src="…"> – a picture for the programme, if the guide has one. */
    val image: String? = null,
    /** Extra guide details: [ProgrammeExtra] packs the episode title, number and NEW/premiere/final. */
    val extra: String? = null,
) {
    /** "The One Where…" (XMLTV <sub-title>). */
    val episodeTitle: String? get() = ProgrammeExtra.part(extra, 1)

    /** "S2 E5" – from XMLTV <episode-num>, or the description ("(S2, ep 5)"). */
    val episodeLabel: String?
        get() = ProgrammeExtra.part(extra, 2) ?: ProgrammeExtra.episodeFromText(desc)

    /** First showing of a new episode. */
    val isNew: Boolean
        get() = 'N' in ProgrammeExtra.flags(extra) || ProgrammeExtra.newText.containsMatchIn(title) || ProgrammeExtra.newDesc.containsMatchIn(desc.orEmpty())

    val isPremiere: Boolean
        get() = 'P' in ProgrammeExtra.flags(extra) || ProgrammeExtra.premiereText.containsMatchIn(title + " " + desc.orEmpty().take(160))

    /** Last in the series (or the last chance to see it). */
    val isFinal: Boolean
        get() = 'L' in ProgrammeExtra.flags(extra) || ProgrammeExtra.finalText.containsMatchIn(title + " " + desc.orEmpty().take(200))
}

/** Packs extra guide details into one stored text: "flags␟episode title␟S2 E5". */
object ProgrammeExtra {
    private const val SEP = '\u001F'

    fun pack(flags: String, episodeTitle: String?, episode: String?): String? {
        if (flags.isEmpty() && episodeTitle.isNullOrBlank() && episode.isNullOrBlank()) return null
        return flags + SEP + episodeTitle.orEmpty().trim() + SEP + episode.orEmpty().trim()
    }

    fun flags(extra: String?): String = extra?.substringBefore(SEP).orEmpty()

    fun part(extra: String?, i: Int): String? = extra?.split(SEP)?.getOrNull(i)?.takeIf { it.isNotBlank() }

    /** XMLTV "xmltv_ns" numbering is zero-based: "1.4.0/1" is series 2, episode 5. */
    fun episodeFromXmltv(system: String?, value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (system.equals("xmltv_ns", ignoreCase = true)) {
            val parts = v.split('.')
            val s = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val e = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            return when {
                s != null && e != null -> "S$s E$e"
                e != null -> "Ep $e"
                else -> null
            }
        }
        if (system.equals("onscreen", ignoreCase = true) || system == null) return episodeFromText(v)
        return null
    }

    private val seriesEp = Regex("(?i)\\b(?:S|Series\\s*)(\\d{1,3})\\s*[,.]?\\s*(?:E|Ep\\.?|Episode\\s*)\\s*(\\d{1,4})\\b")
    private val epOnly = Regex("(?i)\\((?:Ep\\.?|Episode)\\s*(\\d{1,4})(?:\\s*/\\s*\\d+)?\\)")

    fun episodeFromText(text: String?): String? {
        val t = text ?: return null
        seriesEp.find(t)?.let { return "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}" }
        epOnly.find(t)?.let { return "Ep ${it.groupValues[1].toInt()}" }
        return null
    }

    val newText = Regex("(?i)^\\s*new\\s*[:!-]|\\[new\\]|\\(new\\)")
    val newDesc = Regex("(?i)^\\s*(new(\\s+series|\\s+episode)?\\s*[.:!]|\\(new\\)|\\[new\\])")
    val premiereText = Regex("(?i)\\b(premiere|world premiere|tv premiere|first showing)\\b")
    val finalText = Regex("(?i)\\b(series finale|season finale|final episode|last in (the )?series|last episode)\\b")
}

/** "Record every episode": any programme with this title on this channel gets recorded. */
data class SeriesLink(
    val channelId: String,
    val channelName: String,
    val title: String,
    val created: Long,
)

sealed class Source {
    abstract val id: String
    abstract val name: String

    data class M3u(
        override val id: String,
        override val name: String,
        val playlistUrl: String,
        val epgUrl: String?,
    ) : Source()

    data class Xtream(
        override val id: String,
        override val name: String,
        val server: String,
        val username: String,
        val password: String,
        val useHls: Boolean,
        val epgUrl: String?,
    ) : Source()
}

data class VodItem(
    val id: String,
    val name: String,
    val poster: String?,
    val category: String,
    val url: String,
    val rating: String?,
)

data class SeriesItem(
    val id: String,
    val sourceId: String,
    val seriesId: String,
    val name: String,
    val cover: String?,
    val category: String,
    val plot: String?,
    val rating: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int,
)

data class Reminder(
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
)

/** Where you got to in a film or episode. */
data class ResumeEntry(
    val contentId: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesId: String?,
)

/** A guide channel from the XMLTV file (for fixing guide matches by hand). */
data class XmltvChannel(val id: String, val name: String)

/** Someone who watches – their own favourites, recents and (for kids) limits. */
data class Profile(
    val id: String,
    val name: String,
    val kids: Boolean = false,
    /** For kids profiles: the only groups they can see. */
    val allowedGroups: Set<String> = emptySet(),
    /** For kids profiles: minutes of TV per day (0 = no limit). */
    val dailyLimitMinutes: Int = 0,
    /** For kids profiles: no TV after this time (minutes after midnight, -1 = no bedtime) until 6am. */
    val bedtimeMinutes: Int = -1,
    /** Avatar colour (index into the avatar colours; -1 = automatic). */
    val colour: Int = -1,
    /** Avatar picture (an emoji); empty = the first letter of the name. */
    val icon: String = "",
    /** For kids profiles: YouTube allowed (strict SafeSearch, counts towards their TV time). */
    val youtube: Boolean = true,
    /** For kids profiles: only videos from these YouTube channels (no searching). */
    val ytApprovedOnly: Boolean = false,
    /** The approved channels, as "UC…id\tName". */
    val ytChannels: List<String> = emptyList(),
) {
    val ytChannelIds: List<String> get() = ytChannels.map { it.substringBefore('\t') }
}

object RecStatus {
    const val SCHEDULED = "scheduled"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

data class Recording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    /** Recording window (already includes a little padding either side). */
    val start: Long,
    val stop: Long,
    val url: String,
    val path: String,
    val status: String,
    val error: String? = null,
    val category: String? = null,
)

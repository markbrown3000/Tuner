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
)

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
)

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

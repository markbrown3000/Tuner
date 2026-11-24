package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * Finds the teams you follow (Football screen › Follow) in the TV guide, so Tuner can set a
 * reminder or a recording for each match without you looking for it.
 */
object SportFollow {
    /** Sport recordings keep going this long after the guide says they end (extra time, overruns). */
    const val SPORT_EXTRA_MS = 30 * 60_000L

    class Match(val channel: Channel, val programme: Programme) {
        /** Same match on several channels counts once. */
        val key: String get() = keyFor(programme)
    }

    fun keyFor(p: Programme): String = p.title.lowercase(Locale.ROOT).trim() + "|" + p.start

    /** True when [title] mentions [team] as a whole word ("Newcastle" matches "Newcastle v Spurs", not "Newcastleton"). */
    fun mentions(title: String, team: String): Boolean {
        val t = team.trim()
        if (t.length < 3) return false
        return Regex("(^|[^\\p{L}])" + Regex.escape(t) + "($|[^\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(title)
    }

    /**
     * Upcoming matches (starting from 10 minutes ago up to [hoursAhead]) involving any of [teams].
     * Live broadcasts are preferred over highlights, favourite channels over others.
     */
    fun find(
        teams: Set<String>,
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        favourites: Set<String>,
        now: Long,
        hoursAhead: Int = 36,
    ): List<Match> {
        if (teams.isEmpty()) return emptyList()
        val until = now + hoursAhead * 3_600_000L
        val best = LinkedHashMap<String, Match>()
        val ordered = channels.sortedBy { if (it.id in favourites) 0 else 1 }
        for (c in ordered) {
            for (p in epg[c.id].orEmpty()) {
                if (p.start < now - 10 * 60_000L || p.start > until) continue
                if (teams.none { mentions(p.title, it) }) continue
                if (Sports.classify(p, c.group) == null || !Sports.looksLive(p)) continue
                best.putIfAbsent(keyFor(p), Match(c, p))
            }
        }
        return best.values.sortedBy { it.programme.start }
    }
}

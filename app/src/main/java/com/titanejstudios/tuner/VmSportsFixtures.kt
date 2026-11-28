package com.titanejstudios.tuner

import androidx.lifecycle.viewModelScope
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.integrations.Espn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Part of TunerViewModel: the Sports page's "All fixtures" – which of your channels show a match.

/** The names a team might have in the guide ("Newcastle", "Newcastle United", without "FC"). */
internal fun teamNames(n: String): List<String> =
    listOf(n, n.removeSuffix(" FC").removeSuffix(" AFC").removePrefix("AFC ").trim()).filter { it.length >= 3 }.distinct()

/**
 * Every channel of yours with this match on (both teams named, around kick-off), best first:
 * your favourites, live broadcasts, and the one starting nearest kick-off. Blocking.
 */
internal fun TunerViewModel.channelsShowing(m: Espn.Fixture): List<Pair<Channel, Programme>> {
    val ids = activeSources().map { it.id }
    val chans = allowedAll
    val byId = chans.associateBy { it.id }
    val favs = favouriteSet
    val from = m.kickoff - 3 * 3_600_000L
    val hits = HashMap<String, MutableList<Programme>>()
    val seen = HashSet<String>()
    for (term in teamNames(m.home)) {
        for ((cid, p) in db.searchProgrammes(ids, term, from, 400)) {
            if (cid !in byId || p.start > m.kickoff + 2 * 3_600_000L) continue
            if (seen.add(cid + "@" + p.start)) hits.getOrPut(cid) { ArrayList() } += p
        }
    }
    val guide = shiftGuide(chans, hits)
    val found = ArrayList<Triple<Channel, Programme, Int>>()
    for ((cid, list) in guide) {
        val c = byId[cid] ?: continue
        for (p in list) {
            // On around kick-off (a build-up show can start an hour or two before).
            if (p.stop <= m.kickoff || p.start > m.kickoff + 30 * 60_000L || p.start < m.kickoff - 150 * 60_000L) continue
            val both = teamNames(m.home).any { SportFollow.mentions(p.title, it) } && teamNames(m.away).any { SportFollow.mentions(p.title, it) }
            if (!both) continue
            var score = 100
            if (c.id in favs) score += 20
            if (Sports.looksLive(p)) score += 10
            score -= (kotlin.math.abs(p.start - (m.kickoff - 30 * 60_000L)) / 60_000L).toInt().coerceAtMost(60) / 10
            found += Triple(c, p, score)
        }
    }
    return found.sortedByDescending { it.third }.distinctBy { it.first.id }.map { it.first to it.second }
}

/** Clicked a fixture: find its channels, then choose one (to watch now, or a reminder for later). */
fun TunerViewModel.chooseFixtureChannel(m: Espn.Fixture) {
    viewModelScope.launch {
        val all = withContext(Dispatchers.IO) {
            runCatching { channelsShowing(m) }.onFailure { AppLog.warn("All fixtures: couldn't search the guide", it) }.getOrDefault(emptyList())
        }
        val guideEnd = withContext(Dispatchers.IO) { runCatching { db.guideEnd(activeSources().map { it.id }) }.getOrDefault(0L) }
        if (all.isEmpty()) {
            showNotice(
                if (guideEnd > 0 && m.kickoff > guideEnd) "${m.home} v ${m.away} isn't in your TV guide yet – the guide doesn't reach that day"
                else "${m.home} v ${m.away} isn't on any of your channels",
            )
            return@launch
        }
        fixtureChoice = TeamFixture(m, all.first().first, all.first().second, true, all)
    }
}

package com.titanejstudios.tuner.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Live football scores from football-data.org (free account: Premier League, Championship, the big
 * European leagues and the Champions League). Needs your own free API key.
 */
object Football {
    class Match(
        val id: Long,
        val competition: String,
        val home: String,
        val away: String,
        val homeGoals: Int?,
        val awayGoals: Int?,
        /** SCHEDULED, TIMED, IN_PLAY, PAUSED, FINISHED, POSTPONED… */
        val status: String,
        val kickoff: Long,
    ) {
        val live: Boolean get() = status == "IN_PLAY" || status == "PAUSED"
        val finished: Boolean get() = status == "FINISHED"
        val scoreText: String get() = if (homeGoals == null || awayGoals == null) "v" else "$homeGoals – $awayGoals"
    }

    class Goal(val match: Match, val scorer: String)

    /** Today's matches (blocking). */
    fun today(apiKey: String): List<Match> {
        val conn = (URL("https://api.football-data.org/v4/matches").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("X-Auth-Token", apiKey.trim())
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                400, 403 -> throw IllegalArgumentException("football-data.org didn't accept the key")
                429 -> throw IllegalStateException("Too many requests – it'll try again shortly")
                else -> throw IllegalStateException("football-data.org said ${conn.responseCode}")
            }
            return parse(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): List<Match> {
        val arr = JSONObject(json).optJSONArray("matches") ?: return emptyList()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val ft = m.optJSONObject("score")?.optJSONObject("fullTime")
            fun team(key: String) = m.optJSONObject(key)?.let { t -> t.optString("shortName").ifBlank { t.optString("name") } }.orEmpty()
            Match(
                id = m.optLong("id"),
                competition = m.optJSONObject("competition")?.optString("name").orEmpty(),
                home = team("homeTeam"),
                away = team("awayTeam"),
                homeGoals = ft?.takeIf { !it.isNull("home") }?.optInt("home"),
                awayGoals = ft?.takeIf { !it.isNull("away") }?.optInt("away"),
                status = m.optString("status"),
                kickoff = runCatching { iso.parse(m.optString("utcDate"))?.time }.getOrNull() ?: 0L,
            )
        }.sortedWith(compareBy({ !it.live }, { it.kickoff }))
    }

    /** Goals since last time: any match whose score went up. */
    fun goals(previous: Map<Long, Match>, now: List<Match>): List<Goal> {
        val out = ArrayList<Goal>()
        for (m in now) {
            val p = previous[m.id] ?: continue
            if ((m.homeGoals ?: 0) > (p.homeGoals ?: 0)) out += Goal(m, m.home)
            if ((m.awayGoals ?: 0) > (p.awayGoals ?: 0)) out += Goal(m, m.away)
        }
        return out
    }

    private val nonAlnum = Regex("[^a-z0-9]")
    private fun key(s: String) = s.lowercase(Locale.ROOT).replace(nonAlnum, "")

    /** A channel showing this match now, judged by the guide (both team names, or one plus "v"). */
    fun channelFor(match: Match, channels: List<Channel>, epg: Map<String, List<Programme>>, now: Long): Channel? {
        val h = key(match.home).take(6)
        val a = key(match.away).take(6)
        if (h.length < 3 || a.length < 3) return null
        var best: Channel? = null
        for (c in channels) {
            val p = HomeFeed.nowOn(epg[c.id], now) ?: continue
            val t = key(p.title + " " + (p.desc ?: ""))
            if (t.contains(h) && t.contains(a)) return c
            if (best == null && (t.contains(h) || t.contains(a)) && Sports.classify(p) != null) best = c
        }
        return best
    }

    private val clubWords = Regex("\\b(fc|afc|cf|sc|ac)\\b|&", RegexOption.IGNORE_CASE)
    private val nicknames = mapOf(
        "manchester city" to "Man City", "manchester united" to "Man Utd", "tottenham hotspur" to "Spurs",
        "wolverhampton wanderers" to "Wolves", "brighton hove albion" to "Brighton", "nottingham forest" to "Forest",
        "newcastle united" to "Newcastle", "west ham united" to "West Ham", "leeds united" to "Leeds",
        "sheffield united" to "Sheff Utd", "afc bournemouth" to "Bournemouth", "crystal palace" to "Palace",
    )

    /** "Newcastle United FC" → "Newcastle"; for scores on tiles. */
    fun shortName(team: String): String {
        val plain = clubWords.replace(team, " ").replace(Regex("\\s+"), " ").trim()
        nicknames[plain.lowercase(Locale.ROOT)]?.let { return it }
        return if (plain.length <= 12) plain else plain.substringBefore(' ')
    }

    /** True when a programme title names this team ("Newcastle v Arsenal" names "Newcastle United FC"). */
    fun inTitle(title: String, team: String): Boolean {
        val t = key(title)
        val full = key(clubWords.replace(team, " "))
        if (full.length >= 3 && t.contains(full)) return true
        val nick = nicknames[clubWords.replace(team, " ").replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)]
        if (nick != null && t.contains(key(nick))) return true
        val first = key(clubWords.replace(team, " ").trim().substringBefore(' '))
        // "Manchester" alone could be either Manchester club, so only distinctive first words count.
        return first.length >= 5 && first !in setOf("manchester", "sheffield", "bristol", "west") && t.contains(first)
    }
}

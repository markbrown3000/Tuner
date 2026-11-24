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

    /** A team you can follow. [shortName] ("Newcastle") is what the rest of Flicker uses. */
    class Team(val id: Long, val name: String, val shortName: String, val competition: String)

    /** Competitions on football-data.org's free plan whose teams you can pick from. */
    val PICKABLE = listOf("PL" to "Premier League", "ELC" to "Championship", "CL" to "Champions League", "PD" to "La Liga", "SA" to "Serie A", "BL1" to "Bundesliga", "FL1" to "Ligue 1")

    /** One competition's teams (blocking). */
    fun teams(apiKey: String, competition: String, label: String): List<Team> {
        val o = JSONObject(get(apiKey, "https://api.football-data.org/v4/competitions/$competition/teams"))
        val arr = o.optJSONArray("teams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name")
            Team(t.optLong("id"), name, t.optString("shortName").ifBlank { name }, label)
        }
    }

    fun teamsToJson(list: List<Team>): String {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("short", it.shortName).put("comp", it.competition)) }
        return arr.toString()
    }

    fun teamsFromJson(json: String?): List<Team> = runCatching {
        val arr = org.json.JSONArray(json ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Team(o.optLong("id"), o.optString("name"), o.optString("short"), o.optString("comp"))
        }
    }.getOrDefault(emptyList())

    /** Every match of a team this season, as football-data.org's raw JSON objects (blocking). */
    fun teamMatchesRaw(apiKey: String, teamId: Long): List<JSONObject> {
        val arr = JSONObject(get(apiKey, "https://api.football-data.org/v4/teams/$teamId/matches")).optJSONArray("matches") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** A team's league place: "5th", points, games played, which league. */
    class Standing(val position: Int, val points: Int, val played: Int, val goalDifference: Int, val competition: String)

    /** League table positions by team id (blocking). */
    fun standings(apiKey: String, competition: String, label: String): Map<Long, Standing> {
        val o = JSONObject(get(apiKey, "https://api.football-data.org/v4/competitions/$competition/standings"))
        val tables = o.optJSONArray("standings") ?: return emptyMap()
        val out = HashMap<Long, Standing>()
        for (i in 0 until tables.length()) {
            val t = tables.optJSONObject(i) ?: continue
            if (t.optString("type", "TOTAL") != "TOTAL") continue
            val rows = t.optJSONArray("table") ?: continue
            for (j in 0 until rows.length()) {
                val r = rows.optJSONObject(j) ?: continue
                val id = r.optJSONObject("team")?.optLong("id") ?: continue
                out[id] = Standing(r.optInt("position"), r.optInt("points"), r.optInt("playedGames"), r.optInt("goalDifference"), label)
            }
        }
        return out
    }

    /** The last five results for [team] from raw matches, oldest first: "WWDLW". */
    fun form(raw: List<JSONObject>, team: String): String {
        val done = parse(JSONObject().put("matches", org.json.JSONArray(raw)).toString())
            .filter { it.finished && (it.home == team || it.away == team) && it.homeGoals != null && it.awayGoals != null }
            .sortedByDescending { it.kickoff }.take(5).reversed()
        return done.joinToString("") { m ->
            val mine = if (m.home == team) m.homeGoals!! else m.awayGoals!!
            val theirs = if (m.home == team) m.awayGoals!! else m.homeGoals!!
            when {
                mine > theirs -> "W"
                mine == theirs -> "D"
                else -> "L"
            }
        }
    }

    /** Past meetings of the two sides in a fixture: "Newcastle 4 wins · 3 draws · Arsenal 3 wins (last 10)". Blocking. */
    fun headToHead(apiKey: String, matchId: Long): String? {
        val o = JSONObject(get(apiKey, "https://api.football-data.org/v4/matches/$matchId/head2head?limit=10"))
        val agg = o.optJSONObject("aggregates") ?: return null
        val n = agg.optInt("numberOfMatches")
        if (n == 0) return "They haven't met recently"
        val h = agg.optJSONObject("homeTeam") ?: return null
        val a = agg.optJSONObject("awayTeam") ?: return null
        fun name(t: JSONObject) = t.optString("shortName").ifBlank { t.optString("name") }
        return "Last $n meetings: ${name(h)} ${h.optInt("wins")} wins · ${h.optInt("draws")} draws · ${name(a)} ${a.optInt("wins")} wins"
    }

    /** "1st", "2nd", "23rd". */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /** Coming fixtures (and any being played now) from raw match objects, soonest first. */
    fun upcoming(raw: List<JSONObject>, now: Long, perTeamLimit: Int = 99): List<Match> {
        val all = parse(JSONObject().put("matches", org.json.JSONArray(raw)).toString())
        return all.filter { !it.finished && it.status != "POSTPONED" && it.status != "CANCELLED" && it.kickoff > now - 3 * 3_600_000L }
            .distinctBy { it.id }
            .sortedBy { it.kickoff }
            .take(perTeamLimit)
    }

    private fun get(apiKey: String, url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("X-Auth-Token", apiKey.trim())
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                400, 403 -> throw IllegalArgumentException("football-data.org didn't accept the key (or that league isn't on the free plan)")
                429 -> throw IllegalStateException("Too many requests – it'll try again shortly")
                else -> throw IllegalStateException("football-data.org said ${conn.responseCode}")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Which of your channels shows this fixture: a sport programme around kick-off that names one
     * of the teams (both teams and "Live" count for more, then your favourites).
     */
    fun broadcastFor(match: Match, channels: List<Channel>, guide: Map<String, List<Programme>>, favourites: Set<String>): Pair<Channel, Programme>? {
        var best: Pair<Channel, Programme>? = null
        var bestScore = 0
        for (c in channels) {
            for (p in guide[c.id].orEmpty()) {
                if (p.start < match.kickoff - 75 * 60_000L || p.start > match.kickoff + 15 * 60_000L) continue
                val text = p.title + " " + (p.desc ?: "")
                val home = inTitle(text, match.home)
                val away = inTitle(text, match.away)
                if (!home && !away) continue
                if (Sports.classify(p) == null && !(home && away)) continue
                var score = 1
                if (home && away) score += 4
                if (Sports.looksLive(p)) score += 2
                if (c.id in favourites) score += 1
                if (score > bestScore) {
                    bestScore = score
                    best = c to p
                }
            }
        }
        return best
    }

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

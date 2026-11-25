package com.titanejstudios.tuner.integrations

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Football fixtures from ESPN's public site data (no key or account). It isn't an official API,
 * so everything here fails quietly – Flicker then falls back to finding games in the TV guide.
 */
object Espn {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    /** Leagues whose teams you can pick from (ESPN's codes). */
    val LEAGUES = listOf(
        "eng.1" to "Premier League",
        "eng.2" to "Championship",
        "eng.3" to "League One",
        "eng.4" to "League Two",
        "eng.5" to "National League",
        "sco.1" to "Scottish Premiership",
        "sco.2" to "Scottish Championship",
        "wal.1" to "Cymru Premier",
        "irl.1" to "League of Ireland",
        "esp.1" to "LaLiga",
        "ita.1" to "Serie A",
        "ger.1" to "Bundesliga",
        "fra.1" to "Ligue 1",
        "ned.1" to "Eredivisie",
        "por.1" to "Primeira Liga",
        "usa.1" to "MLS",
        "eng.w.1" to "Women's Super League",
    )

    /** A team you can follow: ESPN's id and league, and its names. */
    class Team(val id: String, val league: String, val leagueName: String, val name: String, val shortName: String) {
        /** How it's saved: "eng.1:361". */
        val key: String get() = "$league:$id"

        fun toJson(): JSONObject = JSONObject().put("id", id).put("lg", league).put("lgn", leagueName).put("n", name).put("s", shortName)

        companion object {
            fun fromJson(o: JSONObject) = Team(o.optString("id"), o.optString("lg"), o.optString("lgn"), o.optString("n"), o.optString("s"))
        }
    }

    /** One match. [timeKnown] false = the date's set but not the kick-off time. */
    class Fixture(
        val id: String,
        val home: String,
        val away: String,
        val kickoff: Long,
        val competition: String?,
        /** "pre" (to come), "in" (playing) or "post" (finished). */
        val state: String,
        val timeKnown: Boolean,
        /** Which of your teams this is for. */
        val team: String,
    ) {
        val live: Boolean get() = state == "in"
        val finished: Boolean get() = state == "post"

        fun toJson(): JSONObject = JSONObject().put("id", id).put("h", home).put("a", away).put("k", kickoff)
            .put("c", competition ?: "").put("st", state).put("tk", timeKnown).put("t", team)

        companion object {
            fun fromJson(o: JSONObject) = Fixture(
                o.optString("id"), o.optString("h"), o.optString("a"), o.optLong("k"),
                o.optString("c").ifBlank { null }, o.optString("st", "pre"), o.optBoolean("tk", true), o.optString("t"),
            )
        }
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Flicker")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) throw IllegalStateException("ESPN said ${c.responseCode}")
            return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }

    /** Every team in one league (blocking). */
    fun teams(league: String, leagueName: String): List<Team> {
        val leagues = get("$BASE/$league/teams").optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues") ?: return emptyList()
        val arr = leagues.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
            val id = t.optString("id").ifBlank { return@mapNotNull null }
            val name = t.optString("displayName").ifBlank { t.optString("name") }
            Team(id, league, leagueName, name, t.optString("shortDisplayName").ifBlank { name })
        }
    }

    /** A team's coming matches (cups too), soonest first. Blocking. */
    fun fixtures(team: Team, followedName: String): List<Fixture> {
        val o = get("$BASE/${team.league}/teams/${team.id}/schedule?fixture=true")
        val events = o.optJSONArray("events") ?: JSONArray()
        val out = ArrayList<Fixture>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val sides = comp.optJSONArray("competitors") ?: continue
            var home: String? = null
            var away: String? = null
            for (j in 0 until sides.length()) {
                val s = sides.optJSONObject(j) ?: continue
                val n = s.optJSONObject("team")?.let { it.optString("shortDisplayName").ifBlank { it.optString("displayName") } } ?: continue
                if (s.optString("homeAway") == "home") home = n else away = n
            }
            if (home == null || away == null) continue
            val kickoff = parseTime(e.optString("date").ifBlank { comp.optString("date") }) ?: continue
            val state = comp.optJSONObject("status")?.optJSONObject("type")?.optString("state")
                ?: e.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"
            val competition = e.optJSONObject("league")?.optString("name")?.ifBlank { null }
                ?: e.optJSONObject("seasonType")?.optString("name")?.ifBlank { null }
                ?: team.leagueName
            out += Fixture(
                e.optString("id").ifBlank { "$home-$away-$kickoff" }, home, away, kickoff, competition,
                state.ifBlank { "pre" }, comp.optBoolean("timeValid", e.optBoolean("timeValid", true)), followedName,
            )
        }
        return out.sortedBy { it.kickoff }
    }

    private val formats = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    private fun parseTime(s: String): Long? {
        if (s.isBlank()) return null
        for (f in formats) {
            val p = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            runCatching { return p.parse(s)?.time }
        }
        return null
    }
}

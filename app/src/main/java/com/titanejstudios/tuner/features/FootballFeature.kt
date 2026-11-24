package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.Football
import com.titanejstudios.tuner.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live football scores and goal alerts (football-data.org, with your own free key).
 * Moved out of TunerViewModel in 5.3; the view model forwards to it so screens didn't change.
 */
class FootballFeature(
    private val store: Store,
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean,
    private val notify: (String) -> Unit,
    private val sendHa: (String, Map<String, Any?>) -> Unit,
) {
    var key by mutableStateOf(store.footballKey); private set
    var teams by mutableStateOf(store.footballTeams); private set
    var matches by mutableStateOf<List<Football.Match>>(emptyList()); private set
    var status by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    // ---- Your teams' fixtures ----
    var teamList by mutableStateOf(Football.teamsFromJson(store.footballTeamList)); private set
    var teamIds by mutableStateOf(store.footballTeamIds); private set
    var fixtures by mutableStateOf(cachedFixtures()); private set
    var fixturesStatus by mutableStateOf<String?>(null); private set
    var loadingFixtures by mutableStateOf(false); private set
    /** Each followed team's last five results ("WWDLW", oldest first). */
    var form by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** Each followed team's place in its league. */
    var table by mutableStateOf<Map<String, Football.Standing>>(emptyMap()); private set
    /** Head-to-head lines by match id, looked up when you open a fixture. */
    var headToHead by mutableStateOf<Map<Long, String>>(emptyMap()); private set

    private fun cachedFixtures(): List<Football.Match> = runCatching {
        val arr = org.json.JSONArray(store.footballFixtures ?: "[]")
        Football.upcoming((0 until arr.length()).map { arr.getJSONObject(it) }, System.currentTimeMillis())
    }.getOrDefault(emptyList())

    /** Loads the teams you can pick from (once a week). */
    suspend fun loadTeamList(force: Boolean = false) {
        val k = key ?: return
        if (!force && teamList.isNotEmpty() && System.currentTimeMillis() - store.footballTeamListAt < 7 * 86_400_000L) return
        val all = ArrayList<Football.Team>()
        for ((code, label) in Football.PICKABLE) {
            val r = withContext(Dispatchers.IO) { runCatching { Football.teams(k, code, label) } }
            r.onSuccess { all += it }.onFailure { fixturesStatus = it.message }
        }
        if (all.isNotEmpty()) {
            // A team in two competitions (league and Champions League) once, under its league.
            val list = all.distinctBy { it.id }
            teamList = list
            store.footballTeamList = Football.teamsToJson(list)
            resolveIds()
        }
    }

    /** Teams found by part of their name ("newc" → Newcastle). */
    fun searchTeams(q: String): List<Football.Team> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return teamList.filter { it.name.lowercase().contains(t) || it.shortName.lowercase().contains(t) }
            .sortedBy { if (it.shortName.lowercase().startsWith(t) || it.name.lowercase().startsWith(t)) 0 else 1 }
            .take(12)
    }

    fun follow(team: Football.Team) {
        teams = teams + team.shortName
        store.footballTeams = teams
        teamIds = teamIds + (team.shortName to team.id.toString())
        store.footballTeamIds = teamIds
        notify("Following ${team.shortName} – their fixtures and channels are under My teams")
        scope.launch { loadFixtures(force = true) }
    }

    /** Teams followed from the scores list have a name but no id yet: find it in the team list. */
    private fun resolveIds() {
        val missing = teams.filter { it !in teamIds }
        if (missing.isEmpty() || teamList.isEmpty()) return
        val add = HashMap<String, String>()
        for (n in missing) {
            val t = teamList.firstOrNull { it.shortName.equals(n, true) } ?: teamList.firstOrNull { it.name.equals(n, true) } ?: continue
            add[n] = t.id.toString()
        }
        if (add.isNotEmpty()) {
            teamIds = teamIds + add
            store.footballTeamIds = teamIds
        }
    }

    /** Your teams' coming fixtures (every few hours, or now when [force]). */
    suspend fun loadFixtures(force: Boolean = false) {
        val k = key ?: return
        if (teams.isEmpty()) {
            fixtures = emptyList()
            return
        }
        if (!force && fixtures.isNotEmpty() && System.currentTimeMillis() - store.footballFixturesAt < 4 * 3_600_000L) return
        if (teams.any { it !in teamIds }) loadTeamList()
        resolveIds()
        loadingFixtures = true
        val raw = ArrayList<org.json.JSONObject>()
        var failed: Throwable? = null
        for (name in teams) {
            val id = teamIds[name]?.toLongOrNull() ?: continue
            val r = withContext(Dispatchers.IO) { runCatching { Football.teamMatchesRaw(k, id) } }
            r.onSuccess { list ->
                Football.form(list, name).takeIf { it.isNotEmpty() }?.let { f -> form = form + (name to f) }
                // Keep just the next few of each team.
                val coming = list.filter { o ->
                    val st = o.optString("status")
                    st != "FINISHED" && st != "POSTPONED" && st != "CANCELLED"
                }.sortedBy { it.optString("utcDate") }.take(6)
                raw += coming
            }.onFailure { failed = it }
        }
        loadingFixtures = false
        if (raw.isEmpty() && failed != null) {
            fixturesStatus = failed?.message
            return
        }
        fixturesStatus = null
        store.footballFixtures = org.json.JSONArray(raw).toString()
        fixtures = Football.upcoming(raw, System.currentTimeMillis())
        loadTables()
    }

    /** League positions for your teams (one request per league). */
    private suspend fun loadTables() {
        val k = key ?: return
        val leagues = teams.mapNotNull { n ->
            val id = teamIds[n]?.toLongOrNull() ?: return@mapNotNull null
            val label = teamList.firstOrNull { it.id == id }?.competition ?: return@mapNotNull null
            val code = Football.PICKABLE.firstOrNull { it.second == label }?.first ?: return@mapNotNull null
            if (code == "CL") null else Triple(n, id, code to label)
        }
        val out = HashMap<String, Football.Standing>()
        for ((code, label) in leagues.map { it.third }.distinct()) {
            val r = withContext(Dispatchers.IO) { runCatching { Football.standings(k, code, label) } }
            r.onSuccess { byId -> leagues.filter { it.third.first == code }.forEach { (n, id, _) -> byId[id]?.let { out[n] = it } } }
        }
        if (out.isNotEmpty()) table = out
    }

    /** Looks up the history between the two sides of a fixture (once). */
    fun loadHeadToHead(matchId: Long) {
        val k = key ?: return
        if (matchId in headToHead) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Football.headToHead(k, matchId) } }
            r.getOrNull()?.let { headToHead = headToHead + (matchId to it) }
        }
    }

    fun changeKey(newKey: String?) {
        key = newKey?.trim()?.takeIf { it.isNotEmpty() }
        store.footballKey = key
        matches = emptyList()
        start()
    }

    fun toggleTeam(team: String) {
        teams = if (team in teams) teams - team else teams + team
        store.footballTeams = teams
        if (team !in teams) {
            teamIds = teamIds - team
            store.footballTeamIds = teamIds
            fixtures = fixtures.filter { it.home in teams || it.away in teams }
        } else scope.launch { loadFixtures(force = true) }
        notify(if (team in teams) "Following $team – you'll get goal alerts" else "Stopped following $team")
    }

    /** Checks scores every minute while matches are live and Tuner is open (every 5 minutes otherwise). */
    fun start() {
        job?.cancel()
        val k = key ?: return
        job = scope.launch {
            var previous = emptyMap<Long, Football.Match>()
            while (true) {
                if (isForeground() || previous.isEmpty()) {
                    val result = withContext(Dispatchers.IO) { runCatching { Football.today(k) } }
                    result.onSuccess { list ->
                        status = null
                        if (previous.isNotEmpty()) {
                            for (g in Football.goals(previous, list)) {
                                val m = g.match
                                val followed = teams.isEmpty() || m.home in teams || m.away in teams
                                if (!followed) continue
                                notify("⚽ GOAL – ${g.scorer}!  ${m.home} ${m.scoreText} ${m.away}")
                                sendHa(
                                    "goal",
                                    mapOf("team" to g.scorer, "home" to m.home, "away" to m.away, "score" to m.scoreText, "competition" to m.competition),
                                )
                            }
                        }
                        matches = list
                        previous = list.associateBy { it.id }
                    }.onFailure { status = it.message }
                }
                delay(if (matches.any { it.live }) 60_000L else 5 * 60_000L)
            }
        }
    }

    /** Settings were restored from a backup or another TV. */
    fun reload() {
        key = store.footballKey
        teams = store.footballTeams
        teamIds = store.footballTeamIds
        fixtures = cachedFixtures()
        matches = emptyList()
        if (key != null) start() else stop()
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.Espn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The football teams you follow. Their games stand out in the guide, What's On and Sport, and
 * their fixtures come from ESPN's public data (no key). If ESPN stops answering, Flicker finds
 * their games in the TV guide instead.
 */
class FootballFeature(
    private val store: Store,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    var teams by mutableStateOf(store.footballTeams); private set
    /** Followed name → ESPN team (teams added by name only have none). */
    var espnTeams by mutableStateOf(readTeams()); private set
    var fixtures by mutableStateOf(readFixtures()); private set
    var loadingFixtures by mutableStateOf(false); private set
    /** Set when ESPN couldn't be reached (the guide is used instead). */
    var fixturesStatus by mutableStateOf<String?>(null); private set
    var teamList by mutableStateOf(readTeamList()); private set

    private fun readTeams(): Map<String, Espn.Team> = runCatching {
        val o = JSONObject(store.espnTeams ?: "{}")
        o.keys().asSequence().associateWith { Espn.Team.fromJson(o.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    private fun saveTeams() {
        val o = JSONObject()
        for ((n, t) in espnTeams) o.put(n, t.toJson())
        store.espnTeams = o.toString()
    }

    private fun readFixtures(): List<Espn.Fixture> = runCatching {
        val a = JSONArray(store.espnFixtures ?: "[]")
        (0 until a.length()).map { Espn.Fixture.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun readTeamList(): List<Espn.Team> = runCatching {
        val a = JSONArray(store.espnTeamList ?: "[]")
        (0 until a.length()).map { Espn.Team.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** The teams you can pick from (all the leagues, cached for a fortnight). */
    suspend fun loadTeamList(force: Boolean = false) {
        if (!force && teamList.isNotEmpty() && System.currentTimeMillis() - store.espnTeamListAt < 14 * 86_400_000L) return
        val all = withContext(Dispatchers.IO) {
            Espn.LEAGUES.flatMap { (code, name) ->
                runCatching { Espn.teams(code, name) }.onFailure { AppLog.warn("ESPN: couldn't load $name teams", it) }.getOrDefault(emptyList())
            }
        }
        if (all.isEmpty()) return
        teamList = all.distinctBy { it.key }
        store.espnTeamList = JSONArray().apply { teamList.forEach { put(it.toJson()) } }.toString()
    }

    /** Teams whose name has [q] in it, best matches first. */
    fun searchTeams(q: String): List<Espn.Team> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return teamList.filter { it.name.lowercase().contains(t) || it.shortName.lowercase().contains(t) }
            .sortedBy { if (it.shortName.lowercase().startsWith(t) || it.name.lowercase().startsWith(t)) 0 else 1 }
            .take(20)
    }

    /** Follow a team from ESPN's list (its fixtures come in). */
    fun follow(team: Espn.Team) {
        val name = team.shortName
        espnTeams = espnTeams + (name to team)
        saveTeams()
        if (name !in teams) setTeams(teams + name)
        notify("Following $name – their fixtures are on My teams")
        scope.launch { loadFixtures(force = true) }
    }

    /** Follow (or stop following) a team by name. */
    fun toggleTeam(team: String) {
        val name = team.trim()
        if (name.isEmpty()) return
        if (name in teams) {
            setTeams(teams - name)
            espnTeams = espnTeams - name
            saveTeams()
            fixtures = fixtures.filter { it.team != name }
            store.espnFixtures = JSONArray().apply { fixtures.forEach { put(it.toJson()) } }.toString()
            notify("Stopped following $name")
        } else {
            setTeams(teams + name)
            notify("Following $name – their games will stand out in the guide")
        }
    }

    private fun setTeams(t: Set<String>) {
        teams = t
        store.footballTeams = t
        store.setSyncStamp(com.titanejstudios.tuner.integrations.LinkSync.TEAMS, System.currentTimeMillis())
    }

    /** Your teams' coming fixtures from ESPN (every 6 hours unless [force]). */
    suspend fun loadFixtures(force: Boolean = false) {
        val linked = espnTeams.filterKeys { it in teams }
        if (linked.isEmpty()) {
            fixtures = emptyList()
            return
        }
        if (!force && fixtures.isNotEmpty() && System.currentTimeMillis() - store.espnFixturesAt < 6 * 3_600_000L) return
        if (loadingFixtures) return
        loadingFixtures = true
        try {
            var failed = false
            val got = withContext(Dispatchers.IO) {
                linked.flatMap { (name, team) ->
                    runCatching { Espn.fixtures(team, name) }.onFailure {
                        failed = true
                        AppLog.warn("ESPN: couldn't load $name's fixtures", it)
                    }.getOrDefault(emptyList())
                }
            }
            val now = System.currentTimeMillis()
            // Coming (or playing) only; when two of your teams play each other, once.
            val list = got.filter { it.kickoff > now - 3 * 3_600_000L && !it.finished }.distinctBy { it.id }.sortedBy { it.kickoff }
            if (list.isEmpty() && failed) {
                fixturesStatus = "Couldn't reach ESPN for fixtures – showing games from the TV guide"
                return
            }
            fixturesStatus = null
            fixtures = list
            store.espnFixtures = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        } finally {
            loadingFixtures = false
        }
    }

    /** Settings were restored from a backup or another TV. */
    fun reload() {
        teams = store.footballTeams
        espnTeams = readTeams()
        fixtures = readFixtures()
    }

    /** Another device's ESPN teams (by followed name), from sync. */
    fun mergeEspnTeams(json: JSONObject) {
        val add = HashMap(espnTeams)
        for (k in json.keys()) json.optJSONObject(k)?.let { add[k] = Espn.Team.fromJson(it) }
        espnTeams = add.filterKeys { it in teams }
        saveTeams()
    }
}

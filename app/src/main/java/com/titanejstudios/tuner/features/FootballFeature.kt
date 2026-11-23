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

    fun changeKey(newKey: String?) {
        key = newKey?.trim()?.takeIf { it.isNotEmpty() }
        store.footballKey = key
        matches = emptyList()
        start()
    }

    fun toggleTeam(team: String) {
        teams = if (team in teams) teams - team else teams + team
        store.footballTeams = teams
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
        matches = emptyList()
        if (key != null) start() else stop()
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

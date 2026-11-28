package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.integrations.Espn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Every fixture this week across the competitions Flicker knows (football leagues and cups, rugby,
 * NFL, NBA, NHL, MLB), from ESPN – for the Sports page's "All fixtures". Kept for half an hour.
 */
class FixturesFeature(private val scope: CoroutineScope) {
    class Entry(val fixture: Espn.Fixture, val sport: Espn.SportChoice)

    var entries by mutableStateOf<List<Entry>?>(null); private set
    var loading by mutableStateOf(false); private set
    private var loadedAt = 0L

    /** Every competition with its sport. */
    private fun competitions(): List<Triple<String, String, Espn.SportChoice>> {
        val football = Espn.SPORTS.first()
        return Espn.SPORTS.flatMap { s -> s.leagues.map { (lg, name) -> Triple(lg, name, s) } } +
            Espn.CUPS.map { (lg, name) -> Triple(lg, name, football) }
    }

    fun load(force: Boolean = false) {
        if (loading) return
        if (!force && entries != null && System.currentTimeMillis() - loadedAt < 30 * 60_000L) return
        loading = true
        scope.launch {
            val day = SimpleDateFormat("yyyyMMdd", Locale.UK)
            val now = System.currentTimeMillis()
            val from = day.format(now - 86_400_000L)
            val to = day.format(now + 7 * 86_400_000L)
            val gate = Semaphore(6)
            var failed = 0
            val all = withContext(Dispatchers.IO) {
                competitions().map { (lg, name, sport) ->
                    async {
                        gate.withPermit {
                            runCatching { Espn.scoreboard(lg, name, from, to).map { Entry(it, sport) } }
                                .onFailure { failed++ }
                                .getOrDefault(emptyList())
                        }
                    }
                }.awaitAll().flatten()
            }
            // From three hours ago (still on, or just finished) to a week ahead; the same match once.
            entries = all.filter { it.fixture.kickoff > now - 3 * 3_600_000L && !it.fixture.finished }
                .distinctBy { it.fixture.home + "|" + it.fixture.away + "|" + it.fixture.kickoff / 3_600_000L }
                .sortedBy { it.fixture.kickoff }
            loadedAt = System.currentTimeMillis()
            loading = false
            if (failed > 0) AppLog.info("All fixtures: $failed competition(s) couldn't be read from ESPN")
        }
    }
}

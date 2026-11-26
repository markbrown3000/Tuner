package com.titanejstudios.tuner.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.TeamFixture
import com.titanejstudios.tuner.integrations.Espn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** My teams: pick your teams, see their next fixtures and which of your channels shows each one. */
@Composable
fun TeamsScreen(vm: TunerViewModel) {
    val football = vm.football
    var pickingSport by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf<Espn.SportChoice?>(null) }
    var results by remember { mutableStateOf<Triple<String, Espn.SportChoice, List<Espn.Team>>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var teamMenu by remember { mutableStateOf<String?>(null) }
    var fixtureMenu by remember { mutableStateOf<TeamFixture?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
        vm.loadTeamList()
        vm.refreshTeamFixtures()
    }
    val narrow = narrowScreen()
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.UK) }
    val teams = football.teams.sorted()
    val now = vm.now

    LazyColumn(
        Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp)).widthIn(max = 1000.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("★ My teams", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "Football, rugby, cricket, F1, darts and more: your teams' and players' games, which of your channels shows each one, " +
                    "and recent ones you can still catch up on. Fixtures come from ESPN where it has them, otherwise from the TV guide.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PillButton("+ Add a team or player", { pickingSport = true }, Modifier.focusRequester(first), compact = true) }
                items(teams.size) { i ->
                    val n = teams[i]
                    PillButton(football.sportOf(n).emoji + " " + n, { teamMenu = n }, compact = true)
                }
                if (teams.isNotEmpty()) item {
                    PillButton(if (football.loadingFixtures) "Updating…" else "↻ Update", { vm.refreshTeamFixtures(force = true) }, compact = true)
                }
            }
        }
        football.fixturesStatus?.let { st -> item { Text(st, color = TunerColors.Gold, fontSize = 14.sp) } }
        item { Spacer(Modifier.height(10.dp)) }
        if (teams.isEmpty()) {
            item { Text("Add your team to see their fixtures here.", color = TunerColors.TextSecondary, fontSize = 15.sp) }
        } else {
            item { Text("Coming up", color = TunerColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            val list = vm.teamFixtures
            if (list.isEmpty()) item {
                Text(
                    if (football.loadingFixtures) "Looking up fixtures…" else "No fixtures found yet – try ↻ Update.",
                    color = TunerColors.TextSecondary, fontSize = 14.sp,
                )
            }
            items(list.size) { i ->
                val f = list[i]
                val m = f.match
                val on = m.live || (f.programme != null && f.programme.start <= now && f.programme.stop > now)
                FocusSurface(onClick = { if (on && f.channel != null) vm.watchFixture(f) else fixtureMenu = f }, modifier = Modifier.fillMaxWidth()) { _ ->
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (on) "ON NOW" else day.format(Date(m.kickoff)) + "  " + (if (m.timeKnown) hhmm(m.kickoff) else "TBC"),
                            color = if (on) Color(0xFFFF5252) else TunerColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(if (narrow) 110.dp else 150.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(f.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    m.competition,
                                    f.channel?.name ?: if (f.guideCoversIt) "Not on your channels" else "Not in the guide yet",
                                ).joinToString("  ·  "),
                                color = TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        // Catch up: your teams' recent games still on catch-up, and any you recorded.
        if (teams.isNotEmpty()) {
            val recorded = vm.recordings.filter { r ->
                r.status == com.titanejstudios.tuner.data.RecStatus.DONE && teams.any { com.titanejstudios.tuner.data.SportFollow.mentions(r.title, it) }
            }.sortedByDescending { it.start }.take(6)
            val past = vm.teamCatchup
            item {
                Spacer(Modifier.height(14.dp))
                Text("Catch up", color = TunerColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            if (past.isEmpty() && recorded.isEmpty()) item {
                Text(
                    "Nothing to catch up on – recent games show here when one of your channels has catch-up for them.",
                    color = TunerColors.TextSecondary, fontSize = 14.sp,
                )
            }
            items(recorded.size) { i ->
                val r = recorded[i]
                FocusSurface(memoryKey = "trec|${r.id}", onClick = { vm.playRecording(r) }, modifier = Modifier.fillMaxWidth()) { _ ->
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.format(Date(r.start)) + "  " + hhmm(r.start), color = TunerColors.Catchup, fontSize = 14.sp, modifier = Modifier.width(if (narrow) 110.dp else 150.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("⏺ Your recording", color = TunerColors.TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
            items(past.size) { i ->
                val (c, p) = past[i]
                FocusSurface(memoryKey = "tcu|${c.id}|${p.start}", onClick = { vm.playCatchup(c, p, listOf(c)) }, modifier = Modifier.fillMaxWidth()) { _ ->
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.format(Date(p.start)) + "  " + hhmm(p.start), color = TunerColors.Catchup, fontSize = 14.sp, modifier = Modifier.width(if (narrow) 110.dp else 150.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("↺ Catch-up on ${c.name}", color = TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (pickingSport) {
        MenuDialog(
            title = "Which sport?",
            items = Espn.SPORTS.map { sp ->
                MenuItem("${sp.emoji}  ${sp.label}", if (sp.leagues.isNotEmpty()) "fixtures from ESPN" else "games found in the TV guide") {
                    searching = sp
                }
            },
            onDismiss = { pickingSport = false },
        )
    }
    searching?.let { sport ->
        TextInputDialog(
            title = "${sport.emoji} Add a ${sport.label.lowercase()} " + if (sport.leagues.isEmpty()) "team, player or event" else "team",
            hint = sport.hint,
            label = "Name",
            onDismiss = { searching = null },
        ) { q ->
            searching = null
            val typed = q.trim()
            if (typed.isNotBlank()) {
                if (sport.leagues.isEmpty()) {
                    // Nothing to look up: found by name in the TV guide.
                    if (typed !in football.teams) vm.toggleFollowTeam(typed, sport.key)
                } else scope.launch {
                    if (sport.key != "football") vm.showNotice("${sport.emoji} Looking up ${sport.label.lowercase()} teams…")
                    val all = football.teamsFor(sport)
                    results = Triple(typed, sport, football.searchIn(all, typed))
                }
            }
        }
    }
    results?.let { (typed, sport, list) ->
        MenuDialog(
            title = if (list.isEmpty()) "Not found in ESPN's ${sport.label.lowercase()} leagues" else "Pick your team",
            subtitle = if (list.isEmpty()) "Add it by name: its games are found in the TV guide instead." else null,
            items = list.map { t ->
                MenuItem(t.shortName, t.leagueName + if (football.espnTeams[t.shortName]?.key == t.key) " · following" else "") { vm.followEspnTeam(t) }
            } + MenuItem("Just add \"$typed\"", "games found in the TV guide") { if (typed !in football.teams) vm.toggleFollowTeam(typed, sport.key) },
            onDismiss = { results = null },
        )
    }
    teamMenu?.let { name ->
        MenuDialog(
            title = name,
            subtitle = football.sportOf(name).let { sp -> sp.emoji + " " + sp.label + " · " } +
                (football.espnTeams[name]?.let { "Fixtures from ESPN · ${it.leagueName}" } ?: "Games found in the TV guide"),
            items = listOf(MenuItem("Stop following $name") { vm.toggleFollowTeam(name) }),
            onDismiss = { teamMenu = null },
        )
    }
    fixtureMenu?.let { f ->
        val ch = f.channel
        val p = f.programme
        MenuDialog(
            title = f.title,
            subtitle = listOfNotNull(f.match.competition, day.format(Date(f.match.kickoff)) + " " + hhmm(f.match.kickoff), ch?.name).joinToString(" · "),
            items = buildList {
                if (ch != null && p != null) {
                    if (vm.now >= p.start - 15 * 60_000L) add(MenuItem("▶ Watch on ${ch.name}") { vm.watchFixture(f) })
                    if (vm.now < p.start) add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel the reminder" else "⏰ Remind me", "pops up before kick-off") { vm.toggleReminder(ch, p) })
                    if (vm.recordingFor(ch, p) == null) add(MenuItem("● Record it") { vm.recordProgramme(ch, p) })
                }
                if (ch == null) add(MenuItem(if (f.guideCoversIt) "None of your channels show it" else "No channel yet", "Flicker checks the guide again as it updates") { })
            },
            onDismiss = { fixtureMenu = null },
        )
    }
}

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** My teams: pick your teams, see their next fixtures and which of your channels shows each one. */
@Composable
fun TeamsScreen(vm: TunerViewModel) {
    val football = vm.football
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Pair<String, List<Espn.Team>>?>(null) }
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
            Text("⚽ My teams", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "Your teams' fixtures (from ESPN) and which of your channels shows each one. Their games stand out in the guide, " +
                    "and reminders pop up 5 minutes before kick-off.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PillButton("+ Add a team", { searching = true }, Modifier.focusRequester(first), compact = true) }
                items(teams.size) { i ->
                    val n = teams[i]
                    PillButton((if (football.espnTeams[n] != null) "★ " else "☆ ") + n, { teamMenu = n }, compact = true)
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
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (searching) {
        TextInputDialog(
            title = "Add a team",
            hint = if (football.teamList.isEmpty()) "Loading the list of teams – if nothing comes up, the name's added as you typed it" else "Part of the name, e.g. Newcastle",
            label = "Team",
            onDismiss = { searching = false },
        ) { q ->
            searching = false
            if (q.isNotBlank()) results = q.trim() to football.searchTeams(q)
        }
    }
    results?.let { (typed, list) ->
        MenuDialog(
            title = if (list.isEmpty()) "Not found in ESPN's leagues" else "Pick your team",
            subtitle = if (list.isEmpty()) "Add it by name: its games are found in the TV guide instead." else null,
            items = list.map { t ->
                MenuItem(t.shortName, t.leagueName + if (football.espnTeams[t.shortName]?.key == t.key) " · following" else "") { football.follow(t) }
            } + MenuItem("Just add \"$typed\"", "games found in the TV guide") { if (typed !in football.teams) vm.toggleFollowTeam(typed) },
            onDismiss = { results = null },
        )
    }
    teamMenu?.let { name ->
        MenuDialog(
            title = name,
            subtitle = football.espnTeams[name]?.let { "Fixtures from ESPN · ${it.leagueName}" } ?: "Games found in the TV guide",
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

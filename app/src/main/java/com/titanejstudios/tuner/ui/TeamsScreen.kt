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
import com.titanejstudios.tuner.TeamFixture
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Football
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** My teams: pick your teams, see their next fixtures and which of your channels shows each one. */
@Composable
fun TeamsScreen(vm: TunerViewModel) {
    val football = vm.football
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Football.Team>?>(null) }
    var teamMenu by remember { mutableStateOf<String?>(null) }
    var fixtureMenu by remember { mutableStateOf<TeamFixture?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
        if (football.key != null) {
            vm.loadTeamList()
            vm.refreshTeamFixtures()
        }
    }
    val narrow = narrowScreen()
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.UK) }

    LazyColumn(
        Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp)).widthIn(max = 1000.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("⚽ My teams", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "Your teams' next matches and which of your channels shows each one. Flicker also reminds you 5 minutes before kick-off " +
                    "(Settings › Sport: your teams).",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (football.key == null) {
            item {
                Text(
                    "Fixtures come from football-data.org. Sign up there for a free account, then add the API key from their email " +
                        "on the Live scores screen.",
                    color = Color.White, fontSize = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                PillButton("Add the API key", { vm.navigate(Screen.Football) }, Modifier.focusRequester(first), compact = true)
            }
            return@LazyColumn
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PillButton("＋ Add a team", { searching = true }, Modifier.focusRequester(first), compact = true) }
                val names = football.teams.sorted()
                items(names.size, key = { names[it] }) { i ->
                    PillButton("★ " + names[i], { teamMenu = names[i] }, compact = true, selected = true)
                }
                item { PillButton(if (football.loadingFixtures) "Updating…" else "↻ Update", { vm.refreshTeamFixtures(force = true) }, compact = true) }
            }
            Spacer(Modifier.height(6.dp))
        }
        football.fixturesStatus?.let { st -> item { Text(st, color = TunerColors.Gold, fontSize = 14.sp) } }
        // Form and league place for each team.
        val names = football.teams.sorted()
        items(names.size, key = { "form" + names[it] }) { i ->
            val n = names[i]
            val st = football.table[n]
            val form = football.form[n]
            if (st != null || form != null) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("★ $n", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(if (narrow) 96.dp else 140.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column(Modifier.weight(1f)) {
                        if (st != null) {
                            Text(
                                "${Football.ordinal(st.position)} in the ${st.competition} · ${st.points} pts from ${st.played}" +
                                    " · GD ${if (st.goalDifference > 0) "+" else ""}${st.goalDifference}",
                                color = TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (form != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Form", color = TunerColors.TextSecondary, fontSize = 12.sp)
                                form.forEach { r ->
                                    Text(
                                        r.toString(),
                                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                            .background(when (r) { 'W' -> Color(0xFF2E9E5B); 'D' -> Color(0xFF7A869A); else -> Color(0xFFC8423B) })
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val fx = vm.teamFixtures
        if (football.teams.isEmpty()) {
            item { Text("Add your team to see its fixtures here.", color = TunerColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp)) }
        } else if (fx.isEmpty()) {
            item {
                Text(
                    if (football.loadingFixtures) "Looking up fixtures…" else "No fixtures found yet – try ↻ Update.",
                    color = TunerColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        items(fx.size, key = { fx[it].match.id }) { i ->
            val f = fx[i]
            val m = f.match
            val now = vm.now
            val ours = m.home in football.teams
            FocusSurface(
                onClick = { fixtureMenu = f },
                baseColor = TunerColors.Panel,
                modifier = Modifier.fillMaxWidth().height(if (narrow) 92.dp else 84.dp),
            ) { focused ->
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(if (narrow) 78.dp else 120.dp)) {
                        Text(
                            when {
                                m.live -> if (m.status == "PAUSED") "HT" else "LIVE"
                                isSameDay(m.kickoff, now) -> "Today"
                                isSameDay(m.kickoff, now + 86_400_000L) -> "Tomorrow"
                                else -> day.format(Date(m.kickoff))
                            },
                            color = if (m.live) TunerColors.NowLine else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        )
                        Text(if (m.status == "SCHEDULED") "time TBC" else hhmm(m.kickoff), color = TunerColors.TextSecondary, fontSize = 14.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (ours) "★ " else "") + m.home + "  v  " + m.away + (if (!ours && m.away in football.teams) " ★" else ""),
                            color = Color.White, fontSize = if (narrow) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(m.competition, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                        Text(
                            when {
                                f.channel != null -> "📺 " + f.channel.name + (if (f.programme != null && vm.hasReminder(f.channel, f.programme)) "  ·  ⏰ reminder set" else "")
                                f.guideCoversIt -> "Not on any of your channels"
                                else -> "Channel not in the guide yet"
                            },
                            color = if (f.channel != null) TunerColors.Accent else TunerColors.TextSecondary,
                            fontSize = 14.sp, fontWeight = if (f.channel != null) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (searching) {
        TextInputDialog(
            title = "Find your team",
            hint = if (football.teamList.isEmpty()) "Loading the list of teams – try again in a moment if nothing comes up" else "Part of the name, e.g. Newcastle",
            label = "Team",
            onDismiss = { searching = false },
        ) { q ->
            searching = false
            results = football.searchTeams(q)
        }
    }
    results?.let { list ->
        MenuDialog(
            title = if (list.isEmpty()) "No teams found" else "Pick your team",
            subtitle = if (list.isEmpty()) "Teams come from the Premier League, Championship, Champions League and the big European leagues." else null,
            items = list.map { t ->
                MenuItem(t.shortName, t.competition + if (t.shortName in football.teams) " · following" else "") { if (t.shortName !in football.teams) football.follow(t) }
            },
            onDismiss = { results = null },
        )
    }
    teamMenu?.let { name ->
        MenuDialog(
            title = name,
            items = listOf(MenuItem("Stop following $name") { vm.toggleFollowTeam(name) }),
            onDismiss = { teamMenu = null },
        )
    }
    fixtureMenu?.let { f ->
        val m = f.match
        val ch = f.channel
        val p = f.programme
        LaunchedEffect(m.id) { football.loadHeadToHead(m.id) }
        MenuDialog(
            title = "${m.home} v ${m.away}",
            subtitle = m.competition + " · " + day.format(Date(m.kickoff)) + " " + hhmm(m.kickoff) + (ch?.let { " · ${it.name}" } ?: "") +
                (football.headToHead[m.id]?.let { "\n$it" } ?: ""),
            items = buildList {
                if (ch != null && p != null) {
                    if (vm.now >= p.start - 15 * 60_000L) add(MenuItem("▶ Watch on ${ch.name}") { vm.watchFixture(f) })
                    if (vm.now >= p.start - 15 * 60_000L && vm.isPhone && vm.link.linked) add(MenuItem("📺 Watch it on the TV") { vm.watchOnTv(vm.liveJson(ch)) })
                    if (vm.now < p.start) add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel the reminder" else "⏰ Remind me", "pops up before kick-off") { vm.toggleReminder(ch, p) })
                    if (vm.recordingFor(ch, p) == null) add(MenuItem("● Record it") { vm.recordProgramme(ch, p) })
                }
                if (ch == null) add(MenuItem("No channel yet", "Flicker checks the guide again as it updates") { })
            },
            onDismiss = { fixtureMenu = null },
        )
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val f = SimpleDateFormat("yyyyMMdd", Locale.UK)
    return f.format(Date(a)) == f.format(Date(b))
}

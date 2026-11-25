package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.data.WhatsOn
import com.titanejstudios.tuner.integrations.Espn
import com.titanejstudios.tuner.integrations.Plex
import com.titanejstudios.tuner.integrations.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row in What's On, Sport and search: a programme on a channel. */
@Composable
private fun ProgrammeRow(app: AppState, c: Channel, p: Programme, badge: String? = null) {
    val now = app.now
    val on = p.start <= now && p.stop > now
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
            .clickable { if (on || p.stop <= now) { if (on) app.playChannel(c) else app.playCatchup(c, p) } else app.toggleReminder(c, p) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 60.dp, height = 34.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (on) "Now · until ${time(p.stop)}" else "${dayAndTime(p.start)} – ${time(p.stop)}") + " · " + c.name,
                color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        badge?.let { Text(it, color = if (it == "LIVE") LiveRed else Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp)) }
        if (!on && p.start > now) Text(if (app.hasReminder(c, p)) "⏰ ✓" else "⏰", color = Gold, fontSize = 14.sp)
    }
}

private val dayFmt = SimpleDateFormat("EEE HH:mm", Locale.UK)
private fun dayAndTime(t: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.UK)
    return if (today.format(Date(t)) == today.format(Date())) time(t) else synchronized(dayFmt) { dayFmt.format(Date(t)) }
}

/** What's On: now and coming up by kind, sport, and your teams' fixtures. */
@Composable
fun WhatsOnPage(app: AppState) {
    var tab by remember { mutableStateOf("Now") }
    var kind by remember { mutableStateOf<String?>(null) }
    val now = app.now
    // Your channels' programmes, without shopping, radio and other countries' channels (unless they're favourites).
    val channels = remember(app.visibleChannels, app.listsVersion) {
        val favs = app.favourites
        app.visibleChannels.filter { it.id in favs || (!WhatsOn.isClutter(it) && !WhatsOn.isWorld(it)) }
    }
    val entries = remember(channels, app.guide, now / 60_000, tab) {
        channels.flatMap { c ->
            app.guide[c.id].orEmpty().filter { p ->
                when (tab) {
                    "Now" -> p.start <= now && p.stop > now
                    "Coming up" -> p.start > now && p.start < now + 3 * 3_600_000L
                    "Sport" -> p.stop > now && p.start < now + 24 * 3_600_000L && Sports.classify(p, c.group) != null
                    else -> false
                }
            }.map { c to it }
        }.distinctBy { (_, p) -> p.title.lowercase() + "@" + p.start }.sortedBy { it.second.start }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("What's On", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in listOf("Now", "Coming up", "Sport", "My teams")) Pill(t, selected = tab == t) { tab = t; kind = null }
        }
        Spacer(Modifier.height(10.dp))
        if (tab == "My teams") {
            TeamsView(app)
            return@Column
        }
        val kinds = remember(entries) { entries.map { (c, p) -> Genres.of(p.category, p.title, c.group) ?: "Other" }.distinct().sorted() }
        if (tab != "Sport" && kinds.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Pill("All", selected = kind == null) { kind = null } }
                items(kinds, key = { it }) { k -> Pill(k, selected = kind == k) { kind = k } }
            }
            Spacer(Modifier.height(10.dp))
        }
        val shown = entries.filter { (c, p) -> kind == null || (Genres.of(p.category, p.title, c.group) ?: "Other") == kind }
        if (shown.isEmpty()) Text(if (app.guide.isEmpty()) "The TV guide hasn't loaded yet." else "Nothing here right now.", color = Soft, fontSize = 15.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown.take(300), key = { (c, p) -> c.id + "@" + p.start }) { (c, p) ->
                val badge = if (tab == "Sport") {
                    when {
                        p.start <= now && Sports.looksLive(p) -> "LIVE"
                        else -> Sports.classify(p, c.group)
                    }
                } else null
                ProgrammeRow(app, c, p, badge)
            }
        }
    }
}

/** Your teams (from the TV): fixtures from ESPN, with the channel showing each one. */
@Composable
private fun TeamsView(app: AppState) {
    val teams = app.config.teams
    var fixtures by remember { mutableStateOf<List<Espn.Fixture>?>(null) }
    LaunchedEffect(teams) {
        fixtures = withContext(Dispatchers.IO) {
            val espn = runCatching { JSONObject(app.config.espnTeams) }.getOrDefault(JSONObject())
            val t = System.currentTimeMillis()
            teams.flatMap { name ->
                val o = espn.optJSONObject(name) ?: return@flatMap emptyList()
                runCatching { Espn.fixtures(Espn.Team.fromJson(o), name) }.getOrDefault(emptyList())
            }.filter { !it.finished && it.kickoff > t - 3 * 3_600_000L }.distinctBy { it.id }.sortedBy { it.kickoff }.take(15)
        }
    }
    if (teams.isEmpty()) {
        Text("Follow your teams on the TV (Sport › ★ My teams) – they come across with the sync.", color = Soft, fontSize = 15.sp)
        return
    }
    val list = fixtures
    // Games in the guide, for teams ESPN doesn't cover (or before it's answered).
    val inGuide = remember(app.guide, teams) {
        app.visibleChannels.flatMap { c ->
            app.guide[c.id].orEmpty().filter { p -> p.stop > app.now && teams.any { SportFollow.mentions(p.title, it) } && Sports.classify(p, c.group) != null }
                .map { c to it }
        }.distinctBy { it.second.title.lowercase() + "@" + it.second.start }.sortedBy { it.second.start }.take(20)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Following: " + teams.joinToString(", "), color = Soft, fontSize = 13.sp) }
        if (list == null) item { Text("Looking up fixtures…", color = Soft, fontSize = 14.sp) }
        if (!list.isNullOrEmpty()) {
            item { SectionTitle("Fixtures") }
            items(list, key = { it.id }) { m ->
                // Which of your channels shows it: both teams named, around kick-off.
                val on = remember(m.id, app.guide) {
                    app.visibleChannels.firstNotNullOfOrNull { c ->
                        app.guide[c.id]?.firstOrNull { p ->
                            p.start <= m.kickoff + 30 * 60_000L && p.stop >= m.kickoff - 30 * 60_000L &&
                                SportFollow.mentions(p.title, m.home) && SportFollow.mentions(p.title, m.away)
                        }?.let { c to it }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
                        .clickable(enabled = on != null) { on?.let { (c, p) -> if (p.start <= app.now) app.playChannel(c) else app.toggleReminder(c, p) } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (m.live) "LIVE" else dayAndTime(m.kickoff), color = if (m.live) LiveRed else Gold, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${m.home} v ${m.away}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(m.competition, on?.first?.name ?: "Not in the guide yet").joinToString(" · "), color = Soft, fontSize = 12.sp)
                    }
                }
            }
        }
        if (inGuide.isNotEmpty()) {
            item { SectionTitle("In the TV guide") }
            items(inGuide, key = { (c, p) -> "g" + c.id + "@" + p.start }) { (c, p) -> ProgrammeRow(app, c, p, if (p.start <= app.now) "LIVE" else null) }
        }
    }
}

/** One search for everything: channels, the guide, films, series, Plex and YouTube. */
@Composable
fun SearchPage(app: AppState) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(app.searchFor) {
        app.searchFor?.let { query = it; app.searchFor = null }
    }
    var plex by remember { mutableStateOf<List<Plex.Entry>>(emptyList()) }
    var youtube by remember { mutableStateOf<List<YouTube.Video>>(emptyList()) }
    LaunchedEffect(Unit) { app.loadVod() }
    val q = query.trim().lowercase()
    LaunchedEffect(q) {
        plex = emptyList()
        youtube = emptyList()
        if (q.length < 3) return@LaunchedEffect
        delay(600)
        if (app.plexServers.isNotEmpty()) plex = withContext(Dispatchers.IO) { runCatching { app.plex.search(app.plexServers, q) }.getOrDefault(emptyList()) }
        if (!app.kids && Keys.YOUTUBE_KEY.isNotBlank()) {
            youtube = withContext(Dispatchers.IO) { runCatching { YouTube.search(q, Keys.YOUTUBE_KEY, null).take(12) }.getOrDefault(emptyList()) }
        }
    }
    val now = app.now
    val chans = if (q.length < 2) emptyList() else app.visibleChannels.filter { it.name.lowercase().contains(q) }.take(20)
    val progs = if (q.length < 3) emptyList() else app.visibleChannels.flatMap { c ->
        app.guide[c.id].orEmpty().filter { it.stop > now - 7 * 86_400_000L && it.title.lowercase().contains(q) }.map { c to it }
    }.distinctBy { it.first.id + "@" + it.second.start }.sortedBy { it.second.start }
    val films = if (q.length < 3 || app.kids) emptyList() else app.movies.orEmpty().filter { it.name.lowercase().contains(q) }.take(30)
    val shows = if (q.length < 3 || app.kids) emptyList() else app.series.orEmpty().filter { it.name.lowercase().contains(q) }.take(30)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Channels, programmes, films, series, Plex and YouTube") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (chans.isNotEmpty()) {
            item { SectionTitle("Channels") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(chans, key = { it.id }) { c -> Pill(c.name) { app.playChannel(c) } } }
            }
        }
        val groups = listOf(
            "On now" to progs.filter { it.second.start <= now && it.second.stop > now },
            "Coming up" to progs.filter { it.second.start > now },
            "Earlier – catch-up" to progs.filter { it.second.stop <= now && it.first.hasCatchup }.reversed(),
        )
        for ((title, list) in groups) if (list.isNotEmpty()) {
            item { SectionTitle(title) }
            items(list.take(25), key = { (c, p) -> title + c.id + "@" + p.start }) { (c, p) -> ProgrammeRow(app, c, p) }
        }
        if (films.isNotEmpty()) {
            item { SectionTitle("Films") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(films, key = { it.id }) { m -> PosterTile(m.name, m.poster, m.category, width = 130.dp) { app.playFilm(m) } }
                }
            }
        }
        if (shows.isNotEmpty()) {
            item { SectionTitle("Series") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shows, key = { it.id }) { s ->
                        PosterTile(s.name, s.cover, s.category, width = 130.dp) {
                            app.openSeriesId = s.id
                            app.page = Page.Series
                        }
                    }
                }
            }
        }
        if (plex.isNotEmpty()) {
            item { SectionTitle("Plex") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(plex, key = { it.ratingKey }) { e ->
                        PosterTile(e.title, e.image, e.library ?: e.year, width = 130.dp) {
                            if (e.isShow) app.page = Page.Plex else app.playPlex(e.ratingKey, e.title, e.year, e.image, e.playUrl, e.server, e.viewOffsetMs)
                        }
                    }
                }
            }
        }
        if (youtube.isNotEmpty()) {
            item { SectionTitle("YouTube") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(youtube, key = { it.id }) { v -> WideTile(v.title, v.thumb, v.channel) { openInBrowser("https://www.youtube.com/watch?v=${v.id}") } }
                }
            }
        }
        if (q.length >= 3 && chans.isEmpty() && progs.isEmpty() && films.isEmpty() && shows.isEmpty() && plex.isEmpty() && youtube.isEmpty()) {
            item { Text("Nothing found for \"$query\"", color = Soft, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

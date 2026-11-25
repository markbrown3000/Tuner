package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.data.WhatsOn
import androidx.compose.foundation.layout.aspectRatio

/** Something on Home worth watching now: a programme on a channel (with a score for ordering). */
private class OnNow(val channel: Channel, val programme: Programme, val score: Int)

/** Home: a big pick at the top, then rows – like the TV's Home. */
@Composable
fun HomePage(app: AppState) {
    val now = app.now
    val minute = now / 60_000
    val version = app.listsVersion
    LaunchedEffect(app.kids) { if (!app.kids) app.loadVod() }
    val cont = remember(version, app.profileId) { if (app.kids) emptyList() else app.continueWatching }
    val favourites = remember(version, app.profileId) { app.favourites }
    // Your channels: favourites, else the ones you've watched lately.
    val yourChannels = remember(version, app.profileId, app.channels) {
        val byId = app.visibleChannels.associateBy { it.id }
        favourites.mapNotNull { byId[it] }.ifEmpty { app.config.recents.mapNotNull { byId[it] } }
    }
    val myList = remember(version) { if (app.kids) emptyList() else app.watchlist }

    // Everything on now and later today, scored: films, sport and drama on your channels first.
    val picks = remember(minute, app.guide, app.visibleChannels, version) {
        val recent = app.config.recents.toSet()
        app.visibleChannels.asSequence()
            .filter { it.id in favourites || (!WhatsOn.isClutter(it) && !WhatsOn.isWorld(it)) }
            .flatMap { c -> app.guide[c.id].orEmpty().asSequence().filter { it.stop > now && it.start < now + 12 * 3_600_000L }.map { c to it } }
            .map { (c, p) ->
                val kind = Genres.of(p.category, p.title, c.group)
                var score = when (kind) { "Film" -> 30; "Sport" -> 25; "Drama" -> 20; "Entertainment" -> 15; "Factual" -> 12; "Kids" -> if (app.kids) 30 else 0; "News" -> 2; else -> 5 }
                if (c.id in favourites) score += 25
                if (c.id in recent) score += 15
                if (!p.image.isNullOrBlank()) score += 8
                if (p.desc.isNullOrBlank()) score -= 5
                OnNow(c, p, score)
            }
            .toList()
            .distinctBy { it.programme.title.lowercase() + "@" + it.programme.start }
    }
    val onNow = picks.filter { it.programme.start <= now }.sortedByDescending { it.score }.distinctBy { it.programme.title.lowercase() }.take(20)
    val sport = picks.filter { Sports.classify(it.programme, it.channel.group) != null && it.programme.start < now + 6 * 3_600_000L }
        .sortedBy { it.programme.start }.distinctBy { it.programme.title.lowercase() }.take(20)
    val filmsOnTv = picks.filter { Genres.of(it.programme.category, it.programme.title, it.channel.group) == "Film" }
        .sortedBy { it.programme.start }.distinctBy { it.programme.title.lowercase() }.take(20)
    val tonight = picks.filter {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = it.programme.start }.get(java.util.Calendar.HOUR_OF_DAY)
        it.programme.start > now && h in 19..22
    }.sortedByDescending { it.score }.distinctBy { it.programme.title.lowercase() }.take(20)
    val topFilms = remember(app.movies, version) {
        app.movies.orEmpty().filter { (it.rating?.toDoubleOrNull() ?: 0.0) >= 7.0 && it.id !in app.config.done }
            .sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }.distinctBy { it.name.lowercase() }.take(24)
    }

    // The big pick at the top: what you were watching, else the best thing on now.
    val heroResume = cont.firstOrNull()
    val heroLive = onNow.firstOrNull { !it.programme.image.isNullOrBlank() } ?: onNow.firstOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            when {
                app.config.sources.isEmpty() -> Column(Modifier.padding(24.dp)) {
                    Text("Welcome to Flicker", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Go to Settings and sign in with Google – your TV's channels, profiles, favourites and Continue watching come across. Or add a playlist yourself.",
                        color = Soft, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                    )
                    Pill("Go to Settings", selected = true) { app.page = Page.Settings }
                }
                heroResume != null -> Hero(
                    image = heroResume.poster, kicker = "CARRY ON WATCHING", title = heroResume.title,
                    line = listOfNotNull(heroResume.sub, if (heroResume.dur > 0) "${(heroResume.dur - heroResume.pos) / 60_000} min left" else null).joinToString("  ·  "),
                    progress = if (heroResume.dur > 0) heroResume.pos.toFloat() / heroResume.dur else null,
                    action = "▶ Carry on",
                ) { app.watch(Watching(heroResume.id, heroResume.title, heroResume.sub, heroResume.poster, heroResume.url, heroResume.series, resumable = true)) }
                heroLive != null -> Hero(
                    image = heroLive.programme.image ?: heroLive.channel.logo, kicker = "ON NOW · ${heroLive.channel.name}", title = heroLive.programme.title,
                    line = "${time(heroLive.programme.start)} – ${time(heroLive.programme.stop)}" + (heroLive.programme.desc?.let { "  ·  $it" } ?: ""),
                    progress = ((now - heroLive.programme.start).toFloat() / (heroLive.programme.stop - heroLive.programme.start)).coerceIn(0f, 1f),
                    action = "▶ Watch",
                ) { app.playChannel(heroLive.channel) }
                else -> Text(app.profile?.let { "Hello, ${it.name}" } ?: "Flicker", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(24.dp))
            }
        }
        // Quick buttons.
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.config.lastChannel?.let { id -> app.visibleChannels.firstOrNull { it.id == id } }?.let { c -> item { Pill("▶ ${c.name}", selected = true) { app.playChannel(c) } } }
                item { Pill("📺 Live TV") { app.page = Page.Live } }
                item { Pill("▦ TV Guide") { app.page = Page.Guide } }
                item { Pill("☰ What's On") { app.page = Page.WhatsOn } }
                if (!app.kids) item { Pill("🎬 Films") { app.page = Page.Films } }
                if (!app.kids) item { Pill("🎞 Series") { app.page = Page.Series } }
                item { Pill("🔍 Search") { app.page = Page.Search } }
            }
        }
        if (cont.size > 1 || (cont.size == 1 && heroResume == null)) row(app, "Continue watching") {
            items(cont.take(20), key = { it.id }) { r ->
                WithMenu(listOf(
                    "▶ Carry on" to { app.watch(Watching(r.id, r.title, r.sub, r.poster, r.url, r.series, resumable = true)) },
                    "⏮ From the start" to { app.watch(Watching(r.id, r.title, r.sub, r.poster, r.url, r.series, resumable = true), fromStart = true) },
                    "✕ Remove from Continue watching" to { app.removeFromContinue(r.id) },
                )) {
                WideTile(
                    r.title, r.poster, listOfNotNull(r.sub, if (r.dur > 0) "${(r.dur - r.pos) / 60_000} min left" else null).joinToString(" · "),
                    progress = if (r.dur > 0) r.pos.toFloat() / r.dur else null, width = 260.dp,
                ) { app.watch(Watching(r.id, r.title, r.sub, r.poster, r.url, r.series, resumable = true)) }
                }
            }
        }
        if (yourChannels.isNotEmpty()) row(app, if (favourites.isNotEmpty()) "Your channels" else "Watched lately") {
            items(yourChannels.take(30), key = { it.id }) { c ->
                val cur = app.guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                ChannelTile(c, cur, now) { app.playChannel(c) }
            }
        }
        if (onNow.isNotEmpty()) row(app, "On now") { items(onNow, key = { "n" + it.channel.id + it.programme.start }) { ProgrammeTile(app, it) } }
        if (sport.isNotEmpty()) row(app, "Sport") { items(sport, key = { "s" + it.channel.id + it.programme.start }) { ProgrammeTile(app, it) } }
        if (filmsOnTv.isNotEmpty()) row(app, "Films on TV today") { items(filmsOnTv, key = { "f" + it.channel.id + it.programme.start }) { ProgrammeTile(app, it) } }
        if (myList.isNotEmpty()) row(app, "My list") {
            items(myList, key = { it.id }) { w ->
                PosterTile(w.name, w.image, if (w.isSeries) "Series" else w.category, width = 140.dp) {
                    if (w.isSeries) {
                        app.openSeriesId = w.id
                        app.page = Page.Series
                    } else w.toVod()?.let { app.playFilm(it) }
                }
            }
        }
        if (app.plexDeck.isNotEmpty() && !app.kids) row(app, "Continue on Plex") {
            items(app.plexDeck, key = { it.ratingKey }) { p ->
                WideTile(p.title, p.image, p.subtitle, progress = if (p.durationMs > 0) p.viewOffsetMs.toFloat() / p.durationMs else null, width = 260.dp) {
                    app.playPlex(p.ratingKey, p.title, p.subtitle, p.image, p.playUrl, p.server, p.viewOffsetMs)
                }
            }
        }
        if (tonight.isNotEmpty()) row(app, "Tonight") { items(tonight, key = { "t" + it.channel.id + it.programme.start }) { ProgrammeTile(app, it) } }
        if (topFilms.isNotEmpty() && !app.kids) row(app, "Top rated films") {
            items(topFilms, key = { it.id }) { m -> PosterTile(m.name, m.poster, m.rating?.let { "★ $it" }, width = 140.dp) { app.playFilm(m) } }
        }
        if (app.plexRecent.isNotEmpty() && !app.kids) row(app, "New on Plex") {
            items(app.plexRecent, key = { "pr" + it.ratingKey }) { e ->
                PosterTile(e.title, e.image, e.library ?: e.year, width = 140.dp) {
                    if (e.isShow) app.page = Page.Plex else app.playPlex(e.ratingKey, e.title, e.year, e.image, e.playUrl, e.server, e.viewOffsetMs)
                }
            }
        }
    }
}

/** A titled row of tiles on Home. */
private fun androidx.compose.foundation.lazy.LazyListScope.row(
    app: AppState,
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    item(key = "title-$title") { SectionTitle(title, Modifier.padding(start = 24.dp)) }
    item(key = "row-$title") {
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** The big picture at the top of Home. */
@Composable
private fun Hero(image: String?, kicker: String, title: String, line: String, progress: Float?, action: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(340.dp).clickable(onClick = onClick)) {
        if (!image.isNullOrBlank()) NetImage(image, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Ink, Ink.copy(alpha = 0.85f), Color.Transparent)),
            ),
        )
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Ink))))
        Column(Modifier.align(Alignment.BottomStart).padding(28.dp).fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kicker, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(line, color = Soft, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress != null) Box(Modifier.width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(PanelLight)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Sky))
            }
            Spacer(Modifier.height(4.dp))
            Pill(action, selected = true, onClick = onClick)
        }
    }
}

/** A programme on a channel: its picture (or the channel's logo), time and channel. */
@Composable
private fun ProgrammeTile(app: AppState, o: OnNow) {
    val now = app.now
    val p = o.programme
    val on = p.start <= now
    Column(Modifier.width(260.dp).clip(RoundedCornerShape(10.dp)).clickable { if (on) app.playChannel(o.channel) else app.toggleReminder(o.channel, p) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(PanelLight), contentAlignment = Alignment.Center) {
            if (!p.image.isNullOrBlank()) NetImage(p.image, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else NetImage(o.channel.logo, Modifier.fillMaxSize().padding(30.dp))
            if (on) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)).height(4.dp).background(Sky))
            }
            if (!on) Text(
                (if (app.hasReminder(o.channel, p)) "⏰ ✓  " else "") + time(p.start), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Ink.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(p.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
        Text(
            o.channel.name + if (on) "  ·  until ${time(p.stop)}" else "  ·  ${time(p.start)} – ${time(p.stop)}",
            color = Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun ChannelTile(c: Channel, cur: Programme?, now: Long, onClick: () -> Unit) {
    Column(Modifier.width(200.dp).clip(RoundedCornerShape(10.dp)).background(Panel).clickable(onClick = onClick).padding(10.dp)) {
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
        Text(c.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(cur?.title ?: "", color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (cur != null) Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).background(PanelLight)) {
            Box(Modifier.fillMaxWidth(((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f)).height(3.dp).background(Sky))
        }
    }
}

private const val MIN_DP = 4f // width of one minute in the guide
private const val NAME_W = 200

/** The TV guide: channels down the side, the next day and a half across. */
@Composable
fun GuidePage(app: AppState) {
    val now = app.now
    val favourites = remember(app.listsVersion, app.profileId) { app.favourites }
    var onlyFavourites by remember { mutableStateOf(favourites.isNotEmpty()) }
    var menu by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    val channels = remember(app.visibleChannels, onlyFavourites, favourites, app.guide) {
        val base = if (onlyFavourites) app.visibleChannels.filter { it.id in favourites } else app.visibleChannels
        base.filter { app.guide[it.id]?.isNotEmpty() == true }
    }
    // The timeline starts on the half hour before now.
    val origin = remember(now / 1_800_000L) { now / 1_800_000L * 1_800_000L - 1_800_000L }
    val end = origin + 36 * 3_600_000L
    val totalW = ((end - origin) / 60_000L * MIN_DP).dp + NAME_W.dp
    val h = rememberScrollState()
    LaunchedEffect(Unit) { h.scrollTo(0) }

    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TV Guide", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(12.dp))
            if (favourites.isNotEmpty()) {
                Pill("★ Favourites", selected = onlyFavourites) { onlyFavourites = true }
                Pill("All channels", selected = !onlyFavourites) { onlyFavourites = false }
            }
            Spacer(Modifier.weight(1f))
            Text("Click: watch now · remind me · catch-up", color = Soft, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxSize().horizontalScroll(h)) {
            Column(Modifier.width(totalW)) {
                // Times along the top.
                Box(Modifier.fillMaxWidth().height(28.dp)) {
                    var t = origin
                    while (t < end) {
                        Text(
                            time(t), color = Soft, fontSize = 12.sp,
                            modifier = Modifier.offset(x = (NAME_W + (t - origin) / 60_000L * MIN_DP).dp + 4.dp, y = 6.dp),
                        )
                        t += 1_800_000L
                    }
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(channels, key = { it.id }) { c ->
                            GuideRow(app, c, origin, end, now, h.value) { p ->
                                when {
                                    p.start <= now && p.stop > now -> app.playChannel(c)
                                    else -> menu = c to p
                                }
                            }
                        }
                        if (channels.isEmpty()) item {
                            Text(
                                if (app.channels.isEmpty()) "No channels yet." else "No guide for these channels.",
                                color = Soft, fontSize = 15.sp, modifier = Modifier.offset { IntOffset(h.value, 0) }.padding(20.dp),
                            )
                        }
                    }
                    // The "now" line.
                    Box(
                        Modifier.offset(x = (NAME_W + (now - origin) / 60_000L * MIN_DP).dp).width(2.dp).fillMaxHeight().background(LiveRed.copy(alpha = 0.8f)),
                    )
                }
            }
        }
    }
    menu?.let { (c, p) -> ProgrammeMenu(app, c, p) { menu = null } }
}

@Composable
private fun GuideRow(app: AppState, c: Channel, origin: Long, end: Long, now: Long, scrollX: Int, onClick: (Programme) -> Unit) {
    val progs = app.guide[c.id].orEmpty().filter { it.stop > origin && it.start < end }
    Box(Modifier.fillMaxWidth().height(54.dp).padding(vertical = 2.dp)) {
        for (p in progs) {
            val from = maxOf(p.start, origin)
            val x = NAME_W + (from - origin) / 60_000L * MIN_DP
            val w = ((minOf(p.stop, end) - from) / 60_000L * MIN_DP).coerceAtLeast(6f)
            val on = p.start <= now && p.stop > now
            val past = p.stop <= now
            Column(
                Modifier.offset(x = x.dp).width(w.dp - 2.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(if (on) Sky.copy(alpha = 0.35f) else if (past) Panel.copy(alpha = 0.5f) else Panel)
                    .then(
                        when {
                            app.recordingFor(c, p) != null -> Modifier.border(1.dp, LiveRed, RoundedCornerShape(6.dp))
                            app.hasReminder(c, p) -> Modifier.border(1.dp, Gold, RoundedCornerShape(6.dp))
                            else -> Modifier
                        }
                    )
                    .clickable { onClick(p) }.padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(p.title, color = if (past) Soft else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${time(p.start)} – ${time(p.stop)}", color = Soft, fontSize = 11.sp, maxLines = 1)
            }
        }
        // The channel's name stays on the left as the guide scrolls.
        Row(
            Modifier.offset { IntOffset(scrollX, 0) }.zIndex(1f).width(NAME_W.dp - 4.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                .background(PanelLight).clickable { app.playChannel(c) }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 44.dp, height = 28.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(8.dp))
            Text(c.name, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProgrammeMenu(app: AppState, c: Channel, p: Programme, onDismiss: () -> Unit) {
    val now = app.now
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Panel).clickable(enabled = false) {}.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(p.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${time(p.start)} – ${time(p.stop)} · ${c.name}", color = Soft, fontSize = 14.sp)
            p.desc?.let { Text(it, color = Soft, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.stop <= now && Catchup.isAvailable(c, p, now)) Pill("↺ Watch (catch-up)", selected = true) { onDismiss(); app.playCatchup(c, p) }
                if (p.start > now) Pill(if (app.hasReminder(c, p)) "Cancel the reminder" else "⏰ Remind me", selected = true) { app.toggleReminder(c, p); onDismiss() }
                if (p.stop > now && !app.kids) Pill(if (app.recordingFor(c, p) != null) "Cancel the recording" else "● Record") { app.toggleRecording(c, p); onDismiss() }
                Pill("▶ ${c.name} now") { onDismiss(); app.playChannel(c) }
                Pill("Close", onClick = onDismiss)
            }
        }
    }
}

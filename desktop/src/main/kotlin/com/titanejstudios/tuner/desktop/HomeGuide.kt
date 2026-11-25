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

/** Home: carry on watching, your channels, My list and Plex. */
@Composable
fun HomePage(app: AppState) {
    val now = app.now
    val version = app.listsVersion
    val cont = remember(version, app.profileId) { app.continueWatching }
    val favs = remember(version, app.profileId, app.channels) { app.visibleChannels.filter { it.id in app.favourites } }
    val myList = remember(version) { app.watchlist }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            Text(
                app.profile?.let { "Hello, ${it.name}" } ?: "Flicker", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
            )
            if (app.config.sources.isEmpty()) {
                Text(
                    "To get started, go to Settings and sign in with Google – your TV's channels, profiles, favourites and " +
                        "Continue watching come across. Or add a playlist yourself.",
                    color = Soft, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Pill("Go to Settings", selected = true) { app.page = Page.Settings }
            }
        }
        if (cont.isNotEmpty() && !app.kids) {
            item { SectionTitle("Continue watching") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(cont.take(20), key = { it.id }) { r ->
                        WideTile(
                            r.title, r.poster, listOfNotNull(r.sub, if (r.dur > 0) "${(r.dur - r.pos) / 60_000} min left" else null).joinToString(" · "),
                            progress = if (r.dur > 0) r.pos.toFloat() / r.dur else null,
                        ) { app.watch(Watching(r.id, r.title, r.sub, r.poster, r.url, r.series, resumable = true)) }
                    }
                }
            }
        }
        if (favs.isNotEmpty()) {
            item { SectionTitle("Your channels") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs, key = { it.id }) { c ->
                        val cur = app.guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                        ChannelTile(c, cur?.title) { app.playChannel(c) }
                    }
                }
            }
        }
        if (myList.isNotEmpty() && !app.kids) {
            item { SectionTitle("My list") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(myList, key = { it.id }) { w ->
                        PosterTile(w.name, w.image, if (w.isSeries) "Series" else w.category) {
                            if (w.isSeries) {
                                app.openSeriesId = w.id
                                app.page = Page.Series
                            } else w.toVod()?.let { app.playFilm(it) }
                        }
                    }
                }
            }
        }
        if (app.plexDeck.isNotEmpty() && !app.kids) {
            item { SectionTitle("Continue on Plex") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(app.plexDeck, key = { it.ratingKey }) { p ->
                        WideTile(p.title, p.image, p.subtitle, progress = if (p.durationMs > 0) p.viewOffsetMs.toFloat() / p.durationMs else null) {
                            app.playPlex(p.ratingKey, p.title, p.subtitle, p.image, p.playUrl, p.server, p.viewOffsetMs)
                        }
                    }
                }
            }
        }
        // What's on now on your channels (or the first channels if you've no favourites).
        val onNow = (favs.ifEmpty { app.visibleChannels.take(40) }).mapNotNull { c ->
            app.guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }?.let { c to it }
        }
        if (onNow.isNotEmpty()) {
            item { SectionTitle("On now") }
            items(onNow.take(12), key = { "now" + it.first.id }) { (c, p) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Panel)
                        .clickable { app.playChannel(c) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 60.dp, height = 34.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Text(p.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${c.name}  ·  until ${time(p.stop)}", color = Soft, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(c: Channel, now: String?, onClick: () -> Unit) {
    Column(Modifier.width(180.dp).clip(RoundedCornerShape(10.dp)).background(Panel).clickable(onClick = onClick).padding(10.dp)) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
        Text(c.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(now ?: "", color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    .then(if (app.hasReminder(c, p)) Modifier.border(1.dp, Gold, RoundedCornerShape(6.dp)) else Modifier)
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
                Pill("▶ ${c.name} now") { onDismiss(); app.playChannel(c) }
                Pill("Close", onClick = onDismiss)
            }
        }
    }
}

package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.titanejstudios.tuner.data.Catchup
import kotlinx.coroutines.delay

private const val FAVOURITES = "★ Favourites"
private const val ALL = "All channels"

/** Live TV: categories, channels with what's on, and the video with now/next and catch-up. */
@Composable
fun LivePage(app: AppState, fullScreen: Boolean, toggleFullScreen: () -> Unit) {
    var group by remember { mutableStateOf(if (app.favourites.isNotEmpty()) FAVOURITES else ALL) }
    var search by remember { mutableStateOf("") }
    val now = app.now
    val channels = app.visibleChannels
    val favourites = remember(app.listsVersion, app.profileId) { app.favourites }
    val guide = app.guide

    val groups = remember(channels, favourites) {
        buildList {
            if (favourites.isNotEmpty()) add(FAVOURITES)
            add(ALL)
            addAll(channels.map { it.group }.distinct())
        }
    }
    val shown = remember(channels, group, search, favourites, guide) {
        val base = when (group) {
            ALL -> channels
            FAVOURITES -> channels.filter { it.id in favourites }
            else -> channels.filter { it.group == group }
        }
        val q = search.trim().lowercase()
        if (q.isEmpty()) base else channels.filter { c ->
            c.name.lowercase().contains(q) || guide[c.id]?.any { it.stop > now && it.start < now + 6 * 3_600_000L && it.title.lowercase().contains(q) } == true
        }
    }
    LaunchedEffect(shown) { app.liveList = shown }

    Row(Modifier.fillMaxSize().padding(top = if (fullScreen) 0.dp else 14.dp)) {
        if (!fullScreen) {
            Column(Modifier.width(210.dp).fillMaxHeight().padding(start = 12.dp)) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(groups, key = { it }) { g ->
                        val sel = g == group && search.isBlank()
                        Text(
                            g, color = if (sel) Color.White else Soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) Sky.copy(alpha = 0.35f) else Color.Transparent)
                                .clickable { group = g; search = "" }.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            LazyColumn(Modifier.width(380.dp).fillMaxHeight().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (channels.isEmpty() && app.status == null) {
                    item {
                        Text(
                            if (app.config.sources.isEmpty()) "No channels yet. In Settings, sign in with Google to bring in your TV's channels, or add a playlist."
                            else "No channels loaded." + app.problems.joinToString("\n", prefix = "\n"),
                            color = Soft, fontSize = 15.sp, modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { c ->
                    val cur = guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                    val sel = c.id == app.playingChannel?.id
                    Row(
                        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (sel) Sky.copy(alpha = 0.45f) else Panel)
                            .clickable { app.playChannel(c) }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 58.dp, height = 34.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cur?.title ?: "", color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cur != null) LinearProgressIndicator(
                                progress = { ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp), color = Sky, trackColor = PanelLight,
                            )
                        }
                        val fav = c.id in favourites
                        Text(if (fav) "★" else "☆", color = if (fav) Gold else Soft, fontSize = 18.sp,
                            modifier = Modifier.clickable { app.toggleFavourite(c) }.padding(6.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(end = if (fullScreen) 0.dp else 16.dp)) {
            VideoBox(
                app.player,
                (if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    .clip(RoundedCornerShape(if (fullScreen) 0.dp else 12.dp)),
            )
            if (!fullScreen) {
                val c = app.playingChannel
                Spacer(Modifier.height(12.dp))
                if (c == null) {
                    Text("Click a channel to watch. F11 or double-click for full screen · Page Up/Down change channel · Space pause · Ctrl+M mute", color = Soft, fontSize = 14.sp)
                } else {
                    val list = guide[c.id].orEmpty()
                    val cur = app.catchupOf ?: list.firstOrNull { it.start <= now && it.stop > now }
                    val next = list.firstOrNull { it.start >= (cur?.stop ?: now) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NetImage(c.logo, Modifier.size(width = 72.dp, height = 40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (app.catchupOf != null) "↺ CATCH-UP" else "ON NOW", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(cur?.title ?: c.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.name + (cur?.let { "  ·  ${time(it.start)} – ${time(it.stop)}" } ?: "") + (next?.let { "  ·  Next: ${time(it.start)} ${it.title}" } ?: ""),
                                color = Soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (app.catchupOf != null) Pill("● Back to live") { app.playChannel(c) }
                        Spacer(Modifier.width(8.dp))
                        Pill("⛶ Full screen", onClick = toggleFullScreen)
                    }
                    cur?.desc?.let { Text(it, color = Soft, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                    if (c.hasCatchup) {
                        val earlier = list.filter { it.stop <= now && Catchup.isAvailable(c, it, now) }.sortedByDescending { it.start }.take(30)
                        if (earlier.isNotEmpty()) {
                            Text("Earlier (catch-up)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                items(earlier, key = { it.start }) { p ->
                                    Text(
                                        "${time(p.start)}  ${p.title}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel)
                                            .clickable { app.playCatchup(c, p) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Films, episodes and Plex: the video big, with a seek bar underneath. */
@Composable
fun PlayerPage(app: AppState, fullScreen: Boolean, toggleFullScreen: () -> Unit) {
    val w = app.watching ?: return
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragTo by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(w.id) {
        while (true) {
            if (!dragging) {
                pos = app.player.positionMs
                dur = app.player.durationMs
            }
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize()) {
        VideoBox(app.player, Modifier.fillMaxWidth().weight(1f))
        if (!fullScreen) {
            Column(Modifier.fillMaxWidth().background(InkBottom).padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        w.subtitle?.let { Text(it, color = Soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Text(clock(if (dragging) (dragTo * dur).toLong() else pos) + " / " + clock(dur), color = Soft, fontSize = 13.sp)
                }
                if (dur > 0) Slider(
                    value = if (dragging) dragTo else (pos.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = {
                        dragging = true
                        dragTo = it
                    },
                    onValueChangeFinished = {
                        app.player.seekTo((dragTo * dur).toLong())
                        pos = (dragTo * dur).toLong()
                        dragging = false
                    },
                    colors = SliderDefaults.colors(thumbColor = Sky, activeTrackColor = Sky, inactiveTrackColor = PanelLight),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("⏯ Pause / play") { app.player.togglePause() }
                    Pill("« 10s") { app.player.skip(-10_000L) }
                    Pill("30s »") { app.player.skip(30_000L) }
                    w.next?.let { n -> Pill("Next episode ›") { app.saveProgress(); n() } }
                    Pill("⛶ Full screen", onClick = toggleFullScreen)
                    Spacer(Modifier.weight(1f))
                    Pill("✕ Close") { app.stopWatching(save = true) }
                }
                Text("Space pause · ← → skip · F11 or double-click full screen · Esc close", color = Soft.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

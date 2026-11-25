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
import com.titanejstudios.tuner.data.WatchItem
import androidx.compose.ui.input.pointer.onPointerEvent
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
private const val RECENT = "Recently watched"

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
            if (app.config.recents.isNotEmpty()) add(RECENT)
            add(ALL)
            addAll(channels.map { it.group }.distinct())
        }
    }
    val shown = remember(channels, group, search, favourites, guide) {
        val base = when (group) {
            ALL -> channels
            FAVOURITES -> channels.filter { it.id in favourites }
            RECENT -> app.recentChannels
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
                    if (app.config.lockedGroups.isNotEmpty() && app.unlockedGroups.isEmpty()) item {
                        Text(
                            "🔒 Locked groups…", color = Soft, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { app.unlockGroups() }.padding(horizontal = 12.dp, vertical = 9.dp),
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
                items(shown.size, key = { shown[it].id }) { index ->
                    val c = shown[index]
                    val cur = guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                    val sel = c.id == app.playingChannel?.id
                    androidx.compose.foundation.ContextMenuArea(items = {
                        listOf(
                            androidx.compose.foundation.ContextMenuItem("▶ Watch") { app.playChannel(c) },
                            androidx.compose.foundation.ContextMenuItem(if (c.id in favourites) "☆ Remove from favourites" else "★ Add to favourites") { app.toggleFavourite(c) },
                        ) + listOfNotNull(
                            guide[c.id]?.firstOrNull { it.start > now }?.let { nx ->
                                androidx.compose.foundation.ContextMenuItem("⏰ Remind me: ${time(nx.start)} ${nx.title}") { app.toggleReminder(c, nx) }
                            },
                            cur?.let { p -> androidx.compose.foundation.ContextMenuItem("● Record ${p.title}") { app.toggleRecording(c, p) } }?.takeIf { !app.kids },
                        )
                    }) {
                    Row(
                        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (sel) Sky.copy(alpha = 0.45f) else if (app.previewing?.id == c.id) Sky.copy(alpha = 0.2f) else Panel)
                            .hoverPreview(app, c)
                            .clickable { app.playChannel(c) }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (app.config.showNumbers) Text(
                            app.numberOf(c, channels.indexOf(c)).toString(), color = Soft, fontSize = 12.sp, modifier = Modifier.width(34.dp),
                        )
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
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(end = if (fullScreen) 0.dp else 16.dp)) {
            VideoBox(
                app,
                (if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    .clip(RoundedCornerShape(if (fullScreen) 0.dp else 12.dp)),
            )
            if (!fullScreen) {
                val c = app.playingChannel
                Spacer(Modifier.height(12.dp))
                if (c == null && app.previewing != null) {
                    Text("Preview: ${app.previewing?.name} – click it to watch with sound", color = Soft, fontSize = 14.sp)
                } else if (c == null) {
                    Text("Click a channel to watch (or rest the pointer on one for a silent preview). F11 or double-click for full screen · Page Up/Down change channel · Space pause · Ctrl+M mute", color = Soft, fontSize = 14.sp)
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
                        if (app.timeshift != null) Pill("● Back to live", selected = true) { app.goLive() }
                        else if (app.catchupOf == null) Pill("⏸ Pause") { app.togglePauseLive() }
                        app.startOverProgramme()?.let { (sc, sp) ->
                            Spacer(Modifier.width(8.dp))
                            Pill("⏮ From the start") { app.playCatchup(sc, sp) }
                        }
                        Spacer(Modifier.width(8.dp))
                        PictureMenu(app)
                        Spacer(Modifier.width(8.dp))
                        Pill("▣ Mini", selected = app.mini) { app.toggleMini() }
                        if (app.secondScreen != null || app.otherScreen() != null) {
                            Spacer(Modifier.width(8.dp))
                            Pill("🖥", selected = app.secondScreen != null) { app.toggleSecondScreen() }
                        }
                        Spacer(Modifier.width(8.dp))
                        VolumeSlider(app)
                        Spacer(Modifier.width(8.dp))
                        SendToTv(app)
                        Spacer(Modifier.width(8.dp))
                        WatchingMenu(app)
                        Spacer(Modifier.width(8.dp))
                        Pill("⛶ Full screen", onClick = toggleFullScreen)
                    }
                    if (app.timeshift != null) {
                        val behind = app.behindLiveMs()
                        Text(
                            (if (app.timeshiftPlaying) "⏱ ${clock(behind)} behind live" else "⏸ Paused · ${clock(behind)}") +
                                " – Space to ${if (app.timeshiftPlaying) "pause" else "carry on"}, or Back to live",
                            color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                        )
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
        VideoBox(app, Modifier.fillMaxWidth().weight(1f))
        // Skip intro, and Up next in the last 30 seconds of an episode.
        val introEnd = app.introEnd()
        val left = if (dur > 0) dur - pos else Long.MAX_VALUE
        val showSkip = w.seriesId != null && pos in 5_000L..240_000L && (introEnd == null || pos < introEnd - 3_000L)
        val showNext = w.next != null && left < maxOf(30_000L, app.creditsFor(w.seriesId) ?: 0L) && app.upNextCancelled != w.id
        if (showSkip || showNext) {
            Row(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showSkip) Pill(if (introEnd != null) "⏭ Skip intro" else "⏭ Skip intro (85s)", selected = true) {
                    if (introEnd != null) app.skipIntro() else app.skipIntroGuess()
                }
                if (showNext) {
                    Text(if (left > 30_000L) "Credits – next episode" else "Up next in ${(left / 1000).coerceAtLeast(0)}s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    w.next?.let { n -> Pill("▶ Play now", selected = true) { app.saveProgress(); n() } }
                    Pill("Cancel") { app.upNextCancelled = w.id }
                }
            }
        }
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
                    Pill(if (app.mini) "▣ Back from mini player" else "▣ Mini player") { app.toggleMini() }
                    if (app.secondScreen != null || app.otherScreen() != null) Pill(if (app.secondScreen != null) "🖥 Back to this screen" else "🖥 Other screen") { app.toggleSecondScreen() }
                    PictureMenu(app, films = true)
                    WatchingMenu(app)
                    VolumeSlider(app)
                    SendToTv(app)
                    Spacer(Modifier.weight(1f))
                    Pill("✕ Close") { app.stopWatching(save = true) }
                }
                Text("Space pause · ← → skip · F11 or double-click full screen · Esc close", color = Soft.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}


/** "📺 Watch on…": the TVs on the Wi-Fi (found when opened). */
@Composable
fun SendToTv(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("📺 Watch on…") {
            open = true
            app.findPeers()
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (app.findingPeers) androidx.compose.material3.DropdownMenuItem(text = { Text("Looking for your TVs…") }, onClick = {})
            else if (app.peers.isEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("No TVs found") }, onClick = { open = false })
            for (p in app.peers) androidx.compose.material3.DropdownMenuItem(
                text = { Text((if (p.phone) "📱 " else "📺 ") + p.name) },
                onClick = {
                    open = false
                    app.sendTo(p)
                },
            )
        }
    }
}


/** One line of the Picture & sound menu: a heading (no action) or a choice. */
private class MenuLine(val text: String, val heading: Boolean = false, val selected: Boolean = false, val action: (() -> Unit)? = null)

/** "⚙ Picture & sound": sound track, subtitles, picture shape and (films) speed. */
@Composable
fun PictureMenu(app: AppState, films: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("⚙ Picture & sound") { open = true }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val p = app.player
            val lines = buildList {
                val audio = p.audioTracks().filter { it.first >= 0 }
                if (audio.size > 1) {
                    add(MenuLine("Sound", heading = true))
                    val cur = p.audioTrack
                    for ((id, name) in audio) add(MenuLine(name, selected = id == cur) { p.setAudioTrack(id) })
                }
                add(MenuLine("Subtitles", heading = true))
                val subs = p.subtitleTracks().filter { it.first >= 0 }
                if (subs.isEmpty()) add(MenuLine("None on this"))
                else {
                    val cur = p.subtitleTrack
                    add(MenuLine("Off", selected = cur < 0) { p.setSubtitleTrack(-1) })
                    for ((id, name) in subs) add(MenuLine(name, selected = id == cur) { p.setSubtitleTrack(id) })
                }
                add(MenuLine("Picture", heading = true))
                add(MenuLine("Fit") { p.setShape(null) })
                add(MenuLine("Fill the window") { p.setShape("Fill") })
                add(MenuLine("16:9") { p.setShape("16:9") })
                add(MenuLine("4:3") { p.setShape("4:3") })
                if (films) {
                    add(MenuLine("Speed", heading = true))
                    for (r in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) add(MenuLine(if (r == 1f) "Normal" else "$r×") { p.setSpeed(r) })
                }
                add(MenuLine("Sound", heading = true))
                add(MenuLine("🌙 Night mode (quiet bangs, louder voices)", selected = app.config.nightMode) { app.toggleNightMode() })
                add(MenuLine("🗣 Clearer speech", selected = app.config.clearSpeech) { app.toggleClearSpeech() })
                add(MenuLine("Sound timing: ${app.lipSyncLabel()}", heading = true))
                for (ms in listOf(-300L, -200L, -100L, 0L, 100L, 200L, 300L)) {
                    add(MenuLine(app.lipSyncLabel(ms), selected = ms == app.config.lipSyncMs) { app.setLipSync(ms) })
                }
                add(MenuLine("More", heading = true))
                add(MenuLine("Subtitle size: ${app.subtitleSizeLabel} (press to change)") { app.cycleSubtitleSize() })
                add(MenuLine("ℹ Stream info", selected = app.showStreamInfo) { app.showStreamInfo = !app.showStreamInfo })
            }
            for (l in lines) {
                if (l.heading) Text(l.text, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                else androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (l.selected) "✓ " else "    ") + l.text) },
                    enabled = l.action != null,
                    onClick = {
                        open = false
                        l.action?.invoke()
                    },
                )
            }
        }
    }
}


/** Rest the pointer on a channel (with nothing playing) for a silent preview. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.hoverPreview(app: AppState, c: com.titanejstudios.tuner.data.Channel): Modifier = this
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { app.hoverChannel(c) }
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { app.hoverChannel(null) }

/** 🔊 and a slider (0–150%). */
@Composable
fun VolumeSlider(app: AppState) {
    var v by remember { mutableFloatStateOf(app.config.volume.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (v < 1f) "🔇" else "🔊", fontSize = 15.sp, modifier = Modifier.clickable { app.player.toggleMute() }.padding(end = 4.dp))
        Slider(
            value = v, onValueChange = { v = it; app.player.setVolume(it.toInt()) }, valueRange = 0f..150f,
            onValueChangeFinished = { app.setVolume(v.toInt()) },
            modifier = Modifier.width(120.dp),
            colors = SliderDefaults.colors(thumbColor = Sky, activeTrackColor = Sky, inactiveTrackColor = PanelLight),
        )
    }
}


/** ⋯ More: go to the series or film, My list, more of this show. Playing carries on in the mini player. */
@Composable
fun WatchingMenu(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("⋯ More") {
            open = true
            if (app.link.key != null && app.peers.isEmpty() && !app.findingPeers) app.findPeers()
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val w = app.watching
            val series = app.playingSeries()
            val film = app.playingFilm()
            val channel = app.playingChannel
            val programme = app.catchupOf ?: channel?.let { c -> app.guide[c.id]?.firstOrNull { it.start <= app.now && it.stop > app.now } }
            val lines = buildList {
                if (channel != null && w == null) {
                    add(MenuLine("🛠 Fix it – start it again, then try other copies") { app.fixIt() })
                    val copies = app.versionsFor(channel)
                    if (copies.size > 1) {
                        add(MenuLine("Copies of ${channel.name}", heading = true))
                        for (v in copies) add(MenuLine("📺 " + v.name, selected = app.player.lastUrl == v.url) { app.playVersion(channel, v) })
                    }
                }
                if (w != null && !w.id.startsWith("rec:")) add(MenuLine("🔎 Find subtitles online") { app.findSubtitles() })
                add(MenuLine("😴 Sleep timer: ${app.sleepLabel}", heading = true))
                add(MenuLine("Off", selected = app.sleepAt == 0L) { app.setSleep(0) })
                if (channel != null && w == null) add(MenuLine("At the end of this programme") { app.setSleep(-1) })
                for (m in listOf(15, 30, 60, 90, 120)) add(MenuLine(if (m < 60) "In $m minutes" else "In ${m / 60}h${if (m % 60 > 0) " ${m % 60}m" else ""}") { app.setSleep(m) })
                if (app.togetherWith != null) add(MenuLine("✕ Stop watching together with ${app.togetherWith}") { app.stopTogether() })
                else if (app.link.key != null) {
                    add(MenuLine("👫 Watch together with…", heading = true))
                    if (app.peers.isEmpty()) add(MenuLine(if (app.findingPeers) "Looking…" else "Find your TVs and phones") { app.findPeers() })
                    for (p in app.peers) add(MenuLine((if (p.phone) "📱 " else "📺 ") + p.name) { app.watchTogether(p) })
                }
                add(MenuLine("Go to", heading = true))
                if (series != null) {
                    add(MenuLine("📺 Go to series – all the episodes") { app.goToSeries() })
                    add(MenuLine(if (app.inWatchlist(series.id)) "✓ In My list (remove)" else "＋ Add series to My list") {
                        app.toggleWatchlist(WatchItem.of(series, System.currentTimeMillis()))
                    })
                }
                w?.next?.let { n -> add(MenuLine("⏭ Next episode") { app.saveProgress(); n() }) }
                if (film != null) {
                    add(MenuLine("🎬 Go to film page – cast and the rest of the series") { app.goToFilm() })
                    add(MenuLine(if (app.inWatchlist(film.id)) "✓ In My list (remove)" else "＋ Add film to My list") {
                        app.toggleWatchlist(WatchItem.of(film, System.currentTimeMillis()))
                    })
                    if (app.downloadFor(film.id) == null && !app.kids) add(MenuLine("⤓ Download to this PC") {
                        app.download(film.id, film.name, film.category, film.poster, film.url)
                    })
                }
                if (w != null && w.resumable) add(MenuLine("⏮ Start again") { app.player.seekTo(0L) })
                if (channel != null && programme != null) {
                    if (!app.kids) add(MenuLine("● Record ${programme.title}") { app.toggleRecording(channel, programme) })
                    add(MenuLine("📅 Go to the guide") { app.page = Page.Guide })
                }
                val name = w?.title ?: programme?.title
                if (name != null) {
                    val t = app.thumbOf(name)
                    add(MenuLine(if (t == 1) "👍 Liked (undo)" else "👍 More like this") { app.thumb(name, true) })
                    add(MenuLine(if (t == -1) "👎 Not for me (undo)" else "👎 Less like this") { app.thumb(name, false) })
                }
                app.playingWatchItem()?.let { item ->
                    if (!app.kids) add(MenuLine("👪 Recommend to the family") { app.recommendToFamily(item) })
                }
                if (w?.seriesId != null) {
                    val d = app.player.durationMs
                    if (d > 0 && d - app.player.positionMs in 15_000L..900_000L) add(MenuLine("🎬 The credits start here") { app.setCreditsHere() })
                }
                if (name != null) add(MenuLine("🔍 More of $name – episodes, showings, Plex") { app.findMoreOf(name) })
                if (w != null || channel != null) add(MenuLine(if (app.mini) "⤢ Back to the big picture" else "▣ Keep watching small and look around") { app.toggleMini() })
            }
            if (lines.isEmpty()) Text("Nothing more for this", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            for (l in lines) {
                if (l.heading) Text(l.text, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                else androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (l.selected) "✓ " else "") + l.text) },
                    onClick = {
                        open = false
                        l.action?.invoke()
                    },
                )
            }
        }
    }
}

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// The "Evening" colours from the TV app.
private val Ink = Color(0xFF07123A)
private val InkBottom = Color(0xFF030920)
private val Panel = Color(0xFF10204F)
private val PanelLight = Color(0xFF1A2A5E)
private val Sky = Color(0xFF4D9BFF)
private val Soft = Color(0xFFAAB6DA)
private val Gold = Color(0xFFFFB648)

private const val FAVOURITES = "★ Favourites"
private const val ALL = "All channels"

private val hhmm = SimpleDateFormat("HH:mm", Locale.UK)
private fun time(t: Long) = hhmm.format(Date(t))

fun main() = application {
    val config = remember { Config() }
    val player = remember { Player() }
    val state = rememberWindowState(size = androidx.compose.ui.unit.DpSize(1360.dp, 820.dp))
    var zap by remember { mutableStateOf<(Int) -> Unit>({}) }
    Window(
        onCloseRequest = {
            player.release()
            exitApplication()
        },
        state = state,
        title = "Tuner",
        onKeyEvent = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            val full = state.placement == WindowPlacement.Fullscreen
            when {
                e.key == Key.F11 || (e.key == Key.F && e.isCtrlPressed) -> {
                    state.placement = if (full) WindowPlacement.Floating else WindowPlacement.Fullscreen
                    true
                }
                e.key == Key.Escape && full -> {
                    state.placement = WindowPlacement.Floating
                    true
                }
                e.key == Key.PageUp || (full && e.key == Key.DirectionUp) -> { zap(-1); true }
                e.key == Key.PageDown || (full && e.key == Key.DirectionDown) -> { zap(1); true }
                e.key == Key.M && e.isCtrlPressed -> { player.toggleMute(); true }
                else -> false
            }
        },
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Sky, background = Ink, surface = Panel)) {
            App(config, player, fullScreen = state.placement == WindowPlacement.Fullscreen, onZapReady = { zap = it }) {
                state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
            }
        }
    }
}

@Composable
private fun App(config: Config, player: Player, fullScreen: Boolean, onZapReady: ((Int) -> Unit) -> Unit, toggleFullScreen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var guide by remember { mutableStateOf<Map<String, List<Programme>>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var group by remember { mutableStateOf(ALL) }
    var search by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var catchupOf by remember { mutableStateOf<Programme?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var favourites by remember { mutableStateOf(config.favourites.toSet()) }
    var englishOnly by remember { mutableStateOf(config.englishOnly) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun reload() {
        scope.launch {
            status = "Loading…"
            val loaded = withContext(Dispatchers.IO) {
                Library.load(config.sources, config.englishOnly, config.mergeVersions) { msg -> scope.launch { status = msg } }
            }
            channels = loaded.channels
            guide = loaded.guide
            problems = loaded.problems
            status = null
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) { if (config.sources.isNotEmpty()) reload() }

    fun play(c: Channel) {
        playing = c
        catchupOf = null
        player.play(c.url)
        player.setVolume(config.volume)
        config.lastChannel = c.id
        config.save()
    }

    val groups = remember(channels, favourites) {
        buildList {
            if (favourites.isNotEmpty()) add(FAVOURITES)
            add(ALL)
            addAll(channels.map { it.group }.distinct())
        }
    }
    val shown = remember(channels, group, search, favourites) {
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
    fun zapBy(d: Int) {
        val list = shown.ifEmpty { channels }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == playing?.id }
        play(list[(i + d).mod(list.size)])
    }
    LaunchedEffect(shown, playing) { onZapReady { d -> zapBy(d) } }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Ink, InkBottom)))) {
        if (!fullScreen) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tuner", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(20.dp))
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    placeholder = { Text("Search channels and programmes") },
                    modifier = Modifier.width(380.dp),
                )
                Spacer(Modifier.weight(1f))
                status?.let { Text("⟳ $it", color = Soft, fontSize = 14.sp, modifier = Modifier.padding(end = 14.dp)) }
                TextButton(onClick = {
                    englishOnly = !englishOnly
                    config.englishOnly = englishOnly
                    config.save()
                    reload()
                }) { Text(if (englishOnly) "🇬🇧 English only ✓" else "🇬🇧 English only", color = if (englishOnly) Gold else Soft) }
                TextButton(onClick = { reload() }) { Text("Reload", color = Soft) }
                Button(onClick = { addOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = Sky)) { Text("Add playlist") }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (!fullScreen) {
                // Categories
                LazyColumn(Modifier.width(220.dp).fillMaxHeight().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(groups, key = { it }) { g ->
                        val sel = g == group && search.isBlank()
                        Text(
                            g, color = if (sel) Color.White else Soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) Sky.copy(alpha = 0.35f) else Color.Transparent)
                                .clickable { group = g; search = "" }.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
                // Channels with what's on now
                LazyColumn(Modifier.width(400.dp).fillMaxHeight().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (channels.isEmpty() && status == null) {
                        item {
                            Text(
                                if (config.sources.isEmpty()) "Add your playlist (Add playlist, top right) – the same M3U link or Xtream login as on the TV."
                                else "No channels loaded." + problems.joinToString("\n", prefix = "\n"),
                                color = Soft, fontSize = 15.sp, modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { c ->
                        val cur = guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                        val sel = c.id == playing?.id
                        Row(
                            Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Sky.copy(alpha = 0.45f) else Panel)
                                .clickable { play(c) }.padding(horizontal = 10.dp),
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
                            Text(if (c.id in favourites) "★" else "☆", color = if (c.id in favourites) Gold else Soft, fontSize = 18.sp,
                                modifier = Modifier.clickable {
                                    favourites = if (c.id in favourites) favourites - c.id else favourites + c.id
                                    config.favourites = favourites.toList()
                                    config.save()
                                }.padding(6.dp))
                        }
                    }
                }
            }
            // Video and what's on
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = if (fullScreen) 0.dp else 16.dp)) {
                val video = player.component
                Box(
                    (if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        .clip(RoundedCornerShape(if (fullScreen) 0.dp else 12.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        video != null -> SwingPanel(factory = { video }, modifier = Modifier.fillMaxSize())
                        else -> Text(
                            "Tuner uses VLC to play video.\nInstall VLC (64-bit) from videolan.org, then open Tuner again.",
                            color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                if (!fullScreen) {
                    val c = playing
                    Spacer(Modifier.height(12.dp))
                    if (c == null) {
                        Text("Click a channel to watch. F11 for full screen · Page Up/Down change channel · Ctrl+M mute", color = Soft, fontSize = 14.sp)
                    } else {
                        val list = guide[c.id].orEmpty()
                        val cur = catchupOf ?: list.firstOrNull { it.start <= now && it.stop > now }
                        val next = list.firstOrNull { it.start >= (cur?.stop ?: now) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NetImage(c.logo, Modifier.size(width = 72.dp, height = 40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (catchupOf != null) "↺ CATCH-UP" else "ON NOW", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(cur?.title ?: c.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.name + (cur?.let { "  ·  ${time(it.start)} – ${time(it.stop)}" } ?: "") + (next?.let { "  ·  Next: ${time(it.start)} ${it.title}" } ?: ""),
                                    color = Soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = toggleFullScreen) { Text("⛶ Full screen", color = Soft) }
                        }
                        cur?.desc?.let { Text(it, color = Soft, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                        // Catch-up: earlier programmes on this channel.
                        if (c.hasCatchup) {
                            val earlier = list.filter { it.stop <= now && Catchup.isAvailable(c, it, now) }.sortedByDescending { it.start }.take(30)
                            if (earlier.isNotEmpty()) {
                                Text("Earlier (catch-up)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    items(earlier, key = { it.start }) { p ->
                                        Text(
                                            "${time(p.start)}  ${p.title}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).clickable {
                                                Catchup.url(c, p, System.currentTimeMillis())?.let { url ->
                                                    catchupOf = p
                                                    player.play(url)
                                                }
                                            }.padding(horizontal = 10.dp, vertical = 6.dp),
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

    if (addOpen) AddPlaylistDialog(onDismiss = { addOpen = false }) { src ->
        config.sources = config.sources + src
        config.save()
        addOpen = false
        reload()
    }
}

@Composable
private fun AddPlaylistDialog(onDismiss: () -> Unit, onAdd: (Source) -> Unit) {
    var xtream by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    androidx.compose.ui.window.DialogWindow(onCloseRequest = onDismiss, title = "Add playlist", state = androidx.compose.ui.window.rememberDialogState(size = androidx.compose.ui.unit.DpSize(520.dp, 560.dp))) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Sky, background = Ink, surface = Panel)) {
            Column(Modifier.fillMaxSize().background(Ink).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    TextButton(onClick = { xtream = true }) { Text("Xtream login", color = if (xtream) Sky else Soft) }
                    TextButton(onClick = { xtream = false }) { Text("M3U link", color = if (!xtream) Sky else Soft) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (xtream) {
                    OutlinedTextField(server, { server = it }, label = { Text("Server (http://provider.com:8080)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                } else {
                    OutlinedTextField(url, { url = it }, label = { Text("Playlist URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(epg, { epg = it }, label = { Text("TV guide (XMLTV) URL – optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val id = UUID.randomUUID().toString().take(8)
                            val e = epg.trim().ifEmpty { null }
                            if (xtream && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                                onAdd(Source.Xtream(id, name.ifBlank { "Provider" }, server.trim(), user.trim(), pass, false, e))
                            } else if (!xtream && url.isNotBlank()) {
                                onAdd(Source.M3u(id, name.ifBlank { "Playlist" }, url.trim(), e))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Sky),
                    ) { Text("Add") }
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Soft) }
                }
            }
        }
    }
}

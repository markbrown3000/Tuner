package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.integrations.Plex
import com.titanejstudios.tuner.integrations.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ---------------- Plex ----------------

@Composable
fun PlexPage(app: AppState) {
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf<Plex.Section?>(null) }
    var items by remember { mutableStateOf<List<Plex.Entry>?>(null) }
    var show by remember { mutableStateOf<Plex.Entry?>(null) }
    var episodes by remember { mutableStateOf<List<Plex.Entry>?>(null) }
    LaunchedEffect(app.plexLinked) { if (app.plexLinked && app.plexServers.isEmpty()) app.loadPlex() }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Plex", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        if (!app.plexLinked) {
            Spacer(Modifier.height(12.dp))
            val code = app.plexCode
            if (code == null) {
                Text("Link your Plex account to watch your Plex films and shows here.", color = Soft, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Pill("Link Plex", selected = true) { app.linkPlex() }
            } else {
                Text("Go to plex.tv/link and enter this code:", color = Soft, fontSize = 15.sp)
                Text(code, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Open plex.tv/link ↗", selected = true) { openInBrowser("https://plex.tv/link") }
                    Pill("Cancel") { app.cancelPlexLink() }
                }
            }
            return@Column
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pill("Continue watching", selected = section == null) { section = null; show = null } }
            items(app.plexSections, key = { it.id }) { s ->
                Pill(s.title, selected = section?.id == s.id) {
                    section = s
                    show = null
                    items = null
                    scope.launch { items = withContext(Dispatchers.IO) { runCatching { app.plex.sectionItems(s) }.getOrDefault(emptyList()) } }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val sh = show
        val sec = section
        when {
            sh != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("‹ Back") { show = null }
                    Spacer(Modifier.width(12.dp))
                    Text(sh.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                val eps = episodes
                if (eps == null) Text("Loading episodes…", color = Soft, fontSize = 15.sp)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(eps, key = { it.ratingKey }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
                                .clickable { app.playPlex(e.ratingKey, sh.title, "S${e.season} E${e.episode}  ${e.title}", e.image, e.playUrl, e.server, e.viewOffsetMs) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(PanelLight)) {
                                NetImage(e.image, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("S${e.season} E${e.episode}  ${e.title}" + if (e.watched) "  ✓" else "", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                e.summary?.let { Text(it, color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
            sec == null -> {
                if (app.plexDeck.isEmpty()) Text(if (app.plexServers.isEmpty()) "Looking for your Plex servers…" else "Nothing to carry on with.", color = Soft, fontSize = 15.sp)
                LazyVerticalGrid(GridCells.Adaptive(240.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(app.plexDeck, key = { it.ratingKey }) { p ->
                        WideTile(p.title, p.image, p.subtitle, progress = if (p.durationMs > 0) p.viewOffsetMs.toFloat() / p.durationMs else null) {
                            app.playPlex(p.ratingKey, p.title, p.subtitle, p.image, p.playUrl, p.server, p.viewOffsetMs)
                        }
                    }
                }
            }
            else -> {
                val list = items
                if (list == null) Text("Loading ${sec.title}…", color = Soft, fontSize = 15.sp)
                else LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(list, key = { it.ratingKey }) { e ->
                        PosterTile(e.title, e.image, e.year, progress = if (e.durationMs > 0 && e.viewOffsetMs > 0) e.viewOffsetMs.toFloat() / e.durationMs else null) {
                            if (e.isShow) {
                                show = e
                                episodes = null
                                scope.launch { episodes = withContext(Dispatchers.IO) { runCatching { app.plex.episodes(e) }.getOrDefault(emptyList()) } }
                            } else app.playPlex(e.ratingKey, e.title, e.year, e.image, e.playUrl, e.server, e.viewOffsetMs)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- YouTube ----------------

/**
 * YouTube: what your profile's subscriptions have posted, and search (with the YouTube sign-in
 * from your TV, or the built-in key). Videos open in your browser – YouTube doesn't allow
 * playing them inside other apps on a PC.
 */
@Composable
fun YouTubePage(app: AppState) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<YouTube.Video>?>(null) }
    var heading by remember { mutableStateOf("From your subscriptions") }
    var problem by remember { mutableStateOf<String?>(null) }
    val token = app.config.youtubeTokens[app.profile?.name?.lowercase()?.trim()]
    val key = Keys.YOUTUBE_KEY.ifBlank { null }

    suspend fun access(): String? = token?.let { t -> withContext(Dispatchers.IO) { runCatching { app.sync.drive.refresh(t).access }.getOrNull() } }

    LaunchedEffect(token, app.profileId) {
        val a = access()
        if (a == null) {
            videos = emptyList()
            return@LaunchedEffect
        }
        videos = withContext(Dispatchers.IO) { runCatching { YouTube.subscriptionsFeed(a) }.onFailure { problem = it.message }.getOrDefault(emptyList()) }
    }
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        heading = "Results for \"$q\""
        videos = null
        scope.launch {
            val a = if (key == null) access() else null
            videos = withContext(Dispatchers.IO) {
                runCatching { YouTube.withoutShorts(YouTube.search(q, key, a), key, a) }.onFailure { problem = it.message }.getOrDefault(emptyList())
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YouTube", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(20.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search YouTube") }, modifier = Modifier.width(420.dp))
            Spacer(Modifier.width(10.dp))
            Pill("Search", selected = true) { search() }
        }
        if (token == null && key == null) {
            Text(
                "Sign in to YouTube in Flicker on your TV (on this profile) – after the next sync, your subscriptions and search show here.",
                color = Soft, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        problem?.let { Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        SectionTitle(heading)
        val list = videos
        if (list == null) Text("Loading…", color = Soft, fontSize = 15.sp)
        else LazyVerticalGrid(GridCells.Adaptive(260.dp), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.id }) { v ->
                WideTile(v.title, v.thumb, v.channel, width = 260.dp) { openInBrowser("https://www.youtube.com/watch?v=${v.id}") }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsPage(app: AppState) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PcProfile?>(null) }
    var logVersion by remember { mutableStateOf(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold) }

        item { SectionTitle("Sync with your TV") }
        item {
            Card {
                val code = app.signInCode
                when {
                    !app.sync.configured -> Text("This copy of Flicker was built without Google sign-in (the GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET secrets).", color = Soft, fontSize = 14.sp)
                    code != null -> {
                        Text("On your phone or computer, go to ${code.url} and enter:", color = Soft, fontSize = 15.sp)
                        Text(code.userCode, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Open ${code.url} ↗", selected = true) { openInBrowser(code.url) }
                            Pill("Cancel") { app.cancelSignIn() }
                        }
                    }
                    !app.signedIn -> {
                        Text(
                            "Sign in with the same Google account as Flicker on your TV (Settings › Sync between houses). Your channels, " +
                                "profiles, favourites, My list and Continue watching come across, and what you watch here goes back.",
                            color = Soft, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Pill("Sign in with Google", selected = true) { app.startSignIn() }
                    }
                    else -> {
                        Text("Signed in" + (app.config.googleEmail?.let { " as $it" } ?: ""), color = Color.White, fontSize = 15.sp)
                        Text(
                            if (app.config.lastSync > 0) "Last synced at ${time(app.config.lastSync)} · every 3 minutes while Flicker's open" else "Not synced yet",
                            color = Soft, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("↻ Sync now", selected = true) { app.syncSoon(0) }
                            Pill(if (app.config.useTvPlaylists) "✓ Use the TV's playlists" else "Use the TV's playlists") {
                                app.config.useTvPlaylists = !app.config.useTvPlaylists
                                app.config.save()
                                app.listsVersion++
                                if (app.config.useTvPlaylists) app.syncSoon(0)
                            }
                            Pill("Sign out") { app.signOut() }
                        }
                    }
                }
                app.syncStatus?.let { Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }

        item { SectionTitle("Playlists") }
        item {
            Card {
                val version = app.listsVersion
                if (app.config.sources.isEmpty()) Text("No playlists yet.", color = Soft, fontSize = 14.sp)
                for (s in app.config.sources) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Color.White, fontSize = 15.sp)
                            Text(if (s is Source.Xtream) "Xtream · ${s.server}" else "M3U", color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!app.config.useTvPlaylists || !app.signedIn) Pill("Remove") {
                            app.config.sources = app.config.sources - s
                            app.config.save()
                            app.listsVersion = version + 1
                            app.reload()
                        }
                    }
                }
                if (app.config.useTvPlaylists && app.signedIn) Text("These come from your TV. Turn off \"Use the TV's playlists\" to choose your own.", color = Soft, fontSize = 12.sp)
                else {
                    Spacer(Modifier.height(8.dp))
                    Pill("+ Add a playlist", selected = true) { adding = true }
                }
            }
        }

        item { SectionTitle("Channels") }
        item {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (app.englishOnly) "✓ English channels only" else "English channels only") {
                        app.englishOnly = !app.englishOnly
                        app.config.englishOnly = app.englishOnly
                        app.config.save()
                        app.reload()
                    }
                    Pill(if (app.config.mergeVersions) "✓ Merge HD/SD copies" else "Merge HD/SD copies") {
                        app.config.mergeVersions = !app.config.mergeVersions
                        app.config.save()
                        app.listsVersion++
                        app.reload()
                    }
                    Pill("↻ Reload channels and guide") { app.reload() }
                }
                if (app.problems.isNotEmpty()) Text(app.problems.joinToString("\n"), color = Gold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        item { SectionTitle("This PC") }
        item {
            Card {
                val v = app.listsVersion
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (app.config.keepRunning) "✓ Keep running in the tray" else "Keep running in the tray") {
                        app.config.keepRunning = !app.config.keepRunning
                        app.config.save()
                        app.listsVersion = v + 1
                    }
                    Pill(if (app.config.startWithWindows) "✓ Start with Windows" else "Start with Windows") { app.setStartWithWindows(!app.config.startWithWindows) }
                }
                Text(
                    "In the tray, closing the window leaves Flicker running, so recordings and reminders carry on (right-click its icon › Quit to close it).",
                    color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (app.config.showNumbers) "✓ Channel numbers" else "Channel numbers") {
                        app.config.showNumbers = !app.config.showNumbers
                        app.config.save()
                        app.listsVersion = v + 1
                    }
                    Pill(if (app.config.deinterlace) "✓ Smooth out old channels (deinterlace)" else "Smooth out old channels (deinterlace)") {
                        app.config.deinterlace = !app.config.deinterlace
                        app.player.deinterlace = app.config.deinterlace
                        app.config.save()
                        app.listsVersion = v + 1
                    }
                }
                Text("Type a channel's number (on Live TV, Home or the guide) to go straight to it.", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (app.config.normalise) "✓ Even out loud and quiet channels" else "Even out loud and quiet channels") {
                        app.config.normalise = !app.config.normalise
                        app.player.normalise = app.config.normalise
                        app.config.save()
                        app.listsVersion = v + 1
                    }
                    Pill(if (app.config.notifications) "✓ Windows notifications" else "Windows notifications") {
                        app.config.notifications = !app.config.notifications
                        app.config.save()
                        app.listsVersion = v + 1
                    }
                }
                Text("Notifications: reminders and finished recordings pop up in the corner, even with Flicker in the tray.", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (app.config.recaps) "✓ Previously on…" else "Previously on…") {
                        app.config.recaps = !app.config.recaps
                        app.config.save()
                        app.listsVersion = v + 1
                    }
                    Pill(if (app.miniOnTop) "✓ Mini player stays on top" else "Mini player stays on top") { app.toggleMiniOnTop() }
                }
                Text("Previously on…: starting an episode after a break shows what happened in the one before.", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("Sound and subtitles (${app.profile?.name ?: "this profile"})", color = Color.White, fontSize = 14.sp)
                val (audio, subs, describe) = app.config.languagesOf(app.profileId)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Pill("Sound: " + app.languageLabel(audio)) {
                        val c = app.languageChoices
                        app.setLanguages(c[(c.indexOf(audio) + 1) % c.size], subs, describe)
                    }
                    Pill("Subtitles: " + if (subs == null) "Off" else app.languageLabel(subs)) {
                        val c = app.languageChoices
                        app.setLanguages(audio, c[(c.indexOf(subs) + 1) % c.size], describe)
                    }
                    Pill(if (describe) "✓ Audio description" else "Audio description") { app.setLanguages(audio, subs, !describe) }
                }
                Text("Picked a moment after something starts, when it has more than one.", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("Text size", color = Color.White, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for ((label, scale) in listOf("Normal" to 1f, "Large" to 1.15f, "Extra large" to 1.3f)) Pill(label, selected = uiScale == scale) {
                        uiScale = scale
                        app.config.textScale = scale
                        app.config.save()
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Colours", color = Color.White, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (t in Themes) Pill(t.name, selected = currentTheme.name == t.name) {
                        currentTheme = t
                        app.config.theme = t.name
                        app.config.save()
                    }
                }
            }
        }

        item { SectionTitle("Plex") }
        item {
            Card {
                if (app.plexLinked) {
                    Text("Linked · ${app.plexServers.size} server(s)", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Pill("Unlink Plex") { app.unlinkPlex() }
                } else Pill("Link Plex", selected = true) { app.page = Page.Plex; app.linkPlex() }
            }
        }

        if (app.config.profiles.isNotEmpty()) {
            item { SectionTitle("Profiles") }
            item {
                Card {
                    Text("From your TV – changes here go back to it. Kids' profiles see only their channels.", color = Soft, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (p in app.config.profiles) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(p, 54, selected = p.id == app.profileId) { app.chooseProfile(p) }
                            Text(p.name, color = Color.White, fontSize = 12.sp)
                            if (!app.kids || app.grownUp) Text("Edit", color = Sky, fontSize = 11.sp, modifier = Modifier.clickable { editing = p }.padding(2.dp))
                        }
                    }
                }
            }
        }

        item { SectionTitle("Problem log") }
        item {
            Card {
                val lines = remember(logVersion) {
                    runCatching { com.titanejstudios.tuner.data.AppLog.file.readLines().filter { it.isNotBlank() && !it.startsWith("\t") }.takeLast(12) }.getOrDefault(emptyList())
                }
                Text("What went wrong lately (the full log is ${com.titanejstudios.tuner.data.AppLog.file.absolutePath}).", color = Soft, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                if (lines.isEmpty()) Text("Nothing yet.", color = Soft, fontSize = 12.sp)
                for (l in lines) Text(l, color = if ("WARN" in l) Gold else Soft, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("📋 Copy the log", selected = true) {
                        val text = "Flicker for Windows ${Keys.VERSION} · ${System.getProperty("os.name")}\n\n" +
                            runCatching { com.titanejstudios.tuner.data.AppLog.file.readText().takeLast(60_000) }.getOrDefault("")
                        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
                        app.say("Problem log copied – paste it wherever you need")
                    }
                    Pill("Open the log") { runCatching { java.awt.Desktop.getDesktop().open(com.titanejstudios.tuner.data.AppLog.file) } }
                    Pill("↻") { logVersion++ }
                }
            }
        }

        item { SectionTitle("Phone remote and Watch on…") }
        item {
            Card {
                Text(
                    if (app.config.linkKey != null) "This PC is one of your linked devices: the phone's remote and \"Watch on…\" can use it, " +
                        "and 📺 Watch on… (on Live TV and the player) sends what's on here to the TV. Windows may ask once to let Flicker use the network – say yes (private networks)."
                    else "Comes with the sync: once you've signed in above (and the TV has updated), the phone's remote can find this PC.",
                    color = Soft, fontSize = 13.sp,
                )
                if (app.config.linkKey != null) {
                    Spacer(Modifier.height(8.dp))
                    Pill(if (app.findingPeers) "Looking…" else "Find my TVs") { app.findPeers() }
                    if (app.peers.isNotEmpty()) Text("Found: " + app.peers.joinToString(", ") { it.name }, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item { SectionTitle("Updates") }
        item {
            Card {
                Text("Flicker for Windows ${Keys.VERSION}", color = Color.White, fontSize = 15.sp)
                Text("Checks GitHub for a new version once a day and offers to install it.", color = Soft, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Pill("Check now") { app.checkForUpdate(manual = true) }
            }
        }

        item { SectionTitle("Keys") }
        item {
            Card {
                Text(
                    "F11 or double-click: full screen · Esc: leave full screen / close a film · Space: pause · ← →: skip back 10s / forward 30s · " +
                        "Page Up/Down: change channel · Ctrl+M: mute",
                    color = Soft, fontSize = 13.sp,
                )
            }
        }
    }
    editing?.let { p -> ProfileEditor(app, p) { editing = null } }
    if (adding) AddPlaylistDialog(onDismiss = { adding = false }) { src ->
        app.config.sources = app.config.sources + src
        app.config.save()
        adding = false
        app.reload()
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(18.dp)) { content() }
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
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Ink).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                TextButton(onClick = { xtream = true }) { Text("Xtream login", color = if (xtream) Sky else Soft) }
                TextButton(onClick = { xtream = false }) { Text("M3U link", color = if (!xtream) Sky else Soft) }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (xtream) {
                OutlinedTextField(server, { server = it }, label = { Text("Server (http://provider.com:8080)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            } else {
                OutlinedTextField(url, { url = it }, label = { Text("Playlist URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(epg, { epg = it }, label = { Text("TV guide (XMLTV) URL – optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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


/** A profile's round picture: its colour, with its emoji or first letter (like the TV). */
@Composable
fun Avatar(p: PcProfile, size: Int, selected: Boolean = false, onClick: () -> Unit) {
    val colour = AvatarColours[(if (p.colour in AvatarColours.indices) p.colour else (p.name.hashCode() and 0x7fffffff) % AvatarColours.size)]
    Box(
        Modifier.width(size.dp).height(size.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colour)
            .then(if (selected) Modifier.border(3.dp, Color.White, androidx.compose.foundation.shape.CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.icon.ifBlank { p.name.take(1).uppercase() }, color = Color.White, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Edit a profile (name, picture, kids, daily time and bedtime) – it goes back to the TV. */
@Composable
private fun ProfileEditor(app: AppState, p: PcProfile, onClose: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var kids by remember { mutableStateOf(p.kids) }
    var limit by remember { mutableStateOf(p.limitMinutes) }
    var bedtime by remember { mutableStateOf(p.bedtime) }
    var colour by remember { mutableStateOf(p.colour) }
    var icon by remember { mutableStateOf(p.icon) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(PcProfile(p.id, name, kids, p.groups, limit, bedtime, colour, icon), 60) {}
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
            }
            Text("Colour", color = Soft, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarColours.forEachIndexed { i, c ->
                    Box(
                        Modifier.width(30.dp).height(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                            .then(if (colour == i) Modifier.border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape) else Modifier)
                            .clickable { colour = i },
                    )
                }
            }
            Text("Picture", color = Soft, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AvatarIcons.size) { i ->
                    val e = AvatarIcons[i]
                    Pill(e.ifBlank { "Aa" }, selected = icon == e) { icon = e }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (kids) "✓ Kids' profile" else "Kids' profile") { kids = !kids }
            }
            if (kids) {
                Text("TV a day", color = Soft, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(0, 30, 60, 90, 120, 180)) Pill(if (m == 0) "No limit" else "${m / 60}h${if (m % 60 > 0) " ${m % 60}m" else ""}".replace("0h ", ""), selected = limit == m) { limit = m }
                }
                Text("Bedtime", color = Soft, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in listOf(-1, 18 * 60 + 30, 19 * 60, 19 * 60 + 30, 20 * 60, 21 * 60)) {
                        Pill(if (b < 0) "None" else String.format(java.util.Locale.UK, "%d:%02d", b / 60, b % 60), selected = bedtime == b) { bedtime = b }
                    }
                }
            }
            Text("The channels kids can see are chosen on the TV.", color = Soft, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Save", selected = true) {
                    app.editProfile(p.id, name, kids, if (kids) limit else 0, if (kids) bedtime else -1, colour, icon)
                    onClose()
                }
                Pill("Cancel", onClick = onClose)
            }
        }
    }
}

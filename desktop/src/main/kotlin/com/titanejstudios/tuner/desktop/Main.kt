package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

/** Started by Windows at sign-in: stay in the tray. */
private var startInTray = false

fun main(args: Array<String>) {
    startInTray = "--tray" in args
    // Anything that goes wrong is written to %USERPROFILE%\.tuner\flicker.log, and shown.
    com.titanejstudios.tuner.data.AppLog.info(
        "Flicker starting · Java " + System.getProperty("java.version") + " · " + System.getProperty("os.name") +
            " · resources " + System.getProperty("compose.application.resources.dir"),
    )
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        com.titanejstudios.tuner.data.AppLog.warn("Crash", e)
        runCatching {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "Flicker hit a problem and has to close:\n\n" + (e.message ?: e.javaClass.simpleName) +
                    "\n\nThe details are in " + com.titanejstudios.tuner.data.AppLog.file.absolutePath,
                "Flicker", javax.swing.JOptionPane.ERROR_MESSAGE,
            )
        }
    }
    // Menus open as their own little windows, so they show over the video.
    System.setProperty("compose.layers.type", "WINDOW")
    try {
        runApp()
    } catch (e: Throwable) {
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), e)
    }
}

private fun runApp() = application {
    val config = remember { Config() }
    val player = remember { Player().also { it.deinterlace = config.deinterlace; it.warmUp() } }
    // The window as you left it (size, place, maximised).
    val windowState = rememberWindowState(
        size = DpSize(config.windowW.coerceAtLeast(800).dp, config.windowH.coerceAtLeast(500).dp),
        position = if (config.windowX >= 0 && config.windowY >= 0) androidx.compose.ui.window.WindowPosition(config.windowX.dp, config.windowY.dp)
        else androidx.compose.ui.window.WindowPosition.PlatformDefault,
        placement = if (config.windowMaximised) WindowPlacement.Maximized else WindowPlacement.Floating,
    )
    var shown by remember { mutableStateOf(!(startInTray && config.keepRunning)) }
    val scope = rememberCoroutineScope()
    val app = remember { AppState(config, player, scope) }
    val icon = remember {
        runCatching {
            androidx.compose.ui.graphics.painter.BitmapPainter(
                androidx.compose.ui.res.useResource("flicker.png") { androidx.compose.ui.res.loadImageBitmap(it) },
            )
        }.getOrNull()
    }
    fun fullScreen() = windowState.placement == WindowPlacement.Fullscreen
    fun toggleFullScreen() {
        windowState.placement = if (fullScreen()) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    fun rememberWindow() {
        if (windowState.placement == WindowPlacement.Floating) {
            config.windowW = windowState.size.width.value.toInt()
            config.windowH = windowState.size.height.value.toInt()
            (windowState.position as? androidx.compose.ui.window.WindowPosition.Absolute)?.let {
                config.windowX = it.x.value.toInt()
                config.windowY = it.y.value.toInt()
            }
        }
        if (windowState.placement != WindowPlacement.Fullscreen) config.windowMaximised = windowState.placement == WindowPlacement.Maximized
    }
    fun quit() {
        rememberWindow()
        app.stopWatching(save = true)
        runCatching { config.save() }
        runCatching { app.recorder.release() }
        runCatching { app.multi.release() }
        runCatching { app.link.stop() }
        player.release()
        exitApplication()
    }
    LaunchedEffect(Unit) {
        player.onFinished { app.finished() }
        player.onDoubleClick { toggleFullScreen() }
        if (config.sources.isNotEmpty()) app.reload()
        if (config.plexToken != null) app.loadPlex()
        if (config.googleRefresh != null) app.syncSoon(0)
        if (config.activeProfile == null && config.profiles.size > 1) app.askWhoIsWatching = true
        app.startLink()
        app.checkForUpdate()
    }
    // The clock, reminders, recordings, saving your place, and syncing every few minutes.
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            delay(10_000)
            ticks++
            app.now = System.currentTimeMillis()
            app.checkReminders()
            app.checkRecordings()
            app.tickKids()
            if (app.watching != null) app.saveProgress()
            if (ticks % 18 == 0) app.syncSoon(0)
            if (ticks % 1080 == 0) app.checkForUpdate()
        }
    }
    // Flicker in the system tray: closing the window leaves it running (recordings and reminders carry on).
    if (config.keepRunning && icon != null) {
        Tray(
            icon = icon,
            tooltip = "Flicker",
            onAction = { shown = true },
            menu = {
                Item("Open Flicker", onClick = { shown = true })
                Item("Quit", onClick = ::quit)
            },
        )
    }
    Window(
        onCloseRequest = {
            if (config.keepRunning && icon != null) {
                app.endTimeshift()
                rememberWindow()
                config.save()
                app.stopWatching(save = true)
                app.player.stop()
                app.playingChannel = null
                app.mini = false
                shown = false
            } else quit()
        },
        visible = shown,
        state = windowState,
        title = "Flicker",
        icon = icon,
        onKeyEvent = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            val watching = app.watching != null
            when {
                e.key == Key.F11 || (e.key == Key.F && e.isCtrlPressed) -> { toggleFullScreen(); true }
                e.key == Key.Escape && fullScreen() -> { windowState.placement = WindowPlacement.Floating; true }
                e.key == Key.Escape && watching -> { app.stopWatching(save = true); true }
                e.key == Key.Spacebar && watching && app.player.component != null -> { app.player.togglePause(); true }
                // Live TV: Space pauses (and keeps recording), then carries on from there.
                e.key == Key.Spacebar && app.page == Page.Live && app.player.component != null -> { app.togglePauseLive(); true }
                // Typing a channel number (not while typing in a search box – that keeps the keys).
                !watching && digitOf(e.key) != null && app.page in setOf(Page.Live, Page.Home, Page.Guide) -> { app.typeDigit(digitOf(e.key)!!); true }
                watching && e.key == Key.DirectionLeft -> { app.player.skip(-10_000L); true }
                watching && e.key == Key.DirectionRight -> { app.player.skip(30_000L); true }
                !watching && (e.key == Key.PageUp || (fullScreen() && e.key == Key.DirectionUp)) -> { app.zapBy(-1); true }
                !watching && (e.key == Key.PageDown || (fullScreen() && e.key == Key.DirectionDown)) -> { app.zapBy(1); true }
                e.key == Key.M && e.isCtrlPressed -> { app.player.toggleMute(); true }
                else -> false
            }
        },
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Sky, background = Ink, surface = Panel)) {
            Shell(app, fullScreen(), ::toggleFullScreen)
        }
    }
    // The mini player: a small window that stays on top of everything.
    if (app.mini) {
        val miniState = rememberWindowState(
            size = DpSize(480.dp, 300.dp),
            position = androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.BottomEnd),
        )
        Window(
            onCloseRequest = { app.toggleMini() },
            state = miniState,
            title = "Flicker – mini player",
            icon = icon,
            alwaysOnTop = true,
        ) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Sky, background = Ink, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoBox(app, Modifier.fillMaxWidth().weight(1f), inMini = true)
                    Row(Modifier.fillMaxWidth().background(InkBottom).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("⏯") { app.player.togglePause() }
                        if (app.watching == null) {
                            Pill("▲") { app.zapBy(-1) }
                            Pill("▼") { app.zapBy(1) }
                        }
                        Spacer(Modifier.weight(1f))
                        Pill("⤢ Back") { app.toggleMini() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shell(app: AppState, fullScreen: Boolean, toggleFullScreen: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, InkBottom)))) {
        Row(Modifier.fillMaxSize()) {
            if (!fullScreen) NavRail(app)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    app.watching != null -> PlayerPage(app, fullScreen, toggleFullScreen)
                    else -> when (app.page) {
                        Page.Home -> HomePage(app)
                        Page.Live -> LivePage(app, fullScreen, toggleFullScreen)
                        Page.Guide -> GuidePage(app)
                        Page.Multi -> MultiPage(app)
                        Page.WhatsOn -> WhatsOnPage(app)
                        Page.Search -> SearchPage(app)
                        Page.Recordings -> RecordingsPage(app)
                        Page.Films -> FilmsPage(app)
                        Page.Series -> SeriesPage(app)
                        Page.Plex -> PlexPage(app)
                        Page.YouTube -> YouTubePage(app)
                        Page.Settings -> SettingsPage(app)
                    }
                }
            }
        }
        // Little messages ("Reminder set"), bottom middle.
        app.notice?.let { msg ->
            Text(
                msg, color = Color.White, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).clip(RoundedCornerShape(12.dp))
                    .background(PanelLight).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        app.dueReminder?.let { r -> ReminderDialog(app, r) }
        app.update?.let { u -> UpdateBanner(app, u, Modifier.align(Alignment.TopEnd)) }
        // Typing a channel number.
        if (app.typedNumber.isNotEmpty()) Text(
            app.typedNumber, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.align(Alignment.TopEnd).padding(28.dp).clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.85f)).padding(horizontal = 22.dp, vertical = 8.dp),
        )
        app.timesUp?.let { why -> TimesUp(app, why) }
        app.pinPrompt?.let { (why, _) -> PinPrompt(app, why) }
        if (app.askWhoIsWatching && app.config.profiles.size > 1) WhoIsWatching(app)
    }
}

@Composable
private fun NavRail(app: AppState) {
    Column(
        Modifier.width(190.dp).fillMaxHeight().background(InkBottom.copy(alpha = 0.6f)).padding(vertical = 18.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Flicker", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 10.dp, bottom = 14.dp))
        val kids = app.kids
        for (p in Page.entries) {
            if (kids && (p == Page.Films || p == Page.Series || p == Page.YouTube || p == Page.Recordings || p == Page.Multi)) continue
            val sel = app.page == p && app.watching == null
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Sky.copy(alpha = 0.35f) else Color.Transparent)
                    .clickable {
                        if (app.watching != null) app.stopWatching(save = true)
                        if (app.page == Page.Multi && p != Page.Multi) app.leaveMulti()
                        if (p == Page.Multi && app.page != Page.Multi) app.enterMulti()
                        app.page = p
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.icon, color = if (sel) Color.White else Soft, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                Text(p.label, color = if (sel) Color.White else Soft, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        app.profile?.let { p ->
            Text(
                "👤 " + p.name + if (app.config.profiles.size > 1) "  ▾" else "", color = Soft, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { app.askWhoIsWatching = true }.padding(12.dp),
            )
        }
        app.status?.let { Text("⟳ $it", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
        app.syncStatus?.let { Text(it, color = Soft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
    }
}

@Composable
private fun ReminderDialog(app: AppState, r: Reminder) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⏰ Starting now", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(r.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${time(r.start)} on ${r.channelName}", color = Soft, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("▶ Watch", selected = true) {
                    app.dueReminder = null
                    app.channels.firstOrNull { it.id == r.channelId }?.let { app.playChannel(it) }
                }
                Pill("Not now") { app.dueReminder = null }
            }
        }
    }
}

@Composable
private fun WhoIsWatching(app: AppState) {
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Who's watching?", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (p in app.config.profiles) {
                    Column(
                        Modifier.width(130.dp).clip(RoundedCornerShape(14.dp)).background(if (p.id == app.profileId) Sky.copy(alpha = 0.4f) else Panel)
                            .clickable { app.chooseProfile(p) }.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(p.name.take(1).uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text(p.name, color = Color.White, fontSize = 15.sp)
                        if (p.kids) Text("Kids", color = Gold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


@Composable
private fun UpdateBanner(app: AppState, u: Updater.Release, modifier: Modifier) {
    Column(modifier.padding(16.dp).width(340.dp).clip(RoundedCornerShape(14.dp)).background(PanelLight).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Flicker ${u.version} is ready", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("You've got ${Keys.VERSION}. It installs over this one and keeps your settings.", color = Soft, fontSize = 13.sp)
        val p = app.updateProgress
        if (p != null) Text("Downloading… ${(p * 100).toInt()}%", color = Gold, fontSize = 13.sp)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Install now", selected = true) { app.installUpdate() }
            Pill("Later") { app.update = null }
        }
    }
}


private fun digitOf(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

/** Kids: bedtime or the day's TV time is used up. A grown-up's PIN gives 30 more minutes. */
@Composable
private fun TimesUp(app: AppState, why: String) {
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.97f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("🌙", fontSize = 64.sp)
            Text(why, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("See you tomorrow!", color = Soft, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (app.config.pin != null) Pill("Grown-up: enter the PIN") { app.extendWithPin() }
                Pill("OK") { app.timesUp = null }
            }
        }
    }
}

@Composable
private fun PinPrompt(app: AppState, why: String) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(380.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🔒 PIN", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(why, color = Color.White, fontSize = 17.sp)
            androidx.compose.material3.OutlinedTextField(
                pin, { pin = it.filter { c -> c.isDigit() }.take(8); wrong = false }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                placeholder = { Text("PIN") }, modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) Text("That's not the PIN", color = LiveRed, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("OK", selected = true) { if (!app.checkPin(pin)) wrong = true }
                Pill("Cancel") { app.pinPrompt = null }
            }
        }
    }
}

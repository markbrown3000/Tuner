package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.pointer.onPointerEvent
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
import androidx.compose.ui.input.key.isShiftPressed
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
    // Finding the TV on the Wi-Fi (multicast) fails on Windows when Java tries IPv6 first.
    System.setProperty("java.net.preferIPv4Stack", "true")
    // Anything that goes wrong is written to %USERPROFILE%\.tuner\flicker.log, and shown.
    com.titanejstudios.tuner.data.AppLog.info(
        "Flicker starting · Java " + System.getProperty("java.version") + " · " + System.getProperty("os.name") +
            " · resources " + System.getProperty("compose.application.resources.dir"),
    )
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        com.titanejstudios.tuner.data.AppLog.warn("Crash", e)
        // Shown next time Flicker opens, with a button to copy the log.
        runCatching {
            java.io.File(com.titanejstudios.tuner.data.AppLog.file.parentFile, "crashed.txt")
                .writeText((e.javaClass.simpleName + ": " + (e.message ?: "")).take(300))
        }
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
    val player = remember {
        Player().also {
            it.deinterlace = config.deinterlace
            it.normalise = config.normalise
            it.nightMode = config.nightMode
            it.clearSpeech = config.clearSpeech
            it.subtitleScale = config.subtitleScale
            it.audioDelayMs = config.lipSyncMs
            it.warmUp()
        }
    }
    remember {
        currentTheme = Themes.firstOrNull { it.name == config.theme } ?: Themes[0]
        uiScale = config.textScale
    }
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
    // Full screen: a borderless window over the whole monitor the window's on (taskbar too).
    val mainWindowRef = remember { arrayOfNulls<java.awt.Window>(1) }
    fun fullScreen() = app.secondScreen != null && app.fullHere
    fun toggleFullScreen() {
        if (app.secondScreen != null) {
            app.leaveFull()
            return
        }
        val r = mainWindowRef[0]?.graphicsConfiguration?.bounds
            ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        app.goFull(r)
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
        delay(20_000)
        app.refreshComingSoon()
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
            app.tickSleep()
            app.tidyRecordings()
            app.learnTaste()
            app.tickSaver()
            if (app.watching != null) app.saveProgress()
            if (ticks % 18 == 0) app.syncSoon(0)
            if (ticks % 1080 == 0) app.checkForUpdate()
            if (ticks % 360 == 0) app.refreshComingSoon()
        }
    }
    // Flicker in the system tray: closing the window leaves it running (recordings and reminders carry on).
    val trayState = androidx.compose.ui.window.rememberTrayState()
    LaunchedEffect(Unit) {
        app.notifier = { title, message -> trayState.sendNotification(androidx.compose.ui.window.Notification(title, message)) }
    }
    if (config.keepRunning && icon != null) {
        Tray(
            state = trayState,
            icon = icon,
            tooltip = "Flicker",
            onAction = { shown = true },
            menu = {
                Item("Open Flicker", onClick = { shown = true })
                Item("Quit", onClick = ::quit)
            },
        )
    }
    // The keys work the same in the main window and the full-screen one.
    val onKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ e ->
        app.activity()
        if (e.type != KeyEventType.KeyDown) return@handler false
        val watching = app.watching != null && !app.mini
        when {
            e.key == Key.F11 || (e.key == Key.F && e.isCtrlPressed) -> { toggleFullScreen(); true }
            e.key == Key.F1 || (e.key == Key.Slash && e.isShiftPressed && app.page != Page.Search && app.page != Page.Settings) -> { app.showKeys = !app.showKeys; true }
            e.key == Key.Escape && app.showKeys -> { app.showKeys = false; true }
            e.key == Key.Escape && app.secondScreen != null -> { app.leaveFull(); true }
            e.key == Key.Escape && watching -> { app.stopWatching(save = true); true }
            e.key == Key.Spacebar && watching && app.player.ready -> { app.player.togglePause(); true }
            // Live TV: Space pauses (and keeps recording), then carries on from there.
            e.key == Key.Spacebar && app.page == Page.Live && app.player.ready -> { app.togglePauseLive(); true }
            // Typing a channel number (not while typing in a search box – that keeps the keys).
            !watching && digitOf(e.key) != null && app.page in setOf(Page.Live, Page.Home, Page.Guide) -> { app.typeDigit(digitOf(e.key)!!); true }
            watching && e.key == Key.DirectionLeft -> { app.player.skip(-10_000L); true }
            watching && e.key == Key.DirectionRight -> { app.player.skip(30_000L); true }
            !watching && (e.key == Key.PageUp || (fullScreen() && e.key == Key.DirectionUp)) -> { app.zapBy(-1); true }
            !watching && (e.key == Key.PageDown || (fullScreen() && e.key == Key.DirectionDown)) -> { app.zapBy(1); true }
            e.key == Key.M && e.isCtrlPressed -> { app.player.toggleMute(); true }
            // The keyboard's media and volume keys.
            e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause ->
                { if (watching) app.player.togglePause() else app.togglePauseLive(); true }
            e.key == Key.MediaNext -> { if (!watching) app.zapBy(1) else app.player.skip(30_000L); true }
            e.key == Key.MediaPrevious -> { if (!watching) app.zapBy(-1) else app.player.skip(-10_000L); true }
            e.key == Key.MediaStop -> { app.stopWatching(save = true); true }
            e.key == Key.VolumeUp || (e.key == Key.DirectionUp && e.isCtrlPressed) -> { app.setVolume(app.config.volume + 10); true }
            e.key == Key.VolumeDown || (e.key == Key.DirectionDown && e.isCtrlPressed) -> { app.setVolume(app.config.volume - 10); true }
            e.key == Key.VolumeMute -> { app.player.toggleMute(); true }
            else -> false
        }
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
        onKeyEvent = onKey,
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Sky, background = Ink, surface = Panel)) {
            // Text size (Settings › This PC): everything scales together.
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * uiScale, d.fontScale),
            ) {
                LaunchedEffect(Unit) { mainWindowRef[0] = window }
                Shell(app, false, ::toggleFullScreen)
            }
        }
    }
    // Full screen on the other monitor.
    app.secondScreen?.let { r ->
        val ss = rememberWindowState(
            placement = WindowPlacement.Floating,
            position = androidx.compose.ui.window.WindowPosition(r.x.dp, r.y.dp),
            size = DpSize(r.width.dp, r.height.dp),
        )
        Window(
            onCloseRequest = { app.toggleSecondScreen() },
            state = ss,
            title = "Flicker",
            icon = icon,
            undecorated = true,
            alwaysOnTop = app.fullHere,
            onKeyEvent = onKey,
        ) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Sky, background = Ink, surface = Panel)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoBox(app, Modifier.fillMaxSize(), inMini = true)
                    // A reminder of how to get out, for a few seconds.
                    var hint by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) { delay(3_500); hint = false }
                    if (hint) Text(
                        "Esc or double-click to leave full screen", color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
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
            alwaysOnTop = app.miniOnTop,
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
                        Pill(if (app.miniOnTop) "📌 On top" else "📌", selected = app.miniOnTop) { app.toggleMiniOnTop() }
                        Pill("⤢ Back") { app.toggleMini() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shell(app: AppState, fullScreen: Boolean, toggleFullScreen: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, InkBottom))).dropFiles(app).noticeActivity(app)) {
        Row(Modifier.fillMaxSize()) {
            if (!fullScreen) NavRail(app)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    app.watching != null && !app.mini -> PlayerPage(app, fullScreen, toggleFullScreen)
                    else -> when (app.page) {
                        Page.Home -> HomePage(app)
                        Page.Live -> LivePage(app, fullScreen, toggleFullScreen)
                        Page.Guide -> GuidePage(app)
                        Page.Catchup -> CatchupPage(app)
                        Page.Multi -> MultiPage(app)
                        Page.WhatsOn -> WhatsOnPage(app)
                        Page.Sport -> SportPage(app)
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
        app.timeRequest?.let { r -> TimeRequestDialog(app, r) }
        app.previously?.let { (title, plot) ->
            if (app.watching != null && !app.mini) Column(
                Modifier.align(Alignment.TopStart).padding(start = if (fullScreen) 30.dp else 220.dp, top = 30.dp).width(560.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.92f)).clickable { app.previously = null }.padding(18.dp),
            ) {
                Text(title, color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(plot, color = Color.White, fontSize = 15.sp, maxLines = 6)
            }
        }
        if (app.showKeys) KeysHelp(app)
        app.lastCrash?.let { why ->
            Row(
                Modifier.align(Alignment.TopCenter).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("⚠ Flicker closed unexpectedly last time ($why)", color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.width(560.dp))
                Pill("📋 Copy the problem log", selected = true) { app.copyLog() }
                Pill("OK") { app.lastCrash = null }
            }
        }
        if (app.settingPin) PinSetter(app)
        app.collectingItem?.let { item -> CollectionPicker(app, item) }
        if (app.showStreamInfo && (app.watching != null || app.playingChannel != null)) StreamInfo(app, Modifier.align(Alignment.TopEnd))
        app.subtitleHits?.let { hits -> SubtitlePicker(app, hits) }
        app.nextInSeries?.let { m ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(28.dp).width(380.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("NEXT IN THE SERIES", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(m.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("▶ Play it", selected = true) { app.nextInSeries = null; app.playFilm(m, fromStart = true) }
                    Pill("Not now") { app.nextInSeries = null }
                }
            }
        }
        app.actor?.let { (name, films) -> ActorFilms(app, name, films) }
        if (app.saverOn) Screensaver(app)
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
            val sel = app.page == p && (app.watching == null || app.mini)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Sky.copy(alpha = 0.35f) else Color.Transparent)
                    .clickable {
                        // In the mini player it keeps going while you look around.
                        if (app.watching != null && !app.mini) app.stopWatching(save = true)
                        app.endPreview()
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
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { app.askWhoIsWatching = true }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p, 30) { app.askWhoIsWatching = true }
                Spacer(Modifier.width(10.dp))
                Text(p.name + if (app.config.profiles.size > 1) "  ▾" else "", color = Soft, fontSize = 14.sp)
            }
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
                        Avatar(p, 72) { app.chooseProfile(p) }
                        Spacer(Modifier.height(8.dp))
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
                if (app.link.key != null) Pill("📱 Ask a grown-up") { app.askForMoreTime() }
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


/** Drop a video file, an .m3u playlist or a link on the window. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.dropFiles(app: AppState): Modifier {
    val target = remember {
        object : androidx.compose.ui.draganddrop.DragAndDropTarget {
            override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                val t = event.awtTransferable
                val files = runCatching {
                    if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor))
                        (t.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<java.io.File>()
                    else emptyList()
                }.getOrDefault(emptyList())
                val text = runCatching {
                    if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String else null
                }.getOrNull()
                if (files.isEmpty() && text == null) return false
                app.dropped(files, text)
                return true
            }
        }
    }
    return this.then(Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target))
}

/** A child's asking for more time (from a TV or another PC). */
@Composable
private fun TimeRequestDialog(app: AppState, r: AppState.TimeRequest) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("📱 ${r.kidName} is asking for more TV", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("From ${r.from}" + if (app.config.pin != null) " · you'll need the PIN" else "", color = Soft, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("+15 min", selected = true) { app.answerTimeRequest(15) }
                Pill("+30 min") { app.answerTimeRequest(30) }
                Pill("+1 hour") { app.answerTimeRequest(60) }
                Pill("Not now") { app.answerTimeRequest(0) }
            }
        }
    }
}

/** F1 or ?: every keyboard shortcut. */
@Composable
private fun KeysHelp(app: AppState) {
    val keys = listOf(
        "Space" to "Pause / play (on live TV it pauses and keeps recording)",
        "← →" to "Back 10 seconds / forward 30 seconds",
        "Page Up / Page Down" to "Previous / next channel (also ↑ ↓ in full screen)",
        "0–9" to "Type a channel's number (Live TV, Home, the guide)",
        "F11 or Ctrl+F" to "Full screen (or double-click the picture)",
        "Esc" to "Leave full screen, then close the player",
        "Ctrl+M" to "Mute",
        "Ctrl+↑ / Ctrl+↓" to "Volume up / down",
        "Media keys" to "Play/pause, next, previous, stop, volume",
        "Right-click" to "Menus on channels, films and Continue watching",
        "F1 or ?" to "This page",
        "Drag and drop" to "A video file plays; an .m3u file or playlist link is added",
    )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { app.showKeys = false }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⌨ Keyboard shortcuts", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            for ((k, what) in keys) Row {
                Text(k, color = Gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(190.dp))
                Text(what, color = Color.White, fontSize = 15.sp)
            }
            Text("Click anywhere or press Esc to close", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}


/** ℹ Stream info: picture size, codecs and sound, updated every couple of seconds. */
@Composable
private fun StreamInfo(app: AppState, modifier: Modifier) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = app.player.streamInfo()
            delay(2_000)
        }
    }
    Column(
        modifier.padding(24.dp).width(420.dp).clip(RoundedCornerShape(12.dp)).background(Ink.copy(alpha = 0.9f))
            .clickable { app.showStreamInfo = false }.padding(14.dp),
    ) {
        Text("ℹ Stream info", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (lines.isEmpty()) Text("Waiting for the stream…", color = Soft, fontSize = 13.sp)
        for (l in lines) Text(l, color = Color.White, fontSize = 13.sp, maxLines = 2)
    }
}

/** Subtitles found online: pick one. */
@Composable
private fun SubtitlePicker(app: AppState, hits: List<com.titanejstudios.tuner.integrations.OpenSubtitles.Hit>) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { app.subtitleHits = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Subtitles found", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            for (h in hits) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelLight).clickable { app.useSubtitle(h) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(h.release, color = Color.White, fontSize = 14.sp, maxLines = 1)
                Text("${h.language} · ${h.downloads} downloads", color = Soft, fontSize = 11.sp)
            }
            Pill("Cancel") { app.subtitleHits = null }
        }
    }
}


/** What else an actor's in (the films you have can be played). */
@Composable
private fun ActorFilms(app: AppState, name: String, films: List<Pair<com.titanejstudios.tuner.integrations.Tmdb.Part, com.titanejstudios.tuner.data.VodItem?>>) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { app.actor = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.8f).clip(RoundedCornerShape(16.dp)).background(Panel).clickable(enabled = false) {}.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name's films", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("Close") { app.actor = null }
            }
            Text("The ones you have are first – click to play.", color = Soft, fontSize = 13.sp)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(films.size) { i ->
                    val (p, mine) = films[i]
                    PosterTile(p.title, p.poster, if (mine != null) "▶ yours" else p.year, width = 130.dp) {
                        if (mine != null) {
                            app.actor = null
                            app.playFilm(mine)
                        } else app.say("${p.title} isn't in your films")
                    }
                }
            }
        }
    }
}


/** Any mouse movement counts as being there (for the screensaver). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.noticeActivity(app: AppState): Modifier = this
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { app.activity() }
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) { app.activity() }

/** The artwork screensaver: big pictures of what's on and your films, with the time. Any movement ends it. */
@Composable
private fun Screensaver(app: AppState) {
    val pics = remember { app.saverPictures() }
    var i by remember { mutableStateOf(0) }
    var clock by remember { mutableStateOf(time(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(9_000)
            i++
            clock = time(System.currentTimeMillis())
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { app.activity() }) {
        val pic = pics.getOrNull(if (pics.isEmpty()) 0 else i % pics.size)
        if (pic != null) NetImage(pic.first, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(40.dp)) {
            Text(clock, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
            pic?.let { Text(it.second, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp) }
        }
    }
}


/** Set or change the parental PIN (it goes to the TV too). */
@Composable
private fun PinSetter(app: AppState) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(400.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🔒 The parental PIN", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Used for kids' limits and locked groups – here and on the TV.", color = Soft, fontSize = 13.sp)
            androidx.compose.material3.OutlinedTextField(
                first, { first = it.filter { c -> c.isDigit() }.take(8); problem = null }, singleLine = true, label = { Text("New PIN (4–8 numbers)") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.OutlinedTextField(
                second, { second = it.filter { c -> c.isDigit() }.take(8); problem = null }, singleLine = true, label = { Text("Again") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(it, color = LiveRed, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Save", selected = true) {
                    problem = when {
                        first.length < 4 -> "At least 4 numbers"
                        first != second -> "They don't match"
                        else -> null
                    }
                    if (problem == null) app.savePin(first)
                }
                if (app.config.pin != null) Pill("Remove the PIN") { app.savePin(null) }
                Pill("Cancel") { app.settingPin = false }
            }
        }
    }
}

/** 📚 Add to a collection: pick one, or name a new one. */
@Composable
private fun CollectionPicker(app: AppState, item: com.titanejstudios.tuner.data.WatchItem) {
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { app.collectingItem = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).clip(RoundedCornerShape(16.dp)).background(Panel).clickable(enabled = false) {}.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add ${item.name} to…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            for ((n, list) in app.collections) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelLight).clickable { app.addToCollection(n, item); app.collectingItem = null }.padding(10.dp),
            ) {
                Text("📚 $n", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${list.size}", color = Soft, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("A new collection") }, modifier = Modifier.weight(1f))
                Pill("Add", selected = true) { if (name.isNotBlank()) { app.addToCollection(name, item); app.collectingItem = null } }
            }
            Pill("Cancel") { app.collectingItem = null }
        }
    }
}

package com.titanejstudios.tuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.titanejstudios.tuner.FAVOURITES
import com.titanejstudios.tuner.PlayMode
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKey

private enum class Overlay { None, Channels, Options, Audio, Subtitles, MiniGuide, Favourites, Sleep, LipSync, Versions }

@Composable
fun PlayerScreen(vm: TunerViewModel) {
    val mode = vm.mode
    if (mode == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val playback = vm.playback
    val isLive = mode is PlayMode.Live
    val channel = vm.playing
    val context = LocalContext.current

    var overlay by remember { mutableStateOf(Overlay.None) }
    var showStats by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<List<String>>(emptyList()) }
    var showInfo by remember { mutableStateOf(true) }
    var infoToken by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var behind by remember { mutableLongStateOf(0L) }
    var okDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }
    // ▼ changes channel when let go; holding it opens the favourites strip.
    var downHeld by remember { mutableStateOf(false) }
    var downLong by remember { mutableStateOf(false) }

    fun pokeInfo() {
        showInfo = true
        infoToken++
    }

    val modeKey = when (mode) {
        is PlayMode.Live -> "live:" + mode.channel.id
        is PlayMode.CatchupPlay -> "cu:" + mode.channel.id + mode.programme.start
        is PlayMode.Vod -> "vod:" + mode.contentId
        is PlayMode.RecordingPlay -> "rec:" + mode.recording.id
    }
    LaunchedEffect(modeKey) { pokeInfo() }
    // Flicking through channels quickly: show each name as you go.
    LaunchedEffect(vm.zapPreview) { if (vm.zapPreview != null) pokeInfo() }
    LaunchedEffect(infoToken) {
        delay(5_000)
        if (isLive || !playback.isPaused) showInfo = false
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1_500)
            vm.zapToNumber(digits.toInt())
            digits = ""
        }
    }
    LaunchedEffect(modeKey) {
        while (true) {
            position = playback.positionMs
            duration = playback.durationMs
            behind = playback.behindLiveMs()
            delay(500)
        }
    }
    // Up next: the last 20 seconds of an episode with another one after it.
    var upNextCancelled by remember(modeKey) { mutableStateOf(false) }
    val nextEp = vm.nextEpisode
    val remaining = if (duration > 0) duration - position else Long.MAX_VALUE
    val upNextVisible = mode is PlayMode.Vod && nextEp != null && !upNextCancelled && remaining in 1L..20_000L
    // Skip intro: learned the first time you skip it in a series.
    val introEnd = vm.introEndFor()
    val skipIntroVisible = mode is PlayMode.Vod && introEnd != null && position in 3_000L until (introEnd - 5_000L) && position < 300_000L
    // A forward skip near the start of an episode that you then stay at = where the intro ends.
    var introCandidate by remember(modeKey) { mutableStateOf<Long?>(null) }
    LaunchedEffect(introCandidate) {
        val c = introCandidate ?: return@LaunchedEffect
        delay(8_000)
        if (introCandidate == c && playback.positionMs >= c - 2_000L) vm.learnIntro(c)
    }
    BackHandler(enabled = upNextVisible) {
        upNextCancelled = true
        vm.cancelAutoNext()
    }

    LaunchedEffect(showStats) {
        while (showStats) {
            stats = playback.streamInfo()
            delay(1_000)
        }
    }

    // Keep focus on the player whenever no overlay or dialog is open.
    val rootFocus = remember { FocusRequester() }
    val dialogsOpen = vm.pinPrompt != null || vm.dueReminder != null
    LaunchedEffect(overlay, dialogsOpen) {
        if (overlay == Overlay.None && !dialogsOpen) {
            delay(40)
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }
    BackHandler(enabled = overlay == Overlay.None && digits.isNotEmpty()) { digits = "" }

    fun seek(deltaMs: Long, firstPress: Boolean) {
        val before = playback.positionMs
        if (playback.seekBy(deltaMs)) {
            pokeInfo()
            if (mode is PlayMode.Vod && mode.seriesId != null) {
                introCandidate = if (deltaMs > 0 && before < 180_000L) {
                    (introCandidate ?: before) + deltaMs
                } else null
            }
        } else if (firstPress) vm.showNotice("Rewind isn't available on this stream")
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (overlay != Overlay.None) {
            // BACK always closes whatever menu is open over the video.
            if (e.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_BACK) {
                if (e.type == KeyEventType.KeyUp) overlay = Overlay.None
                return true
            }
            return false
        }
        val code = e.nativeKeyEvent.keyCode
        val down = e.type == KeyEventType.KeyDown
        val first = down && e.nativeKeyEvent.repeatCount == 0
        val paused = playback.isPaused
        if (code in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9) {
            if (first && isLive && digits.length < 4) digits += (code - AndroidKey.KEYCODE_0).toString()
            return true
        }
        when (code) {
            AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    val r = e.nativeKeyEvent.repeatCount
                    if (r == 0) {
                        okDown = true
                        okLong = false
                    } else if (r == 1 && okDown && !okLong) {
                        okLong = true
                        overlay = Overlay.Options
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    if (okDown && !okLong && upNextVisible) {
                        okDown = false
                        vm.playNextEpisodeNow()
                        return true
                    }
                    if (okDown && !okLong) {
                        if (isLive && paused) {
                            playback.resume()
                        } else if (isLive) {
                            // First OK shows the info bar, a second OK opens the mini guide.
                            if (showInfo) overlay = Overlay.MiniGuide else pokeInfo()
                        } else {
                            playback.togglePause()
                            pokeInfo()
                        }
                    }
                    okDown = false
                }
            }
            AndroidKey.KEYCODE_MENU -> if (first) overlay = Overlay.Options
            AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP -> if (first) {
                if (isLive) vm.zap(-1) else pokeInfo()
            }
            AndroidKey.KEYCODE_CHANNEL_DOWN -> if (first) {
                if (isLive) vm.zap(1) else pokeInfo()
            }
            AndroidKey.KEYCODE_DPAD_DOWN -> {
                if (!isLive) {
                    if (first) pokeInfo()
                } else if (down) {
                    val r = e.nativeKeyEvent.repeatCount
                    if (r == 0) {
                        downHeld = true
                        downLong = false
                    } else if (r == 1 && downHeld && !downLong) {
                        downLong = true
                        if (vm.favourites.isNotEmpty()) overlay = Overlay.Favourites
                        else vm.showNotice("Add favourites to get a quick strip here")
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    if (downHeld && !downLong) vm.zap(1)
                    downHeld = false
                }
            }
            AndroidKey.KEYCODE_DPAD_LEFT -> if (down) {
                if (isLive && !paused && !playback.timeshiftActive) {
                    if (first) overlay = Overlay.Channels
                } else seek(-10_000, first)
            }
            AndroidKey.KEYCODE_DPAD_RIGHT -> if (down) {
                if (skipIntroVisible && introEnd != null) {
                    if (first) {
                        playback.seekTo(introEnd)
                        introCandidate = null
                        pokeInfo()
                    }
                } else if (isLive && !paused && !playback.timeshiftActive) {
                    if (first) {
                        if (vm.previousChannel != null) vm.playPrevious() else pokeInfo()
                    }
                } else seek(30_000, first)
            }
            AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKey.KEYCODE_MEDIA_PLAY, AndroidKey.KEYCODE_MEDIA_PAUSE -> if (first) {
                playback.togglePause()
                pokeInfo()
            }
            AndroidKey.KEYCODE_MEDIA_REWIND -> if (down) seek(-30_000, first)
            AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seek(30_000, first)
            AndroidKey.KEYCODE_LAST_CHANNEL -> if (first) vm.playPrevious()
            AndroidKey.KEYCODE_GUIDE -> if (first) vm.openGuide()
            AndroidKey.KEYCODE_INFO -> {
                if (first) pokeInfo()
            }
            else -> return false
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { handleKey(it) }
            .focusable()
    ) {
        VideoSurface(playback.player, Modifier.fillMaxSize(), resizeMode = vm.pictureMode, keepLastFrame = isLive)

        if (!vm.inPip) {
            // Top-right: number entry and reconnect status
            Column(Modifier.align(Alignment.TopEnd).padding(32.dp), horizontalAlignment = Alignment.End) {
                if (digits.isNotEmpty()) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000))
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text(digits, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (playback.reconnecting) {
                    Spacer(Modifier.height(8.dp))
                    Chip("⟳  Reconnecting…")
                }
            }

            if (isLive && (playback.isPaused || playback.timeshiftActive)) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) {
                    val behindText = if (behind > 1_000) "  ·  ${clock(behind)} behind live" else ""
                    Chip(
                        when {
                            playback.isPaused && playback.timeshiftActive -> "⏸  Paused – still recording$behindText  ·  OK to resume"
                            playback.isPaused -> "⏸  Paused  ·  OK to resume" + (if (playback.canSeek) "  ·  ◀ ▶ rewind / forward" else "")
                            else -> "⏱  Timeshift$behindText  ·  ◀ ▶ skip  ·  hold OK › Jump to live"
                        }
                    )
                }
            }

            if (skipIntroVisible) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp)) {
                    Chip("⏭  Skip intro – press ▶")
                }
            }
            if (upNextVisible && nextEp != null) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xE60A1030)).padding(horizontal = 22.dp, vertical = 16.dp)
                ) {
                    Text("Up next in ${(remaining / 1000).coerceAtLeast(1)} s", color = TunerColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("S${nextEp.season} E${nextEp.number}  ${nextEp.title}", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("OK play now  ·  BACK cancel", color = TunerColors.TextSecondary, fontSize = 14.sp)
                }
            }

            if (showStats && stats.isNotEmpty()) {
                Column(
                    Modifier.align(Alignment.TopStart).padding(32.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000))
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    stats.forEach { Text(it, color = Color.White, fontSize = 15.sp) }
                }
            }

            playback.error?.let {
                Column(
                    Modifier.align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000))
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(it, color = Color.White, fontSize = 20.sp)
                    if (isLive) Text("▲ ▼ to try another channel", color = TunerColors.TextSecondary, fontSize = 15.sp)
                }
            }

            if (showInfo && overlay == Overlay.None) {
                when (mode) {
                    is PlayMode.Live -> InfoBanner(vm, vm.zapPreview ?: mode.channel, Modifier.align(Alignment.BottomCenter))
                    is PlayMode.CatchupPlay -> SeekControls(
                        title = mode.programme.title,
                        subtitle = "↺ Catch-up  ·  ${mode.channel.name}  ·  ${dayLabel(mode.programme.start)} ${hhmm(mode.programme.start)}",
                        position = position,
                        duration = duration,
                        paused = playback.isPaused,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    is PlayMode.Vod -> SeekControls(
                        title = mode.title,
                        subtitle = mode.subtitle,
                        position = position,
                        duration = duration,
                        paused = playback.isPaused,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    is PlayMode.RecordingPlay -> SeekControls(
                        title = mode.recording.title,
                        subtitle = "● Recording  ·  ${mode.recording.channelName}  ·  ${dayLabel(mode.recording.start)} ${hhmm(mode.recording.start)}",
                        position = position,
                        duration = duration,
                        paused = playback.isPaused,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            when (overlay) {
                Overlay.None -> Unit
                Overlay.MiniGuide -> channel?.let { ch ->
                    MiniGuideOverlay(vm, ch, Modifier.align(Alignment.BottomCenter)) { overlay = Overlay.None }
                }
                Overlay.Channels -> ChannelListOverlay(vm, Modifier.align(Alignment.CenterStart)) { overlay = Overlay.None }
                Overlay.Sleep -> MenuDialog(
                    title = "Sleep timer",
                    subtitle = vm.sleepLabel,
                    items = buildList {
                        if (vm.sleepAt != 0L) add(MenuItem("Off") { vm.setSleepTimer(0) })
                        if (isLive) add(MenuItem("At the end of this programme") { vm.setSleepTimer(-1) })
                        listOf(15, 30, 45, 60, 90, 120).forEach { m -> add(MenuItem("In ${if (m < 60) "$m minutes" else "${m / 60} hour${if (m >= 120) "s" else ""}${if (m % 60 != 0) " ${m % 60} min" else ""}"}") { vm.setSleepTimer(m) }) }
                    },
                    onDismiss = { overlay = Overlay.None },
                )
                Overlay.LipSync -> MenuDialog(
                    title = "Sound timing",
                    subtitle = "If lips move before you hear the words, pick \"sound earlier\". Now: ${vm.lipSyncLabel()}",
                    items = buildList {
                        listOf(-400L, -300L, -200L, -100L, -50L, 0L, 50L, 100L, 200L, 300L, 400L).forEach { ms ->
                            add(MenuItem(vm.lipSyncLabel(ms) + if (ms == vm.lipSyncMs) "  ✓" else "") { vm.setLipSync(ms) })
                        }
                        add(MenuItem("Use ${vm.lipSyncLabel()} for every channel") { vm.setLipSync(vm.lipSyncMs, allChannels = true) })
                    },
                    onDismiss = { overlay = Overlay.None },
                )
                Overlay.Versions -> channel?.let { ch ->
                    val versions = remember(ch.id) { vm.versionsFor(ch) }
                    MenuDialog(
                        title = "Version",
                        subtitle = "Tuner remembers your choice for this channel. The others are used as backups if a stream fails.",
                        items = versions.map { v ->
                            MenuItem(vm.versionLabel(v), if (v.id == ch.id) "✓ watching" else null) { if (v.id != ch.id) vm.watchVersion(v) }
                        },
                        onDismiss = { overlay = Overlay.None },
                    )
                }
                Overlay.Favourites -> FavouritesStrip(vm, channel, Modifier.align(Alignment.BottomCenter)) {
                    downHeld = false
                    overlay = Overlay.None
                }
                Overlay.Options -> OptionsMenu(
                    vm = vm,
                    mode = mode,
                    channel = channel,
                    showStats = showStats,
                    onToggleStats = { showStats = !showStats },
                    onOverlay = { overlay = it },
                    onPip = { if (!enterPip(context)) vm.showNotice("Picture-in-picture isn't supported on this TV") },
                    onDismiss = { overlay = Overlay.None },
                )
                Overlay.Audio -> {
                    val tracks = remember { playback.tracks(C.TRACK_TYPE_AUDIO, includeUnsupported = true) }
                    val anyUnsupported = tracks.any { !it.supported }
                    MenuDialog(
                        title = "Audio",
                        subtitle = when {
                            !anyUnsupported -> null
                            vm.audioPassthrough -> "Tracks marked ✗ can't be played by this TV or your soundbar."
                            else -> "Tracks marked ✗ (often DTS or Dolby TrueHD) can't be decoded by the TV. " +
                                "Turning on Settings › Audio passthrough lets a soundbar or AV receiver play them instead."
                        },
                        items = tracks.map { t ->
                            MenuItem(
                                t.label,
                                when {
                                    !t.supported -> "✗ not supported"
                                    t.selected -> "✓"
                                    else -> null
                                },
                            ) {
                                if (t.supported) playback.selectTrack(t, C.TRACK_TYPE_AUDIO)
                                else vm.showNotice("This TV can't play that sound track" + (if (!vm.audioPassthrough) " – try turning on Audio passthrough in Settings" else ""))
                            }
                        },
                        onDismiss = { overlay = Overlay.None },
                    )
                }
                Overlay.Subtitles -> {
                    val tracks = remember { playback.tracks(C.TRACK_TYPE_TEXT) }
                    val off = playback.subtitlesDisabled
                    MenuDialog(
                        title = "Subtitles",
                        items = listOf(MenuItem("Off", if (off) "✓" else null) { playback.subtitlesOff() }) +
                            tracks.map { t ->
                                MenuItem(t.label, if (t.selected && !off) "✓" else null) { playback.selectTrack(t, C.TRACK_TYPE_TEXT) }
                            },
                        onDismiss = { overlay = Overlay.None },
                    )
                }
            }
            SubtitleResults(vm)
        }
    }
}

@Composable
private fun SubtitleResults(vm: TunerViewModel) {
    val hits = vm.subtitleHits ?: return
    MenuDialog(
        title = "Subtitles online",
        subtitle = vm.subtitleStatus ?: "From opensubtitles.com – pick the one that best matches your copy",
        items = hits.map { h -> MenuItem(h.release, "${h.language} · ${h.downloads} downloads") { vm.useSubtitle(h) } }
            .ifEmpty { listOf(MenuItem("Close") {}) },
        onDismiss = { vm.closeSubtitleHits() },
    )
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xCC000000))
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Text(text, color = Color.White, fontSize = 17.sp)
    }
}

@Composable
private fun OptionsMenu(
    vm: TunerViewModel,
    mode: PlayMode,
    channel: Channel?,
    showStats: Boolean,
    onToggleStats: () -> Unit,
    onOverlay: (Overlay) -> Unit,
    onPip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playback = vm.playback
    val audio = remember { playback.tracks(C.TRACK_TYPE_AUDIO, includeUnsupported = true) }
    val text = remember { playback.tracks(C.TRACK_TYPE_TEXT) }
    val items = buildList {
        if (mode is PlayMode.Live && channel != null) {
            val fav = channel.id in vm.favouriteSet
            add(MenuItem(if (fav) "Remove from favourites" else "Add to favourites") { vm.toggleFavourite(channel.id) })
            add(
                MenuItem(
                    if (playback.isPaused) "Resume" else "Pause live TV",
                    if (!playback.isPaused && vm.timeshiftEnabled) "keeps recording" else null,
                ) { playback.togglePause() }
            )
            if (playback.canSeek) add(MenuItem("Jump to live") { playback.goLive() })
            add(MenuItem("Mini guide") { onOverlay(Overlay.MiniGuide) })
            val current = vm.nowNext(channel.id, vm.now).first
            if (current != null && vm.canCatchup(channel, current)) {
                add(MenuItem("Watch from the start", current.title) { vm.playCatchup(channel, current, vm.zapChannels) })
            }
            vm.previousChannel?.let { prev -> add(MenuItem("Previous channel", prev.name) { vm.playPrevious() }) }
            if (current != null) {
                val existing = vm.recordingFor(channel, current)
                if (existing == null) add(MenuItem("● Record this programme", current.title) { vm.recordProgramme(channel, current) })
                else add(MenuItem("Stop recording", current.title) { vm.stopRecording(existing) })
                if (!vm.activeProfile.kids) {
                    add(MenuItem(if (vm.seriesLinkFor(channel, current) != null) "Cancel series link" else "🔁 Series link", current.title) { vm.toggleSeriesLink(channel, current) })
                }
            }
            add(MenuItem("● Record for an hour") { vm.recordNow(channel, 60) })
            add(MenuItem("Multi-view: 2 channels") { vm.startMultiView(2) })
            add(MenuItem("Multi-view: 4 channels", "needs a powerful TV") { vm.startMultiView(4) })
            add(MenuItem("Channel list") { onOverlay(Overlay.Channels) })
            add(MenuItem("TV guide") { vm.openGuide() })
        } else {
            add(MenuItem(if (playback.isPaused) "Play" else "Pause") { playback.togglePause() })
            if (mode is PlayMode.CatchupPlay) add(MenuItem("Back to live TV") { vm.backToLive() })
            add(MenuItem("Restart from the beginning") { playback.seekTo(0L) })
        }
        // Shown whenever there's sound to choose or explain – films and Plex often have several tracks.
        if (audio.size > 1 || (audio.isNotEmpty() && mode !is PlayMode.Live)) {
            add(MenuItem("🔈 Audio", audio.firstOrNull { it.selected }?.label ?: "${audio.size} tracks") { onOverlay(Overlay.Audio) })
        }
        if (text.isNotEmpty()) {
            val detail = if (playback.subtitlesDisabled) "Off" else text.firstOrNull { it.selected }?.label ?: "Auto"
            add(MenuItem("Subtitles", detail) { onOverlay(Overlay.Subtitles) })
        }
        if (mode is PlayMode.Vod && !mode.contentId.startsWith("plex:")) {
            add(MenuItem("🔎 Find subtitles online", if (vm.openSubtitlesKey == null) "needs a key in Settings" else null) { vm.findSubtitles() })
            add(MenuItem("ℹ Film info & trailer") { vm.openFilmInfo(mode.title, isSeries = mode.seriesId != null) })
        }
        if (mode is PlayMode.Live) {
            if (channel != null && vm.versionsFor(channel).size > 1) {
                add(MenuItem("📺 Version (HD / SD…)", vm.versionLabel(channel)) { onOverlay(Overlay.Versions) })
            }
            add(MenuItem("🔊 Volume level (this channel)", vm.volumeLabel) { vm.cycleVolumeLevel() })
            add(MenuItem("📶 Picture quality (this channel)", vm.qualityLabel()) { vm.cycleQuality() })
        }
        add(MenuItem("😴 Sleep timer", vm.sleepLabel) { onOverlay(Overlay.Sleep) })
        add(MenuItem("🌙 Night mode sound", if (vm.nightMode) "On" else "Off") { vm.toggleNightMode() })
        add(MenuItem("Subtitle size", vm.subtitleSizeLabel) { vm.cycleSubtitleSize() })
        add(MenuItem("📐 Picture size", vm.pictureModeLabel) { vm.cyclePictureMode() })
        add(MenuItem("👄 Sound timing (lip sync)", vm.lipSyncLabel()) { onOverlay(Overlay.LipSync) })
        add(MenuItem("Stream info", if (showStats) "On" else "Off") { onToggleStats() })
        if (pipSupported(context)) add(MenuItem("Picture-in-picture") { onPip() })
    }
    val title = when (mode) {
        is PlayMode.Live -> mode.channel.name
        is PlayMode.CatchupPlay -> mode.programme.title
        is PlayMode.Vod -> mode.title
        is PlayMode.RecordingPlay -> mode.recording.title
    }
    MenuDialog(title, items, onDismiss)
}

@Composable
private fun ChannelListOverlay(vm: TunerViewModel, modifier: Modifier, onClose: () -> Unit) {
    var group by remember { mutableStateOf(vm.selectedGroup) }
    val list = remember(group, vm.channels, vm.favourites, vm.recents, vm.unlocked) { vm.channelsFor(group) }
    val currentId = vm.playing?.id
    val startIndex = remember { list.indexOfFirst { it.id == currentId } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, startIndex - 3))
    val currentFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        val ok = runCatching { currentFocus.requestFocus() }.isSuccess
        if (!ok) runCatching { groupFocus.requestFocus() }
    }

    Row(
        modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF2000000), Color(0xE6000000), Color.Transparent)))
            .padding(start = 32.dp, top = 28.dp, bottom = 28.dp, end = 90.dp)
            .trapFocus()
    ) {
        LazyColumn(
            Modifier.width(230.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(vm.groupNames, key = { it }) { g ->
                val isSelected = g == group
                FocusSurface(
                    onClick = { group = g },
                    onFocused = { group = g },
                    selected = isSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .then(if (isSelected) Modifier.focusRequester(groupFocus) else Modifier),
                ) { focused ->
                    Text(
                        g,
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = if (focused || isSelected) Color.White else TunerColors.TextSecondary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(560.dp).fillMaxHeight()) {
            if (list.isEmpty()) {
                CenterMessage(if (vm.isGroupLocked(group)) "🔒 Locked group" else "No channels")
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, ch ->
                        ChannelRow(
                            vm = vm,
                            channel = ch,
                            number = vm.displayNumber(ch, i),
                            modifier = Modifier
                                .focusProperties { left = groupFocus }
                                .then(if (ch.id == currentId) Modifier.focusRequester(currentFocus) else Modifier),
                            onClick = {
                                vm.selectedGroup = group
                                vm.playFromOverlay(ch, list)
                                onClose()
                            },
                            compact = true,
                            highlight = ch.id == currentId,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBanner(vm: TunerViewModel, channel: Channel, modifier: Modifier) {
    val now = vm.now
    val (current, next) = vm.nowNext(channel.id, now)
    val isFavourite = channel.id in vm.favouriteSet
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60A1030), Color(0xF704071A))))
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(TunerColors.Accent).padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    vm.channelNumber(channel).toString(),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(20.dp))
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 104.dp, height = 68.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFavourite) Text("  ★", color = TunerColors.Gold, fontSize = 26.sp)
                }
                Text(
                    channel.group + (vm.catchupVersion(channel)?.let { "   ·   ↺ catch-up ${it.catchupDays} days" } ?: ""),
                    color = TunerColors.TextSecondary,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
            Text(hhmm(now), color = Color.White, fontSize = 26.sp)
        }

        Spacer(Modifier.height(16.dp))
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NOW", color = TunerColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("${hhmm(current.start)} – ${hhmm(current.stop)}", color = TunerColors.TextSecondary, fontSize = 18.sp)
                Spacer(Modifier.width(14.dp))
                Text(
                    current.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(progressOf(current, now), Modifier.padding(vertical = 8.dp).width(420.dp))
                Spacer(Modifier.width(14.dp))
                GenreChip(current.category)
                if (vm.recordingFor(channel, current) != null) {
                    Spacer(Modifier.width(10.dp))
                    Text("● REC", color = TunerColors.NowLine, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            current.desc?.let {
                Text(it, color = TunerColors.TextSecondary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("No guide information", color = TunerColors.TextSecondary, fontSize = 18.sp)
        }
        if (next != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT", color = TunerColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${hhmm(next.start)}  ${next.title}",
                    color = TunerColors.TextPrimary,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (vm.hasReminder(channel, next)) Text("   ⏰ reminder set", color = TunerColors.Gold, fontSize = 15.sp)
                if (vm.recordingFor(channel, next) != null) Text("   ● will record", color = TunerColors.NowLine, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        val prev = vm.previousChannel
        Text(
            "▲▼ channels  ·  ◀ list  ·  ▶ " + (prev?.name?.let { "back to $it" } ?: "previous") +
                "  ·  OK mini guide (hold OK there to remind or record)  ·  0–9 number  ·  hold OK menu",
            color = TunerColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeekControls(
    title: String,
    subtitle: String?,
    position: Long,
    duration: Long,
    paused: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60A1030), Color(0xF704071A))))
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 32.dp)
    ) {
        Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let {
            Text(it, color = TunerColors.TextSecondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (paused) "⏸" else "▶", color = Color.White, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Text(clock(position), color = Color.White, fontSize = 17.sp)
            Spacer(Modifier.width(14.dp))
            ProgressBar(
                if (duration > 0) position.toFloat() / duration else 0f,
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            Text(if (duration > 0) clock(duration) else "--:--", color = Color.White, fontSize = 17.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "OK play/pause  ·  ◀ back 10 s  ·  ▶ forward 30 s  ·  hold OK for menu  ·  BACK to exit",
            color = TunerColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}


/**
 * Mini guide over the video: ▲▼ browse channels, ◀▶ move through their programmes,
 * OK watches (or sets a reminder / plays catch-up), BACK closes.
 */
@Composable
private fun MiniGuideOverlay(vm: TunerViewModel, current: Channel, modifier: Modifier, onClose: () -> Unit) {
    val list = remember { vm.zapChannels.ifEmpty { listOf(current) } }
    var chIndex by remember { mutableIntStateOf(list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)) }
    val ch = list[chIndex.coerceIn(0, list.lastIndex)]
    val now = vm.now
    val progs = remember(ch.id, vm.epg) {
        vm.programmesFor(ch.id).filter { it.stop > now || vm.canCatchup(ch, it) }
    }
    val startIndex = remember(ch.id, progs) { progs.indexOfFirst { it.stop > now }.coerceAtLeast(0) }
    var progIndex by remember(ch.id) { mutableIntStateOf(startIndex) }
    val focus = remember { FocusRequester() }
    var token by remember { mutableIntStateOf(0) }
    // Hold OK on a programme: remind / record / series link without leaving the channel.
    var okHeld by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Programme?>(null) }
    LaunchedEffect(menuFor) {
        if (menuFor == null) {
            delay(30)
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(token, menuFor) {
        if (menuFor != null) return@LaunchedEffect
        delay(15_000)
        onClose()
    }
    BackHandler(enabled = menuFor == null, onBack = onClose)

    fun activate() {
        val p = progs.getOrNull(progIndex)
        val t = System.currentTimeMillis()
        if (p == null || (p.start <= t && p.stop > t)) {
            vm.playFromOverlay(ch, list)
        } else if (p.start > t) {
            vm.toggleReminder(ch, p)
        } else if (vm.canCatchup(ch, p)) {
            vm.playCatchup(ch, p, list)
        }
        onClose()
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0A1030), Color(0xF704071A))))
            .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 28.dp)
            .focusRequester(focus)
            .onKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val isOk = code == AndroidKey.KEYCODE_DPAD_CENTER || code == AndroidKey.KEYCODE_ENTER
                if (e.type == KeyEventType.KeyUp) {
                    if (isOk && !okHeld) activate()
                    if (isOk) okHeld = false
                    return@onKeyEvent isOk
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                token++
                if (isOk && e.nativeKeyEvent.repeatCount == 0) okHeld = false
                if (isOk && e.nativeKeyEvent.repeatCount == 1) {
                    okHeld = true
                    val p = progs.getOrNull(progIndex)
                    if (p != null && p.stop > System.currentTimeMillis()) menuFor = p
                    return@onKeyEvent true
                }
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP -> {
                        chIndex = (chIndex - 1).mod(list.size)
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_CHANNEL_DOWN -> {
                        chIndex = (chIndex + 1).mod(list.size)
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_LEFT -> {
                        progIndex = (progIndex - 1).coerceAtLeast(0)
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_RIGHT -> {
                        progIndex = (progIndex + 1).coerceAtMost((progs.size - 1).coerceAtLeast(0))
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER -> true
                    else -> false
                }
            }
            .focusable()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.displayNumber(ch, chIndex)}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            ChannelLogo(ch.logo, ch.name, Modifier.size(width = 84.dp, height = 56.dp))
            Spacer(Modifier.width(16.dp))
            Text(ch.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (ch.id == current.id) "Watching now" else "▲▼ channels  ·  ◀▶ programmes  ·  OK",
                color = TunerColors.TextSecondary,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (progs.isEmpty()) {
            Text("No guide information · OK to watch", color = TunerColors.TextSecondary, fontSize = 17.sp)
        } else {
            val first = (progIndex - 1).coerceAtLeast(0)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in first until minOf(first + 4, progs.size)) {
                    val p = progs[i]
                    val selected = i == progIndex
                    val live = p.start <= now && p.stop > now
                    val past = p.stop <= now
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) TunerColors.Accent else TunerColors.Panel)
                            .padding(12.dp)
                    ) {
                        Text(
                            when {
                                live -> "On now · ${hhmm(p.start)}"
                                past -> "↺ ${hhmm(p.start)}"
                                vm.hasReminder(ch, p) -> "⏰ ${hhmm(p.start)}"
                                else -> hhmm(p.start)
                            },
                            color = if (selected) Color.White else TunerColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                        Text(p.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (live) ProgressBar(progressOf(p, now), Modifier.padding(top = 6.dp).fillMaxWidth(), color = Color.White)
                    }
                }
            }
            progs.getOrNull(progIndex)?.let { p ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenreChip(p.category)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        p.desc ?: "",
                        color = TunerColors.TextSecondary,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when {
                        p.start <= now && p.stop > now -> "OK to watch  ·  hold OK to record"
                        p.start > now -> (if (vm.hasReminder(ch, p)) "OK to cancel the reminder" else "OK to set a reminder") + "  ·  hold OK to record"
                        vm.canCatchup(ch, p) -> "OK to watch on catch-up"
                        else -> "Finished"
                    },
                    color = TunerColors.Accent,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    menuFor?.let { p ->
        val rec = vm.recordingFor(ch, p)
        val link = vm.seriesLinkFor(ch, p)
        val items = buildList {
            if (p.start > System.currentTimeMillis()) {
                add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel reminder" else "Remind me") { vm.toggleReminder(ch, p) })
            }
            if (rec == null) add(MenuItem("Record") { vm.recordProgramme(ch, p) })
            else add(MenuItem(if (rec.status == com.titanejstudios.tuner.data.RecStatus.RECORDING) "Stop recording" else "Cancel recording") { vm.stopRecording(rec) })
            add(MenuItem(if (link != null) "Stop recording the series" else "Record every episode") { vm.toggleSeriesLink(ch, p) })
        }
        MenuDialog(p.title, items, onDismiss = { menuFor = null }, subtitle = "${ch.name} · ${dayLabel(p.start)} ${hhmm(p.start)} – ${hhmm(p.stop)}")
    }
}

/** Hold ▼ while watching: your favourites as a strip of tiles along the bottom. */
@Composable
private fun FavouritesStrip(vm: TunerViewModel, current: Channel?, modifier: Modifier, onClose: () -> Unit) {
    val favs = remember { vm.channelsFor(FAVOURITES) }
    val startIndex = remember { favs.indexOfFirst { it.id == current?.id }.coerceAtLeast(0) }
    val focus = remember { FocusRequester() }
    var token by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(token) {
        delay(12_000)
        onClose()
    }
    BackHandler(onBack = onClose)
    if (favs.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 1).coerceAtLeast(0))
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0A1030), Color(0xF704071A))))
            .padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = 26.dp)
            .trapFocus()
    ) {
        Text("★ Favourites", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        ) {
            items(favs.size, key = { favs[it].id }) { i ->
                val c = favs[i]
                ChannelTile(
                    vm = vm,
                    channel = c,
                    onClick = {
                        vm.playFromOverlay(c, favs)
                        onClose()
                    },
                    onFocused = { token++ },
                    badge = if (c.id == current?.id) "WATCHING" else null,
                    modifier = if (i == startIndex) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

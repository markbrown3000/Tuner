package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.playback.ScrubPreview
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.pointer.pointerInput
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

private enum class Overlay { None, Channels, Options, Audio, Subtitles, MiniGuide, Favourites, Sleep, LipSync, Versions, Send }

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
    // After CH+/CH−: the channel strip instead of the full info bar, for a few seconds.
    var zapStrip by remember { mutableStateOf(false) }
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
    LaunchedEffect(vm.zappedAt) {
        if (vm.zappedAt == 0L) return@LaunchedEffect
        zapStrip = true
        pokeInfo()
        delay(4_000)
        zapStrip = false
    }
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

    // Scrubbing: hold ◀ ▶ (TV) or drag the bar (phone) to pick a spot, with a little picture of it.
    var scrubTarget by remember(modeKey) { mutableStateOf<Long?>(null) }
    var scrubFrame by remember(modeKey) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val previewUrl = when (mode) {
        is PlayMode.RecordingPlay -> mode.recording.path
        is PlayMode.Live -> null
        else -> playback.currentUrl
    }
    val previews = remember(modeKey, previewUrl, vm.scrubPreviewsIptv) {
        previewUrl?.takeIf { vm.scrubPreviewsIptv || ScrubPreview.safeFor(it, (mode as? PlayMode.Vod)?.contentId) }?.let { ScrubPreview(it) }
    }
    DisposableEffect(previews) { onDispose { previews?.release() } }
    LaunchedEffect(scrubTarget, previews) {
        val t = scrubTarget
        if (t == null) {
            scrubFrame = null
            return@LaunchedEffect
        }
        val p = previews ?: return@LaunchedEffect
        delay(180)
        val bmp = withContext(Dispatchers.IO) { runCatching { p.frameAt(t) }.getOrNull() }
        if (bmp != null) scrubFrame = bmp.asImageBitmap()
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

    /** First press jumps straight away; holding the button scrubs (faster the longer you hold) and jumps when let go. */
    fun scrubOrSeek(deltaMs: Long, repeat: Int) {
        if (repeat == 0 || isLive || duration <= 0) {
            seek(deltaMs, repeat == 0)
            return
        }
        val step = deltaMs * when {
            repeat > 24 -> 6
            repeat > 10 -> 3
            else -> 1
        }
        scrubTarget = ((scrubTarget ?: playback.positionMs) + step).coerceIn(0L, (duration - 5_000L).coerceAtLeast(0L))
        pokeInfo()
    }

    fun finishScrub() {
        val t = scrubTarget ?: return
        val before = playback.positionMs
        playback.seekTo(t)
        scrubTarget = null
        if (mode is PlayMode.Vod && mode.seriesId != null && before < 180_000L && t > before) introCandidate = t
        pokeInfo()
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
                    // "Better quality is back" showing: OK switches to it.
                    if (okDown && !okLong && vm.qualityBack != null) {
                        okDown = false
                        vm.switchToBetterQuality()
                        return true
                    }
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
                } else scrubOrSeek(-10_000, e.nativeKeyEvent.repeatCount)
            } else if (e.type == KeyEventType.KeyUp) finishScrub()
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
                } else scrubOrSeek(30_000, e.nativeKeyEvent.repeatCount)
            } else if (e.type == KeyEventType.KeyUp) finishScrub()
            AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKey.KEYCODE_MEDIA_PLAY, AndroidKey.KEYCODE_MEDIA_PAUSE -> if (first) {
                playback.togglePause()
                pokeInfo()
            }
            AndroidKey.KEYCODE_MEDIA_REWIND -> if (down) {
                // Live TV that joined late: ⏪ starts the programme from the beginning (catch-up).
                if (!(first && isLive && !playback.timeshiftActive && vm.startOverCurrent())) seek(-30_000, first)
            }
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

    // Phones: tap for controls, swipe up/down in the middle to change channel, on the left for
    // brightness and on the right for volume; double-tap a side to jump 10 seconds.
    var controls by remember { mutableStateOf(false) }
    var controlsToken by remember { mutableIntStateOf(0) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controls, controlsToken) {
        if (controls) {
            delay(4_500)
            if (!playback.isPaused) controls = false
        }
    }
    LaunchedEffect(gestureLabel) {
        if (gestureLabel != null) {
            delay(900)
            gestureLabel = null
        }
    }
    val activity = context as? android.app.Activity
    val audioManager = remember { context.getSystemService(android.media.AudioManager::class.java) }
    val phoneGestures = if (!vm.isPhone) Modifier else Modifier
        .pointerInput(isLive) {
            detectTapGestures(
                onTap = {
                    controls = !controls
                    controlsToken++
                    if (controls) pokeInfo()
                },
                onDoubleTap = { o ->
                    val back = o.x < size.width / 2f
                    seek(if (back) -10_000L else 10_000L, true)
                    gestureLabel = if (back) "⏪ 10 s" else "10 s ⏩"
                },
            )
        }
        .pointerInput(isLive) {
            var startX = 0f
            var total = 0f
            var volStart = 0
            var brightStart = 0.5f
            detectVerticalDragGestures(
                onDragStart = { o ->
                    startX = o.x
                    total = 0f
                    volStart = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
                    brightStart = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                },
                onDragEnd = {
                    val third = size.width / 3f
                    if (startX in third..(2 * third) && isLive && kotlin.math.abs(total) > 120f) {
                        // Swipe up = next channel, down = previous.
                        vm.zap(if (total < 0) 1 else -1)
                    }
                },
                onVerticalDrag = { _, dy ->
                    total += dy
                    val third = size.width / 3f
                    val frac = -total / size.height
                    when {
                        startX < third -> activity?.window?.let { w ->
                            val b = (brightStart + frac).coerceIn(0.02f, 1f)
                            w.attributes = w.attributes.apply { screenBrightness = b }
                            gestureLabel = "☀ ${(b * 100).toInt()}%"
                        }
                        startX > 2 * third -> audioManager?.let { am ->
                            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val v = (volStart + (frac * max * 1.5f).toInt()).coerceIn(0, max)
                            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                            gestureLabel = "🔊 ${v * 100 / maxOf(max, 1)}%"
                        }
                    }
                },
            )
        }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { handleKey(it) }
            .focusable()
            .then(phoneGestures)
    ) {
        VideoSurface(playback.player, Modifier.fillMaxSize(), resizeMode = vm.pictureMode, keepLastFrame = isLive)

        if (vm.isPhone && !vm.inPip) {
            gestureLabel?.let {
                Text(
                    it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            if (controls && overlay == Overlay.None) {
                PhoneControls(
                    vm = vm,
                    isLive = isLive,
                    position = position,
                    duration = duration,
                    onChannels = { overlay = Overlay.Channels; controls = false },
                    onMenu = { overlay = Overlay.Options; controls = false },
                    onSend = {
                        if (vm.link.linked) {
                            vm.link.search()
                            overlay = Overlay.Send
                        } else vm.showNotice("Link your TV first: Settings › Linked devices")
                        controls = false
                    },
                    onSeek = { playback.seekTo(it); controlsToken++ },
                    scrubTarget = scrubTarget,
                    scrubFrame = scrubFrame,
                    onScrub = { scrubTarget = it; controlsToken++ },
                    onPoke = { controlsToken++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

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

            vm.qualityBack?.let { better ->
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = if (vm.isPhone) 70.dp else 40.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xF00E2A5C))
                        .tvClickable({ vm.switchToBetterQuality() })
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("▲", color = Color(0xFF6FE3A1), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${better.name} is working again", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (vm.isPhone) "Tap to switch back to the better picture" else "Press OK to switch back to the better picture",
                            color = Color(0xFFD4DDF5), fontSize = 13.sp,
                        )
                    }
                }
            }
            if (!vm.isPhone) scrubTarget?.let { t ->
                ScrubBubble(t, duration, scrubFrame, Modifier.align(Alignment.BottomCenter).padding(bottom = 190.dp))
            }
            if (skipIntroVisible && introEnd != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = if (vm.isPhone) 20.dp else 48.dp, bottom = if (vm.isPhone) 90.dp else 150.dp)
                        // Phones: tap it.
                        .tvClickable({
                            playback.seekTo(introEnd)
                            introCandidate = null
                        })
                ) {
                    Chip(if (vm.isPhone) "⏭  Skip intro" else "⏭  Skip intro – press ▶")
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
                    is PlayMode.Live -> if (zapStrip) ZapStrip(vm, vm.zapPreview ?: mode.channel, Modifier.align(Alignment.BottomCenter))
                    else InfoBanner(vm, vm.zapPreview ?: mode.channel, Modifier.align(Alignment.BottomCenter))
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
                        subtitle = "Flicker remembers your choice for this channel. The others are used as backups if a stream fails.",
                        items = versions.map { v ->
                            MenuItem(vm.versionLabel(v), if (v.id == ch.id) "✓ watching" else null) { if (v.id != ch.id) vm.watchVersion(v) }
                        },
                        onDismiss = { overlay = Overlay.None },
                    )
                }
                Overlay.Send -> {
                    val peers = vm.link.peers
                    MenuDialog(
                        title = "Send to…",
                        subtitle = when {
                            peers.isNotEmpty() -> "It carries on from here on the device you pick."
                            vm.link.searching -> "Looking for your other devices…"
                            else -> "No linked devices found. Flicker needs to be open on them (Settings › Linked devices)."
                        },
                        items = peers.map { p ->
                            MenuItem((if (p.phone) "📱  " else "📺  ") + p.name) { vm.sendTo(p) }
                        } + MenuItem("↻ Look again") {
                            vm.link.search()
                            overlay = Overlay.Send
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
        if (mode is PlayMode.Vod && mode.seriesId != null) {
            val pos = playback.positionMs
            if (pos in 10_000L..600_000L) {
                add(MenuItem("⏭ The intro ends here", "${pos / 60_000}:${"%02d".format((pos / 1000) % 60)} – skip to here on every episode") { vm.setIntroEndHere(pos) })
            }
            if (vm.introEndFor() != null) add(MenuItem("Forget this series' intro") { vm.forgetIntro() })
        }
        if (mode is PlayMode.Live) {
            if (channel != null && vm.versionsFor(channel).size > 1) {
                add(MenuItem("📺 Version (HD / SD…)", vm.versionLabel(channel)) { onOverlay(Overlay.Versions) })
            }
            add(MenuItem("🔊 Volume level (this channel)", vm.volumeLabel) { vm.cycleVolumeLevel() })
            add(MenuItem("📶 Picture quality (this channel)", vm.qualityLabel()) { vm.cycleQuality() })
        }
        if (mode !is PlayMode.RecordingPlay) {
            add(
                MenuItem("📺 Send to another TV or phone", if (vm.link.linked) null else "link them first: Settings › Linked devices") {
                    if (vm.link.linked) {
                        vm.link.search()
                        onOverlay(Overlay.Send)
                    } else vm.showNotice("Link your devices first: Settings › Linked devices")
                }
            )
        }
        add(MenuItem("😴 Sleep timer", vm.sleepLabel) { onOverlay(Overlay.Sleep) })
        add(MenuItem("🌙 Night mode sound", if (vm.nightMode) "On" else "Off") { vm.toggleNightMode() })
        add(MenuItem("🗣 Clearer speech", if (vm.clearSpeech) "On" else "Off") { vm.toggleClearSpeech() })
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
    val score = current?.let { vm.liveScoreFor(it) }
    val name = channel.name.uppercase()
    val badges = buildList {
        when {
            Regex("\\b(UHD|4K)\\b").containsMatchIn(name) -> add("4K")
            Regex("\\b(FHD|HD|1080P?|720P?)\\b").containsMatchIn(name) -> add("HD")
        }
        if (name.contains("5.1")) add("5.1")
        if (remember(channel.id, now / 60_000) { vm.playback.tracks(C.TRACK_TYPE_TEXT).isNotEmpty() }) add("SUBTITLES")
        if (vm.catchupVersion(channel) != null) add("↺ CATCH-UP")
        if (current != null && vm.recordingFor(channel, current) != null) add("● REC")
    }
    val live = current != null && (score != null || (com.titanejstudios.tuner.data.Sports.classify(current) != null && com.titanejstudios.tuner.data.Sports.looksLive(current)))
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9030820), Color(0xF5030820))))
            .padding(start = 56.dp, end = 56.dp, top = 90.dp, bottom = 30.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Logo with the channel number underneath.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(width = 150.dp, height = 84.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogo(channel.logo, channel.name, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                vm.channelNumber(channel).toString() + if (isFavourite) "  ★" else "",
                color = if (isFavourite) TunerColors.Gold else Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) {
                    Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFE5213B)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                score?.let {
                    Text(it, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 1.dp))
                }
                badges.forEach { b ->
                    Text(b, color = if (b == "● REC") TunerColors.NowLine else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(hhmm(now), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                current?.title ?: channel.name,
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(hhmm(current.start), color = Color(0xFFD4DDF5), fontSize = 15.sp)
                    Spacer(Modifier.width(12.dp))
                    ProgressBar(progressOf(current, now), Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(hhmm(current.stop), color = Color(0xFFD4DDF5), fontSize = 15.sp)
                }
            } else {
                Text("No guide information", color = TunerColors.TextSecondary, fontSize = 16.sp)
            }
            Text(
                buildString {
                    append(channel.name)
                    if (next != null) {
                        append("  ·  Next: ${hhmm(next.start)} ${next.title}")
                        if (vm.hasReminder(channel, next)) append("  ⏰")
                        if (vm.recordingFor(channel, next) != null) append("  ● will record")
                    }
                },
                color = Color(0xFFC3CDEA), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            current?.desc?.let {
                Text(it, color = TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            val prev = vm.previousChannel
            Text(
                "◀ channels  ·  ▶ " + (prev?.name?.let { "back to $it" } ?: "previous") +
                    "  ·  OK mini guide  ·  hold OK menu" + (if (vm.catchupVersion(channel) != null) "  ·  ⏪ from the start" else ""),
                color = Color(0xFF8FA3D4), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Changing channel: the new channel large, with the ones either side, along the bottom. */
@Composable
private fun ZapStrip(vm: TunerViewModel, channel: Channel, modifier: Modifier) {
    val now = vm.now
    val around = remember(channel.id, vm.zapChannels) { vm.zapNeighbours(channel.id, 2) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9030820), Color(0xF0030820))))
            .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 28.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            around.forEachIndexed { k, c ->
                val centre = k == around.size / 2
                val (cur, next) = vm.nowNext(c.id, now)
                Column(
                    Modifier
                        .then(if (centre) Modifier.width(330.dp) else Modifier.width(196.dp))
                        .clip(RoundedCornerShape(if (centre) 14.dp else 12.dp))
                        .background(if (centre) TunerColors.Accent else Color(0xD9141C45))
                        .then(if (centre) Modifier.border(3.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(if (centre) 16.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.channelNumber(c).toString(), color = Color(0xFFD4DDF5), fontSize = if (centre) 15.sp else 13.sp)
                        Spacer(Modifier.weight(1f))
                        ChannelLogo(c.logo, c.name, Modifier.size(width = if (centre) 64.dp else 48.dp, height = if (centre) 36.dp else 26.dp))
                    }
                    Text(c.name, color = Color.White, fontSize = if (centre) 21.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        cur?.let { if (centre) "${it.title} · until ${hhmm(it.stop)}" else it.title } ?: "No guide",
                        color = if (centre) Color.White else Color(0xFFC3CDEA), fontSize = if (centre) 15.sp else 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (centre) {
                        cur?.let { ProgressBar(progressOf(it, now), Modifier.fillMaxWidth().padding(vertical = 3.dp), color = Color.White) }
                        next?.let { Text("Next: ${it.title}", color = Color(0xFFD4DDF5), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
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


/** Phones: on-screen controls over the video (tap the video to show or hide them). */
@Composable
private fun PhoneControls(
    vm: TunerViewModel,
    isLive: Boolean,
    position: Long,
    duration: Long,
    onChannels: () -> Unit,
    onMenu: () -> Unit,
    onSend: () -> Unit,
    onSeek: (Long) -> Unit,
    scrubTarget: Long?,
    scrubFrame: androidx.compose.ui.graphics.ImageBitmap?,
    onScrub: (Long?) -> Unit,
    onPoke: () -> Unit,
    modifier: Modifier,
) {
    val playback = vm.playback
    val title = when (val m = vm.mode) {
        is PlayMode.Live -> m.channel.name
        is PlayMode.CatchupPlay -> m.programme.title
        is PlayMode.Vod -> m.title
        is PlayMode.RecordingPlay -> m.recording.title
        null -> ""
    }
    @Composable
    fun RoundButton(label: String, size: Int = 52, onClick: () -> Unit) {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000))
                .tvClickable({ onClick(); onPoke() }),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = (size * 0.4f).sp) }
    }
    Box(modifier.background(Color(0x55000000))) {
        // Top: back, title, channels and the menu.
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton("←", 44) { vm.back() }
            Spacer(Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (vm.mode !is PlayMode.RecordingPlay) {
                RoundButton("📺", 44, onSend)
                Spacer(Modifier.width(10.dp))
            }
            if (isLive) {
                RoundButton("☰", 44, onChannels)
                Spacer(Modifier.width(10.dp))
            }
            RoundButton("⋮", 44, onMenu)
        }
        // Middle: previous / play-pause / next (or 10 seconds back and forward).
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLive) RoundButton("⏮") { vm.zap(-1) } else RoundButton("⏪") { playback.seekBy(-10_000L) }
            RoundButton(if (playback.isPaused) "▶" else "⏸", 72) { playback.togglePause() }
            if (isLive) RoundButton("⏭") { vm.zap(1) } else RoundButton("⏩") { playback.seekBy(10_000L) }
        }
        // Bottom: a scrub bar for films, episodes, catch-up and recordings.
        if (!isLive && duration > 0) {
            // While dragging: a picture of where you'll land; it jumps there when you let go.
            scrubTarget?.let { t -> ScrubBubble(t, duration, scrubFrame, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp)) }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(scrubTarget ?: position), color = Color.White, fontSize = 13.sp)
                androidx.compose.material3.Slider(
                    value = ((scrubTarget ?: position).toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { onScrub((it * duration).toLong()) },
                    onValueChangeFinished = {
                        scrubTarget?.let { onSeek(it) }
                        onScrub(null)
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(clock(duration), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

/** Where you'll land when scrubbing: a little picture (when the stream can give one) and the time. */
@Composable
private fun ScrubBubble(target: Long, duration: Long, frame: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE60A1030)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (frame != null) {
            androidx.compose.foundation.Image(
                frame, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 240.dp, height = 135.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(clock(target) + "  /  " + clock(duration), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

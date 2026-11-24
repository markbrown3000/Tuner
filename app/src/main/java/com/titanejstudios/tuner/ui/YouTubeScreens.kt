package com.titanejstudios.tuner.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import com.titanejstudios.tuner.integrations.YouTube
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKey

// ---------------- The player ----------------

/**
 * A YouTube video full screen, in YouTube's own embedded player. Remote: OK play/pause,
 * ◀ ▶ 10 seconds, BACK closes. Phone: tap to pause or play, double-tap a side to skip.
 */
@Composable
fun YouTubePlayerScreen(vm: TunerViewModel) {
    val video = vm.ytVideo
    if (video == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val context = LocalContext.current
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var length by remember { mutableFloatStateOf(0f) }
    var showBar by remember { mutableStateOf(true) }
    var barToken by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    fun poke() {
        showBar = true
        barToken++
    }
    // Plan B: YouTube's own embed page, loaded directly with Flicker named as the referrer.
    var plainPage by remember(video.id) { mutableStateOf(false) }
    // Neither way worked: say why, and offer the YouTube app (only if you ask – it has its own sign-in).
    var failed by remember(video.id) { mutableStateOf<String?>(null) }
    val web = remember(video.id) { mutableStateOf<WebView?>(null) }
    fun js(code: String) {
        web.value?.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return '';$code})()", null)
    }
    fun toggle() {
        if (plainPage) {
            js("if(v.paused)v.play();else v.pause();return '';")
            poke()
            return
        }
        val p = player ?: return
        if (playing) p.pause() else p.play()
        poke()
    }
    fun skip(seconds: Float) {
        val to = (position + seconds).coerceIn(0f, if (length > 0f) length - 1f else Float.MAX_VALUE)
        if (plainPage) js("v.currentTime=$to;return '';")
        else (player ?: return).seekTo(to)
        position = to
        poke()
    }

    val view = remember(video.id) {
        YouTubePlayerView(context).apply {
            enableAutomaticInitialization = false
            keepScreenOn = true
            // Keys go to Flicker, not the web page inside.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // YouTube now refuses embedded players that don't say where they are (error 152/153):
            // name the app as the origin, as version 13 of the player library does.
            val options = IFramePlayerOptions.Builder().controls(0).rel(0).ivLoadPolicy(3).ccLoadPolicy(1)
                .origin("https://" + context.packageName).build()
            initialize(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    player = youTubePlayer
                    youTubePlayer.loadVideo(video.id, 0f)
                }

                override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                    playing = state == PlayerConstants.PlayerState.PLAYING
                    vm.youTubePlaying(playing)
                    if (state == PlayerConstants.PlayerState.ENDED) vm.back()
                }

                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                    position = second
                }

                override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                    if (duration > 0f) length = duration
                }

                override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                    vm.youTubeFailed(video.id, error.name)
                    when {
                        plainPage || failed != null -> Unit
                        // The owner doesn't allow other apps, or it's gone: no point trying again.
                        error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ->
                            failed = if (vm.vpn.connected) "YouTube won't play it here while Flicker's VPN is on (it asks to confirm you're not a bot). Turn the VPN off in Settings and try again."
                            else "YouTube won't play this one inside other apps (the owner's choice, or YouTube checking you're not a bot)."
                        error == PlayerConstants.PlayerError.VIDEO_NOT_FOUND ->
                            failed = "This video has been removed or made private."
                        else -> {
                            player = null
                            playing = false
                            plainPage = true
                        }
                    }
                }
            }, true, options)
        }
    }
    DisposableEffect(view) {
        onDispose {
            vm.youTubePlaying(false)
            runCatching { view.release() }
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(barToken, playing) {
        if (!playing) return@LaunchedEffect
        delay(4_000)
        showBar = false
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (failed != null) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val down = e.type == KeyEventType.KeyDown
                val up = e.type == KeyEventType.KeyUp
                when (code) {
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER,
                    AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKey.KEYCODE_MEDIA_PLAY, AndroidKey.KEYCODE_MEDIA_PAUSE -> {
                        if (up) toggle()
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_MEDIA_REWIND -> {
                        if (down) skip(-10f)
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (down) skip(if (code == AndroidKey.KEYCODE_MEDIA_FAST_FORWARD) 30f else 10f)
                        true
                    }
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_INFO -> {
                        if (down) poke()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (!plainPage) AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        else PlainYouTubePage(
            video.id,
            onCreated = { web.value = it },
            onStatus = { cur, dur, paused ->
                position = cur
                if (dur > 0f) length = dur
                playing = !paused
                vm.youTubePlaying(!paused)
                if (dur > 0f && cur >= dur - 0.5f && paused) vm.back()
            },
            onGiveUp = {
                vm.youTubeFailed(video.id, "embed page didn't load")
                if (failed == null) failed = "YouTube wouldn't play it inside Flicker."
            },
        )
        failed?.let { why ->
            val openFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(100)
                runCatching { openFocus.requestFocus() }
            }
            Column(
                Modifier.align(Alignment.Center).background(Color(0xF0101418), RoundedCornerShape(18.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(video.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(why, color = Color(0xFFCCCCCC), fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Open in the YouTube app", { vm.watchInYouTubeApp(video.id) }, Modifier.focusRequester(openFocus))
                    PillButton("Back", { vm.back() })
                }
            }
        }
        // Phones: tap to pause or play, double-tap a side to skip 10 seconds.
        if (vm.isPhone) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { toggle() },
                        onDoubleTap = { o -> skip(if (o.x < size.width / 2f) -10f else 10f) },
                    )
                }
            )
        }
        if (failed == null && (showBar || !playing)) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = if (vm.isPhone) 20.dp else 48.dp, vertical = if (vm.isPhone) 16.dp else 28.dp),
            ) {
                Text(video.title, color = Color.White, fontSize = if (vm.isPhone) 17.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (video.channel.isNotBlank()) Text(video.channel, color = Color(0xFFCCCCCC), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (player == null) "Loading…" else if (playing) "⏸" else "▶", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(clock((position * 1000).toLong()), color = Color.White, fontSize = 13.sp)
                    ProgressBar(if (length > 0f) (position / length).coerceIn(0f, 1f) else 0f, Modifier.weight(1f).padding(horizontal = 12.dp), color = Color(0xFFFF3B30))
                    Text(clock((length * 1000).toLong()), color = Color.White, fontSize = 13.sp)
                }
                Text(
                    if (vm.isPhone) "Tap to pause · double-tap a side to skip 10 s" else "OK play/pause  ·  ◀ ▶ 10 s  ·  BACK close",
                    color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * YouTube's embed page loaded straight into a WebView, sending Flicker's app ID as the referrer
 * (what YouTube asks of apps). Flicker drives the page's video with a little JavaScript.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlainYouTubePage(
    id: String,
    onCreated: (WebView) -> Unit,
    onStatus: (Float, Float, Boolean) -> Unit,
    onGiveUp: () -> Unit,
) {
    val context = LocalContext.current
    val referrer = "https://" + context.packageName
    val page = remember(id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            keepScreenOn = true
            webViewClient = android.webkit.WebViewClient()
            webChromeClient = android.webkit.WebChromeClient()
            loadUrl(
                "https://www.youtube.com/embed/$id?autoplay=1&controls=0&rel=0&iv_load_policy=3&cc_load_policy=1&playsinline=1&origin=$referrer",
                mapOf("Referer" to "$referrer/"),
            )
            onCreated(this)
        }
    }
    DisposableEffect(page) { onDispose { runCatching { page.destroy() } } }
    LaunchedEffect(page) {
        var seen = false
        var waited = 0
        while (true) {
            delay(1_000)
            waited++
            val result = kotlinx.coroutines.CompletableDeferred<String>()
            page.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return '';if(!v.dataset.f){v.dataset.f=1;v.play();}return v.currentTime+','+(v.duration||0)+','+v.paused;})()"
            ) { result.complete(it ?: "") }
            val parts = result.await().trim('"').split(',')
            if (parts.size == 3) {
                seen = true
                onStatus(parts[0].toFloatOrNull() ?: 0f, parts[1].toFloatOrNull() ?: 0f, parts[2] == "true")
            } else if (!seen && waited >= 15) {
                onGiveUp()
                return@LaunchedEffect
            }
        }
    }
    AndroidView(factory = { page }, modifier = Modifier.fillMaxSize())
}

// ---------------- The YouTube section ----------------

@Composable
fun YouTubeScreen(vm: TunerViewModel) {
    val yt = vm.youtube
    var typing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!vm.youTubeAllowed) {
            vm.back()
            return@LaunchedEffect
        }
        vm.refreshYouTube()
        delay(80)
        runCatching { first.requestFocus() }
    }
    val narrow = narrowScreen()
    if (vm.youTubeApprovedOnly) {
        KidsApprovedYouTube(vm, first, narrow)
        return
    }
    val kids = vm.activeProfile.kids
    val code = yt.code

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (narrow) 160.dp else 250.dp),
        modifier = Modifier.fillMaxSize().skyBackground(),
        contentPadding = PaddingValues(start = if (narrow) 10.dp else 56.dp, end = if (narrow) 10.dp else 56.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = if (narrow) 12.dp else 28.dp)) {
                Text("▶ YouTube", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(if (yt.query.isBlank()) "🔍 Search YouTube" else "🔍 ${yt.query}", { typing = true }, Modifier.focusRequester(first), compact = true)
                    run {
                        when {
                            yt.signedIn -> {
                                PillButton(if (yt.loadingFeed) "Updating…" else "↻ Subscriptions", { yt.loadFeed(force = true) }, compact = true)
                                PillButton("Sign out", { yt.signOut() }, compact = true)
                            }
                            yt.canSignIn && code == null -> PillButton("Sign in to YouTube", { yt.signIn() }, compact = true)
                        }
                    }
                }
                run {
                    Text(
                        if (yt.signedIn) "${vm.activeProfile.name}'s YouTube" + (yt.email?.let { " · $it" } ?: "")
                        else "Each profile signs in to their own YouTube",
                        color = TunerColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                yt.status?.let { Text(it, color = if (it.startsWith("✓")) TunerColors.Accent else TunerColors.Gold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                if (!yt.canSearch && !yt.signedIn) {
                    Text(
                        "YouTube search needs the YOUTUBE_KEY secret (see README › YouTube), or sign in to YouTube.",
                        color = TunerColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (code != null) {
                    Spacer(Modifier.height(10.dp))
                    YouTubeSignInCode(code.url, code.userCode, narrow) { yt.cancelSignIn() }
                }
            }
        }
        if (yt.searching) item(span = { GridItemSpan(maxLineSpan) }) { Header("Searching…", narrow) }
        if (yt.results.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header("Results for \"${yt.query}\"", narrow) }
            items(yt.results.size, key = { "r" + yt.results[it].id }) { i -> VideoCard(vm, yt.results[i], narrow) }
        }
        if (yt.signedIn) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(if (yt.feed.isEmpty() && yt.loadingFeed) "Loading your subscriptions…" else "From your subscriptions", narrow)
            }
            items(yt.feed.size, key = { "f" + yt.feed[it].id }) { i -> VideoCard(vm, yt.feed[i], narrow) }
        }
        if (!yt.signedIn && yt.results.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Search for anything, or sign in to YouTube to see the latest videos from the channels you subscribe to.",
                    color = TunerColors.TextSecondary, fontSize = 15.sp,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }

    if (typing) {
        TextInputDialog(
            title = "Search YouTube",
            initial = yt.query,
            label = "Search",
            onDismiss = { typing = false },
        ) {
            typing = false
            yt.search(it, kids)
        }
    }
}

/**
 * A kids' profile that only gets the channels a grown-up picked: their newest videos,
 * a button per channel, and no searching.
 */
@Composable
private fun KidsApprovedYouTube(vm: TunerViewModel, first: FocusRequester, narrow: Boolean) {
    val yt = vm.youtube
    val channels = vm.activeProfile.ytChannels.map { it.substringBefore('\t') to it.substringAfter('\t', "") }
    var only by remember { mutableStateOf<String?>(null) }
    val videos = yt.approvedFeed.filter { v -> only == null || v.channel == only }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (narrow) 160.dp else 250.dp),
        modifier = Modifier.fillMaxSize().skyBackground(),
        contentPadding = PaddingValues(start = if (narrow) 10.dp else 56.dp, end = if (narrow) 10.dp else 56.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = if (narrow) 12.dp else 28.dp)) {
                Text("▶ YouTube", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "all") { PillButton("All", { only = null }, Modifier.focusRequester(first), selected = only == null, compact = true) }
                    items(channels.size, key = { "c" + channels[it].first }) { i ->
                        val name = channels[i].second.ifBlank { "Channel ${i + 1}" }
                        PillButton(name, { only = name }, selected = only == name, compact = true)
                    }
                    if (vm.youTubeKidsPackage != null) item(key = "ytkids") { PillButton("YouTube Kids app", { vm.openYouTubeKids() }, compact = true) }
                }
            }
        }
        when {
            channels.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No channels yet – ask a grown-up to pick some in Settings › Profiles.", color = TunerColors.TextSecondary, fontSize = 16.sp)
            }
            videos.isEmpty() && yt.loadingApproved -> item(span = { GridItemSpan(maxLineSpan) }) { Header("Loading…", narrow) }
            videos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No videos yet.", color = TunerColors.TextSecondary, fontSize = 16.sp)
            }
            else -> items(videos.size, key = { "v" + videos[it].id }) { i -> VideoCard(vm, videos[i], narrow) }
        }
    }
}

/**
 * For a grown-up editing a kids' profile: find YouTube channels and tick the ones allowed.
 * [chosen] are "UC…id\tName" entries.
 */
@Composable
fun ApprovedChannelsPicker(vm: TunerViewModel, chosen: List<String>, onChange: (List<String>) -> Unit, onDone: () -> Unit) {
    val yt = vm.youtube
    var typing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        yt.clearChannelSearch()
        delay(80)
        runCatching { first.requestFocus() }
    }
    androidx.activity.compose.BackHandler { onDone() }
    val ids = chosen.map { it.substringBefore('\t') }.toSet()
    val narrow = narrowScreen()
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().skyBackground().padding(horizontal = if (narrow) 12.dp else 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
    ) {
        item {
            Text("YouTube channels they can watch", fontSize = if (narrow) 24.sp else 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (query.isBlank()) "🔍 Find a channel" else "🔍 $query", { typing = true }, Modifier.focusRequester(first), compact = true)
                PillButton("✓ Done", { onDone() }, compact = true)
            }
            yt.channelStatus?.let { Text(it, color = TunerColors.Gold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (yt.searchingChannels) Text("Searching…", color = TunerColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (chosen.isNotEmpty()) {
            item { Header("Allowed (${chosen.size}) – OK removes", narrow) }
            items(chosen.size, key = { "a" + chosen[it] }) { i ->
                val entry = chosen[i]
                SettingRow(entry.substringAfter('\t', entry), detail = "✓ Allowed", onClick = { onChange(chosen - entry) })
            }
        }
        if (yt.channelResults.isNotEmpty()) {
            item { Header("Found – OK allows", narrow) }
            items(yt.channelResults.size, key = { "r" + yt.channelResults[it].id }) { i ->
                val c = yt.channelResults[i]
                val on = c.id in ids
                SettingRow(
                    c.title,
                    detail = if (on) "✓ Allowed" else "Allow",
                    onClick = {
                        onChange(if (on) chosen.filterNot { it.startsWith(c.id + "\t") || it == c.id } else chosen + (c.id + "\t" + c.title.replace('\t', ' ')))
                    },
                )
            }
        }
    }
    if (typing) {
        TextInputDialog(
            title = "Find a YouTube channel",
            initial = query,
            label = "Channel name",
            onDone = {
                typing = false
                query = it
                yt.searchChannels(it)
            },
            onDismiss = { typing = false },
        )
    }
}

@Composable
private fun Header(text: String, narrow: Boolean) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun VideoCard(vm: TunerViewModel, v: YouTube.Video, narrow: Boolean) {
    FocusSurface(
        onClick = { vm.playYouTube(v) },
        baseColor = TunerColors.Panel,
        modifier = Modifier.padding(horizontal = if (narrow) 4.dp else 0.dp),
    ) { focused ->
        Column(Modifier.fillMaxWidth().padding(if (focused) 4.dp else 0.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0E12))) {
                if (v.thumb != null) AsyncImage(model = v.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(v.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Text(
                listOf(v.channel, YouTube.age(v.publishedAt)).filter { it.isNotBlank() }.joinToString("  ·  "),
                color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/** The "type this code on google.com/device" panel, with the bold reminder to tick YouTube. */
@Composable
private fun YouTubeSignInCode(url: String, userCode: String, narrow: Boolean, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val qr = remember(url) { PhoneSetupServer.qrCode(url, 300).asImageBitmap() }
        androidx.compose.foundation.Image(qr, contentDescription = "QR code for $url", modifier = Modifier.width(if (narrow) 110.dp else 150.dp).aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(18.dp))
        Column {
            Text("On your phone, go to", color = TunerColors.TextSecondary, fontSize = 14.sp)
            Text(url.removePrefix("https://").removePrefix("www."), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("and enter:", color = TunerColors.TextSecondary, fontSize = 14.sp)
            Text(
                userCode, color = Color.White, fontSize = if (narrow) 28.sp else 36.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TunerColors.Panel).padding(horizontal = 12.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFFFD66B), fontWeight = FontWeight.ExtraBold)) { append("⚠ IMPORTANT: ") }
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("tick the YouTube box (or press Select all) on Google's page.") }
                },
                color = Color.White, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFD66B)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            PillButton("Cancel", onCancel, compact = true)
        }
    }
}

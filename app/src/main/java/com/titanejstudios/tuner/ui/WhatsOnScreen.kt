package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.openStats
import com.titanejstudios.tuner.recordProgramme
import com.titanejstudios.tuner.recordingFor
import com.titanejstudios.tuner.stopRecording
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.offset
import com.titanejstudios.tuner.data.WhatsOn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.WatchStats
import kotlinx.coroutines.delay

/**
 * What's On: a slim strip about the highlighted show, one row of controls, then picture tiles –
 * "Tonight's best", "Starting soon" and a row per kind (or a grid for one kind) – or a timeline.
 */
@Composable
fun WhatsOnScreen(vm: TunerViewModel) {
    val result = vm.whatsOnResult
    val restorer = rememberFocusRestorer()
    var menuFor by remember { mutableStateOf<WhatsOn.Entry?>(null) }
    var pickDay by remember { mutableStateOf(false) }
    var pickKind by remember { mutableStateOf(false) }
    var timeline by remember { mutableStateOf(false) }
    RestoreFocusWhenClosed(menuFor != null || pickDay || pickKind, restorer)
    var focusedEntry by remember { mutableStateOf<WhatsOn.Entry?>(null) }
    val first = remember { FocusRequester() }
    val narrow = narrowScreen()
    LaunchedEffect(Unit) {
        vm.updateWhatsOn()
        delay(80)
        runCatching { first.requestFocus() }
    }
    // "Now" keeps up with the clock.
    LaunchedEffect(vm.now / 300_000) { if (vm.whatsOnMinute == null) vm.updateWhatsOn() }
    val main = remember(result) { result?.main.orEmpty() }
    val soon = remember(result) { result?.soon.orEmpty() }
    val channels = remember(result) { main.map { it.channel } }
    val shown = focusedEntry ?: soon.firstOrNull() ?: main.firstOrNull()
    val shownFilm = shown?.let { vm.filmLookups[it.programme.title.trim()] }
    val heroImage = shown?.let { pictureFor(vm, it) }
    val bgTop = rememberPictureTint(heroImage, vm.tintFromPicture)
    val context = androidx.compose.ui.platform.LocalContext.current

    // Rows: the best picks, then one per kind (with "Everything"), or a grid of one kind.
    val best = remember(result, vm.filmLookups.size) {
        main.filter { e ->
            val p = e.programme
            val rating = vm.filmLookups[p.title.trim()]?.rating ?: 0.0
            e.yourTeam || p.isPremiere || p.isNew || e.reason != null || rating >= 7.0 ||
                (com.titanejstudios.tuner.data.Sports.classify(p, e.channel.group) != null && com.titanejstudios.tuner.data.Sports.looksLive(p))
        }.take(16)
    }
    val byKind = remember(result) {
        main.groupBy { Genres.of(it.programme.category, it.programme.title, it.channel.group) ?: "Other" }
    }
    val kindOrder = listOf("Sport", "Film", "Drama", "Entertainment", "Factual", "Kids", "Music", "News", "Other")

    fun open(e: WhatsOn.Entry) {
        restorer.key = e.channel.id + "|" + e.programme.start
        menuFor = e
    }
    val onTile: (WhatsOn.Entry) -> Unit = { e ->
        val p = e.programme
        if (p.start <= vm.now && p.stop > vm.now) vm.watchFromWhatsOn(e.channel, channels) else vm.toggleReminder(e.channel, p)
    }
    val onFocus: (WhatsOn.Entry) -> Unit = { e ->
        focusedEntry = e
        vm.whatsOnFocused(e.channel, e.programme)
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, TunerColors.BackgroundBottom)))) {
        if (!narrow && heroImage != null) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.55f).height(230.dp)) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(heroImage).crossfade(400).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bgTop, bgTop.copy(alpha = 0.6f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to bgTop)))
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 44.dp, vertical = if (narrow) 10.dp else 18.dp)) {
            TopNav(vm, Screen.WhatsOn)
            if (!narrow) WhatsOnStrip(vm, shown, shownFilm, Modifier.fillMaxWidth().height(112.dp).clipToBounds())
            // One row of controls.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(vertical = 6.dp)) {
                item(key = "now") { PillButton("Now", { vm.whatsOnNow() }, Modifier.focusRequester(first), selected = vm.whatsOnMinute == null, compact = true) }
                item(key = "earlier") { PillButton("◀", { vm.stepWhatsOnTime(-1) }, compact = true) }
                item(key = "time") {
                    Text(hhmm(vm.whatsOnAt), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
                }
                item(key = "later") { PillButton("▶", { vm.stepWhatsOnTime(1) }, compact = true) }
                item(key = "nine") { PillButton("9pm", { vm.chooseWhatsOnTime(21 * 60) }, selected = vm.whatsOnMinute == 21 * 60, compact = true) }
                item(key = "day") { PillButton(WhatsOn.dayName(vm.whatsOnDay, vm.now) + " ▾", { pickDay = true }, compact = true) }
                item(key = "kind") { PillButton((vm.whatsOnGenre ?: "Everything") + " ▾", { pickKind = true }, selected = vm.whatsOnGenre != null, compact = true) }
                item(key = "fav") {
                    PillButton(if (vm.whatsOnFavouritesOnly) "★" else "☆", {
                        vm.whatsOnFavouritesOnly = !vm.whatsOnFavouritesOnly
                        vm.updateWhatsOn()
                    }, selected = vm.whatsOnFavouritesOnly, compact = true)
                }
                item(key = "tidy") { PillButton("🧹", { vm.toggleWhatsOnTidy() }, selected = vm.whatsOnTidy, compact = true) }
                item(key = "timeline") { PillButton("🕒 Timeline", { timeline = !timeline }, selected = timeline, compact = true) }
                if (!narrow) item(key = "preview") { PillButton("📺", { vm.toggleWhatsOnPreview() }, selected = vm.whatsOnPreview, compact = true) }
            }
            when {
                result == null -> CenterMessage("⟳  Looking through the guide…")
                soon.isEmpty() && main.isEmpty() -> CenterMessage(
                    if (vm.epg.isEmpty()) "No TV guide yet – it loads after the channels" else "Nothing found for that – try another time or kind"
                )
                timeline -> WhatsOnTimeline(vm, main, restorer, first, onFocus, onTile) { open(it) }
                else -> LazyColumn(Modifier.clipToBounds(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    var firstRow: String? = null
                    fun row(title: String, list: List<WhatsOn.Entry>) {
                        if (list.isEmpty()) return
                        val top = firstRow == null
                        if (top) firstRow = title
                        item(key = "h-$title") { WhatsOnSection(title) }
                        item(key = "r-$title") {
                            LazyRow(
                                // ▲ from the top row: back up to the buttons (Now, ◀ ▶, Day…).
                                modifier = if (top) Modifier.upTo(first) else Modifier,
                                horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                            ) {
                                items(list.size, key = { list[it].channel.id + "|" + list[it].programme.start }) { i ->
                                    val e = list[i]
                                    WhatsOnTile(vm, e, restorer, narrow, onFocused = { onFocus(e) }, onClick = { onTile(e) }, onLongClick = { open(e) })
                                }
                            }
                        }
                    }
                    val at = if (vm.whatsOnMinute == null) "now" else "at ${hhmm(vm.whatsOnAt)}"
                    if (vm.whatsOnGenre == null) {
                        row("Tonight's best · for you", best)
                        row("Starting soon", soon)
                        for (k in kindOrder) row(if (k == "Other") "Everything else $at" else "$k $at", byKind[k].orEmpty())
                    } else {
                        row("Starting soon", soon)
                        // One kind: a grid, a few to a row.
                        val per = if (narrow) 2 else 5
                        item(key = "h-grid") { WhatsOnSection("${vm.whatsOnGenre} $at · for you") }
                        val chunks = main.chunked(per)
                        items(chunks.size, key = { "g$it" }) { r ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp).then(if (r == 0 && soon.isEmpty()) Modifier.upTo(first) else Modifier),
                            ) {
                                chunks[r].forEach { e ->
                                    WhatsOnTile(vm, e, restorer, narrow, onFocused = { onFocus(e) }, onClick = { onTile(e) }, onLongClick = { open(e) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickDay) {
        MenuDialog(
            "Which day?",
            (0..6).map { d -> MenuItem(WhatsOn.dayName(d, vm.now), if (d == vm.whatsOnDay) "✓" else null) { vm.chooseWhatsOnDay(d) } },
            onDismiss = { pickDay = false },
        )
    }
    if (pickKind) {
        MenuDialog(
            "What kind?",
            (listOf<String?>(null) + Genres.all).map { g ->
                MenuItem(g ?: "Everything", if (g == vm.whatsOnGenre) "✓" else null) {
                    vm.whatsOnGenre = g
                    vm.updateWhatsOn()
                }
            },
            onDismiss = { pickKind = false },
        )
    }
    menuFor?.let { e ->
        val ch = e.channel
        val p = e.programme
        val t = System.currentTimeMillis()
        val items = buildList {
            if (p.start <= t) add(MenuItem("Watch ${ch.name}") { vm.watchFromWhatsOn(ch, channels) })
            if (p.start > t) add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel reminder" else "Remind me") { vm.toggleReminder(ch, p) })
            if (!vm.activeProfile.kids && p.stop > t) {
                val rec = vm.recordingFor(ch, p)
                if (rec == null) add(MenuItem("● Record") { vm.recordProgramme(ch, p) }) else add(MenuItem("Cancel recording") { vm.stopRecording(rec) })
                add(MenuItem(if (vm.seriesLinkFor(ch, p) != null) "Cancel series link" else "🔁 Series link (record every episode)") { vm.toggleSeriesLink(ch, p) })
            }
            if (Genres.of(p.category, p.title, ch.group) == "Film") add(MenuItem("Film details & trailer") { vm.openFilmInfo(p.title, false) })
            add(MenuItem(if (ch.id in vm.favouriteSet) "Remove channel from favourites" else "Add channel to favourites") { vm.toggleFavourite(ch.id) })
        }
        MenuDialog(p.title, items, onDismiss = { menuFor = null }, subtitle = "${ch.name} · ${dayLabel(p.start)} ${hhmm(p.start)} – ${hhmm(p.stop)}" + (p.desc?.let { "\n$it" } ?: ""))
    }
}

/**
 * ▲ inside this goes to [target] (the row of buttons) when nothing above it in here can take the
 * focus – lazy rows inside lazy lists don't always find their way up on their own.
 */
private fun Modifier.upTo(target: FocusRequester): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
    runCatching { target.requestFocus() }.isSuccess
}

/** The best picture for a show: the guide's own, a film's backdrop, or a TV show's from TMDB. */
private fun pictureFor(vm: TunerViewModel, e: WhatsOn.Entry): String? {
    val t = e.programme.title.trim()
    return e.programme.image ?: vm.filmLookups[t]?.backdrop ?: vm.showPictures[t]
}

@Composable
private fun WhatsOnSection(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

/** The NEW / PREMIERE / FINAL / JUST STARTED / LIVE / YOUR TEAM labels for a show. */
private fun badgesFor(e: WhatsOn.Entry, now: Long): List<Pair<String, Color>> = buildList {
    val p = e.programme
    if (e.yourTeam) add("YOUR TEAM" to Color(0xFF2E9E5B))
    // A live broadcast says so before it starts too ("Live: Champions League" in Starting soon).
    if (com.titanejstudios.tuner.data.Sports.classify(p, e.channel.group) != null && com.titanejstudios.tuner.data.Sports.looksLive(p)) add("LIVE" to Color(0xFFE5243B))
    if (p.isPremiere) add("PREMIERE" to Color(0xFF8E44AD))
    else if (p.isNew) add("NEW" to Color(0xFF1E88E5))
    if (p.isFinal) add("FINAL" to Color(0xFFD35400))
    if (p.start <= now && now - p.start < 10 * 60_000L) add("JUST STARTED" to Color(0xFF00897B))
}

@Composable
private fun Badge(text: String, colour: Color) {
    Text(
        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(colour).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** "S2 E5 · The Reckoning" / "★ 7.8 · 2019 · 2h 10m". */
private fun detailLine(e: WhatsOn.Entry, film: com.titanejstudios.tuner.integrations.Tmdb.Info?): String? {
    val p = e.programme
    val bits = ArrayList<String>()
    if (film != null) {
        com.titanejstudios.tuner.integrations.Tmdb.ratingText(film.rating)?.let { bits += it }
        film.year?.let { bits += it }
        film.runtimeMinutes?.takeIf { it > 0 }?.let { bits += if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
    }
    p.episodeLabel?.let { bits += it }
    p.episodeTitle?.let { bits += it }
    return bits.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

/** The slim strip at the top: the highlighted show, and the live preview beside it. */
@Composable
private fun WhatsOnStrip(vm: TunerViewModel, e: WhatsOn.Entry?, film: com.titanejstudios.tuner.integrations.Tmdb.Info?, modifier: Modifier) {
    Box(modifier) {
        if (e == null) return@Box
        val p = e.programme
        Column(Modifier.fillMaxWidth(0.6f).fillMaxHeight().padding(top = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                badgesFor(e, vm.now).forEach { (t, c) -> Badge(t, c) }
                e.reason?.let { Text(it.uppercase(), color = Color(0xFFFFB648), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(p.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
            Text(
                listOfNotNull(
                    e.channel.name, "${hhmm(p.start)} – ${hhmm(p.stop)}", detailLine(e, film),
                    e.next?.let { "then ${it.title}" }, e.alsoOn,
                ).joinToString("  ·  "),
                color = Color(0xFFD4DDF5), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(p.desc ?: film?.overview ?: "", color = Color(0xFFBFC9E8), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        }
        if (vm.whatsOnPreview && vm.mode != null) {
            MiniPreview(vm, Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(16f / 9f), focusable = false)
        }
    }
}

private val TILE_WIDE = 214.dp

/** A show as a picture tile: OK watches (on now) or reminds (later); ▶ records; hold OK for more. */
@Composable
private fun WhatsOnTile(
    vm: TunerViewModel,
    e: WhatsOn.Entry,
    restorer: FocusRestorer,
    narrow: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val p = e.programme
    val key = e.channel.id + "|" + p.start
    val now = vm.now
    val onNow = p.start <= now && p.stop > now
    val picture = pictureFor(vm, e)
    val film = vm.filmLookups[p.title.trim()]
    val width = if (narrow) 160.dp else TILE_WIDE
    FocusSurface(
        memoryKey = "wo|$key",
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        baseColor = TunerColors.Panel,
        zoom = true,
        modifier = Modifier.width(width).then(restorer.mod(key)).onPreviewKeyEvent { ev ->
            val code = ev.nativeKeyEvent.keyCode
            val record = code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY || code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                code == android.view.KeyEvent.KEYCODE_MEDIA_RECORD || code == android.view.KeyEvent.KEYCODE_PROG_RED
            if (record && !vm.activeProfile.kids) {
                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) vm.toggleRecordFromWhatsOn(e.channel, p)
                true
            } else false
        },
    ) { focused ->
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(Color(0x22FFFFFF))) {
                when {
                    picture != null -> AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    film?.poster != null -> AsyncImage(model = film.poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else -> ChannelLogo(e.channel.logo, e.channel.name, Modifier.fillMaxSize().padding(18.dp))
                }
                // The channel in the corner (when the picture isn't its logo).
                if (picture != null || film?.poster != null) {
                    Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(width = 52.dp, height = 28.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xCC0B0E12))) {
                        ChannelLogo(e.channel.logo, e.channel.name, Modifier.fillMaxSize().padding(2.dp))
                    }
                }
                Column(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    badgesFor(e, now).take(2).forEach { (t, c) -> Badge(t, c) }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (vm.hasReminder(e.channel, p)) Text("🔔", fontSize = 13.sp)
                    if (vm.recordingFor(e.channel, p) != null) Text("●", color = Color(0xFFFF4D4D), fontSize = 14.sp)
                }
                if (onNow) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(progressOf(p, now).coerceIn(0.02f, 1f)).fillMaxHeight().background(if (focused) Color.White else TunerColors.Accent))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(p.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val starts = if (p.start > now && p.start - now <= 60 * 60_000L) "in ${(p.start - now + 59_999) / 60_000} min" else null
                Text(
                    listOfNotNull(if (onNow) "until ${hhmm(p.stop)}" else hhmm(p.start), starts, film?.rating?.let { com.titanejstudios.tuner.integrations.Tmdb.ratingText(it) }, p.episodeLabel)
                        .joinToString("  ·  "),
                    color = if (focused) Color(0xEEFFFFFF) else TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 🕒 Timeline: what's on each channel for the next three hours, as bars. */
@Composable
private fun WhatsOnTimeline(
    vm: TunerViewModel,
    entries: List<WhatsOn.Entry>,
    restorer: FocusRestorer,
    up: FocusRequester,
    onFocus: (WhatsOn.Entry) -> Unit,
    onClick: (WhatsOn.Entry) -> Unit,
    onMenu: (WhatsOn.Entry) -> Unit,
) {
    val from = vm.whatsOnAt / (30 * 60_000L) * (30 * 60_000L)
    val span = 3 * 3_600_000L
    val to = from + span
    val chans = remember(entries) { entries.map { it.channel }.distinctBy { it.id }.take(60) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val logoW = 110.dp
        val barW = maxWidth - logoW - 12.dp
        LazyColumn(Modifier.clipToBounds(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            // Times along the top.
            item(key = "times") {
                Row(Modifier.padding(start = logoW + 12.dp)) {
                    for (h in 0 until 6) {
                        Text(hhmm(from + h * 30 * 60_000L), color = TunerColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(barW / 6))
                    }
                }
            }
            items(chans.size, key = { "t" + chans[it].id }) { i ->
                val c = chans[i]
                val progs = vm.programmesFor(c.id).filter { it.stop > from && it.start < to }
                Row(Modifier.height(48.dp).then(if (i == 0) Modifier.upTo(up) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(c.logo, c.name, Modifier.width(logoW).fillMaxHeight().padding(4.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(barW).fillMaxHeight()) {
                        progs.forEach { p ->
                            val s = maxOf(p.start, from)
                            val e2 = minOf(p.stop, to)
                            val x = barW * ((s - from).toFloat() / span)
                            val w = barW * ((e2 - s).toFloat() / span)
                            val entry = WhatsOn.Entry(c, p, null, null, null, false, c.id in vm.favouriteSet)
                            FocusSurface(
                                memoryKey = "wo|${c.id}|${p.start}",
                                onClick = { onClick(entry) },
                                onLongClick = { onMenu(entry) },
                                onFocused = { onFocus(entry) },
                                baseColor = if (p.start <= vm.now && p.stop > vm.now) Color(0x553D7BFF) else TunerColors.Panel,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.offset(x = x).width((w - 3.dp).coerceAtLeast(6.dp)).fillMaxHeight().then(restorer.mod(c.id + "|" + p.start)),
                            ) { focused ->
                                Text(
                                    p.title, color = if (focused) Color.White else Color(0xFFDDE4FA), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Watch-time stats for a profile: this week by day, top channels and kinds of programme. */
@Composable
fun StatsScreen(vm: TunerViewModel) {
    val s = vm.statsSummary
    val profile = vm.statsProfile ?: vm.activeProfile
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    val narrow = narrowScreen()
    val profilePills: @Composable () -> Unit = {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.profiles.size, key = { vm.profiles[it].id }) { i ->
                val p = vm.profiles[i]
                PillButton(p.name, { vm.openStats(p) }, if (i == 0) Modifier.focusRequester(first) else Modifier, selected = p.id == profile.id, compact = true)
            }
        }
    }
    Column(
        Modifier.fillMaxSize().skyBackground()
            .then(if (narrow) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(screenPadding(64.dp, 32.dp))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watch time", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Text((if (profile.kids) "🧸 " else "👤 ") + profile.name, color = TunerColors.TextSecondary, fontSize = 18.sp)
            if (!narrow) {
                Spacer(Modifier.weight(1f))
                profilePills()
            }
        }
        if (narrow && vm.profiles.size > 1) {
            Spacer(Modifier.height(8.dp))
            profilePills()
        }
        Text(
            if (profile.kids && vm.link.peers.isNotEmpty()) "The last 7 days on every linked device – including YouTube."
            else if (vm.isPhone) "Time spent watching in Flicker on this phone over the last 7 days."
            else "Time spent watching in Flicker on this TV over the last 7 days.",
            color = TunerColors.TextSecondary, fontSize = 15.sp,
        )
        Spacer(Modifier.height(16.dp))
        if (s == null) {
            CenterMessage("⟳")
            return@Column
        }
        val week: @Composable () -> Unit = {
            Text(WatchStats.hoursText(s.totalSeconds), color = Color.White, fontSize = if (narrow) 36.sp else 44.sp, fontWeight = FontWeight.Bold)
            val prev = s.previousWeekSeconds
            Text(
                when {
                    prev == 0L -> "this week"
                    s.totalSeconds >= prev -> "this week · up ${WatchStats.hoursText(s.totalSeconds - prev)} on the week before"
                    else -> "this week · down ${WatchStats.hoursText(prev - s.totalSeconds)} on the week before"
                },
                color = TunerColors.TextSecondary,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(20.dp))
            val max = (s.days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            val barHeight = if (narrow) 120f else 170f
            Row(
                Modifier.height(if (narrow) 170.dp else 220.dp).then(if (narrow) Modifier.fillMaxWidth() else Modifier),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = if (narrow) Arrangement.SpaceBetween else Arrangement.spacedBy(14.dp),
            ) {
                s.days.forEach { (label, secs) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(WatchStats.hoursText(secs), color = TunerColors.TextSecondary, fontSize = if (narrow) 10.sp else 12.sp, maxLines = 1)
                        Box(
                            Modifier.padding(vertical = 4.dp).width(if (narrow) 30.dp else 44.dp).height((barHeight * secs / max).coerceAtLeast(3f).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(TunerColors.Accent)
                        )
                        Text(label, color = Color.White, fontSize = if (narrow) 12.sp else 14.sp)
                    }
                }
            }
        }
        // Kids: what they actually watched, and what was stopped or blocked.
        val kidsExtras: @Composable () -> Unit = {
            if (profile.kids) {
                Spacer(Modifier.height(18.dp))
                StatList("What they watched", s.titles, 10)
                Spacer(Modifier.height(18.dp))
                Text("Stopped or blocked", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val blocked = vm.statsBlocked
                if (blocked.isEmpty()) Text("Nothing this week", color = TunerColors.TextSecondary, fontSize = 15.sp)
                blocked.take(8).forEach { (t, what, why) ->
                    Text(
                        java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.UK).format(java.util.Date(t)) + "  ·  $what  ·  $why",
                        color = TunerColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!vm.activeProfile.kids) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { m -> PillButton("🎁 +$m min today", { vm.sendBonusTime(profile, m) }, compact = true) }
                    }
                }
            }
        }
        if (narrow) {
            week()
            Spacer(Modifier.height(24.dp))
            StatList("Top channels", s.channels)
            Spacer(Modifier.height(18.dp))
            StatList("Kinds of programme", s.genres)
            kidsExtras()
            Spacer(Modifier.height(24.dp))
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    week()
                    kidsExtras()
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.width(330.dp)) {
                    StatList("Top channels", s.channels)
                    Spacer(Modifier.height(18.dp))
                    StatList("Kinds of programme", s.genres)
                    if (!profile.kids && s.titles.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        StatList("Most watched", s.titles)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatList(title: String, rows: List<Pair<String, Long>>, show: Int = 6) {
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (rows.isEmpty()) Text("Nothing yet", color = TunerColors.TextSecondary, fontSize = 15.sp)
    rows.take(show).forEach { (name, secs) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(name, color = TunerColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(WatchStats.hoursText(secs), color = Color.White, fontSize = 15.sp)
        }
    }
}

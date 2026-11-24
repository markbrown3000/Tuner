package com.titanejstudios.tuner.ui

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
 * What's On: any day this week and any time (◀ ▶ half an hour at a time), "for you" order,
 * shows starting soon, and the highlighted show's details – with its channel live in the corner.
 */
@Composable
fun WhatsOnScreen(vm: TunerViewModel) {
    val result = vm.whatsOnResult
    val restorer = rememberFocusRestorer()
    var menuFor by remember { mutableStateOf<WhatsOn.Entry?>(null) }
    RestoreFocusWhenClosed(menuFor != null, restorer)
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
    val nowList = remember(result) { result?.main.orEmpty() }
    val channels = remember(result) { nowList.map { it.channel } }
    val shown = focusedEntry ?: result?.soon?.firstOrNull() ?: nowList.firstOrNull()
    val shownFilm = shown?.let { vm.filmLookups[it.programme.title.trim()] }
    val heroImage = shownFilm?.backdrop ?: shown?.programme?.image
    val bgTop = rememberPictureTint(heroImage, vm.tintFromPicture)
    val context = androidx.compose.ui.platform.LocalContext.current

    fun open(e: WhatsOn.Entry) {
        restorer.key = e.channel.id + "|" + e.programme.start
        menuFor = e
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, TunerColors.BackgroundBottom)))) {
        // The highlighted show's picture, top right, fading into the background.
        if (!narrow && heroImage != null) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).height(330.dp)) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(heroImage).crossfade(400).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bgTop, bgTop.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to bgTop)))
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 44.dp, vertical = if (narrow) 10.dp else 22.dp)) {
            TopNav(vm, Screen.WhatsOn)
            if (!narrow) {
                Spacer(Modifier.height(6.dp))
                WhatsOnHero(vm, shown, shownFilm, Modifier.fillMaxWidth().height(170.dp))
            }
            Spacer(Modifier.height(6.dp))
            // When: Now, ◀ time ▶, the days of the week.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(vertical = 4.dp)) {
                item(key = "now") { PillButton("Now", { vm.whatsOnNow() }, Modifier.focusRequester(first), selected = vm.whatsOnMinute == null, compact = true) }
                item(key = "earlier") { PillButton("◀", { vm.stepWhatsOnTime(-1) }, compact = true) }
                item(key = "time") {
                    Text(
                        if (vm.whatsOnMinute == null) "Now · " + hhmm(vm.now) else hhmm(vm.whatsOnAt),
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                item(key = "later") { PillButton("▶", { vm.stepWhatsOnTime(1) }, compact = true) }
                item(key = "nine") { PillButton("9pm", { vm.chooseWhatsOnTime(21 * 60) }, selected = vm.whatsOnMinute == 21 * 60, compact = true) }
                items(7, key = { "day$it" }) { d ->
                    PillButton(WhatsOn.dayName(d, vm.now), { vm.chooseWhatsOnDay(d) }, selected = vm.whatsOnDay == d && (d > 0 || vm.whatsOnMinute != null), compact = true)
                }
            }
            // What: kinds, favourites, tidy, preview.
            val genres = listOf<String?>(null) + Genres.all
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(genres.size, key = { "genre" + (genres[it] ?: "all") }) { i ->
                    val g = genres[i]
                    PillButton(
                        g ?: "Everything",
                        {
                            vm.whatsOnGenre = g
                            vm.updateWhatsOn()
                        },
                        selected = g == vm.whatsOnGenre,
                        compact = true,
                    )
                }
                item(key = "favonly") {
                    PillButton(
                        if (vm.whatsOnFavouritesOnly) "★ Favourites only" else "☆ All channels",
                        {
                            vm.whatsOnFavouritesOnly = !vm.whatsOnFavouritesOnly
                            vm.updateWhatsOn()
                        },
                        compact = true,
                    )
                }
                item(key = "tidy") {
                    PillButton(if (vm.whatsOnTidy) "🧹 Tidy" else "Showing every channel", { vm.toggleWhatsOnTidy() }, selected = vm.whatsOnTidy, compact = true)
                }
                if (!narrow) item(key = "preview") {
                    PillButton(if (vm.whatsOnPreview) "📺 Preview on" else "📺 Preview off", { vm.toggleWhatsOnPreview() }, selected = vm.whatsOnPreview, compact = true)
                }
                if (vm.catchupChannels.isNotEmpty()) item(key = "catchup") {
                    PillButton("⏪ Catch-up", { vm.navigate(Screen.Catchup) }, compact = true)
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                result == null -> CenterMessage("⟳  Looking through the guide…")
                result.soon.isEmpty() && result.main.isEmpty() -> CenterMessage(
                    if (vm.epg.isEmpty()) "No TV guide yet – it loads after the channels" else "Nothing found for that – try another time or kind"
                )
                else -> LazyColumn(Modifier.widthIn(max = 1200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (result.soon.isNotEmpty()) {
                        item(key = "h-soon") { WhatsOnSection("Starting soon") }
                        items(result.soon.size, key = { "s" + result.soon[it].channel.id + "|" + result.soon[it].programme.start }) { i ->
                            val e = result.soon[i]
                            WhatsOnRow(
                                vm, e, restorer,
                                onFocused = {
                                    focusedEntry = e
                                    vm.whatsOnFocused(e.channel, e.programme)
                                },
                                // One press: remind me (or cancel it).
                                onClick = { vm.toggleReminder(e.channel, e.programme) },
                                onLongClick = { open(e) },
                            )
                        }
                    }
                    if (nowList.isNotEmpty()) {
                        item(key = "h-main") {
                            WhatsOnSection(
                                when {
                                    vm.whatsOnMinute == null -> "On now · for you"
                                    else -> "On at ${hhmm(vm.whatsOnAt)} " + WhatsOn.dayName(vm.whatsOnDay, vm.now).let { if (vm.whatsOnDay <= 1) it.lowercase() else "on $it" } + " · for you"
                                }
                            )
                        }
                    }
                    items(nowList.size, key = { "m" + nowList[it].channel.id + "|" + nowList[it].programme.start }) { i ->
                        val e = nowList[i]
                        val onNow = e.programme.start <= vm.now && e.programme.stop > vm.now
                        WhatsOnRow(
                            vm, e, restorer,
                            onFocused = {
                                focusedEntry = e
                                vm.whatsOnFocused(e.channel, e.programme)
                            },
                            onClick = { if (onNow) vm.watchFromWhatsOn(e.channel, channels) else open(e) },
                            onLongClick = { open(e) },
                        )
                    }
                }
            }
        }
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

@Composable
private fun WhatsOnSection(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

/** The NEW / PREMIERE / FINAL / JUST STARTED / YOUR TEAM labels for a show. */
private fun badgesFor(e: WhatsOn.Entry, now: Long): List<Pair<String, Color>> = buildList {
    val p = e.programme
    if (e.yourTeam) add("YOUR TEAM" to Color(0xFF2E9E5B))
    if (com.titanejstudios.tuner.data.Sports.classify(p, e.channel.group) != null && com.titanejstudios.tuner.data.Sports.looksLive(p) && p.start <= now) add("LIVE" to Color(0xFFE5243B))
    if (p.isPremiere) add("PREMIERE" to Color(0xFF8E44AD))
    else if (p.isNew) add("NEW" to Color(0xFF1E88E5))
    if (p.isFinal) add("FINAL" to Color(0xFFD35400))
    if (p.start <= now && now - p.start < 10 * 60_000L) add("JUST STARTED" to Color(0xFF00897B))
}

@Composable
private fun Badge(text: String, colour: Color) {
    Text(
        text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(colour).padding(horizontal = 6.dp, vertical = 1.dp),
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

/** The big area at the top: everything about the highlighted show, and the live preview. */
@Composable
private fun WhatsOnHero(vm: TunerViewModel, e: WhatsOn.Entry?, film: com.titanejstudios.tuner.integrations.Tmdb.Info?, modifier: Modifier) {
    Box(modifier) {
        if (e == null) return@Box
        val p = e.programme
        Column(Modifier.fillMaxWidth(0.56f).fillMaxHeight()) {
            val badges = badgesFor(e, vm.now)
            val label = e.reason?.uppercase()
            if (badges.isNotEmpty() || label != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    badges.forEach { (t, c) -> Badge(t, c) }
                    label?.let { Text(it, color = Color(0xFFFFB648), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(p.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${e.channel.name}  ·  ${dayLabel(p.start)} ${hhmm(p.start)} – ${hhmm(p.stop)}" + (detailLine(e, film)?.let { "  ·  $it" } ?: ""),
                color = Color(0xFFD4DDF5), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            AutoScrollText(p.desc ?: film?.overview ?: "", Modifier.weight(1f).fillMaxWidth(), fontSize = 15.sp)
            val after = listOfNotNull(e.next?.let { "Next: ${it.title} at ${hhmm(it.start)}" }, e.alsoOn)
            if (after.isNotEmpty()) Text(after.joinToString("   ·   "), color = TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            vm.whatsOnPreview && vm.mode != null -> MiniPreview(vm, Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(16f / 9f))
            film?.poster != null -> AsyncImage(
                model = film.poster, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
            )
            p.image == null -> ChannelLogo(e.channel.logo, e.channel.name, Modifier.align(Alignment.TopEnd).padding(top = 12.dp).size(width = 200.dp, height = 100.dp))
        }
    }
}

@Composable
private fun WhatsOnRow(
    vm: TunerViewModel,
    e: WhatsOn.Entry,
    restorer: FocusRestorer,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val p = e.programme
    val key = e.channel.id + "|" + p.start
    val now = vm.now
    val onNow = p.start <= now && p.stop > now
    val film = vm.filmLookups[p.title.trim()]
    val narrow = narrowScreen()
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        baseColor = TunerColors.Panel,
        modifier = Modifier.fillMaxWidth().height(if (narrow) 84.dp else 78.dp).then(restorer.mod(key)),
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF))) {
                when {
                    film?.poster != null -> Row(Modifier.fillMaxSize()) {
                        AsyncImage(model = film.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f))
                        ChannelLogo(e.channel.logo, e.channel.name, Modifier.fillMaxSize().padding(4.dp))
                    }
                    p.image != null -> AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else -> ChannelLogo(e.channel.logo, e.channel.name, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    badgesFor(e, now).take(if (narrow) 1 else 3).forEach { (t, c) -> Badge(t, c) }
                    if (!narrow) GenreChip(p.category)
                }
                val starts = if (p.start > now && p.start - now <= 60 * 60_000L) "in ${(p.start - now + 59_999) / 60_000} min" else null
                Text(
                    listOfNotNull(
                        e.channel.name,
                        "${hhmm(p.start)} – ${hhmm(p.stop)}",
                        starts,
                        detailLine(e, film),
                        e.alsoOn,
                        if (narrow) null else p.desc,
                    ).joinToString("  ·  "),
                    color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = if (narrow) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onNow) ProgressBar(progressOf(p, now), Modifier.padding(top = 5.dp).width(if (narrow) 120.dp else 220.dp), color = if (focused) Color.White else TunerColors.Accent)
            }
            if (vm.hasReminder(e.channel, p)) Text("🔔", fontSize = 18.sp)
            if (vm.recordingFor(e.channel, p) != null) Text("  ●", color = TunerColors.NowLine, fontSize = 18.sp)
            if (p.start > now && focused && !narrow) Text(if (vm.hasReminder(e.channel, p)) "  OK: cancel reminder" else "  OK: remind me", color = Color.White, fontSize = 13.sp)
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
            if (vm.isPhone) "Time spent watching in Flicker on this phone over the last 7 days."
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
        if (narrow) {
            week()
            Spacer(Modifier.height(24.dp))
            StatList("Top channels", s.channels)
            Spacer(Modifier.height(18.dp))
            StatList("Kinds of programme", s.genres)
            Spacer(Modifier.height(24.dp))
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) { week() }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.width(330.dp)) {
                    StatList("Top channels", s.channels)
                    Spacer(Modifier.height(18.dp))
                    StatList("Kinds of programme", s.genres)
                }
            }
        }
    }
}

@Composable
private fun StatList(title: String, rows: List<Pair<String, Long>>) {
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (rows.isEmpty()) Text("Nothing yet", color = TunerColors.TextSecondary, fontSize = 15.sp)
    rows.take(6).forEach { (name, secs) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(name, color = TunerColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(WatchStats.hoursText(secs), color = Color.White, fontSize = 15.sp)
        }
    }
}

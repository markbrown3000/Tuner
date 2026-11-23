package com.titanejstudios.tuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.FAVOURITES
import com.titanejstudios.tuner.RECENT
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.apps.TvApp
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.ResumeEntry
import kotlinx.coroutines.delay

/** What the big area at the top describes – whatever tile is highlighted. */
private class HeroInfo(
    val title: String,
    val subtitle: String,
    val description: String?,
    val channel: Channel?,
    val image: String?,
)

private enum class HomeRow(val title: String) {
    Recent("Continue watching"),
    Plex("Continue on Plex"),
    TopPicks("Top picks for you"),
    Favourites("Favourites"),
    Sport("Sport on now"),
    Apps("Your apps"),
    Recordings("Recordings"),
    Resume("Carry on watching"),
    NewEpisodes("New episodes"),
    MyList("My list"),
    All("All channels"),
}

/** Sky-style home: menu bar, a big "now" area with the live picture, and rows of tiles. */
@Composable
fun HomeScreen(vm: TunerViewModel) {
    BackHandler(enabled = vm.mode != null && !vm.canGoBack) { vm.stopPlayback() }
    if (vm.activeProfile.kids) {
        KidsHome(vm)
        return
    }

    val now = vm.now
    val recent = remember(vm.recents, vm.channels, vm.unlocked) { vm.channelsFor(RECENT).take(15) }
    val favourites = remember(vm.favourites, vm.channels, vm.unlocked) { vm.channelsFor(FAVOURITES).take(30) }
    val allChannels = remember(vm.channels, vm.unlocked, vm.lockedGroups) { vm.channelsFor(ALL) }
    // Worked out in the background by the view model (it looks at every channel's guide).
    val sportNow = vm.sportNow
    val topPicks = vm.topPicks
    val apps = remember(vm.tvApps, vm.hiddenApps) { vm.visibleApps() }
    val recordings = remember(vm.recordings) {
        vm.recordings.filter { it.status == RecStatus.DONE || it.status == RecStatus.RECORDING }
            .sortedByDescending { it.start }.take(15)
    }
    val resumeEntries = remember(vm.resume) { vm.resume.values.sortedByDescending { it.updatedAt }.take(15) }
    val newEpisodes = remember(vm.followedSeries) { vm.newEpisodeSeries.map { it.series to "${it.newEpisodes} new" } }
    val myList = remember(vm.watchlist) { vm.watchlist.take(30).map { it to (if (it.isSeries) "Series" else "Film") } }

    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    fun showChannel(c: Channel) {
        val (cur, next) = vm.nowNext(c.id, System.currentTimeMillis())
        hero = HeroInfo(
            title = cur?.title ?: c.name,
            subtitle = buildString {
                append(c.name)
                if (cur != null) append("  ·  ${hhmm(cur.start)} – ${hhmm(cur.stop)}")
                if (next != null) append("  ·  Next: ${next.title}")
            },
            description = cur?.desc,
            channel = c,
            image = cur?.image,
        )
    }
    fun showPick(p: HomeFeed.Pick) {
        val pr = p.programme
        hero = HeroInfo(
            title = pr.title,
            subtitle = "${p.channel.name}  ·  " +
                (if (pr.start > System.currentTimeMillis()) "Starts ${hhmm(pr.start)}" else "On now, until ${hhmm(pr.stop)}"),
            description = pr.desc,
            channel = p.channel,
            image = pr.image,
        )
    }

    val firstTile = remember { FocusRequester() }
    LaunchedEffect(recent.isNotEmpty() || favourites.isNotEmpty() || allChannels.isNotEmpty()) {
        delay(80)
        runCatching { firstTile.requestFocus() }
    }

    // Rows – only the non-empty ones are shown.
    val rows = buildList {
        if (recent.isNotEmpty()) add(HomeRow.Recent)
        if (vm.plexItems.isNotEmpty()) add(HomeRow.Plex)
        if (topPicks.isNotEmpty()) add(HomeRow.TopPicks)
        if (favourites.isNotEmpty()) add(HomeRow.Favourites)
        if (sportNow.isNotEmpty()) add(HomeRow.Sport)
        if (apps.isNotEmpty()) add(HomeRow.Apps)
        if (recordings.isNotEmpty()) add(HomeRow.Recordings)
        if (resumeEntries.isNotEmpty() && vm.hasVod) add(HomeRow.Resume)
        if (newEpisodes.isNotEmpty() && vm.hasVod) add(HomeRow.NewEpisodes)
        if (myList.isNotEmpty() && vm.hasVod) add(HomeRow.MyList)
        if (allChannels.isNotEmpty()) add(HomeRow.All)
    }

    Box(Modifier.fillMaxSize().skyBackground()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 22.dp)) {
            TopNav(vm, Screen.Home)
            Spacer(Modifier.height(10.dp))
            if (vm.safeMode && !vm.safeModeBannerHidden) {
                SafeModeBanner(vm)
                Spacer(Modifier.height(10.dp))
            }

            // Hero
            Row(Modifier.fillMaxWidth().height(250.dp)) {
                Column(Modifier.weight(1f).padding(top = 12.dp, end = 24.dp)) {
                    val h = hero
                    Text(
                        h?.title ?: vm.playing?.name ?: if (vm.status != null || !vm.initialLoadDone) "Getting things ready…" else "Welcome",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        vm.status?.let { "⟳ $it" } ?: h?.subtitle ?: "${allChannels.size} channels",
                        color = TunerColors.TextSecondary,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        h?.description ?: "",
                        color = Color(0xFFD5DCF2),
                        fontSize = 16.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(width = 420.dp, height = 236.dp).clip(RoundedCornerShape(14.dp)).background(TunerColors.Panel),
                    contentAlignment = Alignment.Center,
                ) {
                    val h = hero
                    when {
                        vm.mode != null -> MiniPreview(vm, Modifier.fillMaxSize())
                        h?.image != null -> AsyncImage(model = h.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        h?.channel != null -> ChannelLogo(h.channel.logo, h.channel.name, Modifier.size(width = 220.dp, height = 130.dp))
                        else -> Text("📺", fontSize = 64.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (rows.isEmpty()) {
                val status = vm.status
                when {
                    status != null || !vm.initialLoadDone -> CenterMessage(
                        "⟳  " + (status ?: "Loading your channels…") +
                            "\n\nThe first load after an update can take a minute or two."
                    )
                    vm.error != null -> CenterMessage(vm.error ?: "")
                    vm.sources.isEmpty() -> CenterMessage("No channels yet – add a playlist in Settings")
                    else -> CenterMessage("No channels to show – check Settings › Playlists")
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 30.dp),
                ) {
                    rows.forEachIndexed { i, row ->
                        item(key = row.name) {
                            Column {
                                Text(row.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                                val m = if (i == 0) Modifier.focusRequester(firstTile) else Modifier
                                when (row) {
                                    HomeRow.Recent -> ChannelRail(vm, recent, m, ::showChannel)
                                    HomeRow.TopPicks -> PickRail(vm, topPicks, m, ::showPick)
                                    HomeRow.Plex -> PlexRail(vm, vm.plexItems, m) { p ->
                                        hero = HeroInfo(p.title, listOfNotNull(p.subtitle, "on Plex").joinToString("  ·  "), null, null, p.image)
                                    }
                                    HomeRow.Apps -> AppRail(vm, apps, m) { a ->
                                        hero = HeroInfo(a.label, "Open ${a.label}  ·  hold OK to hide it from Home", null, null, null)
                                    }
                                    HomeRow.Favourites -> ChannelRail(vm, favourites, m, ::showChannel)
                                    HomeRow.Sport -> ChannelRail(vm, sportNow, m, ::showChannel, badge = "LIVE")
                                    HomeRow.Recordings -> RecordingRail(vm, recordings, m) { r ->
                                        hero = HeroInfo(r.title, "${r.channelName} · ${dayLabel(r.start)} ${hhmm(r.start)}", null, null, null)
                                    }
                                    HomeRow.Resume -> ResumeRail(vm, resumeEntries, m) { e ->
                                        hero = HeroInfo(e.title, e.subtitle ?: "", null, null, e.poster)
                                    }
                                    HomeRow.NewEpisodes -> WatchRail(vm, newEpisodes, m) { w ->
                                        hero = HeroInfo(w.name, "New episodes to watch", w.plot, null, w.image)
                                    }
                                    HomeRow.MyList -> WatchRail(vm, myList, m) { w ->
                                        hero = HeroInfo(w.name, (if (w.isSeries) "Series" else "Film") + "  ·  ${w.category}  ·  hold OK to remove", w.plot, null, w.image)
                                    }
                                    HomeRow.All -> ChannelRail(vm, allChannels.take(40), m, ::showChannel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRail(
    vm: TunerViewModel,
    channels: List<Channel>,
    firstModifier: Modifier,
    onFocus: (Channel) -> Unit,
    badge: String? = null,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(channels.size, key = { channels[it].id }) { i ->
            val c = channels[i]
            ChannelTile(
                vm = vm,
                channel = c,
                onClick = { vm.play(c, channels) },
                onFocused = { onFocus(c) },
                badge = badge,
                modifier = if (i == 0) firstModifier else Modifier,
            )
        }
    }
}

@Composable
private fun RecordingRail(vm: TunerViewModel, list: List<Recording>, firstModifier: Modifier, onFocus: (Recording) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val r = list[i]
            FocusSurface(
                onClick = { vm.playRecording(r) },
                onFocused = { onFocus(r) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { focused ->
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        if (r.status == RecStatus.RECORDING) "● Recording now" else "${dayLabel(r.start)} ${hhmm(r.start)}",
                        color = if (r.status == RecStatus.RECORDING) TunerColors.NowLine else TunerColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(r.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.channelName, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Films and series (My list, New episodes) as wide picture tiles. */
@Composable
private fun WatchRail(
    vm: TunerViewModel,
    list: List<Pair<WatchItem, String>>,
    firstModifier: Modifier,
    onFocus: (WatchItem) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list.size, key = { list[it].first.id }) { i ->
            val (w, badge) = list[i]
            FocusSurface(
                onClick = { vm.openWatchItem(w) },
                onLongClick = {
                    if (w.isSeries) w.toSeries()?.let { vm.toggleWatchlist(it) } else w.toVod()?.let { vm.toggleWatchlist(it) }
                },
                onFocused = { onFocus(w) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { _ ->
                if (w.image != null) {
                    AsyncImage(model = w.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD050816)))))
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(6.dp)).background(TunerColors.Accent).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    w.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
                )
            }
        }
    }
}

/** Shown on Home after Tuner restarted itself in safe mode. */
@Composable
private fun SafeModeBanner(vm: TunerViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF5A3A0A)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Safe mode – Tuner closed unexpectedly, so the newer extras are switched off", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                vm.lastCrash?.let { "Last problem: $it" } ?: "Night mode, lip sync, frame-rate matching, Live tab, Plex and scores are off for now.",
                color = Color(0xFFFFE2B0),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        PillButton("Check everything", { vm.openSelfTest() }, compact = true)
        Spacer(Modifier.width(8.dp))
        PillButton("Turn off", { vm.leaveSafeMode() }, compact = true)
        Spacer(Modifier.width(8.dp))
        PillButton("Hide", { vm.safeModeBannerHidden = true }, compact = true)
    }
}

@Composable
private fun ResumeRail(vm: TunerViewModel, list: List<ResumeEntry>, firstModifier: Modifier, onFocus: (ResumeEntry) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list.size, key = { list[it].contentId }) { i ->
            val e = list[i]
            FocusSurface(
                onClick = { vm.playResume(e) },
                onFocused = { onFocus(e) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { _ ->
                if (e.poster != null) {
                    AsyncImage(model = e.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD050816)))))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
                    Text(e.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.durationMs > 0) ProgressBar(e.positionMs.toFloat() / e.durationMs, Modifier.padding(top = 4.dp).fillMaxWidth())
                }
            }
        }
    }
}

/** "Top picks": programme pictures where the guide has them, otherwise the channel logo. */
@Composable
private fun PickRail(vm: TunerViewModel, picks: List<HomeFeed.Pick>, firstModifier: Modifier, onFocus: (HomeFeed.Pick) -> Unit) {
    val channels = remember(picks) { picks.map { it.channel } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(picks.size, key = { picks[it].channel.id + "|" + picks[it].programme.start }) { i ->
            val pick = picks[i]
            val p = pick.programme
            val upcoming = p.start > vm.now
            FocusSurface(
                onClick = {
                    if (upcoming && p.start - vm.now > 2 * 60_000L) vm.toggleReminder(pick.channel, p)
                    else vm.play(pick.channel, channels)
                },
                onFocused = { onFocus(pick) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { focused ->
                if (p.image != null) {
                    AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    ChannelLogo(pick.channel.logo, pick.channel.name, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(width = 110.dp, height = 60.dp))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (focused) Color(0x88000000) else Color(0xDD050816)))))
                Text(
                    if (upcoming) "Starts ${hhmm(p.start)}" else "ON NOW",
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA050816), RoundedCornerShape(bottomEnd = 8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (upcoming) Color.White else TunerColors.NowLine,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(p.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pick.channel.name, color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AppRail(vm: TunerViewModel, apps: List<TvApp>, firstModifier: Modifier, onFocus: (TvApp) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(apps.size, key = { apps[it].packageName }) { i ->
            AppTile(vm, apps[i], (if (i == 0) firstModifier else Modifier).size(width = 192.dp, height = 108.dp)) { onFocus(apps[i]) }
        }
    }
}

/** An app's TV banner (or its icon and name). OK opens it; holding OK hides/shows it on Home. */
@Composable
fun AppTile(vm: TunerViewModel, app: TvApp, modifier: Modifier, dimmed: Boolean = false, onFocus: () -> Unit = {}) {
    FocusSurface(
        onClick = { vm.launchApp(app) },
        onLongClick = { vm.toggleHideApp(app) },
        onFocused = onFocus,
        baseColor = Color(0xFF1B2440),
        shape = RoundedCornerShape(12.dp),
        zoom = true,
        modifier = modifier,
    ) { focused ->
        val banner = app.banner
        val icon = app.icon
        if (banner != null) {
            Image(bitmap = banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(6.dp))
                Text(app.label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (dimmed) Box(Modifier.fillMaxSize().background(Color(0xAA000000)))
        if (focused && banner != null) {
            Text(
                app.label,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC050816)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** A simple, big-tile home for kids profiles: their channels, and how much TV time is left today. */
@Composable
private fun KidsHome(vm: TunerViewModel) {
    val channels = remember(vm.channels, vm.unlocked, vm.activeProfile) { vm.channelsFor(ALL) }
    val first = remember { FocusRequester() }
    LaunchedEffect(channels.isNotEmpty()) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().skyBackground()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 22.dp)) {
            TopNav(vm, Screen.Home)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hi ${vm.activeProfile.name}! 🧸", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val left = vm.timeLeftSeconds()
                if (left != Long.MAX_VALUE) {
                    val mins = (left.coerceAtLeast(0) + 59) / 60
                    Text(
                        if (mins <= 0) "No TV time left today" else "⏱ $mins minute${if (mins == 1L) "" else "s"} of TV left today",
                        color = if (mins <= 10) Color(0xFFFFC857) else TunerColors.TextSecondary,
                        fontSize = 20.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (channels.isEmpty()) {
                CenterMessage(if (!vm.initialLoadDone || vm.status != null) "⟳  Loading channels…" else "No channels picked for this profile yet – ask a grown-up to choose some in Settings › Profiles")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(channels.size, key = { channels[it].id }) { i ->
                        val c = channels[i]
                        ChannelTile(
                            vm = vm,
                            channel = c,
                            onClick = { vm.play(c, channels) },
                            onFocused = {},
                            modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).size(width = 270.dp, height = 160.dp),
                        )
                    }
                }
            }
        }
    }
}

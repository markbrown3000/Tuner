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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.WatchStats
import kotlinx.coroutines.delay

/** "What's on": now, later today or tomorrow night – all channels or one kind (Film, Sport…). */
@Composable
fun WhatsOnScreen(vm: TunerViewModel) {
    val slots = remember(vm.now / 600_000) { vm.whatsOnSlots }
    val results = vm.whatsOnResults
    val restorer = rememberFocusRestorer()
    var menuFor by remember { mutableStateOf<HomeFeed.Pick?>(null) }
    RestoreFocusWhenClosed(menuFor != null, restorer)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.updateWhatsOn()
        delay(80)
        runCatching { first.requestFocus() }
    }
    val slotIndex = vm.whatsOnSlot.coerceIn(0, slots.lastIndex)
    val channels = remember(results) { results.orEmpty().map { it.channel } }

    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = if (narrowScreen()) 12.dp else 44.dp, vertical = if (narrowScreen()) 10.dp else 22.dp)) {
        TopNav(vm, Screen.WhatsOn)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(slots.size, key = { "slot" + slots[it].label }) { i ->
                PillButton(
                    slots[i].label,
                    {
                        vm.whatsOnSlot = i
                        vm.updateWhatsOn()
                    },
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    selected = i == slotIndex,
                    compact = true,
                )
            }
            if (vm.catchupChannels.isNotEmpty()) item(key = "catchup") {
                PillButton("⏪ Catch-up", { vm.navigate(Screen.Catchup) }, compact = true)
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
        }
        val genres = listOf<String?>(null) + Genres.all
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
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
        }
        Spacer(Modifier.height(8.dp))
        when {
            results == null -> CenterMessage("⟳  Looking through the guide…")
            results.isEmpty() -> CenterMessage(
                if (vm.epg.isEmpty()) "No TV guide yet – it loads after the channels" else "Nothing found for that – try another time or kind"
            )
            else -> LazyColumn(Modifier.widthIn(max = 1200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results.size, key = { results[it].channel.id + "|" + results[it].programme.start }) { i ->
                    val pick = results[i]
                    val p = pick.programme
                    val key = pick.channel.id + "|" + p.start
                    val onNow = p.start <= vm.now && p.stop > vm.now
                    FocusSurface(
                        onClick = {
                            if (onNow) vm.play(pick.channel, channels)
                            else {
                                restorer.key = key
                                menuFor = pick
                            }
                        },
                        onLongClick = {
                            restorer.key = key
                            menuFor = pick
                        },
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(78.dp).then(restorer.mod(key)),
                    ) { focused ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF))) {
                                if (p.image != null) {
                                    AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else {
                                    ChannelLogo(pick.channel.logo, pick.channel.name, Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(10.dp))
                                    GenreChip(p.category)
                                }
                                Text(
                                    "${pick.channel.name}  ·  ${hhmm(p.start)} – ${hhmm(p.stop)}" + (p.desc?.let { "  ·  $it" } ?: ""),
                                    color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (onNow) ProgressBar(progressOf(p, vm.now), Modifier.padding(top = 5.dp).width(if (narrowScreen()) 120.dp else 220.dp), color = if (focused) Color.White else TunerColors.Accent)
                            }
                            if (vm.hasReminder(pick.channel, p)) Text("🔔", fontSize = 18.sp)
                            if (vm.recordingFor(pick.channel, p) != null) Text("  ●", color = TunerColors.NowLine, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { pick ->
        val ch = pick.channel
        val p = pick.programme
        val t = System.currentTimeMillis()
        val items = buildList {
            if (p.start <= t) add(MenuItem("Watch ${ch.name}") { vm.play(ch, channels) })
            if (p.start > t) add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel reminder" else "Remind me") { vm.toggleReminder(ch, p) })
            if (!vm.activeProfile.kids && p.stop > t) {
                val rec = vm.recordingFor(ch, p)
                if (rec == null) add(MenuItem("● Record") { vm.recordProgramme(ch, p) }) else add(MenuItem("Cancel recording") { vm.stopRecording(rec) })
                add(MenuItem(if (vm.seriesLinkFor(ch, p) != null) "Cancel series link" else "🔁 Series link (record every episode)") { vm.toggleSeriesLink(ch, p) })
            }
            add(MenuItem(if (ch.id in vm.favouriteSet) "Remove channel from favourites" else "Add channel to favourites") { vm.toggleFavourite(ch.id) })
        }
        MenuDialog(p.title, items, onDismiss = { menuFor = null }, subtitle = "${ch.name} · ${dayLabel(p.start)} ${hhmm(p.start)} – ${hhmm(p.stop)}" + (p.desc?.let { "\n$it" } ?: ""))
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
            if (vm.isPhone) "Time spent watching in Tuner on this phone over the last 7 days."
            else "Time spent watching in Tuner on this TV over the last 7 days.",
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

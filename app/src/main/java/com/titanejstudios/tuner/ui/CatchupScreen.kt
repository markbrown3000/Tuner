package com.titanejstudios.tuner.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.FAVOURITES
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val ALL_CATCHUP = "All catch-up"

/** Catch-up: pick a category along the top, a channel on the left, then anything from the last few days on the right. */
@Composable
fun CatchupScreen(vm: TunerViewModel) {
    val all = remember(vm.channels, vm.unlocked) { vm.catchupChannels }
    // Categories that have at least one catch-up channel, in the TV Guide's order.
    val groups = remember(all, vm.groupNames, vm.favouriteSet) {
        val present = all.mapTo(HashSet()) { it.group }
        buildList {
            add(ALL_CATCHUP)
            if (all.any { it.id in vm.favouriteSet }) add(FAVOURITES)
            addAll(vm.groupNames.filter { it in present && it != ALL_CATCHUP })
        }
    }
    var group by remember { mutableStateOf(ALL_CATCHUP) }
    val channels = remember(all, group, vm.favouriteSet) {
        when (group) {
            ALL_CATCHUP -> all
            FAVOURITES -> all.filter { it.id in vm.favouriteSet }
            else -> all.filter { it.group == group }
        }
    }
    var selected by remember { mutableStateOf(channels.firstOrNull()) }
    LaunchedEffect(group) {
        if (selected == null || channels.none { it.id == selected?.id }) selected = channels.firstOrNull()
    }
    val first = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = if (narrowScreen()) 12.dp else 44.dp, vertical = if (narrowScreen()) 12.dp else 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Catch-up", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (query.isEmpty()) "Programmes from the last few days, on channels your provider offers catch-up for."
                    else "Catch-up matching \"$query\" on every channel",
                    color = if (query.isEmpty()) TunerColors.TextSecondary else TunerColors.Accent, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (all.isNotEmpty()) {
                PillButton("🔍 Search", { searching = true }, compact = true)
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    PillButton("✕ Clear", { query = "" }, compact = true)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (searching) {
            TextInputDialog(
                title = "Search catch-up",
                initial = query,
                hint = "A programme, team, or anything in the description",
                label = "Search",
                onDismiss = { searching = false },
                onDone = { searching = false; query = it.trim() },
            )
        }
        if (all.isEmpty()) {
            CenterMessage("None of your channels have catch-up")
            return@Column
        }
        if (query.isNotEmpty()) {
            CatchupResults(vm, all, query, first)
            return@Column
        }
        if (groups.size > 2) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(groups, key = { it }) { g ->
                    FocusSurface(
                        onClick = { group = g },
                        onFocused = { group = g },
                        selected = g == group,
                        baseColor = TunerColors.Panel,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(40.dp),
                    ) { f ->
                        Text(
                            g,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = if (f || g == group) Color.White else TunerColors.TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = if (g == group) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // Phones: channels as a row of chips, programmes underneath.
        if (narrowScreen()) {
            CategoryChips(channels.map { it.id }, selected?.id, label = { id -> channels.firstOrNull { it.id == id }?.name ?: id }) { id ->
                selected = channels.firstOrNull { it.id == id }
            }
            Spacer(Modifier.height(10.dp))
            selected?.let { ch -> CatchupList(vm, ch, channels) }
            return@Column
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                items(channels.size, key = { group + "|" + channels[it].id }) { i ->
                    val c = channels[i]
                    FocusSurface(
                        onClick = { selected = c },
                        onFocused = { selected = c },
                        selected = selected?.id == c.id,
                        baseColor = TunerColors.Panel,
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(56.dp),
                    ) { _ ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(c.logo, c.name, Modifier.size(width = 64.dp, height = 38.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.catchupDays} day${if (c.catchupDays == 1) "" else "s"}", color = TunerColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            selected?.let { ch -> CatchupList(vm, ch, channels) }
        }
    }
}

@Composable
private fun CatchupList(vm: TunerViewModel, ch: Channel, channels: List<Channel>) {
    val progs = remember(ch.id, vm.epg, vm.now / 300_000) { vm.catchupProgrammes(ch) }
    if (progs.isEmpty()) {
        Text("No catch-up guide for ${ch.name} yet", color = TunerColors.TextSecondary, fontSize = 17.sp, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
        var lastDay = ""
        progs.forEach { p ->
            val day = dayLabel(p.start)
            if (day != lastDay) {
                lastDay = day
                item(key = "day|" + ch.id + "|" + day) {
                    Text(day, color = TunerColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
            }
            item(key = ch.id + "|" + p.start) {
                FocusSurface(
                    onClick = { vm.playCatchup(ch, p, channels) },
                    baseColor = TunerColors.Panel,
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(hhmm(p.start), color = TunerColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.width(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            p.desc?.let {
                                Text(it, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        GenreChip(p.category)
                    }
                }
            }
        }
    }
}

/** Normalised for matching: lower case, no punctuation, single spaces. */
private fun plain(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

/**
 * Searches catch-up on every channel. Every word has to be there (in any order) in the title,
 * or failing that in the description. Title matches come first, then the newest.
 */
@Composable
private fun CatchupResults(vm: TunerViewModel, all: List<Channel>, query: String, first: FocusRequester) {
    var results by remember { mutableStateOf<List<Pair<Channel, Programme>>?>(null) }
    LaunchedEffect(query, all, vm.epg) {
        results = null
        results = withContext(Dispatchers.Default) {
            val q = plain(query)
            val words = q.split(' ').filter { it.isNotBlank() }
            val found = ArrayList<Triple<Int, Channel, Programme>>()
            for (ch in all) {
                for (p in vm.catchupProgrammes(ch)) {
                    val t = plain(p.title)
                    val score = when {
                        t == q -> 0
                        t.startsWith(q) -> 1
                        words.all { it in t } -> 2
                        words.all { it in plain(p.title + " " + (p.desc ?: "") + " " + (p.category ?: "")) } -> 3
                        else -> continue
                    }
                    found += Triple(score, ch, p)
                }
            }
            found.sortedWith(compareBy<Triple<Int, Channel, Programme>> { it.first }.thenByDescending { it.third.start })
                .take(300)
                .map { it.second to it.third }
        }
    }
    val list = results
    when {
        list == null -> CenterMessage("Searching catch-up…")
        list.isEmpty() -> CenterMessage("Nothing on catch-up matches \"$query\"")
        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
            items(list.size, key = { list[it].first.id + "|" + list[it].second.start }) { i ->
                val (ch, p) = list[i]
                FocusSurface(
                    onClick = { vm.playCatchup(ch, p, all) },
                    baseColor = TunerColors.Panel,
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(66.dp),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(110.dp)) {
                            Text(dayLabel(p.start), color = TunerColors.Accent, fontSize = 13.sp, maxLines = 1)
                            Text(hhmm(p.start), color = TunerColors.TextSecondary, fontSize = 15.sp)
                        }
                        ChannelLogo(ch.logo, ch.name, Modifier.size(width = 64.dp, height = 38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(ch.name, p.desc).joinToString("  ·  "),
                                color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        GenreChip(p.category)
                    }
                }
            }
        }
    }
}

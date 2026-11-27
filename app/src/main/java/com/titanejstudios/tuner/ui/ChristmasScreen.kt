package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.recordProgramme
import com.titanejstudios.tuner.recordingFor
import com.titanejstudios.tuner.stopRecording
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.FestiveItem
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SECTIONS = listOf("specials" to "🎄 Specials", "films" to "🎬 Christmas films", "sport" to "🎯 Sport", "picked" to "✓ Picked")

/** The Christmas TV planner: festive specials, Christmas films and the big sport – tick what you want. */
@Composable
fun ChristmasScreen(vm: TunerViewModel) {
    var section by remember { mutableStateOf("specials") }
    var menuFor by remember { mutableStateOf<FestiveItem?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.loadChristmas()
        delay(80)
        runCatching { first.requestFocus() }
    }
    val narrow = narrowScreen()
    val dayFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.UK) }
    val all = vm.festive
    fun picked(f: FestiveItem) = vm.hasReminder(f.channel, f.programme) || vm.recordingFor(f.channel, f.programme) != null
    val shown = when (section) {
        "picked" -> all.orEmpty().filter { picked(it) }
        else -> all.orEmpty().filter { it.section == section }
    }

    LazyColumn(
        Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp)).widthIn(max = 1000.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item {
            Text("🎄 Christmas TV planner", fontSize = if (narrow) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "The festive specials, Christmas films and big sport from your TV guide, up to New Year. Pick one to set a reminder or record it. " +
                    "Guides only reach about a week ahead, so more appears as Christmas gets closer.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SECTIONS.size, key = { SECTIONS[it].first }) { i ->
                    val (id, label) = SECTIONS[i]
                    val n = if (id == "picked") all.orEmpty().count { picked(it) } else all.orEmpty().count { it.section == id }
                    PillButton(
                        "$label ($n)", { section = id },
                        if (i == 0) Modifier.focusRequester(first) else Modifier,
                        selected = section == id, compact = true,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            all == null -> item { Text("Looking through the guide…", color = TunerColors.TextSecondary, fontSize = 16.sp) }
            shown.isEmpty() -> item {
                Text(
                    when (section) {
                        "picked" -> "Nothing picked yet – choose a programme to set a reminder or record it."
                        "sport" -> "No darts or matches with your teams in the guide yet."
                        else -> "Nothing in the guide yet – check back nearer Christmas."
                    },
                    color = TunerColors.TextSecondary, fontSize = 16.sp,
                )
            }
        }
        var lastDay = ""
        shown.forEach { f ->
            val p = f.programme
            val day = dayFmt.format(Date(p.start))
            if (day != lastDay) {
                lastDay = day
                item(key = "day$day") {
                    Text(day, color = TunerColors.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
            }
            item(key = f.channel.id + "|" + p.start + "|" + p.title) {
                val reminder = vm.hasReminder(f.channel, p)
                val recording = vm.recordingFor(f.channel, p) != null
                FocusSurface(
                    onClick = { menuFor = f },
                    baseColor = TunerColors.Panel,
                    modifier = Modifier.fillMaxWidth().height(if (narrow) 70.dp else 64.dp),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(hhmm(p.start), color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                f.channel.name + "  ·  ${(p.stop - p.start) / 60_000} min",
                                color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (recording) Text("●", color = TunerColors.NowLine, fontSize = 16.sp)
                        if (reminder) Text("  ⏰", fontSize = 15.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    menuFor?.let { f ->
        val p = f.programme
        val ch = f.channel
        val t = vm.now
        MenuDialog(
            title = p.title,
            subtitle = ch.name + " · " + dayFmt.format(Date(p.start)) + " " + hhmm(p.start) + (p.desc?.let { "\n" + it.take(200) } ?: ""),
            items = buildList {
                if (p.start <= t && p.stop > t) add(MenuItem("▶ Watch now") { vm.play(ch, vm.channelsFor(ALL)) })
                if (p.start > t) add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel the reminder" else "⏰ Remind me") { vm.toggleReminder(ch, p) })
                val rec = vm.recordingFor(ch, p)
                if (p.stop > t && !vm.activeProfile.kids) {
                    if (rec == null) add(MenuItem("● Record it") { vm.recordProgramme(ch, p) }) else add(MenuItem("Cancel the recording") { vm.stopRecording(rec) })
                }
                if (p.stop <= t && vm.canCatchup(ch, p)) add(MenuItem("Watch on catch-up") { vm.playCatchup(ch, p, vm.channelsFor(ALL)) })
            },
            onDismiss = { menuFor = null },
        )
    }
}

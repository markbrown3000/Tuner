package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
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
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.RecordingPlanner
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.SeriesLink
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> "${bytes / 1000} KB"
}

@Composable
fun RecordingsScreen(vm: TunerViewModel) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshRecordings()
            tick++
            delay(2_000)
        }
    }
    val restorer = rememberFocusRestorer()
    var menuFor by remember { mutableStateOf<Recording?>(null) }
    var showStorage by remember { mutableStateOf(false) }
    var linkMenu by remember { mutableStateOf<SeriesLink?>(null) }
    RestoreFocusWhenClosed(menuFor != null || showStorage || linkMenu != null, restorer)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
        vm.loadAccountStreams()
    }

    val now = vm.now
    val list = vm.recordings
    val active = list.filter { it.status == RecStatus.RECORDING }
    // The week ahead by day, with overlapping recordings picked out.
    val days = remember(list, now / 60_000) { RecordingPlanner.days(list.filter { it.status == RecStatus.SCHEDULED }, now) }
    val clashes = remember(list, now / 60_000) { RecordingPlanner.clashes(list, now) }
    val streams = vm.accountStreams
    val done = list.filter { it.status == RecStatus.DONE || it.status == RecStatus.FAILED }.sortedByDescending { it.start }
    val storage = remember(tick / 15) { vm.storageOptions().firstOrNull { it.path == vm.recordingDir } }

    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = if (narrowScreen()) 12.dp else 64.dp, vertical = if (narrowScreen()) 10.dp else 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(18.dp))
            Text(
                storage?.let { "${it.label} · ${sizeText(it.freeBytes)} free" } ?: "",
                fontSize = 15.sp,
                color = TunerColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            PillButton("Storage", {
                restorer.key = "storage"
                showStorage = true
            }, restorer.mod("storage").then(Modifier.focusRequester(first)), compact = true)
        }
        Text(
            "Set recordings from the TV guide (hold OK on a programme) or the player menu. " +
                "Pick \"Series link\" to record every episode.",
            fontSize = 15.sp,
            color = TunerColors.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        if (list.isEmpty() && vm.seriesLinks.isEmpty()) {
            CenterMessage("Nothing recorded yet")
            return@Column
        }
        LazyColumn(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active.isNotEmpty()) {
                item { SectionTitle("Recording now") }
                items(active, key = { it.id }) { r -> RecordingRow(vm, r, restorer, clashWith = clashes[r.id].orEmpty()) { menuFor = r } }
            }
            if (clashes.isNotEmpty()) {
                item(key = "clash-note") {
                    Text(
                        "⚠ Some recordings overlap. " + (
                            when (streams) {
                                null -> "Many IPTV accounts only allow one or two streams at once, so one of them may fail."
                                1 -> "Your account allows 1 stream at a time, so only one of them can record (and you can't watch another channel meanwhile)."
                                else -> "Your account allows $streams streams at a time – watching TV at the same time uses one too."
                            }
                        ),
                        color = Color(0xFFFFC857),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-" + day.label) { SectionTitle("Scheduled · ${day.label}") }
                items(day.recordings, key = { it.id }) { r ->
                    RecordingRow(vm, r, restorer, clashWith = clashes[r.id].orEmpty()) { menuFor = r }
                }
            }
            if (done.isNotEmpty()) {
                item { SectionTitle("Recorded") }
                items(done, key = { it.id }) { r -> RecordingRow(vm, r, restorer) { menuFor = r } }
            }
            if (vm.seriesLinks.isNotEmpty()) {
                item { SectionTitle("Series links – every episode is recorded") }
                items(vm.seriesLinks, key = { "link|" + it.channelId + "|" + it.title }) { link ->
                    val key = "link|" + link.channelId + "|" + link.title
                    FocusSurface(
                        onClick = {
                            restorer.key = key
                            linkMenu = link
                        },
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(60.dp).then(restorer.mod(key)),
                    ) { focused ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔁  " + link.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(link.channelName, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { r ->
        val items = buildList {
            if (r.status == RecStatus.RECORDING) add(MenuItem("Watch from the start") { vm.playRecording(r) })
            if (r.status == RecStatus.DONE || (r.status == RecStatus.FAILED && File(r.path).length() > 0)) {
                add(MenuItem("Play") { vm.playRecording(r) })
            }
            if (r.status == RecStatus.RECORDING) add(MenuItem("Stop recording") { vm.stopRecording(r) })
            if (r.status == RecStatus.SCHEDULED) add(MenuItem("Cancel") { vm.stopRecording(r) })
            if (r.status == RecStatus.DONE && vm.autoDeleteDays > 0) {
                add(MenuItem(if (r.id in vm.keptRecordings) "Allow tidy-up" else "📌 Keep (never auto-delete)") { vm.toggleKeep(r) })
            }
            add(MenuItem("Delete") { vm.deleteRecording(r) })
        }
        MenuDialog(r.title, items, onDismiss = { menuFor = null }, subtitle = "${r.channelName} · ${dayLabel(r.start)} ${hhmm(r.start)} – ${hhmm(r.stop)}")
    }

    linkMenu?.let { link ->
        MenuDialog(
            title = link.title,
            subtitle = "Series link on ${link.channelName}",
            items = listOf(MenuItem("Remove series link") { vm.removeSeriesLink(link) }),
            onDismiss = { linkMenu = null },
        )
    }

    if (showStorage) {
        val options = remember { vm.storageOptions() }
        MenuDialog(
            title = "Save recordings to",
            subtitle = "Plug in a USB drive and it appears here (if your TV supports USB storage).",
            items = options.map { o ->
                MenuItem(o.label, sizeText(o.freeBytes) + " free" + (if (o.path == vm.recordingDir) "  ✓" else "")) {
                    vm.setRecordingDir(o.path)
                }
            },
            onDismiss = { showStorage = false },
        )
    }
}

@Composable
private fun RecordingRow(
    vm: TunerViewModel,
    r: Recording,
    restorer: FocusRestorer,
    clashWith: List<Recording> = emptyList(),
    onMenu: () -> Unit,
) {
    val size = File(r.path).let { if (it.exists()) it.length() else 0L }
    val (label, color) = when (r.status) {
        RecStatus.RECORDING -> "● Recording · ${sizeText(size)}" to TunerColors.NowLine
        RecStatus.SCHEDULED -> "Scheduled" to TunerColors.Accent
        RecStatus.DONE -> sizeText(size) to TunerColors.Catchup
        else -> ("Failed" + (r.error?.let { " – $it" } ?: "")) to TunerColors.Error
    }
    FocusSurface(
        onClick = {
            if (r.status == RecStatus.DONE) vm.playRecording(r) else {
                restorer.key = r.id
                onMenu()
            }
        },
        onLongClick = {
            restorer.key = r.id
            onMenu()
        },
        baseColor = TunerColors.Panel,
        modifier = Modifier.fillMaxWidth().height(72.dp).then(restorer.mod(r.id)),
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(10.dp))
                    GenreChip(r.category)
                }
                Text(
                    "${r.channelName} · ${dayLabel(r.start)} ${hhmm(r.start)} – ${hhmm(r.stop)}" +
                        (if (clashWith.isNotEmpty()) "   ⚠ overlaps " + clashWith.joinToString { "${it.title} (${it.channelName})" } else ""),
                    color = if (clashWith.isNotEmpty() && !focused) Color(0xFFFFC857) else if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(label, color = if (focused) Color.White else color, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp))
        }
    }
}

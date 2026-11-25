package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Multi-view: two or four channels at once. Click a picture for its sound; pick channels on the left. */
@Composable
fun MultiPage(app: AppState) {
    var slot by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    val favourites = remember(app.listsVersion, app.profileId) { app.favourites }
    val list = remember(app.visibleChannels, favourites, search) {
        val q = search.trim().lowercase()
        val base = if (q.isNotEmpty()) app.visibleChannels.filter { it.name.lowercase().contains(q) }
        else app.visibleChannels.filter { it.id in favourites }.ifEmpty { app.visibleChannels }
        base.take(500)
    }
    val count = app.multiCount
    Row(Modifier.fillMaxSize().padding(14.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            Text("Multi-view", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Pick a box, then a channel. Click a picture to hear it.", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("2 channels", selected = count == 2) {
                    app.multiCount = 2
                    app.multi.stop(2)
                    app.multi.stop(3)
                    if (slot > 1) slot = 0
                }
                Pill("4 channels", selected = count == 4) { app.multiCount = 4 }
            }
            OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Find a channel") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(list, key = { it.id }) { c ->
                    val cur = app.guide[c.id]?.firstOrNull { it.start <= app.now && it.stop > app.now }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).clickable {
                            app.setMulti(slot, c)
                            slot = (slot + 1) % count
                        }.padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(c.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        cur?.let { Text(it.title, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = if (count == 2) listOf(listOf(0, 1)) else listOf(listOf(0, 1), listOf(2, 3))
            for (r in rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in r) MultiBox(app, i, selected = i == slot, Modifier.weight(1f).fillMaxHeight()) { slot = i }
                }
            }
        }
    }
}

@Composable
private fun MultiBox(app: AppState, i: Int, selected: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val c = app.multiChannels[i]
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(2.dp, if (selected) Sky else Color.Transparent, RoundedCornerShape(10.dp)).background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val comp = if (c != null) app.multi.component(i) else null
            if (comp != null) SwingPanel(factory = { comp }, modifier = Modifier.fillMaxSize())
            else Text("Box ${i + 1}: pick a channel", color = Soft, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onPick))
        }
        Row(Modifier.fillMaxWidth().background(InkBottom).clickable(onClick = onPick).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c?.name ?: "Empty", color = if (selected) Color.White else Soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (c != null) {
                Text(if (app.multiSound == i) "🔊" else "🔈", fontSize = 14.sp, modifier = Modifier.clickable { app.multiSoundFrom(i) }.padding(horizontal = 6.dp))
                Text("⤢", color = Soft, fontSize = 16.sp, modifier = Modifier.clickable { app.page = Page.Live; app.multiToFull(i) }.padding(horizontal = 6.dp))
            }
        }
    }
}

/** Recordings and downloads: what's recorded (in Videos\Flicker), what's coming up, and what's saved to watch offline. */
@Composable
fun RecordingsPage(app: AppState) {
    val fmt = remember { SimpleDateFormat("EEE d MMM HH:mm", Locale.UK) }
    val list = app.recordings.sortedWith(compareBy({ if (it.state == "scheduled" || it.state == "recording") 0 else 1 }, { -it.start }))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Recordings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Record from the TV Guide (click a programme › ● Record). Flicker records while it's open – it can be minimised. " +
                    "Files go in ${app.recorder.folder.absolutePath}.",
                color = Soft, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Open the folder") { runCatching { java.awt.Desktop.getDesktop().open(app.recorder.folder) } }
                Pill("📁 Save recordings somewhere else…") { app.chooseRecordFolder() }
                if (app.config.recordFolder.isNotBlank()) Pill("Back to Videos\\Flicker") { app.resetRecordFolder() }
            }
            Text("A network drive (NAS or another PC's shared folder) works too.", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            Text("Delete finished recordings after", color = Color.White, fontSize = 14.sp)
            val v = app.listsVersion
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                for (d in listOf(0, 7, 14, 30, 60)) Pill(if (d == 0) "Never" else "$d days", selected = app.config.keepRecordingsDays == d && v >= 0) { app.setKeepRecordings(d) }
            }
            Spacer(Modifier.height(10.dp))
        }
        val links = app.config.seriesLinks
        if (links.isNotEmpty()) {
            item { SectionTitle("Series links – every episode records") }
            items(links, key = { "s" + it }) { l ->
                val cid = l.substringBefore('\t')
                val title = l.substringAfter('\t')
                val ch = app.channels.firstOrNull { it.id == cid }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔁", fontSize = 16.sp, modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        val next = app.guide[cid]?.firstOrNull { it.title == title && it.start > app.now }
                        Text((ch?.name ?: "A channel that's gone") + (next?.let { " · next: ${fmt.format(Date(it.start))}" } ?: " · nothing in the guide yet"), color = Soft, fontSize = 12.sp)
                    }
                    if (ch != null) Pill("Cancel") {
                        app.toggleSeriesLink(ch, com.titanejstudios.tuner.data.Programme(0L, 0L, title, null, null))
                    }
                }
            }
        }
        val downloads = app.downloads
        if (downloads.isNotEmpty()) {
            item { SectionTitle("Downloads – watch without the internet") }
            items(downloads, key = { "d" + it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
                        .clickable(enabled = d.state == "done") { app.playDownload(d) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (d.state) { "done" -> "▶"; "failed" -> "⚠"; else -> "⤓" },
                        color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                d.sub,
                                when (d.state) {
                                    "downloading" -> app.downloadProgress[d.id]?.let { "downloading ${(it * 100).toInt()}%" } ?: "downloading…"
                                    "failed" -> "didn't download"
                                    else -> runCatching { "%.1f GB".format(java.io.File(d.file).length() / 1e9) }.getOrNull()
                                },
                            ).joinToString(" · "),
                            color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Pill(if (d.state == "downloading") "Cancel" else "Delete") { app.deleteDownload(d) }
                }
            }
            item { SectionTitle("Recordings") }
        }
        if (list.isEmpty()) item { Text("Nothing recorded yet.", color = Soft, fontSize = 15.sp) }
        items(list, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
                    .clickable(enabled = r.state == "done" || r.state == "recording") { app.playRecording(r) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (r.state) {
                        "recording" -> "● REC"
                        "scheduled" -> "⏱"
                        "failed" -> "⚠"
                        else -> "▶"
                    },
                    color = if (r.state == "recording") LiveRed else Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${fmt.format(Date(r.start))} – ${time(r.stop)} · ${r.channelName}" + when (r.state) {
                            "failed" -> " · didn't record (was Flicker open?)"
                            "scheduled" -> " · keep Flicker open"
                            else -> ""
                        },
                        color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Pill(if (r.state == "scheduled" || r.state == "recording") "Cancel" else "Delete") {
                    if (r.state == "scheduled" || r.state == "recording") {
                        app.channels.firstOrNull { it.id == r.channelId }?.let { c ->
                            app.toggleRecording(c, com.titanejstudios.tuner.data.Programme(r.start, r.stop, r.title, null))
                        } ?: app.deleteRecording(r)
                    } else app.deleteRecording(r)
                }
            }
        }
    }
}


/** Catch-up: channels that keep the last few days, then a day, then what was on. */
@Composable
fun CatchupPage(app: AppState) {
    val channels = app.catchupChannels
    var chosen by remember { mutableStateOf<com.titanejstudios.tuner.data.Channel?>(null) }
    var dayBack by remember { mutableStateOf(0) }
    val c = chosen ?: channels.firstOrNull()
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.UK) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Text("Catch-up", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Channels that keep the last few days.", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (channels.isEmpty()) Text("None of your channels have catch-up.", color = Soft, fontSize = 14.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(channels, key = { it.id }) { ch ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (ch.id == c?.id) Sky.copy(alpha = 0.35f) else Panel)
                            .clickable { chosen = ch; dayBack = 0 }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ch.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${ch.catchupDays}d", color = Soft, fontSize = 11.sp)
                    }
                }
            }
        }
        if (c != null) Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(c.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val days = c.catchupDays.coerceIn(1, 7)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                for (d in 0..days) {
                    val label = when (d) {
                        0 -> "Today"
                        1 -> "Yesterday"
                        else -> dayFmt.format(Date(System.currentTimeMillis() - d * 86_400_000L))
                    }
                    Pill(label, selected = d == dayBack) { dayBack = d }
                }
            }
            val now = app.now
            val shown = remember(c.id, dayBack, app.guide, now / 60_000L) {
                val cal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_MONTH, -dayBack)
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                val from = cal.timeInMillis
                val to = from + 86_400_000L
                app.guide[c.id].orEmpty().filter { it.start >= from && it.start < to && it.stop <= now }.reversed()
            }
            if (shown.isEmpty()) Text("Nothing in the guide for this day.", color = Soft, fontSize = 14.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.start }) { p ->
                    val ok = com.titanejstudios.tuner.data.Catchup.isAvailable(c, p, now)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel)
                            .clickable(enabled = ok) { app.playCatchup(c, p) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${timeFmt.format(Date(p.start))}–${timeFmt.format(Date(p.stop))}", color = Soft, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = if (ok) Color.White else Soft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            p.desc?.let { Text(it, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        Text(if (ok) "▶" else "gone", color = if (ok) Gold else Soft, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

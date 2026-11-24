package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.features.DownloadEntry
import kotlinx.coroutines.delay

/** Films and episodes saved on this phone: watch them with no internet, e.g. on a plane. */
@Composable
fun DownloadsScreen(vm: TunerViewModel) {
    val dl = vm.downloader
    var menuFor by remember { mutableStateOf<DownloadEntry?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
        while (true) {
            dl.refresh()
            delay(1_000)
        }
    }
    val narrow = narrowScreen()
    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Text("Downloads", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Text(
            "Saved on this phone to watch without internet. Hold a film or episode in Films & TV and choose ⬇ Download. " +
                "Uses ${dlSize(dl.usedBytes())}.",
            color = TunerColors.TextSecondary, fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PillButton(
                    if (dl.wifiOnly) "📶 Wi-Fi only: On" else "📶 Wi-Fi only: Off",
                    { dl.toggleWifiOnly() }, Modifier.focusRequester(first), compact = true,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (dl.items.isEmpty()) {
            CenterMessage("Nothing downloaded yet")
            return@Column
        }
        LazyColumn(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val list = dl.items.sortedByDescending { it.addedAt }
            items(list.size, key = { list[it].contentId }) { i ->
                val d = list[i]
                val p = dl.progress[d.contentId]
                val ready = dl.isReady(d.contentId)
                FocusSurface(
                    onClick = { if (ready) vm.playDownload(d) else menuFor = d },
                    onLongClick = { menuFor = d },
                    baseColor = TunerColors.Panel,
                    modifier = Modifier.fillMaxWidth().height(if (narrow) 84.dp else 96.dp),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = if (narrow) 46.dp else 52.dp, height = if (narrow) 64.dp else 76.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0E12))) {
                            if (d.poster != null) AsyncImage(model = d.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            d.subtitle?.let { Text(it, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Spacer(Modifier.height(4.dp))
                            when {
                                p?.failed == true -> Text("Failed – hold to delete and try again", color = TunerColors.Error, fontSize = 13.sp)
                                ready -> Text("✓ Ready · ${dlSize(p?.total ?: java.io.File(d.file).length())}", color = TunerColors.Accent, fontSize = 13.sp)
                                else -> {
                                    val pct = ((p?.fraction ?: 0f) * 100).toInt()
                                    Text(
                                        if (p == null || p.total <= 0) "Waiting…" else "Downloading $pct% · ${dlSize(p.bytes)} of ${dlSize(p.total)}",
                                        color = TunerColors.TextSecondary, fontSize = 13.sp,
                                    )
                                    ProgressBar(p?.fraction ?: 0f, Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                            }
                        }
                        vm.resume[d.contentId]?.let { r ->
                            if (r.durationMs > 0) Text("${(r.positionMs * 100 / r.durationMs)}%", color = TunerColors.TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { d ->
        val ready = dl.isReady(d.contentId)
        MenuDialog(
            title = d.title + (d.subtitle?.let { " · $it" } ?: ""),
            items = buildList {
                if (ready) add(MenuItem("▶ Play") { vm.playDownload(d) })
                add(MenuItem(if (ready) "🗑 Delete the download" else "✕ Cancel the download") { vm.removeDownload(d.contentId) })
            },
            onDismiss = { menuFor = null },
        )
    }
}

private fun dlSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.UK, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
    else -> "${bytes / 1_000} KB"
}

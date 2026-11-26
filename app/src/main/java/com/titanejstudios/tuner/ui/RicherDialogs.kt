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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Moods
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings › Look › Colour theme: every theme as a little preview, applied as you pick. */
@Composable
fun ThemePickerDialog(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val close = { vm.themePickerOpen = false }
    ModalLayer(onDismiss = close) {
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 980.dp).clip(RoundedCornerShape(16.dp)).background(TunerColors.Panel).padding(26.dp),
        ) {
            Text("Colour theme", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Just for ${vm.activeProfile.name} – each profile keeps its own. The PC app has the same themes.",
                color = TunerColors.TextSecondary, fontSize = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            val themes = TunerColors.themes
            val current = themes.indexOfFirst { it.key == vm.themeName }.coerceAtLeast(0)
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.height(330.dp),
            ) {
                items(themes.size, key = { themes[it].key }) { i ->
                    val t = themes[i]
                    FocusSurface(
                        onClick = { vm.setTheme(theme = t.key) },
                        selected = t.key == vm.themeName,
                        baseColor = Color(t.panel),
                        modifier = Modifier.height(96.dp).then(if (i == current) Modifier.focusRequester(first) else Modifier),
                    ) { _ ->
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(t.bg), Color(t.bgBottom))))) {
                            // A tiny "screen": a panel, a highlighted button and two lines of text.
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 34.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(t.accent)))
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.size(width = 24.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(t.panelLight)))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(t.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (t.key == vm.themeName) "✓ In use" else "OK to try it", color = Color(t.soft), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Highlight colour", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TunerColors.accents.size) { i ->
                    val (key, name) = TunerColors.accents[i]
                    PillButton(name, { vm.setTheme(accent = key) }, selected = vm.themeAccent == key, compact = true)
                }
            }
            Spacer(Modifier.height(14.dp))
            PillButton("Done", close)
        }
    }
}

/** Kids: "Bedtime in 10 minutes" – a soft card at the top, not a stop. */
@Composable
fun BedtimeSoonCard(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 28.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xEE1B1F4A), Color(0xEE35225C))))
            .padding(horizontal = 26.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🌙", fontSize = 34.sp)
        Spacer(Modifier.width(14.dp))
        Text(text.removePrefix("🌙 "), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 📍 Where can I watch…? Everywhere a title is, best first. */
@Composable
fun WhereToWatchDialog(vm: TunerViewModel, w: TunerViewModel.WhereTo) {
    val options = w.options
    if (options == null) {
        MenuDialog(
            title = "📍 Where can I watch ${w.title}?",
            subtitle = "Looking in Plex, your provider, recordings and the TV guide…",
            items = listOf(MenuItem("Cancel") { vm.closeWhereTo() }),
            onDismiss = { vm.closeWhereTo() },
        )
        return
    }
    MenuDialog(
        title = "📍 ${w.title}",
        subtitle = if (options.isEmpty()) "Not on Plex, your provider or the TV guide this week" else "Here's everywhere it is",
        items = options.map { o -> MenuItem(o.label, o.detail) { o.action() } } +
            MenuItem("🔍 Search for it instead") {
                vm.closeWhereTo()
                vm.navigate(com.titanejstudios.tuner.Screen.Search)
                vm.updateSearch(w.title)
            },
        onDismiss = { vm.closeWhereTo() },
    )
}

/** "What's on BBC One later": the channel's next few programmes. */
@Composable
fun ChannelScheduleDialog(vm: TunerViewModel, c: com.titanejstudios.tuner.data.Channel, list: List<com.titanejstudios.tuner.data.Programme>) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val t = System.currentTimeMillis()
    MenuDialog(
        title = "📺 ${c.name}",
        subtitle = "OK on something on now to watch it, or on something later for a reminder",
        items = list.map { p ->
            val now = p.start <= t && p.stop > t
            MenuItem(
                (if (now) "▶ Now  " else fmt.format(Date(p.start)) + "  ") + p.title,
                if (now) "until " + fmt.format(Date(p.stop)) else if (vm.hasReminder(c, p)) "⏰ reminder set" else null,
            ) {
                vm.closeChannelSchedule()
                if (now) vm.play(c, listOf(c)) else vm.toggleReminder(c, p)
            }
        },
        onDismiss = { vm.closeChannelSchedule() },
    )
}

/** Home: "In the mood for…" – a row of feelings, each opening the films that fit. */
@Composable
fun MoodRow(vm: TunerViewModel, modifier: Modifier = Modifier) {
    val moods = if (vm.activeProfile.kids) Moods.all.filter { it.key == "family" || it.key == "funny" } else Moods.all
    Column(modifier) {
        Text("In the mood for…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(moods.size, key = { moods[it].key }) { i ->
                val m = moods[i]
                PillButton("${m.emoji} ${m.label}", { vm.openMood(m.key) }, compact = true)
            }
        }
    }
}

/**
 * Above a series' episodes: how far through you are, carry on from where you're up to, or start
 * again from the very first episode.
 */
@Composable
fun SeriesProgress(
    watched: Int,
    total: Int,
    upToLabel: String?,
    onCarryOn: (() -> Unit)?,
    onFromStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onCarryOn != null && upToLabel != null) PillButton("▶ Carry on – $upToLabel", onCarryOn, compact = true)
            PillButton("⏮ From the very start", onFromStart, compact = true)
            Spacer(Modifier.width(6.dp))
            val pct = (watched * 100f / total).toInt()
            Text(
                when {
                    watched >= total -> "✓ All $total episodes watched"
                    watched == 0 -> "$total episodes · not started"
                    else -> "$watched of $total watched · $pct%"
                },
                color = TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(watched.toFloat() / total, Modifier.fillMaxWidth(0.6f))
    }
}

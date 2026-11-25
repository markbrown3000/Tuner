package com.titanejstudios.tuner.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.features.LinkFeature
import kotlinx.coroutines.delay

/** Phones: a remote control for a linked TV running Flicker – buttons, and its channels with what's on. */
@Composable
fun RemoteScreen(vm: TunerViewModel) {
    val link = vm.link
    var tvId by remember { mutableStateOf<String?>(null) }
    val tv: LinkFeature.Peer? = link.tvs.firstOrNull { it.id == tvId } ?: link.tvs.firstOrNull()
    var tab by remember { mutableStateOf("buttons") }
    var nowTitle by remember { mutableStateOf<String?>(null) }
    var nowDetail by remember { mutableStateOf<String?>(null) }
    var nowWhat by remember { mutableStateOf<String?>(null) }
    var nowSeries by remember { mutableStateOf(true) }
    var nowDesc by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    var filter by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (link.linked && link.tvs.isEmpty()) link.search() }
    // What the TV is showing, every few seconds.
    LaunchedEffect(tv?.id) {
        val t = tv ?: return@LaunchedEffect
        while (true) {
            link.remoteState(t)?.let {
                nowTitle = it.optString("title")
                nowDetail = it.optString("detail")
                nowWhat = it.optString("what").takeIf { w -> w.isNotBlank() }
                nowSeries = it.optBoolean("series", true)
                nowDesc = it.optString("desc").takeIf { d -> d.isNotBlank() }
            }
            delay(3_000)
        }
    }
    LaunchedEffect(tv?.id, tab) {
        val t = tv ?: return@LaunchedEffect
        if (tab == "channels" && channels == null) {
            val arr = link.remoteChannels(t)
            channels = arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        }
    }
    fun press(code: Int) {
        tv?.let { link.remoteKey(it, code) }
    }

    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Text("📺 TV remote", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        when {
            !link.linked -> {
                Text("Link this phone with your TV first: Settings › Linked devices.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp))
                return@Column
            }
            tv == null -> {
                Text(
                    if (link.searching) "Looking for your TV…" else "No TV found. Open Flicker on the TV (it can be in the background) and look again.",
                    color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp),
                )
                PillButton("↻ Look again", { link.search() }, compact = true)
                return@Column
            }
        }
        val t = tv ?: return@Column
        // Which TV (when there's more than one), and what it's showing.
        if (link.tvs.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(link.tvs.size, key = { link.tvs[it].id }) { i ->
                    val p = link.tvs[i]
                    PillButton(p.name, { tvId = p.id; channels = null }, compact = true, selected = p.id == t.id)
                }
            }
        } else Text(t.name, color = TunerColors.TextSecondary, fontSize = 14.sp)
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(TunerColors.Panel).padding(12.dp)
        ) {
            Text(nowTitle ?: "…", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            nowDetail?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Buttons", { tab = "buttons" }, compact = true, selected = tab == "buttons")
            PillButton("Channels", { tab = "channels" }, compact = true, selected = tab == "channels")
            PillButton("ⓘ About this", { tab = "about" }, compact = true, selected = tab == "about")
        }
        Spacer(Modifier.height(12.dp))

        if (tab == "about") {
            AboutTvPanel(vm, t, nowWhat, nowSeries, nowDesc)
            return@Column
        }
        if (tab == "buttons") {
            @Composable
            fun Key(label: String, code: Int, size: Int = 64, wide: Boolean = false) {
                Box(
                    Modifier.size(width = if (wide) (size * 1.6f).dp else size.dp, height = size.dp)
                        .clip(RoundedCornerShape(if (wide) 16.dp else 50.dp)).background(Color(0x33FFFFFF))
                        .tvClickable({ press(code) }),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = Color.White, fontSize = (size * 0.32f).sp, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Key("⌂", -1, 52)
                    Key("☰ Guide", KeyEvent.KEYCODE_GUIDE, 52, wide = true)
                    Key("⋮", KeyEvent.KEYCODE_MENU, 52)
                }
                Key("▲", KeyEvent.KEYCODE_DPAD_UP, 72)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Key("◀", KeyEvent.KEYCODE_DPAD_LEFT, 72)
                    Key("OK", KeyEvent.KEYCODE_DPAD_CENTER, 88)
                    Key("▶", KeyEvent.KEYCODE_DPAD_RIGHT, 72)
                }
                Key("▼", KeyEvent.KEYCODE_DPAD_DOWN, 72)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Key("← Back", KeyEvent.KEYCODE_BACK, 56, wide = true)
                    Key("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 56)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Key("CH −", KeyEvent.KEYCODE_CHANNEL_DOWN, 52, wide = true)
                    Key("⏪", KeyEvent.KEYCODE_MEDIA_REWIND, 52)
                    Key("⏩", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 52)
                    Key("CH +", KeyEvent.KEYCODE_CHANNEL_UP, 52, wide = true)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (filter.isBlank()) "🔍 Find a channel" else "🔍 $filter", { typing = true }, compact = true)
                if (filter.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    PillButton("✕", { filter = "" }, compact = true)
                }
            }
            Spacer(Modifier.height(8.dp))
            val all = channels
            if (all == null) {
                CenterMessage("Getting the TV's channels…")
            } else {
                val q = filter.trim().lowercase()
                val shown = (if (q.isEmpty()) all else all.filter {
                    it.optString("name").lowercase().contains(q) || it.optString("now").lowercase().contains(q)
                }).distinctBy { it.optString("id") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown.size, key = { shown[it].optString("id") }) { i ->
                        val c = shown[i]
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TunerColors.Panel)
                                .tvClickable({ link.remotePlay(t, c.optString("id")) }).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.optString("n"), color = TunerColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.optString("name"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val now = c.optString("now").takeIf { it.isNotBlank() && it != "null" }
                                if (now != null) Text(now, color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("▶", color = TunerColors.Accent, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
    if (typing) {
        TextInputDialog(
            title = "Find a channel or programme",
            initial = filter,
            label = "Search",
            onDismiss = { typing = false },
        ) {
            filter = it
            typing = false
        }
    }
}


/**
 * The phone as a second screen: cast, rating and plot for what the TV is showing, and what else
 * the actors are in (with any you have, ready to send to the TV).
 */
@Composable
private fun AboutTvPanel(vm: TunerViewModel, tv: LinkFeature.Peer, what: String?, series: Boolean, desc: String?) {
    var info by remember { mutableStateOf<com.titanejstudios.tuner.integrations.Tmdb.Info?>(null) }
    var looking by remember { mutableStateOf(false) }
    var actor by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var films by remember { mutableStateOf<List<TunerViewModel.SeriesPart>?>(null) }
    LaunchedEffect(what, series) {
        info = null
        actor = null
        if (what == null) return@LaunchedEffect
        looking = true
        info = vm.aboutTv(what, series)
        looking = false
    }
    LaunchedEffect(actor) {
        films = null
        val a = actor ?: return@LaunchedEffect
        films = vm.actorFilms(a.first)
    }
    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            what == null -> item { Text("Nothing to look up – the TV isn't playing a programme or film.", color = Color.White, fontSize = 15.sp) }
            vm.tmdbKey == null -> item { Text("Add a TMDB key on this phone (Settings › Films & series) to see the cast and details.", color = Color.White, fontSize = 15.sp) }
            looking -> item { Text("Looking up $what…", color = TunerColors.TextSecondary, fontSize = 15.sp) }
            info == null -> item {
                Column {
                    Text(what, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    desc?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 14.sp) }
                    Text("No more details found.", color = TunerColors.TextSecondary, fontSize = 13.sp)
                }
            }
        }
        val i = info ?: return@LazyColumn
        item {
            Row {
                i.poster?.let {
                    coil.compose.AsyncImage(model = it, contentDescription = null, modifier = Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(i.title + (i.year?.let { " ($it)" } ?: ""), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            vm.ratingLabelFor(what ?: i.title, series, null),
                            i.runtimeMinutes?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                            i.genres.take(2).joinToString(", ").ifEmpty { null },
                        ).joinToString("  ·  "),
                        color = TunerColors.Gold, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(desc ?: i.overview ?: "", color = Color(0xFFD5DCF2), fontSize = 14.sp)
                }
            }
        }
        if (i.castIds.isNotEmpty()) {
            item { Text("Cast – tap for what else they're in", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(i.castIds.size) { k ->
                        val c = i.castIds[k]
                        PillButton(c.second, { actor = if (actor == c) null else c }, compact = true, selected = actor == c)
                    }
                }
            }
        }
        actor?.let { a ->
            item { Text("${a.second} is also in", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            val list = films
            if (list == null) item { Text("…", color = TunerColors.TextSecondary) }
            else if (list.isEmpty()) item { Text("Nothing found", color = TunerColors.TextSecondary, fontSize = 14.sp) }
            else items(list.size) { k ->
                val p = list[k]
                val mine = p.film
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TunerColors.Panel).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.part.title + (p.part.year?.let { " ($it)" } ?: ""), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (mine != null) PillButton("▶ On TV", { vm.playFilmOnTv(tv, mine) }, compact = true)
                }
            }
        }
        item { Text("Details from TMDB", color = TunerColors.TextSecondary, fontSize = 12.sp) }
    }
}

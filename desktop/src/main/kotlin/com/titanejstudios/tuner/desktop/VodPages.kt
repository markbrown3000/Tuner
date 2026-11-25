package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.FilmName
import com.titanejstudios.tuner.data.SeriesItem
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.integrations.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ALL_CATS = "All"
private const val MY_LIST = "★ My list"

/** Film and series names without the provider's clutter ("EN - ", "[4K]"). */
private fun tidy(name: String): String = runCatching { FilmName.parse(name).first }.getOrDefault(name).ifBlank { name }

@Composable
private fun CatalogueLayout(
    title: String,
    categories: List<String>,
    category: String,
    onCategory: (String) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    loading: Boolean,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(start = 12.dp)) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            OutlinedTextField(search, onSearch, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(categories, key = { it }) { g ->
                    val sel = g == category && search.isBlank()
                    Text(
                        g, color = if (sel) Color.White else Soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) Sky.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable { onCategory(g) }.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp)) {
            if (loading) Text("Loading…", color = Soft, fontSize = 15.sp, modifier = Modifier.padding(12.dp)) else content()
        }
    }
}

@Composable
fun FilmsPage(app: AppState) {
    LaunchedEffect(Unit) { app.loadVod() }
    var category by remember { mutableStateOf(ALL_CATS) }
    var search by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<VodItem?>(null) }
    val films = app.movies.orEmpty()
    val myList = remember(app.listsVersion) { app.watchlist.filter { !it.isSeries }.map { it.id }.toSet() }
    val categories = remember(films, myList) { listOf(ALL_CATS) + (if (myList.isNotEmpty()) listOf(MY_LIST) else emptyList()) + films.map { it.category }.distinct() }
    val shown = remember(films, category, search, myList) {
        val q = search.trim().lowercase()
        when {
            q.isNotEmpty() -> films.filter { it.name.lowercase().contains(q) }
            category == MY_LIST -> films.filter { it.id in myList }
            category == ALL_CATS -> films
            else -> films.filter { it.category == category }
        }.take(3000)
    }
    CatalogueLayout("Films", categories, category, { category = it; search = "" }, search, { search = it }, app.movies == null) {
        if (films.isEmpty()) Text("No films from your provider (films need an Xtream login).", color = Soft, fontSize = 15.sp, modifier = Modifier.padding(12.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(shown, key = { it.id }) { m ->
                val r = app.config.resume[m.id]
                PosterTile(tidy(m.name), m.poster, m.rating?.let { "★ $it" }, progress = r?.let { if (it.dur > 0) it.pos.toFloat() / it.dur else null }) { open = m }
            }
        }
    }
    open?.let { m -> FilmDetail(app, m) { open = null } }
}

@Composable
private fun FilmDetail(app: AppState, m: VodItem, onClose: () -> Unit) {
    var info by remember(m.id) { mutableStateOf<Tmdb.Info?>(null) }
    LaunchedEffect(m.id) {
        if (Keys.TMDB_KEY.isNotBlank()) info = withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(Keys.TMDB_KEY, m.name, false) }.getOrNull() }
    }
    val r = app.config.resume[m.id]
    val inList = remember(app.listsVersion) { app.inWatchlist(m.id) }
    DetailOverlay(onClose) {
        Row {
            Box(Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(PanelLight)) {
                NetImage(info?.poster ?: m.poster, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info?.title ?: tidy(m.name), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    listOfNotNull(
                        info?.year, info?.runtimeMinutes?.let { "${it / 60}h ${it % 60}m" }, info?.certificate,
                        info?.rating?.let { String.format(java.util.Locale.UK, "★ %.1f", it) } ?: m.rating?.let { "★ $it" },
                        info?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
                    ).joinToString("  ·  "),
                    color = Gold, fontSize = 14.sp,
                )
                info?.overview?.let { Text(it, color = Soft, fontSize = 15.sp) }
                info?.cast?.take(6)?.takeIf { it.isNotEmpty() }?.let { Text("With " + it.joinToString(", "), color = Soft, fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r != null && r.dur > 0) {
                        Pill("▶ Carry on from ${clock(r.pos)}", selected = true) { onClose(); app.playFilm(m) }
                        Pill("From the start") { onClose(); app.playFilm(m, fromStart = true) }
                    } else Pill("▶ Play", selected = true) { onClose(); app.playFilm(m) }
                    Pill(if (inList) "✓ In My list" else "+ My list") { app.toggleWatchlist(WatchItem.of(m, System.currentTimeMillis())) }
                    info?.trailerKey?.let { k -> Pill("Trailer ↗") { openInBrowser("https://www.youtube.com/watch?v=$k") } }
                    DownloadPill(app, m.id, tidy(m.name), m.category, m.poster, m.url)
                }
            }
        }
    }
}

/** A panel over the page, closed by clicking outside it or ✕. */
@Composable
fun DetailOverlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.8f).clip(RoundedCornerShape(18.dp)).background(Panel).clickable(enabled = false) {}.padding(28.dp)) {
            Row {
                Spacer(Modifier.weight(1f))
                Text("✕", color = Soft, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
            }
            content()
        }
    }
}

@Composable
fun SeriesPage(app: AppState) {
    LaunchedEffect(Unit) { app.loadVod() }
    var category by remember { mutableStateOf(ALL_CATS) }
    var search by remember { mutableStateOf("") }
    val all = app.series.orEmpty()
    // Opened from Home's My list.
    val requested = app.openSeriesId
    var open by remember { mutableStateOf<SeriesItem?>(null) }
    LaunchedEffect(requested, all) {
        if (requested != null && all.isNotEmpty()) {
            open = all.firstOrNull { it.id == requested }
            app.openSeriesId = null
        }
    }
    val myList = remember(app.listsVersion) { app.watchlist.filter { it.isSeries }.map { it.id }.toSet() }
    val categories = remember(all, myList) { listOf(ALL_CATS) + (if (myList.isNotEmpty()) listOf(MY_LIST) else emptyList()) + all.map { it.category }.distinct() }
    val shown = remember(all, category, search, myList) {
        val q = search.trim().lowercase()
        when {
            q.isNotEmpty() -> all.filter { it.name.lowercase().contains(q) }
            category == MY_LIST -> all.filter { it.id in myList }
            category == ALL_CATS -> all
            else -> all.filter { it.category == category }
        }.take(3000)
    }
    val o = open
    if (o != null) {
        SeriesDetail(app, o) { open = null }
        return
    }
    CatalogueLayout("Series", categories, category, { category = it; search = "" }, search, { search = it }, app.series == null) {
        if (all.isEmpty()) Text("No series from your provider (series need an Xtream login).", color = Soft, fontSize = 15.sp, modifier = Modifier.padding(12.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(shown, key = { it.id }) { s -> PosterTile(tidy(s.name), s.cover, s.rating?.let { "★ $it" }) { open = s } }
        }
    }
}

@Composable
private fun SeriesDetail(app: AppState, s: SeriesItem, onBack: () -> Unit) {
    var episodes by remember(s.id) { mutableStateOf<List<Episode>?>(null) }
    var season by remember(s.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(s.id) {
        val list = app.episodes(s).sortedWith(compareBy({ it.season }, { it.number }))
        episodes = list
        // Open on the season you're part way through.
        val resumeSeason = list.filter { app.config.resume[it.id] != null }.maxByOrNull { app.config.resume[it.id]?.at ?: 0L }?.season
        season = resumeSeason ?: list.firstOrNull()?.season
    }
    val inList = remember(app.listsVersion) { app.inWatchlist(s.id) }
    val list = episodes
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(160.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(PanelLight)) {
                NetImage(s.cover, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("‹ Back", onClick = onBack)
                Text(tidy(s.name), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(listOfNotNull(s.category, s.rating?.let { "★ $it" }).joinToString("  ·  "), color = Gold, fontSize = 14.sp)
                s.plot?.let { Text(it, color = Soft, fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Carry on: the episode you were last watching (or the next one).
                    val last = list?.filter { app.config.resume[it.id] != null }?.maxByOrNull { app.config.resume[it.id]?.at ?: 0L }
                    if (list != null && last != null) Pill("▶ Carry on: S${last.season} E${last.number}", selected = true) { app.playEpisode(s, list, list.indexOf(last)) }
                    else if (!list.isNullOrEmpty()) Pill("▶ Play from the start", selected = true) { app.playEpisode(s, list, 0, fromStart = true) }
                    Pill(if (inList) "✓ In My list" else "+ My list") { app.toggleWatchlist(WatchItem.of(s, System.currentTimeMillis())) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (list == null) {
            Text("Loading episodes…", color = Soft, fontSize = 15.sp)
            return@Column
        }
        if (list.isEmpty()) {
            Text("No episodes found.", color = Soft, fontSize = 15.sp)
            return@Column
        }
        val seasons = list.map { it.season }.distinct()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons, key = { it }) { n -> Pill("Season $n", selected = n == season) { season = n } }
        }
        Spacer(Modifier.height(10.dp))
        val eps = list.filter { it.season == season }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(eps, key = { it.id }) { e ->
                val r = app.config.resume[e.id]
                val done = (app.config.done[e.id] ?: 0L) > 0L
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).clickable { app.playEpisode(s, list, list.indexOf(e)) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(PanelLight)) {
                        NetImage(e.image ?: s.cover, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (r != null && r.dur > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(r.pos.toFloat() / r.dur).height(4.dp).background(Sky))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.number}. ${e.title}" + if (done) "  ✓" else "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.plot?.let { Text(it, color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (e.durationSecs > 0) Text("${e.durationSecs / 60} min", color = Soft, fontSize = 12.sp)
                    }
                    DownloadPill(app, e.id, tidy(s.name), "S${e.season} E${e.number}  ${e.title}", e.image ?: s.cover, e.url, short = true)
                }
            }
        }
    }
}


/** "⤓ Download" (to watch without the internet), its progress, or "✓ Downloaded". */
@Composable
fun DownloadPill(app: AppState, id: String, title: String, sub: String?, poster: String?, url: String, short: Boolean = false) {
    val d = app.downloads.firstOrNull { it.id == id }
    val progress = app.downloadProgress[id]
    when {
        d == null -> Pill(if (short) "⤓" else "⤓ Download") { app.download(id, title, sub, poster, url) }
        d.state == "downloading" -> Pill(progress?.let { "⤓ ${(it * 100).toInt()}%" } ?: "⤓ …") { }
        d.state == "failed" -> Pill(if (short) "⚠" else "⚠ Download failed – remove") { app.deleteDownload(d) }
        else -> Pill(if (short) "✓" else "✓ Downloaded") { }
    }
}

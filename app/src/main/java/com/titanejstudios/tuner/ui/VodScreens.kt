package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.ResumeEntry
import kotlinx.coroutines.delay

private const val CONTINUE = "▶ Continue watching"
private const val MY_LIST = "★ My list"
private const val ALL_ITEMS = "All"

/** Poster tile used for films and series. */
@Composable
internal fun PosterCard(
    title: String,
    image: String?,
    caption: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        baseColor = TunerColors.Panel,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) { focused ->
        Column(Modifier.fillMaxWidth().padding(if (focused) 4.dp else 0.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0E12)),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(title, color = TunerColors.TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp))
                }
                if (progress != null) {
                    ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
                }
            }
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            if (caption != null) {
                Text(caption, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            }
        }
    }
}

/** Shared layout: categories on the left, a poster grid on the right. */
@Composable
private fun <T> CatalogueScreen(
    title: String,
    items: List<T>?,
    status: String?,
    categoryOf: (T) -> String,
    nameOf: (T) -> String,
    continueEntries: List<ResumeEntry>,
    onReload: () -> Unit,
    switchLabel: String,
    onSwitch: () -> Unit,
    tile: @Composable (T, Modifier) -> Unit,
    onResume: (ResumeEntry) -> Unit,
    /** Your saved films/series (shown as "★ My list"). */
    myList: List<T> = emptyList(),
    /** True while a saved list is being refreshed in the background. */
    updating: Boolean = false,
    /** "Plex ›" button, when Plex is linked. */
    onPlex: (() -> Unit)? = null,
    /** Poster and details for the strip above the grid. */
    imageOf: (T) -> String? = { null },
    detailsOf: (T) -> String? = { null },
    tintOn: Boolean = true,
    /** Phone held upright: categories as chips along the top. */
    compact: Boolean = false,
) {
    var focusedItem by remember { mutableStateOf<T?>(null) }
    var category by remember { mutableStateOf(if (continueEntries.isNotEmpty()) CONTINUE else ALL_ITEMS) }
    val categories = remember(items, continueEntries.isNotEmpty(), myList.isNotEmpty()) {
        buildList {
            if (continueEntries.isNotEmpty()) add(CONTINUE)
            if (myList.isNotEmpty()) add(MY_LIST)
            add(ALL_ITEMS)
            items?.map(categoryOf)?.distinct()?.filter { it != CONTINUE && it != ALL_ITEMS && it != MY_LIST }?.sorted()?.let { addAll(it) }
        }
    }
    val shown = remember(items, category, myList) {
        when (category) {
            ALL_ITEMS -> items.orEmpty().sortedBy { nameOf(it).lowercase() }
            CONTINUE -> emptyList()
            MY_LIST -> myList
            else -> items.orEmpty().filter { categoryOf(it) == category }
        }
    }
    val firstCategory = remember { FocusRequester() }
    LaunchedEffect(items != null) {
        delay(60)
        runCatching { firstCategory.requestFocus() }
    }

    val tint = rememberPictureTint(focusedItem?.let(imageOf), tintOn)
    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, TunerColors.BackgroundBottom)))
            .padding(horizontal = if (compact) 12.dp else 40.dp, vertical = if (compact) 10.dp else 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (compact) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier,
        ) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Text(category, fontSize = 18.sp, color = TunerColors.TextSecondary)
            Spacer(Modifier.width(16.dp))
            Text(
                if (updating) "⟳ Updating the list…" else "Hold OK on a poster for info and My list",
                fontSize = 14.sp,
                color = TunerColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            if (onPlex != null) {
                PillButton("Plex ›", onPlex, compact = true)
                Spacer(Modifier.width(8.dp))
            }
            PillButton(switchLabel, onSwitch, compact = true)
            Spacer(Modifier.width(8.dp))
            PillButton("Reload", onReload, compact = true)
        }
        Spacer(Modifier.height(10.dp))
        if (!compact) focusedItem?.let { f ->
            FocusStrip(nameOf(f), detailsOf(f), imageOf(f), "OK to watch  ·  hold OK for info, trailer and My list")
            Spacer(Modifier.height(10.dp))
        }
        if (compact && items != null && status == null && items.isNotEmpty()) {
            CategoryChips(categories, category) { category = it }
            Spacer(Modifier.height(10.dp))
        }
        if (items == null || status != null) {
            CenterMessage(status ?: "Loading…")
        } else if (items.isEmpty()) {
            CenterMessage("Nothing here – your provider may not include $title.")
        } else {
            Row(Modifier.weight(1f)) {
                if (!compact) LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(categories, key = { it }) { c ->
                        val selected = c == category
                        FocusSurface(
                            onClick = { category = c },
                            onFocused = { category = c },
                            selected = selected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .then(if (c == categories.first()) Modifier.focusRequester(firstCategory) else Modifier),
                        ) { focused ->
                            Text(
                                c,
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = if (focused || selected) Color.White else TunerColors.TextSecondary,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!compact) Spacer(Modifier.width(20.dp))
                if (category == CONTINUE) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (compact) 104.dp else 124.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(continueEntries, key = { it.contentId }) { e ->
                            PosterCard(
                                title = e.title,
                                image = e.poster,
                                caption = e.subtitle ?: "${clock(e.positionMs)} of ${clock(e.durationMs)}",
                                progress = if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null,
                                onClick = { onResume(e) },
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (compact) 104.dp else 124.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(shown.size) { i ->
                            val item = shown[i]
                            tile(item, Modifier.onFocusChanged { if (it.hasFocus) focusedItem = item })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoviesScreen(vm: TunerViewModel) {
    LaunchedEffect(Unit) { vm.loadMovies() }
    var resumeAsk by remember { mutableStateOf<com.titanejstudios.tuner.data.VodItem?>(null) }
    var menuFor by remember { mutableStateOf<com.titanejstudios.tuner.data.VodItem?>(null) }
    val continueEntries = remember(vm.resume, vm.started, vm.activeProfile.id) {
        vm.myResume.values.filter { it.seriesId == null }.sortedByDescending { it.updatedAt }
    }
    CatalogueScreen(
        title = "Films",
        items = vm.movies,
        status = vm.vodStatus,
        categoryOf = { it.category },
        nameOf = { it.name },
        imageOf = { it.poster },
        detailsOf = { m -> listOfNotNull(m.category, m.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }).joinToString("  ·  ") },
        continueEntries = continueEntries,
        onReload = { vm.loadMovies(force = true) },
        switchLabel = "Series ›",
        onSwitch = { vm.navigate(com.titanejstudios.tuner.Screen.Series) },
        onResume = { vm.playResume(it) },
        tile = { m, mod ->
            val r = vm.resume[m.id]
            PosterCard(
                title = m.name,
                image = m.poster,
                caption = listOfNotNull(m.rating?.let { "★ $it" }, if (vm.inWatchlist(m.id)) "✓ My list" else null)
                    .joinToString("  ·  ").ifEmpty { null },
                progress = r?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else null },
                onClick = { if (r != null) resumeAsk = m else vm.playMovie(m, fromStart = true) },
                onLongClick = { menuFor = m },
                modifier = mod,
            )
        },
        myList = remember(vm.watchlist) { vm.watchlist.filter { !it.isSeries }.mapNotNull { it.toVod() } },
        updating = vm.vodUpdating,
        onPlex = if (vm.plexLinked) ({ vm.openPlexLibrary() }) else null,
        tintOn = vm.tintFromPicture,
        compact = compactLayout(vm),
    )
    menuFor?.let { m ->
        MenuDialog(
            title = m.name,
            items = buildList {
                add(MenuItem("ℹ Film info & trailer") { vm.openFilmInfo(m.name, isSeries = false) })
                add(MenuItem(if (vm.inWatchlist(m.id)) "Remove from My list" else "＋ Add to My list") { vm.toggleWatchlist(m) })
                add(MenuItem("Play from the beginning") { vm.playMovie(m, fromStart = true) })
                if (vm.plexLinked && vm.preferPlexFilms) add(MenuItem("Play the IPTV copy", "not Plex's") { vm.playMovie(m, fromStart = false, viaPlex = false) })
                if (vm.isPhone && vm.link.linked) add(MenuItem("📺 Watch on TV") { vm.watchOnTv(vm.filmJson(m)) })
                if (vm.isPhone) addAll(downloadItems(vm, m.id) { vm.downloadMovie(m) })
            },
            onDismiss = { menuFor = null },
        )
    }
    resumeAsk?.let { m ->
        val r = vm.resume[m.id]
        MenuDialog(
            title = m.name,
            items = listOf(
                MenuItem("Resume", r?.let { clock(it.positionMs) }) { vm.playMovie(m, fromStart = false) },
                MenuItem("Start from the beginning") { vm.playMovie(m, fromStart = true) },
            ),
            onDismiss = { resumeAsk = null },
        )
    }
}

@Composable
fun SeriesScreen(vm: TunerViewModel) {
    LaunchedEffect(Unit) { vm.loadSeries() }
    var menuFor by remember { mutableStateOf<com.titanejstudios.tuner.data.SeriesItem?>(null) }
    val continueEntries = remember(vm.resume, vm.started, vm.activeProfile.id) {
        vm.myResume.values.filter { it.seriesId != null }.sortedByDescending { it.updatedAt }
    }
    CatalogueScreen(
        title = "Series",
        items = vm.series,
        status = vm.vodStatus,
        categoryOf = { it.category },
        nameOf = { it.name },
        imageOf = { it.cover },
        detailsOf = { s -> listOfNotNull(s.category, s.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }, s.plot).joinToString("  ·  ") },
        continueEntries = continueEntries,
        onReload = { vm.loadSeries(force = true) },
        switchLabel = "Films ›",
        onSwitch = { vm.back() },
        onResume = { vm.playResume(it) },
        tile = { s, mod ->
            PosterCard(
                title = s.name,
                image = s.cover,
                caption = listOfNotNull(s.rating?.let { "★ $it" }, if (vm.inWatchlist(s.id)) "✓ My list" else null)
                    .joinToString("  ·  ").ifEmpty { null },
                progress = null,
                onClick = { vm.openSeriesDetail(s) },
                onLongClick = { menuFor = s },
                modifier = mod,
            )
        },
        myList = remember(vm.watchlist) { vm.watchlist.filter { it.isSeries }.mapNotNull { it.toSeries() } },
        updating = vm.vodUpdating,
        onPlex = if (vm.plexLinked) ({ vm.openPlexLibrary() }) else null,
        tintOn = vm.tintFromPicture,
        compact = compactLayout(vm),
    )
    menuFor?.let { s ->
        MenuDialog(
            title = s.name,
            items = listOf(
                MenuItem("Open") { vm.openSeriesDetail(s) },
                MenuItem("ℹ Series info & trailer") { vm.openFilmInfo(s.name, isSeries = true) },
                MenuItem(if (vm.inWatchlist(s.id)) "Remove from My list" else "＋ Add to My list") { vm.toggleWatchlist(s) },
            ),
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
fun SeriesDetailScreen(vm: TunerViewModel) {
    val s = vm.openSeries ?: return
    val eps = vm.episodes
    val seasons = remember(eps) { eps.orEmpty().map { it.season }.distinct().sorted() }
    // Start on the season of the episode you were last watching
    val lastWatched = remember(eps, vm.resume) {
        eps.orEmpty().filter { vm.resume.containsKey(it.id) }.maxByOrNull { vm.resume[it.id]?.updatedAt ?: 0L }
    }
    // The episode you're up to: the last one watched, or the next one if you'd nearly finished it.
    val upTo = remember(eps, lastWatched) {
        val list = eps.orEmpty()
        val r = lastWatched?.let { vm.resume[it.id] }
        if (lastWatched == null || r == null) null
        else if (r.durationMs > 0 && r.positionMs >= r.durationMs * 0.9) list.getOrNull(list.indexOf(lastWatched) + 1) ?: lastWatched
        else lastWatched
    }
    var season by remember(seasons) { mutableStateOf(upTo?.season ?: seasons.firstOrNull() ?: 1) }
    val shown = remember(eps, season) { eps.orEmpty().filter { it.season == season } }
    var resumeAsk by remember { mutableStateOf<Episode?>(null) }
    var episodeMenu by remember { mutableStateOf<Episode?>(null) }
    val firstFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val target = remember(shown, upTo) { shown.indexOfFirst { it.id == upTo?.id } }
    LaunchedEffect(eps != null) {
        delay(60)
        if (target >= 0) {
            // Straight to the episode you're up to.
            runCatching { listState.scrollToItem(target) }
            delay(40)
            runCatching { episodeFocus.requestFocus() }
        } else runCatching { firstFocus.requestFocus() }
    }

val paneA: @Composable (Modifier) -> Unit = { pane ->
        Column(pane) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(TunerColors.Panel)) {
                if (s.cover != null) AsyncImage(model = s.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(12.dp))
            Text(s.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            s.rating?.let { Text("★ $it", color = TunerColors.Gold, fontSize = 15.sp) }
            s.plot?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (vm.inWatchlist(s.id)) "✓ In My list" else "＋ My list", { vm.toggleWatchlist(s) }, compact = true)
                PillButton("ℹ Info", { vm.openFilmInfo(s.name, isSeries = true) }, compact = true)
            }
        }
    }
    val paneB: @Composable (Modifier) -> Unit = { pane ->
        Column(pane) {
            if (eps == null) {
                CenterMessage("Loading episodes…")
                return@Column
            }
            if (eps.isEmpty()) {
                CenterMessage("No episodes found")
                return@Column
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it }) { n ->
                    PillButton(
                        "Season $n",
                        { season = n },
                        selected = n == season,
                        modifier = if (n == season) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { e ->
                    val r = vm.resume[e.id]
                    FocusSurface(
                        onClick = { if (r != null) resumeAsk = e else vm.playEpisode(e, eps, fromStart = true) },
                        onLongClick = { episodeMenu = e },
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(96.dp)
                            .then(if (e.id == upTo?.id) Modifier.focusRequester(episodeFocus) else Modifier),
                    ) { focused ->
                        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 136.dp, height = 76.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0E12))) {
                                val img = e.image ?: s.cover
                                if (img != null) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (r != null && r.durationMs > 0) {
                                    ProgressBar(r.positionMs.toFloat() / r.durationMs, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${e.number}. ${e.title}" + (if (vm.downloads.any { it.contentId == e.id }) "  ⬇" else ""),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                e.plot?.let {
                                    Text(it, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (e.durationSecs > 0) {
                                Text("${e.durationSecs / 60} min", color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    // Phones held upright: one above the other instead of side by side.
    if (narrowScreen()) {
        Column(Modifier.fillMaxSize().skyBackground().padding(14.dp)) {
            // A small header instead of the big poster.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 70.dp, height = 105.dp).clip(RoundedCornerShape(8.dp)).background(TunerColors.Panel)) {
                    if (s.cover != null) AsyncImage(model = s.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        PillButton(if (vm.inWatchlist(s.id)) "✓ My list" else "＋ My list", { vm.toggleWatchlist(s) }, compact = true)
                        PillButton("ℹ Info", { vm.openFilmInfo(s.name, isSeries = true) }, compact = true)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            paneB(Modifier.fillMaxWidth().weight(1f))
        }
    } else {
        Row(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
            paneA(Modifier.width(300.dp))
            paneB(Modifier.weight(1f))
        }
    }

    episodeMenu?.let { e ->
        MenuDialog(
            title = "S${e.season} E${e.number}  ${e.title}",
            items = buildList {
                add(MenuItem("▶ Play") { vm.playEpisode(e, eps.orEmpty(), fromStart = vm.resume[e.id] == null) })
                add(MenuItem("Start from the beginning") { vm.playEpisode(e, eps.orEmpty(), fromStart = true) })
                if (vm.isPhone && vm.link.linked) add(MenuItem("📺 Watch on TV") { vm.watchOnTv(vm.episodeJson(e)) })
                if (vm.isPhone) addAll(downloadItems(vm, e.id) { vm.downloadEpisode(e) })
            },
            onDismiss = { episodeMenu = null },
        )
    }
    resumeAsk?.let { e ->
        val r = vm.resume[e.id]
        MenuDialog(
            title = "S${e.season} E${e.number}  ${e.title}",
            items = listOf(
                MenuItem("Resume", r?.let { clock(it.positionMs) }) { vm.playEpisode(e, eps.orEmpty(), fromStart = false) },
                MenuItem("Start from the beginning") { vm.playEpisode(e, eps.orEmpty(), fromStart = true) },
            ),
            onDismiss = { resumeAsk = null },
        )
    }
}

/** Menu items for saving a film or episode on a phone, or playing and deleting the saved copy. */
internal fun downloadItems(vm: TunerViewModel, contentId: String, start: () -> Unit): List<MenuItem> {
    val d = vm.downloads.firstOrNull { it.contentId == contentId }
    return when {
        d == null -> listOf(MenuItem("⬇ Download", "watch it offline") { start() })
        vm.downloader.isReady(contentId) -> listOf(
            MenuItem("▶ Play the download") { vm.playDownload(d) },
            MenuItem("🗑 Delete the download") { vm.removeDownload(contentId) },
        )
        else -> listOf(MenuItem("⬇ Downloading…", "cancel") { vm.removeDownload(contentId) })
    }
}

package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.delay

private const val PLEX_CONTINUE = "▶ Continue watching"
private const val PLEX_RESULTS = "🔍 Search results"

/** Films & TV › Plex: your Plex libraries (films and TV), with Continue watching first. */
@Composable
fun PlexLibraryScreen(vm: TunerViewModel) {
    val plex = vm.plexFeature
    val compact = compactLayout(vm)
    val kids = vm.activeProfile.kids
    // Kids' profiles only see the libraries picked for them.
    val sections = remember(plex.sections, kids, plex.kidsSections) { plex.sections.filter { vm.plexSectionAllowed(it) } }
    // "Films" on two servers would look the same – add the server's name then.
    val labels = remember(sections) {
        val dupes = sections.groupBy { it.title }.filter { it.value.size > 1 }.keys
        sections.associate { it.id to (if (it.title in dupes) "${it.title} (${it.server.name})" else it.title) }
    }
    val categories = remember(sections, vm.myPlexItems.isNotEmpty(), plex.query.isNotEmpty()) {
        buildList {
            if (plex.query.isNotEmpty()) add(PLEX_RESULTS)
            if (vm.myPlexItems.isNotEmpty() && !kids) add(PLEX_CONTINUE)
            sections.forEach { add(it.id) }
        }
    }
    val category = plex.libraryCategory
    LaunchedEffect(categories) {
        if (category == null || category !in categories) plex.libraryCategory = categories.firstOrNull()
    }
    val section = sections.firstOrNull { it.id == category }
    val genre = section?.let { plex.genreFor[it.id] }
    LaunchedEffect(section?.id, plex.sort, genre) { section?.let { plex.loadSection(it) } }
    var menuFor by remember { mutableStateOf<Plex.Entry?>(null) }
    var genreMenu by remember { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf<Plex.Section?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val sortLabel = com.titanejstudios.tuner.features.PlexFeature.SORTS.firstOrNull { it.key == plex.sort }?.label ?: "Title A–Z"

    // One focus handle per library, so focus goes back to the one you were on (not the top one)
    // after the Sort or Search box closes, or when you come back from a film or show.
    val focusFor = remember(categories) { categories.associateWith { FocusRequester() } }
    val dialogOpen = sortMenu || searching || genreMenu || libraryMenu != null
    LaunchedEffect(categories.isNotEmpty(), dialogOpen) {
        if (dialogOpen || categories.isEmpty()) return@LaunchedEffect
        delay(80)
        runCatching { focusFor[category ?: categories.first()]?.requestFocus() }
    }

    var focusedEntry by remember { mutableStateOf<Plex.Entry?>(null) }
    val tint = rememberPictureTint(focusedEntry?.let { it.art ?: it.image }, vm.tintFromPicture)
    Column(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, TunerColors.BackgroundBottom)))
            .padding(horizontal = if (compact) 12.dp else 40.dp, vertical = if (compact) 10.dp else 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (compact) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier,
        ) {
            Text("Plex", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Text(
                category?.let { if (it == PLEX_CONTINUE || it == PLEX_RESULTS) it else labels[it] } ?: "",
                fontSize = 18.sp, color = TunerColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (plex.query.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("for \"${plex.query}\"", fontSize = 16.sp, color = TunerColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            PillButton("🔍 Search", { searching = true }, compact = true)
            Spacer(Modifier.width(8.dp))
            if (plex.query.isNotEmpty()) {
                PillButton("✕ Clear", {
                    plex.changeQuery("")
                    if (category == PLEX_RESULTS) plex.libraryCategory = null
                }, compact = true)
                Spacer(Modifier.width(8.dp))
            }
            if (section != null) {
                PillButton("Genre: " + (genre?.second ?: "All"), {
                    plex.loadGenres(section)
                    genreMenu = true
                }, compact = true)
                Spacer(Modifier.width(8.dp))
            }
            PillButton("Sort: $sortLabel", { sortMenu = true }, compact = true)
            Spacer(Modifier.width(8.dp))
            if (vm.hasVod) {
                PillButton("Films ›", { vm.navigate(com.titanejstudios.tuner.Screen.Movies) }, compact = true)
                Spacer(Modifier.width(8.dp))
            }
            PillButton("Reload", {
                plex.loadSections(force = true)
                vm.refreshPlex(force = true)
            }, compact = true)
        }
        Spacer(Modifier.height(10.dp))
        if (compact && categories.isNotEmpty()) {
            CategoryChips(categories, category, label = { c ->
                if (c == PLEX_CONTINUE || c == PLEX_RESULTS) c else labels[c] ?: c
            }) { plex.libraryCategory = it }
            Spacer(Modifier.height(10.dp))
        }
        if (!compact) focusedEntry?.let { e ->
            FocusStrip(
                e.title,
                listOfNotNull(e.year, if (e.isShow) "TV" else "Film", e.rating, e.library).joinToString("  ·  "),
                e.image,
                if (e.isShow) "OK opens the show  ·  hold OK for more" else "OK to watch  ·  hold OK for more",
            )
            Spacer(Modifier.height(10.dp))
        }
        if (categories.isEmpty()) {
            CenterMessage(plex.libraryStatus ?: "Looking for your Plex libraries…")
            return@Column
        }
        Row(Modifier.weight(1f)) {
            if (!compact) LazyColumn(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(categories.size, key = { categories[it] }) { i ->
                    val c = categories[i]
                    val selected = c == category
                    val s = sections.firstOrNull { it.id == c }
                    FocusSurface(
                        memoryKey = "plexcat|$c",
                        onClick = { plex.libraryCategory = c },
                        onFocused = { plex.libraryCategory = c },
                        // Grown-ups: hold OK on a library to let kids' profiles see it.
                        onLongClick = if (s != null && !kids) ({ libraryMenu = s }) else null,
                        selected = selected,
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                            .then(focusFor[c]?.let { Modifier.focusRequester(it) } ?: Modifier),
                    ) { focused ->
                        Text(
                            if (c == PLEX_CONTINUE || c == PLEX_RESULTS) c
                            else (if (s?.isShows == true) "📺 " else "🎬 ") + (labels[c] ?: c) + (if (!kids && c in plex.kidsSections) "  🧸" else ""),
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
            when {
                category == PLEX_RESULTS -> {
                    val results = plex.searchResults?.let { r -> if (kids) r.filter { plex.isKidsEntry(it) } else r }
                    if (results == null) {
                        CenterMessage("Searching all your Plex libraries for \"${plex.query}\"…")
                    } else if (results.isEmpty()) {
                        CenterMessage("Nothing on Plex matches \"${plex.query}\"")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (compact) 104.dp else 116.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(results.size, key = { results[it].server.machineId + results[it].ratingKey }) { i ->
                                val e = results[i]
                                PosterCard(
                                    title = e.title,
                                    image = e.image,
                                    caption = listOfNotNull(e.year, e.library).joinToString("  ·  ").ifEmpty { null },
                                    progress = if (!e.isShow && e.durationMs > 0 && e.viewOffsetMs > 0) e.viewOffsetMs.toFloat() / e.durationMs else null,
                                    onClick = { if (e.isShow) vm.openPlexShow(e) else vm.playPlexEntry(e) },
                                    onLongClick = { menuFor = e },
                                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedEntry = e },
                                )
                            }
                        }
                    }
                }
                category == PLEX_CONTINUE -> {
                    val deck = vm.myPlexItems
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (compact) 104.dp else 116.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(deck.size, key = { deck[it].server.machineId + deck[it].ratingKey }) { i ->
                            val p = deck[i]
                            PosterCard(
                                title = p.title,
                                image = p.image,
                                caption = p.subtitle,
                                progress = if (p.durationMs > 0) p.viewOffsetMs.toFloat() / p.durationMs else null,
                                onClick = { vm.playPlex(p) },
                            )
                        }
                    }
                }
                section != null -> {
                    val entries = plex.itemsFor(section)
                    if (entries == null) {
                        CenterMessage(plex.libraryStatus ?: "Loading ${section.title}…")
                    } else if (entries.isEmpty()) {
                        CenterMessage("${section.title} is empty")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (compact) 104.dp else 116.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(entries.size, key = { entries[it].ratingKey }) { i ->
                                val e = entries[i]
                                PosterCard(
                                    title = e.title,
                                    image = e.image,
                                    caption = plexCaption(e),
                                    progress = if (!e.isShow && e.durationMs > 0 && e.viewOffsetMs > 0) e.viewOffsetMs.toFloat() / e.durationMs else null,
                                    onClick = { if (e.isShow) vm.openPlexShow(e) else vm.playPlexEntry(e) },
                                    onLongClick = { menuFor = e },
                                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedEntry = e },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sortMenu) {
        MenuDialog(
            title = "Sort by",
            items = com.titanejstudios.tuner.features.PlexFeature.SORTS.map { o ->
                MenuItem((if (o.key == plex.sort) "✓ " else "") + o.label) { plex.changeSort(o.key) }
            },
            onDismiss = { sortMenu = false },
        )
    }
    if (searching) {
        TextInputDialog(
            title = "Search all of Plex",
            initial = plex.query,
            hint = "A title, part of one, or an actor – films and TV",
            label = "Title",
            onDismiss = { searching = false },
            onDone = { text ->
                searching = false
                plex.changeQuery(text)
                plex.libraryCategory = if (text.isBlank()) null else PLEX_RESULTS
            },
        )
    }

    menuFor?.let { e ->
        MenuDialog(
            title = e.title + (e.year?.let { " ($it)" } ?: ""),
            subtitle = e.summary?.take(220),
            items = buildList {
                if (e.isShow) {
                    add(MenuItem("Open") { vm.openPlexShow(e) })
                } else {
                    if (e.viewOffsetMs > 0) add(MenuItem("Carry on from ${clock(e.viewOffsetMs)}") { vm.playPlexEntry(e) })
                    add(MenuItem("Play from the beginning") { vm.playPlexEntry(e, fromStart = true) })
                }
                val watched = if (e.isShow) e.leafCount > 0 && e.viewedLeafCount >= e.leafCount else e.watched
                add(MenuItem(if (watched) "Mark as unwatched" else "✓ Mark as watched") { vm.setPlexWatched(e, !watched) })
                add(MenuItem("ℹ Info & trailer") { vm.openFilmInfo(e.title + (e.year?.let { " ($it)" } ?: ""), isSeries = e.isShow) })
            },
            onDismiss = { menuFor = null },
        )
    }

    if (genreMenu && section != null) {
        val list = plex.genres
        MenuDialog(
            title = "Genre – " + (labels[section.id] ?: section.title),
            subtitle = if (list == null) "Loading genres…" else if (list.isEmpty()) "Plex has no genres for this library" else null,
            items = buildList {
                add(MenuItem((if (genre == null) "✓ " else "") + "All") { plex.changeGenre(section, null) })
                list.orEmpty().forEach { g ->
                    add(MenuItem((if (genre?.first == g.first) "✓ " else "") + g.second) { plex.changeGenre(section, g) })
                }
            },
            onDismiss = { genreMenu = false },
        )
    }

    libraryMenu?.let { s ->
        val allowed = s.id in plex.kidsSections
        MenuDialog(
            title = labels[s.id] ?: s.title,
            subtitle = if (allowed) "Kids' profiles can see this library" else "Kids' profiles can't see this library",
            items = listOf(
                MenuItem(if (allowed) "🚫 Hide from kids' profiles" else "🧸 Allow for kids' profiles") { vm.toggleKidsPlexLibrary(s) },
            ),
            onDismiss = { libraryMenu = null },
        )
    }
}

/** A row of Plex posters (Home's "New on Plex"). */
@Composable
internal fun PlexEntryRail(vm: TunerViewModel, entries: List<Plex.Entry>, firstModifier: Modifier, onFocus: (Plex.Entry) -> Unit = {}) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(entries.size, key = { "plexnew" + entries[it].server.machineId + entries[it].ratingKey }) { i ->
            val e = entries[i]
            PosterCard(
                title = e.title,
                image = e.image,
                caption = listOfNotNull(e.year, if (e.isShow) "TV" else null).joinToString("  ·  ").ifEmpty { null },
                progress = null,
                onClick = { if (e.isShow) vm.openPlexShow(e) else vm.playPlexEntry(e) },
                modifier = (if (i == 0) firstModifier else Modifier).width(118.dp)
                    .onFocusChanged { if (it.hasFocus) onFocus(e) },
            )
        }
    }
}

private fun plexCaption(e: Plex.Entry): String? = when {
    e.isShow && e.leafCount > 0 -> {
        val left = e.leafCount - e.viewedLeafCount
        if (left > 0 && e.viewedLeafCount > 0) "$left unwatched" else "${e.leafCount} episodes"
    }
    e.watched -> listOfNotNull(e.year, "✓ watched").joinToString("  ·  ")
    else -> listOfNotNull(e.year, e.rating).joinToString("  ·  ").ifEmpty { null }
}

/** A Plex show: seasons along the top, episodes below. Plays here, then carries on to the next episode. */
@Composable
fun PlexShowScreen(vm: TunerViewModel) {
    val plex = vm.plexFeature
    val show = plex.show ?: return
    val episodes = plex.showEpisodes
    val seasons = remember(episodes) { episodes.orEmpty().map { it.season }.distinct().sorted() }
    // The episode you're up to: one you're part-way through, else the first unwatched after the
    // last one you watched, else the first unwatched.
    val upTo = remember(episodes) {
        val list = episodes.orEmpty()
        list.firstOrNull { it.viewOffsetMs > 0 && !it.watched }
            ?: list.indexOfLast { it.watched }.takeIf { it >= 0 }?.let { i -> list.drop(i + 1).firstOrNull { !it.watched } }
            ?: list.firstOrNull { !it.watched }
    }
    var season by remember(episodes) {
        // "Go to Season N" from the player opens that season.
        mutableStateOf(plex.openAtSeason?.takeIf { it in seasons } ?: upTo?.season ?: seasons.firstOrNull() ?: 1)
    }
    LaunchedEffect(episodes != null) { if (episodes != null) plex.openAtSeason = null }
    val shown = remember(episodes, season) { episodes.orEmpty().filter { it.season == season } }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Index of the highlighted episode in this season (the one you're up to, or the first).
    val target = remember(shown, upTo) { shown.indexOfFirst { it.ratingKey == upTo?.ratingKey }.coerceAtLeast(0) }
    LaunchedEffect(episodes != null, season) {
        if (shown.isEmpty()) return@LaunchedEffect
        runCatching { listState.scrollToItem(target) }
        delay(80)
        runCatching { first.requestFocus() }
    }
    val compact = compactLayout(vm)
    // The seasons and episodes, beside the poster on a TV, under a small header on a phone.
    val episodesPane: @Composable (Modifier) -> Unit = { paneModifier ->
        Column(paneModifier) {
            if (episodes == null) {
                CenterMessage("Loading episodes…")
                return@Column
            }
            if (episodes.isEmpty()) {
                CenterMessage("Couldn't load the episodes")
                return@Column
            }
            // How far through the whole show, and a way back to the very first episode.
            val firstEp = remember(episodes) { episodes.filter { it.season > 0 }.minWithOrNull(compareBy({ it.season }, { it.episode })) ?: episodes.first() }
            val carryOn: (() -> Unit)? = upTo?.let { u -> { vm.playPlexEntry(u, fromStart = u.watched) } }
            SeriesProgress(
                watched = episodes.count { it.watched }, total = episodes.size,
                upToLabel = upTo?.let { "S${it.season} E${it.episode}" }, onCarryOn = carryOn,
                onFromStart = { vm.playPlexEntry(firstEp, fromStart = true) },
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons.size, key = { seasons[it] }) { i ->
                    val s = seasons[i]
                    PillButton(
                        if (s == 0) "Specials" else "Season $s",
                        { season = s },
                        compact = true,
                        selected = s == season,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown.size, key = { shown[it].ratingKey }) { i ->
                    val e = shown[i]
                    FocusSurface(
                        memoryKey = "pep|${e.ratingKey}",
                        onClick = { vm.playPlexEntry(e, fromStart = e.watched) },
                        onLongClick = { vm.setPlexWatched(e, !e.watched) },
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(96.dp)
                            .then(if (i == target) Modifier.focusRequester(first) else Modifier),
                    ) { focused ->
                        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (e.image != null) {
                                AsyncImage(
                                    model = e.image, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 136.dp, height = 76.dp).clip(RoundedCornerShape(8.dp)),
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${e.episode}. ${e.title}",
                                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        if (e.durationMs > 0) "${e.durationMs / 60_000} min" else null,
                                        when {
                                            e.ratingKey == upTo?.ratingKey && e.viewOffsetMs <= 0 -> "▶ up next"
                                            e.watched -> "✓ watched"
                                            e.viewOffsetMs > 0 -> "carry on from ${clock(e.viewOffsetMs)}"
                                            else -> null
                                        },
                                    ).joinToString("  ·  "),
                                    color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 14.sp,
                                )
                                e.summary?.let {
                                    Text(
                                        it, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (e.durationMs > 0 && e.viewOffsetMs > 0 && !e.watched) {
                                ProgressBar(e.viewOffsetMs.toFloat() / e.durationMs, Modifier.width(80.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (compact) {
        Column(Modifier.fillMaxSize().skyBackground().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (show.image != null) {
                    AsyncImage(
                        model = show.image, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 80.dp, height = 120.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(show.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(show.year, show.rating, "${show.leafCount} episodes").joinToString("  ·  "), color = TunerColors.TextSecondary, fontSize = 13.sp)
                    show.summary?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.height(12.dp))
            episodesPane(Modifier.weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize().skyBackground().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Column(Modifier.width(300.dp)) {
            if (show.image != null) {
                AsyncImage(
                    model = show.image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 240.dp, height = 360.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(14.dp))
            }
            Text(show.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(show.year, show.rating, "${show.leafCount} episodes").joinToString("  ·  "),
                color = TunerColors.TextSecondary, fontSize = 15.sp,
            )
            show.summary?.let { AutoScrollText(it, Modifier.weight(1f).padding(top = 10.dp)) }
        }
            Spacer(Modifier.width(28.dp))
            episodesPane(Modifier.weight(1f))
        }
    }
}

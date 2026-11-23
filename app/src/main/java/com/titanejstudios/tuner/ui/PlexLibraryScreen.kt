package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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

/** Films & TV › Plex: your Plex libraries (films and TV), with Continue watching first. */
@Composable
fun PlexLibraryScreen(vm: TunerViewModel) {
    val plex = vm.plexFeature
    val sections = plex.sections
    // "Films" on two servers would look the same – add the server's name then.
    val labels = remember(sections) {
        val dupes = sections.groupBy { it.title }.filter { it.value.size > 1 }.keys
        sections.associate { it.id to (if (it.title in dupes) "${it.title} (${it.server.name})" else it.title) }
    }
    val categories = remember(sections, vm.plexItems.isNotEmpty()) {
        buildList {
            if (vm.plexItems.isNotEmpty()) add(PLEX_CONTINUE)
            sections.forEach { add(it.id) }
        }
    }
    var category by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(categories) {
        if (category == null || category !in categories) category = categories.firstOrNull()
    }
    val section = sections.firstOrNull { it.id == category }
    LaunchedEffect(section?.id, plex.sort, plex.query) { section?.let { plex.loadSection(it) } }
    var menuFor by remember { mutableStateOf<Plex.Entry?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val sortLabel = com.titanejstudios.tuner.features.PlexFeature.SORTS.firstOrNull { it.key == plex.sort }?.label ?: "Title A–Z"

    val firstCategory = remember { FocusRequester() }
    LaunchedEffect(categories.isNotEmpty()) {
        delay(60)
        runCatching { firstCategory.requestFocus() }
    }

    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plex", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Text(
                category?.let { if (it == PLEX_CONTINUE) it else labels[it] } ?: "",
                fontSize = 18.sp, color = TunerColors.TextSecondary,
            )
            if (plex.query.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("matching \"${plex.query}\"", fontSize = 16.sp, color = TunerColors.Accent)
            }
            Spacer(Modifier.weight(1f))
            PillButton("🔍 Search", { searching = true }, compact = true)
            Spacer(Modifier.width(8.dp))
            if (plex.query.isNotEmpty()) {
                PillButton("✕ Clear", { plex.changeQuery("") }, compact = true)
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
        Spacer(Modifier.height(16.dp))
        if (categories.isEmpty()) {
            CenterMessage(plex.libraryStatus ?: "Looking for your Plex libraries…")
            return@Column
        }
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(categories.size, key = { categories[it] }) { i ->
                    val c = categories[i]
                    val selected = c == category
                    val s = sections.firstOrNull { it.id == c }
                    FocusSurface(
                        onClick = { category = c },
                        onFocused = { category = c },
                        selected = selected,
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                            .then(if (i == 0) Modifier.focusRequester(firstCategory) else Modifier),
                    ) { focused ->
                        Text(
                            if (c == PLEX_CONTINUE) c else (if (s?.isShows == true) "📺 " else "🎬 ") + (labels[c] ?: c),
                            modifier = Modifier.padding(horizontal = 14.dp),
                            color = if (focused || selected) Color.White else TunerColors.TextSecondary,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            when {
                category == PLEX_CONTINUE -> {
                    val deck = vm.plexItems
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(116.dp),
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
                        CenterMessage(if (plex.query.isNotEmpty()) "Nothing in ${section.title} matches \"${plex.query}\"" else "${section.title} is empty")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(116.dp),
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
            title = "Search " + (section?.let { labels[it.id] } ?: "Plex"),
            initial = plex.query,
            hint = "Part of a title is enough",
            label = "Title",
            onDismiss = { searching = false },
            onDone = { text ->
                searching = false
                // Search needs a library – from Continue watching, use the first one.
                if (section == null) categories.firstOrNull { it != PLEX_CONTINUE }?.let { category = it }
                plex.changeQuery(text)
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
                add(MenuItem("ℹ Info & trailer") { vm.openFilmInfo(e.title + (e.year?.let { " ($it)" } ?: ""), isSeries = e.isShow) })
            },
            onDismiss = { menuFor = null },
        )
    }
}

private fun plexCaption(e: Plex.Entry): String? = when {
    e.isShow && e.leafCount > 0 -> {
        val left = e.leafCount - e.viewedLeafCount
        if (left > 0 && e.viewedLeafCount > 0) "$left unwatched" else "${e.leafCount} episodes"
    }
    e.watched -> listOfNotNull(e.year, "✓ watched").joinToString("  ·  ")
    else -> listOfNotNull(e.year, e.rating?.let { "★ $it" }).joinToString("  ·  ").ifEmpty { null }
}

/** A Plex show: seasons along the top, episodes below. Plays here, then carries on to the next episode. */
@Composable
fun PlexShowScreen(vm: TunerViewModel) {
    val plex = vm.plexFeature
    val show = plex.show ?: return
    val episodes = plex.showEpisodes
    val seasons = remember(episodes) { episodes.orEmpty().map { it.season }.distinct().sorted() }
    // Start on the season with the first unwatched episode.
    var season by remember(episodes) {
        mutableStateOf(episodes?.firstOrNull { !it.watched }?.season ?: seasons.firstOrNull() ?: 1)
    }
    val shown = remember(episodes, season) { episodes.orEmpty().filter { it.season == season } }
    val first = remember { FocusRequester() }
    LaunchedEffect(episodes != null) {
        delay(80)
        runCatching { first.requestFocus() }
    }
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
                listOfNotNull(show.year, show.rating?.let { "★ $it" }, "${show.leafCount} episodes").joinToString("  ·  "),
                color = TunerColors.TextSecondary, fontSize = 15.sp,
            )
            show.summary?.let { AutoScrollText(it, Modifier.weight(1f).padding(top = 10.dp)) }
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            if (episodes == null) {
                CenterMessage("Loading episodes…")
                return@Column
            }
            if (episodes.isEmpty()) {
                CenterMessage("Couldn't load the episodes")
                return@Column
            }
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown.size, key = { shown[it].ratingKey }) { i ->
                    val e = shown[i]
                    FocusSurface(
                        onClick = { vm.playPlexEntry(e, fromStart = e.watched) },
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(96.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
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
}

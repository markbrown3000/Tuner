package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plex linking (plex.tv/link PIN) and the "Continue on Plex" list.
 * Moved out of TunerViewModel in 5.3; playing a Plex item still happens in the view model,
 * because it shares the player with everything else.
 */
class PlexFeature(private val store: Store, private val scope: CoroutineScope) {
    var linked by mutableStateOf(store.plexToken != null); private set
    var code by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var items by mutableStateOf<List<Plex.Item>>(emptyList()); private set
    private var servers: List<Plex.Server> = emptyList()
    private var job: Job? = null

    private fun client() = Plex(store.plexClientId)

    /** Shows a code to type in at plex.tv/link, then waits for it to be used. */
    fun link() {
        job?.cancel()
        code = null
        status = "Getting a code from Plex…"
        job = scope.launch {
            val plex = client()
            val pin = withContext(Dispatchers.IO) { runCatching { plex.createPin() } }.getOrElse {
                status = "Couldn't reach Plex: ${it.message}"
                return@launch
            }
            code = pin.code
            status = "On your phone or computer, go to plex.tv/link and enter the code"
            repeat(150) {
                delay(2_000)
                val token = withContext(Dispatchers.IO) { runCatching { plex.checkPin(pin) }.getOrNull() }
                if (token != null) {
                    store.plexToken = token
                    linked = true
                    code = null
                    status = "Linked ✓"
                    job = null
                    refresh(force = true)
                    return@launch
                }
            }
            code = null
            status = "The code expired – try again"
        }
    }

    fun unlink() {
        job?.cancel()
        store.plexToken = null
        linked = false
        code = null
        items = emptyList()
        servers = emptyList()
        status = "Plex unlinked"
    }

    /** Re-reads Continue watching from each server. */
    fun refresh(force: Boolean = false) {
        val token = store.plexToken ?: return
        if (!force && job?.isActive == true) return
        job = scope.launch {
            val plex = client()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (servers.isEmpty() || force) servers = plex.servers(token)
                    plex.onDeck(servers)
                }.onFailure { AppLog.warn("Plex: couldn't load Continue watching", it) }.getOrNull()
            }
            if (result != null) {
                items = result
                if (force) {
                    status = if (servers.isEmpty()) "Linked, but no Plex server answered on this network"
                    else "Linked ✓ · ${servers.joinToString { it.name }}"
                }
            }
        }
    }

    /** Settings were restored from a backup or another TV. */
    fun reload() {
        linked = store.plexToken != null
        servers = emptyList()
        items = emptyList()
        if (linked) refresh(force = true)
    }

    // ---------------- Library browsing ----------------

    var sections by mutableStateOf<List<Plex.Section>>(emptyList()); private set
    /** Section id → its films or shows (loaded when you open it, then kept). */
    var sectionItems by mutableStateOf<Map<String, List<Plex.Entry>>>(emptyMap()); private set
    var libraryStatus by mutableStateOf<String?>(null); private set
    var show by mutableStateOf<Plex.Entry?>(null); private set
    var showEpisodes by mutableStateOf<List<Plex.Entry>?>(null); private set
    private var libraryJob: Job? = null

    /** Finds the film and TV libraries on your servers. */
    fun loadSections(force: Boolean = false) {
        val token = store.plexToken ?: return
        if (sections.isNotEmpty() && !force) return
        libraryStatus = "Looking for your Plex libraries…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (servers.isEmpty() || force) servers = client().servers(token)
                    client().sections(servers)
                }
            }
            result.onSuccess { list ->
                sections = list
                if (force) sectionItems = emptyMap()
                libraryStatus = if (list.isEmpty()) {
                    if (servers.isEmpty()) "No Plex server answered on this network" else "No film or TV libraries found"
                } else null
            }.onFailure { libraryStatus = "Couldn't reach Plex: ${it.message}" }
        }
    }

    /** The library picked on the left of the Plex screen – kept while you open shows and films. */
    var libraryCategory by mutableStateOf<String?>(null)

    /** Library order: Title A–Z, Newly added, … */
    var sort by mutableStateOf(store.plexSort.takeIf { k -> SORTS.any { it.key == k } } ?: "title"); private set
    /** Search text for the library being shown ("" = everything). */
    var query by mutableStateOf(""); private set

    fun changeSort(key: String) { sort = key; store.plexSort = key }
    fun changeQuery(text: String) { query = text.trim() }

    private fun cacheKey(section: Plex.Section) = "${section.id}|$sort|$query"

    /** What's loaded for [section] with the current order and search, or null while loading. */
    fun itemsFor(section: Plex.Section): List<Plex.Entry>? = sectionItems[cacheKey(section)]

    /** Loads one library's contents in the current order/search (once; [force] reloads). */
    fun loadSection(section: Plex.Section, force: Boolean = false) {
        val key = cacheKey(section)
        if (sectionItems.containsKey(key) && !force) return
        libraryJob?.cancel()
        libraryStatus = if (query.isEmpty()) "Loading ${section.title}…" else "Searching ${section.title} for \"$query\"…"
        val plexSort = SORTS.firstOrNull { it.key == sort }?.let { if (section.isShows) it.shows else it.films }
        val title = query
        libraryJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    client().sectionItems(section, plexSort, title) { soFar ->
                        // Show posters as each page arrives instead of waiting for the whole library.
                        scope.launch { if (libraryJob?.isActive == true) sectionItems = keepRecent(sectionItems, key, soFar) }
                    }
                }
            }
            result.onSuccess { list ->
                sectionItems = keepRecent(sectionItems, key, list)
                libraryStatus = null
            }.onFailure {
                AppLog.warn("Plex: couldn't load ${section.title}", it)
                libraryStatus = "Couldn't load ${section.title}: ${it.message}"
            }
        }
    }

    /** Keeps the last few libraries in memory – they can be thousands of titles each. */
    private fun keepRecent(map: Map<String, List<Plex.Entry>>, id: String, list: List<Plex.Entry>): Map<String, List<Plex.Entry>> {
        val m = LinkedHashMap(map)
        m.remove(id)
        m[id] = list
        while (m.size > 4) m.remove(m.keys.first())
        return m
    }

    /** Opens a show: loads all its episodes. */
    fun openShow(entry: Plex.Entry) {
        show = entry
        showEpisodes = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client().episodes(entry) } }
            showEpisodes = result.getOrElse {
                AppLog.warn("Plex: couldn't load episodes of ${entry.title}", it)
                emptyList()
            }
        }
    }

    /** Tells Plex where you got to (so its own apps carry on from there). */
    fun reportProgress(item: Plex.Item, stopped: Boolean, positionMs: Long, durationMs: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching { client().timeline(item, if (stopped) "stopped" else "playing", positionMs, durationMs) }
        }
    }

    /** One way of ordering a library; [films]/[shows] are Plex's sort values. */
    class SortOption(val key: String, val label: String, val films: String, val shows: String)

    companion object {
        val SORTS = listOf(
            SortOption("title", "Title A–Z", "titleSort", "titleSort"),
            SortOption("added", "Newly added", "addedAt:desc", "episode.addedAt:desc"),
            SortOption("released", "Newest releases", "originallyAvailableAt:desc", "originallyAvailableAt:desc"),
            SortOption("rating", "Highest rated", "audienceRating:desc", "audienceRating:desc"),
            SortOption("watched", "Recently watched", "lastViewedAt:desc", "lastViewedAt:desc"),
            SortOption("unwatched", "Unwatched first", "viewCount,titleSort", "unviewedLeafCount:desc,titleSort"),
        )
    }
}

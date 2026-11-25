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
    /** Newest films and shows on Plex (Home's "New on Plex" row). */
    var recent by mutableStateOf<List<Plex.Entry>>(emptyList()); private set
    var servers: List<Plex.Server> = emptyList(); private set
    private var job: Job? = null

    private fun client() = Plex(store.plexClientId)

    /** The Plex Home user this profile watches as (null = the main account). */
    var userToken: String? = null; private set

    private fun activeToken(): String? = if (store.plexToken == null) null else userToken ?: store.plexToken

    /** A different Flicker profile: its own Plex Home user (or the main account). */
    fun useUser(token: String?) {
        if (token == userToken) return
        userToken = token
        job?.cancel()
        servers = emptyList()
        items = emptyList()
        recent = emptyList()
        sections = emptyList()
        sectionItems = emptyMap()
        if (linked) refresh(force = true)
    }

    /** The next episode after [item] and the show's name (null for films, or the last episode). */
    suspend fun nextEpisode(item: Plex.Item): Pair<Plex.Entry, String>? =
        withContext(Dispatchers.IO) { runCatching { client().nextEpisode(item.server, item.ratingKey) }.getOrNull() }

    /** Plex Home users, for the profile editor. */
    suspend fun homeUsers(): List<Plex.HomeUser> {
        val token = store.plexToken ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { client().homeUsers(token) }.getOrDefault(emptyList()) }
    }

    /** Signs in as a Plex Home user; their token, or an error message. */
    suspend fun switchTo(user: Plex.HomeUser, pin: String?): Result<String> {
        val token = store.plexToken ?: return Result.failure(IllegalStateException("Plex isn't linked"))
        return withContext(Dispatchers.IO) { runCatching { client().switchUser(token, user.uuid, pin, user.id) } }
    }

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
        val token = activeToken() ?: return
        if (!force && job?.isActive == true) return
        job = scope.launch {
            val plex = client()
            val known = servers
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val srv = if (known.isEmpty() || force) plex.servers(token) else known
                    val deck = plex.onDeck(srv)
                    Triple(srv, deck, runCatching { plex.recentlyAdded(srv) }.getOrNull())
                }.onFailure { AppLog.warn("Plex: couldn't load Continue watching", it) }.getOrNull()
            }
            // Switched Plex user meanwhile: this answer is for someone else.
            if (token != activeToken()) return@launch
            if (result != null) {
                servers = result.first
                items = result.second
                result.third?.let { recent = it }
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
        recent = emptyList()
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
        val token = activeToken() ?: return
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
    /** Search results from every library, or null while searching. */
    var searchResults by mutableStateOf<List<Plex.Entry>?>(null); private set
    private var searchJob: Job? = null

    /** Searches all your Plex libraries at once ("" clears the search). */
    fun changeQuery(text: String) {
        query = text.trim()
        searchJob?.cancel()
        searchResults = null
        if (query.isEmpty()) return
        val q = query
        searchJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (servers.isEmpty()) activeToken()?.let { servers = client().servers(it) }
                    client().search(servers, q)
                }
            }
            searchResults = result.getOrElse {
                AppLog.warn("Plex search failed", it)
                emptyList()
            }
        }
    }

    // ---------------- Because you watched ----------------

    /** Plex films/shows like the last one you watched, and its title. */
    var similar by mutableStateOf<List<Plex.Entry>>(emptyList()); private set
    var similarTo by mutableStateOf<String?>(null); private set

    fun loadSimilar(serverId: String, ratingKey: String, title: String) {
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    if (servers.isEmpty()) activeToken()?.let { servers = client().servers(it) }
                    val s = servers.firstOrNull { it.machineId == serverId } ?: return@runCatching emptyList()
                    client().similar(s, ratingKey)
                }.getOrDefault(emptyList())
            }
            if (found.isNotEmpty()) {
                similar = found
                similarTo = title
            }
        }
    }

    // ---------------- Genres ----------------

    /** The genre picked for each library (Plex's genre id and name); none = everything. */
    var genreFor by mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()); private set
    /** Genres of the library the genre menu was opened for, or null while loading. */
    var genres by mutableStateOf<List<Pair<String, String>>?>(null); private set

    fun loadGenres(section: Plex.Section) {
        genres = null
        scope.launch {
            genres = withContext(Dispatchers.IO) { runCatching { client().genres(section) }.getOrDefault(emptyList()) }
        }
    }

    fun changeGenre(section: Plex.Section, genre: Pair<String, String>?) {
        genreFor = if (genre == null) genreFor - section.id else genreFor + (section.id to genre)
    }

    // ---------------- Watched / unwatched ----------------

    /** Marks something watched or unwatched on Plex, then runs [then] (to reload what's on screen). */
    fun setWatched(entry: Plex.Entry, watched: Boolean, onNotice: (String) -> Unit, then: () -> Unit) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { client().setWatched(entry, watched) }.isSuccess }
            if (ok) {
                onNotice((if (watched) "Marked watched: " else "Marked unwatched: ") + entry.title)
                // Lists that show it are now out of date.
                sectionItems = emptyMap()
                then()
                refresh()
            } else onNotice("Plex didn't accept that – try again")
        }
    }

    // ---------------- Copies (Films / Films 4K) ----------------

    /** Every copy of a film on your Plex servers, 4K first. Empty if none (or Plex isn't answering). */
    suspend fun copiesOf(title: String, year: String?): List<Plex.Entry> = withContext(Dispatchers.IO) {
        runCatching {
            if (servers.isEmpty()) activeToken()?.let { servers = client().servers(it) }
            client().copiesOf(servers, title, year)
        }.getOrDefault(emptyList())
    }

    /** Every film (or every show) in all your Plex libraries – for the combined library. */
    suspend fun allOf(shows: Boolean): List<Plex.Entry> = withContext(Dispatchers.IO) {
        runCatching {
            if (servers.isEmpty()) activeToken()?.let { servers = client().servers(it) }
            val secs = sections.ifEmpty { client().sections(servers) }
            secs.filter { it.isShows == shows }.flatMap { sec ->
                runCatching { client().sectionItems(sec) }.onFailure { AppLog.warn("Plex: couldn't load ${sec.title}", it) }.getOrDefault(emptyList())
            }
        }.getOrDefault(emptyList())
    }

    suspend fun entryFor(server: Plex.Server, ratingKey: String): Plex.Entry? = withContext(Dispatchers.IO) { client().entryFor(server, ratingKey) }

    /** The season to open a show at ("Go to Season 29"), used once. */
    var openAtSeason by mutableStateOf<Int?>(null)

    suspend fun showNamed(title: String): Plex.Entry? = withContext(Dispatchers.IO) {
        runCatching {
            if (servers.isEmpty()) activeToken()?.let { servers = client().servers(it) }
            client().showNamed(servers, title)
        }.getOrNull()
    }

    // ---------------- Kids ----------------

    /** Libraries (section ids) kids' profiles may see. */
    var kidsSections by mutableStateOf(store.plexKidsSections); private set

    fun toggleKids(section: Plex.Section) {
        kidsSections = if (section.id in kidsSections) kidsSections - section.id else kidsSections + section.id
        store.plexKidsSections = kidsSections
    }

    /** Kids' libraries changed on another device (synced with the profiles). */
    fun reloadKids() {
        kidsSections = store.plexKidsSections
    }

    /** For search results and recently added: is this from a kids' library? */
    fun isKidsEntry(e: Plex.Entry): Boolean = e.sectionKey != null && (e.server.machineId + "/" + e.sectionKey) in kidsSections

    private fun cacheKey(section: Plex.Section) = "${section.id}|$sort|${genreFor[section.id]?.first ?: ""}"

    /** What's loaded for [section] with the current order and search, or null while loading. */
    fun itemsFor(section: Plex.Section): List<Plex.Entry>? = sectionItems[cacheKey(section)]

    /** Loads one library's contents in the current order/search (once; [force] reloads). */
    fun loadSection(section: Plex.Section, force: Boolean = false) {
        val key = cacheKey(section)
        if (sectionItems.containsKey(key) && !force) return
        libraryJob?.cancel()
        libraryStatus = "Loading ${section.title}" + (genreFor[section.id]?.let { " · ${it.second}" } ?: "") + "…"
        val plexSort = SORTS.firstOrNull { it.key == sort }?.let { if (section.isShows) it.shows else it.films }
        val title: String? = null
        libraryJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    client().sectionItems(section, plexSort, title, genreFor[section.id]?.first) { soFar ->
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

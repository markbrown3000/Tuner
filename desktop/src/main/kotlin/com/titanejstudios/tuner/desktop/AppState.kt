package com.titanejstudios.tuner.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.SeriesItem
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import com.titanejstudios.tuner.integrations.Tmdb
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.integrations.GoogleDrive
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Page(val label: String, val icon: String) {
    Search("Search", "🔍"), Home("Home", "⌂"), Live("Live TV", "📺"), Guide("TV Guide", "▦"), WhatsOn("What's On", "☰"),
    Multi("Multi-view", "⊞"), Films("Films", "🎬"), Series("Series", "🎞"),
    Plex("Plex", "▶"), YouTube("YouTube", "▷"), Recordings("Recordings", "●"), Settings("Settings", "⚙"),
}

/** What's playing in the big player (films, episodes, Plex, catch-up). Live TV plays on the Live page. */
class Watching(
    val id: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String,
    val seriesId: String?,
    /** Saved for Continue watching (not catch-up). */
    val resumable: Boolean,
    /** What to play when it ends (the next episode). */
    val next: (() -> Unit)? = null,
)

/** Everything the screens show, in one place. Runs on the UI thread; work happens on IO. */
class AppState(val config: Config, val player: Player, private val scope: CoroutineScope) {
    val sync = Sync(config)

    var page by mutableStateOf(Page.Home)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var status by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var problems by mutableStateOf<List<String>>(emptyList())

    var channels by mutableStateOf<List<Channel>>(emptyList())
    var guide by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
    var movies by mutableStateOf<List<VodItem>?>(null)
    var series by mutableStateOf<List<SeriesItem>?>(null)
    var vodLoading by mutableStateOf(false)

    var playingChannel by mutableStateOf<Channel?>(null)
    /** The channels in the list on Live TV (for channel up and down). */
    var liveList by mutableStateOf<List<Channel>>(emptyList())
    var catchupOf by mutableStateOf<Programme?>(null)
    var watching by mutableStateOf<Watching?>(null)
    var englishOnly by mutableStateOf(config.englishOnly)
    /** A series to open on the Series page (from Home's My list). */
    var openSeriesId by mutableStateOf<String?>(null)
    /** A film page to open (from the player's "Go to film page"). */
    var openFilmId by mutableStateOf<String?>(null)
    /** Something to search for (from the player's "More of…"). */
    var searchFor by mutableStateOf<String?>(null)

    // Lists that sync with the TV (bumped so screens redraw).
    var listsVersion by mutableStateOf(0)
    var profileId by mutableStateOf(config.profileKey)
    var askWhoIsWatching by mutableStateOf(false)

    // Google sign-in (for sync).
    var signInCode by mutableStateOf<GoogleDrive.DeviceCode?>(null)
    var syncStatus by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf(config.googleRefresh != null)

    // Plex.
    val plex = Plex(config.plexClientId)
    var plexLinked by mutableStateOf(config.plexToken != null)
    var plexCode by mutableStateOf<String?>(null)
    var plexServers by mutableStateOf<List<Plex.Server>>(emptyList())
    var plexDeck by mutableStateOf<List<Plex.Item>>(emptyList())
    var plexSections by mutableStateOf<List<Plex.Section>>(emptyList())
    var plexRecent by mutableStateOf<List<Plex.Entry>>(emptyList())

    // Reminders.
    var dueReminder by mutableStateOf<Reminder?>(null)

    private var saveJob: Job? = null

    fun say(message: String) {
        notice = message
        scope.launch {
            delay(4_000)
            if (notice == message) notice = null
        }
    }

    // ---------------- Profiles ----------------

    val profile: PcProfile? get() = config.profiles.firstOrNull { it.id == profileId }

    fun chooseProfile(p: PcProfile) {
        if (kids && !p.kids && config.pin != null && !grownUp) {
            askPin("Enter the PIN to switch to ${p.name}") { chooseProfile(p) }
            return
        }
        config.activeProfile = p.id
        config.save()
        profileId = p.id
        askWhoIsWatching = false
        listsVersion++
    }

    /** Kids see only their groups (and no films or series). */
    val visibleChannels: List<Channel>
        get() {
            val p = profile
            val list = managedChannels
            return if (p != null && p.kids) list.filter { it.group in p.groups || it.originalGroup() in p.groups } else list
        }

    // ---------------- Channels and categories (Settings › Manage channels) ----------------

    /** Bumped when hiding, renaming, ordering or locking changes. */
    var manageVersion by mutableStateOf(0)
    /** Locked groups opened with the PIN (until Flicker closes). */
    var unlockedGroups by mutableStateOf<Set<String>>(emptySet())
    private var managedKey: Any? = null
    private var managedCache: List<Channel> = emptyList()
    /** Renamed group → its name in the playlist. */
    private var originalNames: Map<String, String> = emptyMap()

    private fun Channel.originalGroup(): String = originalNames[group] ?: group

    /** Your channels: hidden ones and groups left out, groups renamed and in your order, locked groups shut. */
    val managedChannels: List<Channel>
        get() {
            val key = listOf(channels, manageVersion, unlockedGroups, grownUp)
            if (key == managedKey) return managedCache
            val names = config.groupNames
            val hiddenG = config.hiddenGroups
            val hiddenC = config.hiddenChannels
            val locked = config.lockedGroups - unlockedGroups
            val offline = if (config.hideOffline) config.offline else emptySet()
            val order = config.groupOrder
            originalNames = names.entries.associate { (k, v) -> v to k }
            val list = channels.asSequence()
                .filter { it.group !in hiddenG && it.id !in hiddenC && it.id !in offline && (grownUp || it.group !in locked) }
                .map { c -> names[c.group]?.let { c.copy(group = it) } ?: c }
                .toList()
            val rank = order.withIndex().associate { (i, g) -> g to i }
            val firstSeen = HashMap<String, Int>()
            list.forEachIndexed { i, c -> firstSeen.putIfAbsent(c.originalGroup(), i) }
            managedCache = list.sortedWith(compareBy({ rank[it.originalGroup()] ?: (order.size + (firstSeen[it.originalGroup()] ?: 0)) }))
            managedKey = key
            return managedCache
        }

    /** Every group in the playlists (original names), in your order. */
    val allGroups: List<String>
        get() {
            val order = config.groupOrder
            val seen = channels.map { it.group }.distinct()
            return order.filter { it in seen } + seen.filter { it !in order }
        }

    fun groupLabel(g: String) = config.groupNames[g] ?: g

    private fun managed(change: () -> Unit) {
        change()
        manageVersion++
        changed(sync = false)
    }

    fun toggleGroupHidden(g: String) = managed { config.hiddenGroups = if (g in config.hiddenGroups) config.hiddenGroups - g else config.hiddenGroups + g }
    fun toggleChannelHidden(id: String) = managed { config.hiddenChannels = if (id in config.hiddenChannels) config.hiddenChannels - id else config.hiddenChannels + id }

    fun renameGroup(g: String, name: String) = managed {
        val n = name.trim()
        config.groupNames = if (n.isEmpty() || n == g) config.groupNames - g else config.groupNames + (g to n)
    }

    fun moveGroup(g: String, by: Int) = managed {
        val list = allGroups.toMutableList()
        val i = list.indexOf(g)
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i >= 0 && i != j) {
            list.removeAt(i)
            list.add(j, g)
        }
        config.groupOrder = list
    }

    fun toggleGroupLocked(g: String) {
        val change = { managed { config.lockedGroups = if (g in config.lockedGroups) config.lockedGroups - g else config.lockedGroups + g } }
        if (config.pin == null) say("Set a PIN first (Settings › Profiles) – locked groups need it to open") else askPin("The PIN to change which groups are locked") { change() }
    }

    fun unlockGroups() {
        if (config.lockedGroups.isEmpty()) return
        askPin("The PIN to show locked groups") { unlockedGroups = config.lockedGroups; say("Locked groups shown until Flicker closes") }
    }

    fun resetChannelChanges() = managed {
        config.hiddenGroups = emptySet()
        config.hiddenChannels = emptySet()
        config.groupNames = emptyMap()
        config.groupOrder = emptyList()
    }

    /** "Use the guide from…": a channel with no guide borrows another's. */
    fun useGuideFrom(c: Channel, from: Channel?) {
        config.epgMap = if (from == null) config.epgMap - c.id else config.epgMap + (c.id to from.id)
        if (from == null) reload() else applyEpgMap()
        changed(sync = false)
        say(if (from == null) "${c.name} uses its own guide again" else "${c.name} now shows ${from.name}'s guide")
    }

    private fun applyEpgMap() {
        val map = config.epgMap
        if (map.isEmpty()) return
        val g = guide.toMutableMap()
        var any = false
        for ((cid, from) in map) guide[from]?.let { g[cid] = it; any = true }
        if (any) guide = g
    }

    // ---------------- Channel health ----------------

    var checking by mutableStateOf<String?>(null)

    /** Tries each channel (favourites first, then [group] or all) and notes the ones that don't answer. */
    fun checkChannels(group: String? = null) {
        if (checking != null) return
        val favs = favourites
        val list = (channels.filter { it.id in favs } + channels.filter { group == null || it.group == group }).distinctBy { it.id }.take(600)
        if (list.isEmpty()) return
        checking = "Checking 0 of ${list.size}…"
        scope.launch {
            val dead = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val done = java.util.concurrent.atomic.AtomicInteger()
            withContext(Dispatchers.IO) {
                list.chunked(8).forEach { batch ->
                    batch.map { c ->
                        launch {
                            if (!answers(c.url)) dead += c.id
                            val n = done.incrementAndGet()
                            if (n % 8 == 0) scope.launch { checking = "Checking $n of ${list.size}…" }
                        }
                    }.forEach { it.join() }
                }
            }
            val checkedIds = list.map { it.id }.toSet()
            config.offline = (config.offline - checkedIds) + dead
            config.offlineCheckedAt = System.currentTimeMillis()
            manageVersion++
            changed(sync = false)
            checking = null
            say(if (dead.isEmpty()) "All ${list.size} channels answered ✓" else "${dead.size} of ${list.size} channels didn't answer")
        }
    }

    private fun answers(url: String): Boolean = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 6_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "VLC/3.0.21 LibVLC/3.0.21")
            c.setRequestProperty("Range", "bytes=0-1023")
            val code = c.responseCode
            if (code !in 200..399) return@runCatching false
            c.inputStream.use { it.read(ByteArray(512)) } > 0
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    fun setHideOffline(on: Boolean) = managed { config.hideOffline = on }

    fun clearOffline() = managed { config.offline = emptySet() }

    /** Recently watched channels, newest first. */
    val recentChannels: List<Channel>
        get() {
            val byId = managedChannels.associateBy { it.id }
            return config.recents.mapNotNull { byId[it] }
        }

    val kids: Boolean get() = profile?.kids == true

    // ---------------- Favourites, My list, Continue watching ----------------

    val favourites: Set<String> get() = config.favourites[profileId].orEmpty().toSet()

    fun toggleFavourite(c: Channel) {
        val mine = config.favourites[profileId].orEmpty()
        config.favourites = config.favourites + (profileId to if (c.id in mine) mine - c.id else mine + c.id)
        config.favouritesAt = System.currentTimeMillis()
        changed()
    }

    val watchlist: List<WatchItem> get() = Watchlist.decode(config.watchlistJson)

    fun inWatchlist(id: String): Boolean = watchlist.any { it.id == id }

    fun toggleWatchlist(item: WatchItem) {
        config.watchlistJson = Watchlist.encode(Watchlist.toggle(watchlist, item))
        config.watchlistAt = System.currentTimeMillis()
        changed()
    }

    fun removeFromContinue(id: String) {
        config.resume = config.resume - id
        config.done = config.done + (id to System.currentTimeMillis())
        changed()
    }

    /** Part-watched films and episodes, newest first. */
    val continueWatching: List<Resume>
        get() = config.resume.values.filter { it.dur <= 0 || it.pos < it.dur * 95 / 100 }.sortedByDescending { it.at }

    /** Saves soon, and (unless it's just the every-10-seconds place in a film) syncs. */
    private fun changed(sync: Boolean = true) {
        listsVersion++
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_500)
            withContext(Dispatchers.IO) { config.save() }
            if (sync) syncSoon()
        }
    }

    // ---------------- Loading ----------------

    fun reload() {
        scope.launch {
            status = "Loading channels…"
            val loaded = withContext(Dispatchers.IO) {
                Library.load(config.sources, config.englishOnly, config.mergeVersions) { msg -> scope.launch { status = msg } }
            }
            channels = loaded.channels
            guide = loaded.guide
            applyEpgMap()
            problems = loaded.problems
            status = null
            now = System.currentTimeMillis()
            movies = null
            series = null
        }
    }

    private fun xtream(): List<Source.Xtream> = config.sources.filterIsInstance<Source.Xtream>()

    fun loadVod() {
        if (vodLoading || (movies != null && series != null)) return
        vodLoading = true
        scope.launch {
            val (m, s) = withContext(Dispatchers.IO) {
                val films = xtream().flatMap { src -> runCatching { XtreamClient.loadMovies(src) }.getOrDefault(emptyList()) }
                val shows = xtream().flatMap { src -> runCatching { XtreamClient.loadSeries(src) }.getOrDefault(emptyList()) }
                films to shows
            }
            movies = m
            series = s
            vodLoading = false
        }
    }

    suspend fun episodes(s: SeriesItem): List<Episode> {
        val src = xtream().firstOrNull { it.id == s.sourceId } ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { XtreamClient.loadEpisodes(src, s.seriesId) }.getOrDefault(emptyList()) }
    }

    // ---------------- Playing ----------------

    fun playChannel(c: Channel) {
        if (blockedForKids()) return
        previewJob?.cancel()
        previewing = null
        endTimeshift()
        stopWatching(save = true)
        playingChannel = c
        catchupOf = null
        page = Page.Live
        player.play(c.url)
        player.setVolume(config.volume)
        pickLanguages()
        config.lastChannel = c.id
        config.recents = (listOf(c.id) + config.recents.filter { it != c.id }).take(20)
        scope.launch(Dispatchers.IO) { config.save() }
    }

    fun playCatchup(c: Channel, p: Programme) {
        if (blockedForKids()) return
        endTimeshift()
        val url = Catchup.url(c, p, System.currentTimeMillis()) ?: return say("Catch-up isn't available for that")
        stopWatching(save = true)
        playingChannel = c
        catchupOf = p
        page = Page.Live
        player.play(url)
    }

    /** Films, episodes and Plex: the big player, from where you left off. */
    fun watch(w: Watching, fromStart: Boolean = false) {
        if (blockedForKids()) return
        endTimeshift()
        stopWatching(save = true)
        playingChannel = null
        val start = if (fromStart) 0L else config.resume[w.id]?.pos?.takeIf { w.resumable && it > 10_000L } ?: 0L
        watching = w
        upNextCancelled = null
        endPreview()
        // Downloaded: play the copy on this PC.
        val local = config.downloads.firstOrNull { it.id == w.id && it.state == "done" && java.io.File(it.file).exists() }
        player.play(local?.let { java.io.File(it.file).toURI().toString() } ?: w.url, start)
        player.setVolume(config.volume)
        pickLanguages()
        maybeRecap(w, start)
    }

    // ---------------- "Go to…" from the player ----------------

    /** Keeps what's playing going in the mini player, and opens [to]. */
    private fun browseWhileWatching(to: Page) {
        if (watching != null && !mini) toggleMini()
        page = to
    }

    fun playingSeries(): SeriesItem? = watching?.seriesId?.let { id -> series?.firstOrNull { it.id == id } }

    fun playingFilm(): VodItem? = watching?.takeIf { it.seriesId == null }?.let { w -> movies?.firstOrNull { it.id == w.id } }

    fun goToSeries() {
        val s = playingSeries() ?: return
        openSeriesId = s.id
        browseWhileWatching(Page.Series)
    }

    fun goToFilm() {
        val m = playingFilm() ?: return
        openFilmId = m.id
        browseWhileWatching(Page.Films)
    }

    /** Search everything for the show (other episodes, showings, films, Plex, YouTube). */
    fun findMoreOf(name: String) {
        searchFor = com.titanejstudios.tuner.integrations.Tmdb.cleanTitle(name).first
        if (watching != null && !mini) toggleMini()
        page = Page.Search
    }

    fun playFilm(m: VodItem, fromStart: Boolean = false) =
        watch(Watching(m.id, m.name, m.category, m.poster, m.url, null, resumable = true), fromStart)

    fun playEpisode(s: SeriesItem, list: List<Episode>, index: Int, fromStart: Boolean = false) {
        val e = list.getOrNull(index) ?: return
        val next = list.getOrNull(index + 1)?.let { { playEpisode(s, list, index + 1, fromStart = true) } }
        watch(
            Watching(e.id, s.name, "S${e.season} E${e.number}  ${e.title}", e.image ?: s.cover, e.url, s.id, resumable = true, next = next),
            fromStart,
        )
    }

    /** Saves where you got to (every few seconds while playing, and when you stop). */
    fun saveProgress(sync: Boolean = false) {
        val w = watching ?: return
        if (!w.resumable) return
        val pos = player.positionMs
        val dur = player.durationMs
        if (pos < 5_000L || dur <= 0L) return
        val t = System.currentTimeMillis()
        if (pos > dur * 95 / 100) {
            config.resume = config.resume - w.id
            config.done = config.done + (w.id to t)
        } else {
            config.resume = config.resume + (w.id to Resume(w.id, w.title, w.subtitle, w.poster, w.url, pos, dur, t, w.seriesId))
        }
        changed(sync)
    }

    fun stopWatching(save: Boolean) {
        if (watching != null) {
            if (save) saveProgress(sync = true)
            player.stop()
            watching = null
        }
    }

    /** The film or episode reached its end. */
    fun finished() {
        // Paused live TV caught up with live: back to the channel itself.
        if (timeshift != null && watching == null) {
            goLive()
            return
        }
        val w = watching ?: return
        if (w.resumable) {
            config.resume = config.resume - w.id
            config.done = config.done + (w.id to System.currentTimeMillis())
            changed()
        }
        val next = w.next.takeIf { upNextCancelled != w.id }
        watching = null
        if (next != null) {
            say("Playing the next episode")
            next()
        }
    }

    // ---------------- Pause live TV ----------------

    /** Live TV paused: the channel's being saved to a file from that moment, and plays from there. */
    class Timeshift(val channel: Channel, val file: java.io.File, val pausedAt: Long, val rec: PcRecording)

    var timeshift by mutableStateOf<Timeshift?>(null)
    var timeshiftPlaying by mutableStateOf(false)

    /** Space on Live TV: pause (and keep recording), carry on from where you paused, pause again… */
    fun togglePauseLive() {
        val ts = timeshift
        val c = playingChannel
        when {
            ts != null && !timeshiftPlaying -> {
                timeshiftPlaying = true
                player.play(ts.file.toURI().toString())
                player.setVolume(config.volume)
            }
            ts != null -> player.togglePause()
            c != null && catchupOf == null -> {
                val file = java.io.File(System.getProperty("java.io.tmpdir"), "flicker-pause-${System.currentTimeMillis()}.ts")
                val rec = PcRecording("timeshift", c.id, c.name, c.name, System.currentTimeMillis(), Long.MAX_VALUE, file.absolutePath, "recording")
                // One connection to the provider: stop watching, record from here.
                player.stop()
                if (!recorder.start(rec, c.url)) {
                    player.play(c.url)
                    return say("Couldn't pause live TV")
                }
                timeshift = Timeshift(c, file, System.currentTimeMillis(), rec)
                timeshiftPlaying = false
            }
            else -> player.togglePause()
        }
    }

    /** How far behind live you are. */
    fun behindLiveMs(): Long {
        val ts = timeshift ?: return 0L
        val watchedTo = if (timeshiftPlaying) ts.pausedAt + player.positionMs else ts.pausedAt
        return (System.currentTimeMillis() - watchedTo).coerceAtLeast(0L)
    }

    fun goLive() {
        val c = timeshift?.channel ?: playingChannel ?: return
        endTimeshift()
        playingChannel = c
        player.play(c.url)
        player.setVolume(config.volume)
    }

    fun endTimeshift() {
        val ts = timeshift ?: return
        timeshift = null
        timeshiftPlaying = false
        player.stop()
        recorder.stop(ts.rec.id)
        runCatching { ts.file.delete() }
    }

    // ---------------- Kids' limits (the TV's PIN, daily time and bedtime) ----------------

    /** A grown-up's PIN lets kids carry on for a while. */
    private var grownUpUntil = 0L
    val grownUp: Boolean get() = System.currentTimeMillis() < grownUpUntil
    var timesUp by mutableStateOf<String?>(null)
    var pinPrompt by mutableStateOf<Pair<String, () -> Unit>?>(null)

    fun askPin(why: String, then: () -> Unit) {
        if (config.pin == null) return then()
        pinPrompt = why to then
    }

    fun checkPin(entered: String): Boolean {
        val p = pinPrompt ?: return false
        if (entered.trim() != config.pin) return false
        pinPrompt = null
        grownUpUntil = System.currentTimeMillis() + 30 * 60_000L
        p.second()
        return true
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date())

    private fun usedSeconds(p: PcProfile): Long {
        val v = config.usage[p.id] ?: return 0L
        return if (v.substringBefore(':') == today()) v.substringAfter(':').toLongOrNull() ?: 0L else 0L
    }

    /** Why a kids' profile can't watch now (null = it can). */
    fun kidsReason(): String? {
        val p = profile ?: return null
        if (!p.kids || grownUp) return null
        val cal = java.util.Calendar.getInstance()
        val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        if (p.bedtime >= 0 && (mins >= p.bedtime || mins < 6 * 60)) return "It's bedtime – TV's finished for today"
        if (p.limitMinutes > 0 && usedSeconds(p) >= p.limitMinutes * 60L) return "That's all the TV time for today"
        return null
    }

    private fun blockedForKids(): Boolean {
        val r = kidsReason() ?: return false
        timesUp = r
        return true
    }

    /** Every 10 seconds while something's playing: count kids' time, and stop at the limit or bedtime. */
    fun tickKids() {
        val p = profile ?: return
        if (!p.kids) return
        val playing = watching != null || playingChannel != null
        if (playing && !grownUp) {
            config.usage = config.usage + (p.id to "${today()}:${usedSeconds(p) + 10}")
        }
        if (playing) kidsReason()?.let { r ->
            stopWatching(save = true)
            endTimeshift()
            player.stop()
            playingChannel = null
            timesUp = r
        }
    }

    fun extendWithPin() {
        askPin("A grown-up's PIN gives 30 more minutes") { timesUp = null }
    }

    // ---------------- Kids: ask a grown-up for more time ----------------

    class TimeRequest(val from: String, val profileId: String, val kidName: String)
    var timeRequest by mutableStateOf<TimeRequest?>(null)
    /** The keyboard shortcuts page (F1 or ?). */
    var showKeys by mutableStateOf(false)
    /** The mini player stays on top of other windows. */
    var miniOnTop by mutableStateOf(config.miniOnTop)

    fun toggleMiniOnTop() {
        miniOnTop = !miniOnTop
        config.miniOnTop = miniOnTop
        changed(sync = false)
        say(if (miniOnTop) "📌 The mini player stays on top" else "The mini player can go behind other windows")
    }

    fun askForMoreTime() {
        val kid = profile ?: return
        if (link.key == null) return say("Ask a grown-up for the PIN")
        say("📱 Asking a grown-up…")
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }
            val what = JSONObject().put("kind", "askmore").put("profile", kid.id).put("name", kid.name)
            val asked = withContext(Dispatchers.IO) { found.count { link.send(it, what, sync.deviceLabel()) } }
            say(if (asked > 0) "📱 Asked! You'll see when a grown-up answers" else "Couldn't find a grown-up's phone – is Flicker open on it?")
        }
    }

    fun answerTimeRequest(minutes: Int) {
        val r = timeRequest ?: return
        timeRequest = null
        val send = {
            scope.launch {
                val found = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }.filter { !it.phone }
                val what = if (minutes > 0) JSONObject().put("kind", "bonus").put("profile", r.profileId).put("minutes", minutes)
                else JSONObject().put("kind", "askno").put("profile", r.profileId)
                val told = withContext(Dispatchers.IO) { found.count { link.send(it, what, sync.deviceLabel()) } }
                if (minutes > 0) say(if (told > 0) "+$minutes minutes for ${r.kidName} ✓" else "Couldn't reach the TV")
            }
        }
        if (minutes > 0) askPin("The PIN to give ${r.kidName} $minutes more minutes") { send() } else send()
    }

    /** From a linked device: extra time, or "not now". */
    private fun receiveKids(from: String, j: JSONObject) {
        val p = profile
        when (j.optString("kind")) {
            "askmore" -> if (p == null || !p.kids) timeRequest = TimeRequest(from.ifBlank { "The TV" }, j.optString("profile"), j.optString("name").ifBlank { "Your child" })
            "bonus" -> if (p != null && p.id == j.optString("profile")) {
                grownUpUntil = System.currentTimeMillis() + j.optInt("minutes").coerceIn(0, 240) * 60_000L
                timesUp = null
                say("🎁 ${from.ifBlank { "A grown-up" }} gave ${p.name} ${j.optInt("minutes")} more minutes")
            }
            "askno" -> if (p != null && p.id == j.optString("profile")) say("A grown-up said not now – sorry!")
        }
    }

    // ---------------- Sound and subtitle languages (each profile) ----------------

    private val languageNames = mapOf(
        "en" to listOf("english", "eng"), "fr" to listOf("french", "français", "fre", "fra"), "de" to listOf("german", "deutsch", "ger", "deu"),
        "es" to listOf("spanish", "español", "spa"), "it" to listOf("italian", "ita"), "pt" to listOf("portuguese", "por"),
        "pl" to listOf("polish", "pol"), "nl" to listOf("dutch", "nld", "dut"), "ar" to listOf("arabic", "ara"), "tr" to listOf("turkish", "tur"),
    )
    val languageChoices = listOf(null) + languageNames.keys
    fun languageLabel(code: String?) = code?.let { languageNames[it]?.first()?.replaceFirstChar { c -> c.uppercase() } } ?: "Stream's default"

    /** A couple of seconds after something starts: its sound and subtitles in this profile's languages. */
    private fun pickLanguages() {
        val (audio, subs, describe) = config.languagesOf(profileId)
        if (audio == null && subs == null && !describe) return
        scope.launch {
            delay(2_500)
            fun matches(name: String, code: String) = languageNames[code].orEmpty().any { name.lowercase().contains(it) } || name.lowercase().contains("[$code]")
            val tracks = player.audioTracks().filter { it.first >= 0 }
            if (tracks.size > 1) {
                val inLang = if (audio != null) tracks.filter { matches(it.second, audio) }.ifEmpty { tracks } else tracks
                val ad = inLang.firstOrNull { t -> val n = t.second.lowercase(); "descri" in n || Regex("\\bad\\b").containsMatchIn(n) }
                val pick = if (describe) ad ?: inLang.first() else inLang.firstOrNull { it != ad } ?: inLang.first()
                player.setAudioTrack(pick.first)
            }
            if (subs != null) player.subtitleTracks().firstOrNull { it.first >= 0 && matches(it.second, subs) }?.let { player.setSubtitleTrack(it.first) }
        }
    }

    fun setLanguages(audio: String?, subs: String?, describe: Boolean) {
        config.saveLanguages(profileId, audio, subs, describe)
        changed(sync = false)
    }

    // ---------------- Previously on…, skip credits ----------------

    var previously by mutableStateOf<Pair<String, String>?>(null)
    private val lastEpisodeStart = HashMap<String, Long>()

    private fun maybeRecap(w: Watching, startMs: Long) {
        previously = null
        val m = Regex("S(\\d+) ?E(\\d+)").find(w.subtitle ?: "") ?: return
        val season = m.groupValues[1].toInt()
        val ep = m.groupValues[2].toInt()
        val now = System.currentTimeMillis()
        val last = lastEpisodeStart.put(w.title, now)
        val key = Keys.TMDB_KEY
        if (!config.recaps || key.isBlank() || (season <= 1 && ep <= 1) || startMs > 60_000L) return
        if (last != null && now - last < 4 * 3600_000L) return
        scope.launch {
            val recap = withContext(Dispatchers.IO) {
                runCatching {
                    val info = Tmdb.lookup(key, w.title, isSeries = true) ?: return@runCatching null
                    val (ps, pe) = if (ep > 1) season to ep - 1 else (season - 1) to Tmdb.seasonLength(key, info.id, season - 1)
                    if (ps < 1 || pe < 1) return@runCatching null
                    val (name, plot) = Tmdb.episode(key, info.id, ps, pe) ?: return@runCatching null
                    plot?.let { "Previously on ${w.title} – S$ps E$pe $name".trim() to it }
                }.getOrNull()
            } ?: return@launch
            if (watching !== w) return@launch
            previously = recap
            delay(15_000)
            if (previously === recap) previously = null
        }
    }

    /** How long before the end this series' credits start (learnt on the TV or here). */
    fun creditsFor(seriesId: String?): Long? = seriesId?.let { config.intros["credits:$it"] }

    fun setCreditsHere() {
        val w = watching ?: return
        val sid = w.seriesId ?: return
        val fromEnd = player.durationMs - player.positionMs
        if (player.durationMs <= 0 || fromEnd !in 15_000L..900_000L) return say("That's not near enough the end")
        config.intros = config.intros + ("credits:$sid" to fromEnd)
        changed(sync = false)
        say("Got it – Up next will start when the credits do")
    }

    // ---------------- 👍 / 👎 ----------------

    private fun thumbKey(title: String) = com.titanejstudios.tuner.data.FilmName.parse(title).first.lowercase()
    fun thumbOf(title: String): Int = config.thumbs[thumbKey(title)] ?: 0

    fun thumb(title: String, like: Boolean) {
        val k = thumbKey(title)
        val v = if (like) 1 else -1
        config.thumbs = if (config.thumbs[k] == v) config.thumbs - k else config.thumbs + (k to v)
        changed(sync = false)
        say(if (config.thumbs[k] == null) "Rating removed" else if (like) "👍 More like \"$title\"" else "👎 Fewer like \"$title\"")
    }

    // ---------------- Family picks ----------------

    val familyPicks: List<com.titanejstudios.tuner.data.FamilyPick> get() = Watchlist.decodePicks(config.familyPicksJson)
    val familyPicksForMe: List<com.titanejstudios.tuner.data.FamilyPick>
        get() = if (kids) emptyList() else familyPicks.filter { !it.from.equals(profile?.name ?: "", ignoreCase = true) }

    fun recommendToFamily(item: WatchItem) {
        if (kids) return say("Only grown-ups' profiles can recommend things")
        val now = System.currentTimeMillis()
        val from = profile?.name ?: "Someone"
        config.familyPicksJson = Watchlist.encodePicks(Watchlist.addPick(familyPicks, com.titanejstudios.tuner.data.FamilyPick(item, from, now), now))
        config.familyPicksAt = now
        changed()
        say("👪 ${item.name} is on everyone's Home under Family picks")
    }

    fun removeFamilyPick(id: String) {
        config.familyPicksJson = Watchlist.encodePicks(familyPicks.filter { it.item.id != id })
        config.familyPicksAt = System.currentTimeMillis()
        changed()
    }

    /** What's playing, as something to recommend (films and series from your provider). */
    fun playingWatchItem(): WatchItem? =
        playingSeries()?.let { WatchItem.of(it, System.currentTimeMillis()) } ?: playingFilm()?.let { WatchItem.of(it, System.currentTimeMillis()) }

    // ---------------- Coming soon ----------------

    val comingSoon: List<com.titanejstudios.tuner.data.ComingEpisode>
        get() = runCatching {
            val today = today()
            val a = org.json.JSONArray(config.comingSoonJson)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val w = Watchlist.decode(org.json.JSONArray().put(o.getJSONObject("w")).toString()).firstOrNull() ?: return@mapNotNull null
                com.titanejstudios.tuner.data.ComingEpisode(w, o.optString("date"), o.optInt("s"), o.optInt("e"), o.optString("name").takeIf { it.isNotBlank() })
            }.filter { it.date >= today }
        }.getOrDefault(emptyList())

    /** Twice a day: when the series you follow have their next episode (from TMDB). */
    fun refreshComingSoon() {
        val key = Keys.TMDB_KEY
        if (key.isBlank() || kids) return
        val now = System.currentTimeMillis()
        if (now - config.comingSoonAt < 12 * 3600_000L) return tellComingToday()
        config.comingSoonAt = now
        val shows = (Watchlist.decodeFollowed(config.followedJson).map { it.series } + watchlist.filter { it.isSeries })
            .distinctBy { com.titanejstudios.tuner.data.FilmName.parse(it.name).first.lowercase() }.take(40)
        if (shows.isEmpty()) return
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                val out = org.json.JSONArray()
                shows.mapNotNull { w -> runCatching { Tmdb.upcoming(key, w.name) }.getOrNull()?.let { w to it.second } }
                    .sortedBy { it.second.date }
                    .forEach { (w, up) ->
                        out.put(JSONObject().put("w", org.json.JSONArray(Watchlist.encode(listOf(w))).getJSONObject(0))
                            .put("date", up.date).put("s", up.season).put("e", up.episode).put("name", up.name ?: ""))
                    }
                out
            }
            config.comingSoonJson = a.toString()
            changed(sync = false)
            tellComingToday()
        }
    }

    private fun tellComingToday() {
        val today = today()
        if (config.comingTold == today) return
        val due = comingSoon.filter { it.date == today }
        if (due.isEmpty()) return
        config.comingTold = today
        val text = due.joinToString(", ") { "${com.titanejstudios.tuner.data.FilmName.parse(it.item.name).first} S${it.season} E${it.episode}" }
        notify("📅 New episodes today", text)
        say("📅 New today: $text")
    }

    // ---------------- Drag and drop ----------------

    private val videoTypes = setOf("mp4", "mkv", "avi", "mov", "ts", "m4v", "webm", "wmv", "mpg", "mpeg", "flv", "m2ts")

    /** A video file plays; an .m3u file or playlist link is added; any other link plays. */
    fun dropped(files: List<java.io.File>, text: String?) {
        val f = files.firstOrNull()
        if (f != null) {
            val ext = f.extension.lowercase()
            when {
                ext in videoTypes -> watch(Watching("file:" + f.absolutePath, f.nameWithoutExtension, "From this PC", null, f.toURI().toString(), null, resumable = true))
                ext == "m3u" || ext == "m3u8" -> addDroppedPlaylist(f.nameWithoutExtension, f.toURI().toString())
                else -> say("Drop a video, an .m3u playlist or a link")
            }
            return
        }
        val url = text?.trim()?.lineSequence()?.firstOrNull { it.startsWith("http") }?.trim() ?: return say("Drop a video, an .m3u playlist or a link")
        val low = url.lowercase()
        if (low.contains(".m3u") || low.contains("get.php") || low.contains("type=m3u")) addDroppedPlaylist("Playlist", url)
        else watch(Watching("url:$url", url.substringAfterLast('/').substringBefore('?').ifBlank { "Video" }, url, null, url, null, resumable = false))
    }

    private fun addDroppedPlaylist(name: String, url: String) {
        if (config.sources.any { it is com.titanejstudios.tuner.data.Source.M3u && it.playlistUrl == url }) return say("That playlist's already added")
        config.sources = config.sources + com.titanejstudios.tuner.data.Source.M3u("pc-" + System.currentTimeMillis(), name, url, null)
        config.save()
        say("Added $name – loading its channels")
        reload()
    }

    // ---------------- Downloads (watch without the internet) ----------------

    var downloads by mutableStateOf(config.downloads)
    var downloadProgress by mutableStateOf<Map<String, Float>>(emptyMap())
    val downloadFolder: java.io.File get() = java.io.File(recorder.folder, "Downloads").apply { mkdirs() }

    fun downloadFor(id: String) = downloads.firstOrNull { it.id == id }

    fun download(id: String, title: String, sub: String?, poster: String?, url: String) {
        if (downloadFor(id) != null) return say("Already downloaded (or downloading)")
        val ext = url.substringAfterLast('.', "mp4").substringBefore('?').take(4).ifBlank { "mp4" }
        val safe = (title + (sub?.let { " $it" } ?: "")).replace(Regex("[\\\\/:*?\"<>|]"), "").take(80).trim()
        val d = PcDownload(id, title, sub, poster, url, java.io.File(downloadFolder, "$safe.$ext").absolutePath, "downloading")
        downloads = downloads + d
        saveDownloads()
        say("⤓ Downloading $title")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 15_000
                    c.readTimeout = 60_000
                    c.setRequestProperty("User-Agent", "Flicker")
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        java.io.File(d.file).outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var got = 0L
                            var lastShown = 0L
                            while (true) {
                                if (downloadFor(id) == null) throw java.util.concurrent.CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (total > 0 && got - lastShown > 4_000_000L) {
                                    lastShown = got
                                    val f = got.toFloat() / total
                                    scope.launch { downloadProgress = downloadProgress + (id to f) }
                                }
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            downloadProgress = downloadProgress - id
            val now = downloadFor(id) ?: return@launch
            now.state = if (ok) "done" else "failed"
            downloads = downloads.toList()
            saveDownloads()
            say(if (ok) "✓ $title downloaded" else "Couldn't download $title")
        }
    }

    fun deleteDownload(d: PcDownload) {
        downloads = downloads.filterNot { it.id == d.id }
        saveDownloads()
        runCatching { java.io.File(d.file).delete() }
    }

    fun playDownload(d: PcDownload) {
        watch(Watching(d.id, d.title, d.sub, d.poster, d.url, null, resumable = true))
    }

    private fun saveDownloads() {
        config.downloads = downloads
        scope.launch(Dispatchers.IO) { config.save() }
    }

    // ---------------- Windows notifications ----------------

    /** Shows a notification in the corner of the screen (set up by the tray icon). */
    var notifier: ((String, String) -> Unit)? = null

    fun notify(title: String, message: String) {
        if (config.notifications) notifier?.invoke(title, message)
    }

    // ---------------- Up next, skip intro ----------------

    /** Where this episode's series intro ends (learnt on the TV), if it does. */
    fun introEnd(): Long? = watching?.seriesId?.let { config.intros[it] }

    /** Skips the intro (and remembers where it ends for this series). */
    fun skipIntro() {
        val end = introEnd() ?: return
        player.seekTo(end)
    }

    /** "Skip intro" without a saved end: 85 seconds on, saved for the rest of the series. */
    fun skipIntroGuess() {
        val w = watching ?: return
        val to = player.positionMs + 85_000L
        player.seekTo(to)
        w.seriesId?.let { sid ->
            config.intros = config.intros + (sid to to)
            changed()
        }
    }

    /** The next episode's countdown was cancelled. */
    var upNextCancelled by mutableStateOf<String?>(null)

    // ---------------- Volume ----------------

    fun setVolume(v: Int) {
        config.volume = v.coerceIn(0, 150)
        player.setVolume(config.volume)
        scope.launch(Dispatchers.IO) { config.save() }
    }

    // ---------------- Full screen on another monitor ----------------

    /** The other screen's position and size (in Windows' units), or null with one screen. */
    fun otherScreen(): java.awt.Rectangle? = runCatching {
        val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screens = env.screenDevices
        if (screens.size < 2) return null
        val main = env.defaultScreenDevice
        (screens.firstOrNull { it != main } ?: screens[1]).defaultConfiguration.bounds
    }.getOrNull()

    var secondScreen by mutableStateOf<java.awt.Rectangle?>(null)

    fun toggleSecondScreen() {
        if (secondScreen != null) {
            secondScreen = null
        } else {
            val r = otherScreen() ?: return say("Only one screen found")
            if (watching == null && playingChannel == null) return say("Play something first")
            mini = false
            secondScreen = r
        }
        scope.launch {
            delay(500)
            player.restart()
        }
    }

    /** The video's in its own window (mini player or the other screen). */
    val videoElsewhere: Boolean get() = mini || secondScreen != null

    // ---------------- Hover preview (Live TV, when nothing's playing) ----------------

    var previewing by mutableStateOf<Channel?>(null)
    private var previewJob: Job? = null

    fun hoverChannel(c: Channel?) {
        previewJob?.cancel()
        if (c == null || playingChannel != null || watching != null) return
        previewJob = scope.launch {
            delay(1_200)
            if (playingChannel != null || watching != null) return@launch
            previewing = c
            player.play(c.url)
            player.setVolume(0)
        }
    }

    fun endPreview() {
        previewJob?.cancel()
        if (previewing != null && playingChannel == null) player.stop()
        previewing = null
    }

    // ---------------- Profiles (edited here, back to the TV) ----------------

    fun editProfile(id: String, name: String, kids: Boolean, limitMinutes: Int, bedtime: Int, colour: Int, icon: String) {
        val arr = runCatching { JSONArray(config.profilesRaw) }.getOrDefault(JSONArray())
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            o.put("name", name.trim().ifEmpty { o.optString("name") }).put("kids", kids).put("limit", limitMinutes).put("bedtime", bedtime)
                .put("colour", colour).put("icon", icon)
            found = true
        }
        if (!found) return say("That profile hasn't come from the TV yet – sync first")
        config.profilesRaw = arr.toString()
        config.profiles = config.profilesFromJson(arr)
        config.profilesAt = System.currentTimeMillis()
        changed()
        say("Saved – the TV gets it at the next sync")
    }

    // ---------------- Start with Windows ----------------

    /** Adds (or removes) Flicker to the programs Windows starts when you sign in – in the tray, out of the way. */
    fun setStartWithWindows(on: Boolean) {
        val exe = System.getProperty("jpackage.app-path")
        if (exe.isNullOrBlank()) return say("That only works in the installed Flicker")
        val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        val cmd = if (on) listOf("reg", "add", key, "/v", "Flicker", "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f")
        else listOf("reg", "delete", key, "/v", "Flicker", "/f")
        val ok = runCatching { ProcessBuilder(cmd).start().waitFor() == 0 }.getOrDefault(false)
        if (ok || !on) {
            config.startWithWindows = on
            config.save()
            listsVersion++
        }
        say(if (!ok && on) "Windows didn't allow it" else if (on) "Flicker will start with Windows (in the tray)" else "Flicker won't start with Windows")
    }

    // ---------------- Type a channel number ----------------

    var typedNumber by mutableStateOf("")
    private var numberJob: Job? = null

    /** The number shown for a channel: the provider's, else its place in the list. */
    fun numberOf(c: Channel, index: Int): Int = if (c.number > 0) c.number else index + 1

    fun typeDigit(d: Int) {
        typedNumber = (typedNumber + d).takeLast(4)
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(1_500)
            val n = typedNumber.toIntOrNull()
            typedNumber = ""
            if (n != null) {
                val list = visibleChannels
                val c = list.firstOrNull { it.number == n } ?: list.getOrNull(n - 1)
                if (c != null) playChannel(c) else say("No channel $n")
            }
        }
    }

    // ---------------- Mini player ----------------

    /** The video in a small window that stays on top. */
    var mini by mutableStateOf(false)

    fun toggleMini() {
        if (watching == null && playingChannel == null) return say("Play something first")
        mini = !mini
        if (mini) secondScreen = null
        // VLC has to be told where to draw once the video's moved window.
        scope.launch {
            delay(500)
            player.restart()
        }
    }

    // ---------------- Multi-view ----------------

    val multi = MultiPlayers(player)
    var multiChannels by mutableStateOf<List<Channel?>>(List(4) { null })
    var multiSound by mutableStateOf(0)
    var multiCount by mutableStateOf(4)

    fun enterMulti() {
        stopWatching(save = true)
        player.stop()
        playingChannel = null
        mini = false
    }

    fun leaveMulti() {
        multi.stopAll()
    }

    fun setMulti(i: Int, c: Channel) {
        multiChannels = multiChannels.toMutableList().also { it[i] = c }
        multi.play(i, c.url, withSound = i == multiSound)
    }

    fun multiSoundFrom(i: Int) {
        multiSound = i
        multi.soundFrom(i)
    }

    /** One of the four full size on Live TV. */
    fun multiToFull(i: Int) {
        val c = multiChannels[i] ?: return
        multi.stopAll()
        playChannel(c)
    }

    // ---------------- Recordings ----------------

    val recorder = Recorder(player)
    var recordings by mutableStateOf(config.recordings)

    private fun saveRecordings() {
        config.recordings = recordings
        scope.launch(Dispatchers.IO) { config.save() }
    }

    fun recordingFor(c: Channel, p: Programme) = recordings.firstOrNull { it.channelId == c.id && it.start == p.start }

    fun toggleRecording(c: Channel, p: Programme) {
        val existing = recordingFor(c, p)
        if (existing != null) {
            recorder.stop(existing.id)
            recordings = recordings.filterNot { it.id == existing.id }
            saveRecordings()
            return say("Recording cancelled")
        }
        val safe = p.title.replace(Regex("[\\\\/:*?\"<>|]"), "").take(60).trim().ifEmpty { "Recording" }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.UK).format(java.util.Date(p.start))
        val file = java.io.File(recorder.folder, "$safe – ${c.name.replace(Regex("[\\\\/:*?\"<>|]"), "")} – $stamp.ts").absolutePath
        recordings = recordings + PcRecording(java.util.UUID.randomUUID().toString(), c.id, c.name, p.title, p.start, p.stop, file, "scheduled")
        saveRecordings()
        checkRecordings()
        say(if (p.start <= System.currentTimeMillis()) "● Recording ${p.title}" else "● ${p.title} will record (keep Flicker open – it can be minimised)")
    }

    /** Starts and stops recordings on time (a couple of minutes either side). */
    fun checkRecordings() {
        val t = System.currentTimeMillis()
        var changed = false
        for (r in recordings) {
            when {
                r.state == "scheduled" && t >= r.start - 2 * 60_000L && t < r.stop -> {
                    val c = channels.firstOrNull { it.id == r.channelId }
                    r.state = if (c != null && recorder.start(r, c.url)) "recording" else "failed"
                    changed = true
                }
                r.state == "recording" && (t >= r.stop + 2 * 60_000L || !recorder.isRunning(r.id)) -> {
                    recorder.stop(r.id)
                    r.state = if (java.io.File(r.file).length() > 0) "done" else "failed"
                    notify(if (r.state == "done") "● Recorded" else "⚠ Didn't record", r.title)
                    changed = true
                }
                r.state == "scheduled" && t >= r.stop -> {
                    r.state = "failed"
                    changed = true
                }
            }
        }
        if (changed) {
            recordings = recordings.toList()
            saveRecordings()
        }
    }

    fun playRecording(r: PcRecording) {
        watch(Watching("rec:" + r.id, r.title, "Recording · ${r.channelName}", null, java.io.File(r.file).toURI().toString(), null, resumable = false))
    }

    fun deleteRecording(r: PcRecording) {
        recorder.stop(r.id)
        runCatching { java.io.File(r.file).delete() }
        recordings = recordings.filterNot { it.id == r.id }
        saveRecordings()
    }

    // ---------------- Updates ----------------

    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableStateOf<Float?>(null)

    /** At start and every few hours (or now): is there a newer Flicker for Windows? */
    fun checkForUpdate(manual: Boolean = false) {
        // Every start, then every few hours while it's open (not more than once an hour).
        if (!manual && System.currentTimeMillis() - config.lastUpdateCheck < 3_600_000L) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.latest() }.getOrNull() }
            config.lastUpdateCheck = System.currentTimeMillis()
            withContext(Dispatchers.IO) { config.save() }
            if (r != null && Updater.newer(r.version, Keys.VERSION)) update = r
            else if (manual) say(if (r == null) "Couldn't check for updates" else "You've got the latest Flicker (${Keys.VERSION})")
        }
    }

    fun installUpdate() {
        val r = update ?: return
        if (updateProgress != null) return
        updateProgress = 0f
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Updater.download(r) { p -> scope.launch { updateProgress = p } } }.getOrNull()
            }
            if (file == null) {
                updateProgress = null
                return@launch say("Couldn't download the update – try again later")
            }
            stopWatching(save = true)
            config.save()
            Updater.install(file)
            kotlin.system.exitProcess(0)
        }
    }

    // ---------------- Linked devices (phone remote, Watch on…) ----------------

    val link = PcLink(config)
    var peers by mutableStateOf<List<PcLink.Peer>>(emptyList())
    var findingPeers by mutableStateOf(false)

    fun startLink() {
        link.host = object : PcLink.Host {
            override fun deviceName() = sync.deviceLabel()
            override fun stateJson() = sync.snapshot()
            override fun remoteState(): JSONObject {
                val o = JSONObject()
                val w = watching
                val c = playingChannel
                when {
                    w != null -> o.put("title", w.title).put("detail", w.subtitle ?: "Film").put("what", w.title).put("series", w.seriesId != null)
                    c != null -> {
                        val p = catchupOf ?: guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                        o.put("title", c.name).put("detail", p?.title ?: "Live")
                        if (p != null) o.put("what", p.title).put("desc", p.desc ?: "").put("series", true)
                    }
                    else -> o.put("title", "Flicker").put("detail", "Nothing playing · ${page.label}")
                }
                return o
            }
            override fun remoteChannels(): JSONArray {
                val a = JSONArray()
                val t = System.currentTimeMillis()
                visibleChannels.take(4000).forEachIndexed { i, c ->
                    val cur = guide[c.id]?.firstOrNull { it.start <= t && it.stop > t }
                    a.put(JSONObject().put("id", c.id).put("n", i + 1).put("name", c.name).put("group", c.group).put("logo", c.logo).put("now", cur?.title))
                }
                return a
            }
            override fun remoteKey(code: Int) = pressFromRemote(code)
            override fun remotePlay(channelId: String) {
                channels.firstOrNull { it.id == channelId }?.let { playChannel(it) }
            }
            override fun play(from: String, json: JSONObject): Boolean = playFromLink(from, json)
        }
        link.start()
    }

    /** Buttons from the phone's remote (Android key codes). */
    private fun pressFromRemote(code: Int) {
        val w = watching != null
        when (code) {
            166, 19 -> if (!w) zapBy(1)          // channel up / ▲
            167, 20 -> if (!w) zapBy(-1)         // channel down / ▼
            21 -> if (w) player.skip(-10_000L)    // ◀
            22 -> if (w) player.skip(30_000L)     // ▶
            23, 66, 85, 126, 127 -> player.togglePause()
            24 -> { config.volume = (config.volume + 10).coerceAtMost(150); player.setVolume(config.volume) }
            25 -> { config.volume = (config.volume - 10).coerceAtLeast(0); player.setVolume(config.volume) }
            164 -> player.toggleMute()
            4 -> if (w) stopWatching(save = true) else page = Page.Home
            -1 -> {
                stopWatching(save = true)
                page = Page.Home
            }
        }
    }

    fun zapBy(d: Int) {
        val list = liveList.ifEmpty { visibleChannels }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == playingChannel?.id }
        playChannel(list[(i + d).mod(list.size)])
    }

    /** The TV or phone sent something to play here. */
    private fun playFromLink(from: String, j: JSONObject): Boolean {
        val who = from.ifBlank { "another device" }
        when (j.optString("kind")) {
            "askmore", "bonus", "askno" -> {
                receiveKids(from, j)
                return true
            }
            "live" -> {
                val c = channels.firstOrNull { it.id == j.optString("channel") } ?: return false
                playChannel(c)
            }
            "catchup" -> {
                val c = channels.firstOrNull { it.id == j.optString("channel") } ?: return false
                val p = guide[c.id]?.firstOrNull { it.start == j.optLong("start") }
                    ?: Programme(j.optLong("start"), j.optLong("stop"), j.optString("title"), null, j.optString("category").ifBlank { null })
                playCatchup(c, p)
            }
            "vod" -> {
                val url = j.optString("url").ifBlank { return false }
                val id = j.optString("id")
                val w = Watching(id, j.optString("title"), j.optString("sub").ifBlank { null }, j.optString("poster").ifBlank { null }, url,
                    j.optString("series").ifBlank { null }?.takeIf { it != "null" }, resumable = !id.startsWith("plex:"))
                stopWatching(save = true)
                playingChannel = null
                watching = w
                player.play(url, j.optLong("pos"))
                player.setVolume(config.volume)
            }
            "youtube" -> {
                val id = j.optString("id").ifBlank { return false }
                openInBrowser("https://www.youtube.com/watch?v=$id")
            }
            else -> return false
        }
        say("▶ Sent from $who")
        return true
    }

    fun findPeers() {
        if (link.key == null) return say("Sign in to Sync first – the TV shares its link through it")
        findingPeers = true
        scope.launch {
            peers = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }
            findingPeers = false
            if (peers.isEmpty()) say("No TVs found – is Flicker open on them, and on the same Wi-Fi?")
        }
    }

    /** What's playing here, for "Watch on the TV". */
    private fun sendable(): JSONObject? {
        val w = watching
        val c = playingChannel
        return when {
            w != null && !w.id.startsWith("rec:") -> JSONObject().put("kind", "vod").put("id", w.id).put("title", w.title).put("sub", w.subtitle)
                .put("poster", w.poster).put("url", w.url).put("series", w.seriesId).put("pos", player.positionMs)
            c != null && catchupOf != null -> catchupOf?.let { p ->
                JSONObject().put("kind", "catchup").put("channel", c.id).put("name", c.name).put("url", c.url)
                    .put("start", p.start).put("stop", p.stop).put("title", p.title).put("category", p.category).put("pos", player.positionMs)
            }
            c != null -> JSONObject().put("kind", "live").put("channel", c.id)
            else -> null
        }
    }

    /** A channel (or a catch-up programme) straight to the TV, from the guide. */
    fun watchOnTv(c: Channel, catchup: Programme?) {
        if (link.key == null) return say("Sign in to Sync first – the TV shares its link through it")
        val what = if (catchup != null) JSONObject().put("kind", "catchup").put("channel", c.id).put("name", c.name).put("url", c.url)
            .put("start", catchup.start).put("stop", catchup.stop).put("title", catchup.title).put("category", catchup.category).put("pos", 0L)
        else JSONObject().put("kind", "live").put("channel", c.id)
        say("Finding your TV…")
        scope.launch {
            val tvs = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }.filter { !it.phone }
            val tv = tvs.firstOrNull() ?: return@launch say("No TV found – is Flicker open on it, and on the same Wi-Fi?")
            val ok = withContext(Dispatchers.IO) { link.send(tv, what, sync.deviceLabel()) }
            say(if (ok) "▶ Playing on ${tv.name}" else "${tv.name} didn't answer")
        }
    }

    fun sendTo(peer: PcLink.Peer) {
        val what = sendable() ?: return say("Nothing to send")
        if (watching != null) saveProgress(sync = true)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { link.send(peer, what, sync.deviceLabel()) }
            if (ok) {
                say("▶ Playing on ${peer.name}")
                stopWatching(save = false)
                player.stop()
                playingChannel = null
            } else say("${peer.name} didn't answer")
        }
    }

    // ---------------- Reminders ----------------

    fun hasReminder(c: Channel, p: Programme) = config.reminders.any { it.channelId == c.id && it.start == p.start }

    fun toggleReminder(c: Channel, p: Programme) {
        config.reminders = if (hasReminder(c, p)) config.reminders.filterNot { it.channelId == c.id && it.start == p.start }
        else config.reminders + Reminder(c.id, c.name, p.title, p.start)
        scope.launch(Dispatchers.IO) { config.save() }
        say(if (hasReminder(c, p)) "⏰ Flicker will remind you when ${p.title} starts" else "Reminder cancelled")
    }

    /** Every 20 seconds: a reminder that's due. */
    fun checkReminders() {
        val t = System.currentTimeMillis()
        if (dueReminder != null) return
        val due = config.reminders.firstOrNull { t >= it.start - 60_000L } ?: return
        config.reminders = config.reminders - due
        scope.launch(Dispatchers.IO) { config.save() }
        if (t - due.start < 30 * 60_000L) {
            dueReminder = due
            notify("⏰ ${due.title}", "Starting now on ${due.channelName}")
        }
    }

    // ---------------- Sync ----------------

    private var syncJob: Job? = null

    fun startSignIn() {
        if (!sync.configured) return say("This copy of Flicker was built without Google sign-in (see the README)")
        scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { sync.drive.startSignIn() } }.getOrElse {
                syncStatus = "Couldn't reach Google: ${it.message}"
                return@launch
            }
            signInCode = code
            syncStatus = null
            var interval = code.intervalSec.coerceAtLeast(5) * 1000L
            while (signInCode === code && System.currentTimeMillis() < code.expiresAt) {
                delay(interval)
                when (val r = withContext(Dispatchers.IO) { runCatching { sync.drive.poll(code) }.getOrNull() }) {
                    is GoogleDrive.Poll.Done -> {
                        config.googleRefresh = r.tokens.refresh
                        config.googleEmail = r.tokens.email
                        withContext(Dispatchers.IO) { config.save() }
                        signInCode = null
                        signedIn = true
                        say("Signed in – bringing in your TV's channels and lists")
                        syncSoon(0)
                        return@launch
                    }
                    is GoogleDrive.Poll.Failed -> {
                        signInCode = null
                        syncStatus = r.reason
                        return@launch
                    }
                    GoogleDrive.Poll.SlowDown -> interval += 5_000L
                    else -> Unit
                }
            }
            if (signInCode === code) {
                signInCode = null
                syncStatus = "The code expired – try again"
            }
        }
    }

    fun cancelSignIn() {
        signInCode = null
    }

    fun signOut() {
        config.googleRefresh = null
        config.googleEmail = null
        config.save()
        signedIn = false
        say("Signed out – the TV's lists stay here")
    }

    fun syncSoon(delayMs: Long = 5_000L) {
        if (config.googleRefresh == null) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            syncStatus = "Syncing…"
            val r = withContext(Dispatchers.IO) { runCatching { sync.syncNow() } }
            r.onSuccess { res ->
                syncStatus = null
                if (res.linkKeyChanged) startLink()
                if (res.listsChanged) listsVersion++
                if (config.activeProfile == null && config.profiles.size > 1) askWhoIsWatching = true
                profileId = config.profileKey
                if (res.playlistsChanged) reload()
            }.onFailure { syncStatus = "Couldn't sync: ${it.message}" }
        }
    }

    // ---------------- Plex ----------------

    fun linkPlex() {
        scope.launch {
            val pin = withContext(Dispatchers.IO) { runCatching { plex.createPin() }.getOrNull() } ?: return@launch say("Couldn't reach Plex")
            plexCode = pin.code
            repeat(120) {
                delay(3_000)
                if (plexCode != pin.code) return@launch
                val token = withContext(Dispatchers.IO) { runCatching { plex.checkPin(pin) }.getOrNull() }
                if (token != null) {
                    config.plexToken = token
                    withContext(Dispatchers.IO) { config.save() }
                    plexCode = null
                    plexLinked = true
                    say("Plex linked ✓")
                    loadPlex()
                    return@launch
                }
            }
            plexCode = null
        }
    }

    fun cancelPlexLink() {
        plexCode = null
    }

    fun unlinkPlex() {
        config.plexToken = null
        config.save()
        plexLinked = false
        plexServers = emptyList()
        plexDeck = emptyList()
        plexSections = emptyList()
    }

    fun loadPlex() {
        val token = config.plexToken ?: return
        scope.launch {
            val servers = withContext(Dispatchers.IO) { runCatching { plex.servers(token) }.getOrDefault(emptyList()) }
            plexServers = servers
            if (servers.isEmpty()) return@launch
            plexDeck = withContext(Dispatchers.IO) { runCatching { plex.onDeck(servers) }.getOrDefault(emptyList()) }
            plexSections = withContext(Dispatchers.IO) { runCatching { plex.sections(servers) }.getOrDefault(emptyList()) }
            plexRecent = withContext(Dispatchers.IO) { runCatching { plex.recentlyAdded(servers, 30) }.getOrDefault(emptyList()) }
        }
    }

    /** Plays a Plex film or episode (asks the server for the file if the list didn't say). */
    fun playPlex(ratingKey: String, title: String, subtitle: String?, image: String?, playUrl: String?, server: Plex.Server, offsetMs: Long, fromStart: Boolean = false) {
        scope.launch {
            val url = playUrl ?: withContext(Dispatchers.IO) { runCatching { plex.playUrlFor(server, ratingKey) }.getOrNull() }
            if (url == null) return@launch say("Plex didn't give the file for $title")
            stopWatching(save = true)
            playingChannel = null
            watching = Watching("plex:$ratingKey", title, subtitle, image, url, null, resumable = false)
            player.play(url, if (fromStart) 0L else offsetMs)
            player.setVolume(config.volume)
        }
    }
}

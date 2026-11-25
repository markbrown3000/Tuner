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
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.integrations.GoogleDrive
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Page(val label: String, val icon: String) {
    Home("Home", "⌂"), Live("Live TV", "📺"), Guide("TV Guide", "▦"), Films("Films", "🎬"), Series("Series", "🎞"),
    Plex("Plex", "▶"), YouTube("YouTube", "▷"), Settings("Settings", "⚙"),
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
            return if (p != null && p.kids) channels.filter { it.group in p.groups } else channels
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
        stopWatching(save = true)
        playingChannel = c
        catchupOf = null
        page = Page.Live
        player.play(c.url)
        player.setVolume(config.volume)
        config.lastChannel = c.id
        scope.launch(Dispatchers.IO) { config.save() }
    }

    fun playCatchup(c: Channel, p: Programme) {
        val url = Catchup.url(c, p, System.currentTimeMillis()) ?: return say("Catch-up isn't available for that")
        stopWatching(save = true)
        playingChannel = c
        catchupOf = p
        page = Page.Live
        player.play(url)
    }

    /** Films, episodes and Plex: the big player, from where you left off. */
    fun watch(w: Watching, fromStart: Boolean = false) {
        stopWatching(save = true)
        playingChannel = null
        val start = if (fromStart) 0L else config.resume[w.id]?.pos?.takeIf { w.resumable && it > 10_000L } ?: 0L
        watching = w
        player.play(w.url, start)
        player.setVolume(config.volume)
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
        val w = watching ?: return
        if (w.resumable) {
            config.resume = config.resume - w.id
            config.done = config.done + (w.id to System.currentTimeMillis())
            changed()
        }
        val next = w.next
        watching = null
        if (next != null) {
            say("Playing the next episode")
            next()
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
        if (t - due.start < 30 * 60_000L) dueReminder = due
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

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
        if (blockedForKids()) return
        endTimeshift()
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
        // Downloaded: play the copy on this PC.
        val local = config.downloads.firstOrNull { it.id == w.id && it.state == "done" && java.io.File(it.file).exists() }
        player.play(local?.let { java.io.File(it.file).toURI().toString() } ?: w.url, start)
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
        val next = w.next
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

    /** Once a day (or now): is there a newer Flicker for Windows? */
    fun checkForUpdate(manual: Boolean = false) {
        if (!manual && System.currentTimeMillis() - config.lastUpdateCheck < 20 * 3_600_000L) return
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

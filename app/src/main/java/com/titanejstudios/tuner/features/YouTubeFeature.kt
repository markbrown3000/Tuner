package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.BuildConfig
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.GoogleDrive
import com.titanejstudios.tuner.integrations.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YouTube in Flicker: search (with the built-in API key, or your sign-in) and – once you've signed
 * in to YouTube on this device – the latest videos from the channels you subscribe to.
 */
class YouTubeFeature(private val store: Store, private val scope: CoroutineScope, startProfile: String) {
    /** Each profile has its own YouTube sign-in and subscriptions. */
    private var pid = startProfile

    private val google = GoogleDrive(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET)
    private val apiKey: String? = BuildConfig.YOUTUBE_KEY.takeIf { it.isNotBlank() }

    /** Search works (an API key is built in, or you're signed in). */
    val canSearch: Boolean get() = apiKey != null || signedIn
    /** Signing in is possible (the Google sign-in is set up). */
    val canSignIn: Boolean get() = google.configured

    var signedIn by mutableStateOf(store.ytRefreshOf(pid) != null); private set
    var email by mutableStateOf(store.ytEmailOf(pid)); private set
    var code by mutableStateOf<GoogleDrive.DeviceCode?>(null); private set
    var status by mutableStateOf<String?>(null); private set

    var query by mutableStateOf(""); private set
    var results by mutableStateOf<List<YouTube.Video>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var feed by mutableStateOf(YouTube.fromJson(store.ytFeedOf(pid))); private set
    var loadingFeed by mutableStateOf(false); private set

    /** One video watched here: where you got to, and how often. */
    class Watched(val video: YouTube.Video, val at: Long, val position: Float, val length: Float, val plays: Int) {
        /** Stopped part way (worth carrying on). */
        val unfinished: Boolean get() = length > 60f && position > 20f && position < length - 30f
    }

    var history by mutableStateOf(loadHistory(startProfile)); private set
    /** Videos picked for this profile from what it watches here. */
    var recommended by mutableStateOf(YouTube.fromJson(store.ytRecsOf(startProfile))); private set
    var loadingRecs by mutableStateOf(false); private set

    private fun loadHistory(profileId: String): List<Watched> = runCatching {
        val a = org.json.JSONArray(store.ytHistoryOf(profileId) ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Watched(YouTube.videoFromJson(o.getJSONObject("v")), o.optLong("at"), o.optDouble("pos", 0.0).toFloat(), o.optDouble("len", 0.0).toFloat(), o.optInt("n", 1))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory() {
        val a = org.json.JSONArray()
        history.forEach { w ->
            a.put(org.json.JSONObject().put("v", YouTube.videoJson(w.video)).put("at", w.at).put("pos", w.position.toDouble()).put("len", w.length.toDouble()).put("n", w.plays))
        }
        store.saveYtHistory(pid, a.toString())
    }

    /** A video was closed after [position] of [length] seconds. Trailers and quick looks aren't kept. */
    fun watched(v: YouTube.Video, position: Float, length: Float) {
        if (v.channel.isBlank() || position < 15f) return
        val before = history.firstOrNull { it.video.id == v.id }
        val w = Watched(v, System.currentTimeMillis(), position, length, (before?.plays ?: 0) + 1)
        history = (listOf(w) + history.filter { it.video.id != v.id }).take(300)
        saveHistory()
        // Something new to go on: freshen the picks (soon, not on every video).
        if (System.currentTimeMillis() - store.ytRecsAtOf(pid) > 30 * 60_000L) loadRecommended(force = true)
    }

    /** Where to start [id] (carry on part-watched videos). */
    fun resumeAt(id: String): Float = history.firstOrNull { it.video.id == id }?.takeIf { it.unfinished }?.position?.minus(3f)?.coerceAtLeast(0f) ?: 0f

    fun forget(id: String) {
        history = history.filter { it.video.id != id }
        saveHistory()
    }

    /**
     * Picks from what this profile watches: the newest from the channels it watches most (that it
     * hasn't seen), plus YouTube's results for its most-watched kinds of video. Every 6 hours.
     */
    fun loadRecommended(force: Boolean = false) {
        if (loadingRecs || history.isEmpty()) return
        if (!force && recommended.isNotEmpty() && System.currentTimeMillis() - store.ytRecsAtOf(pid) < 6 * 3_600_000L) return
        val forProfile = pid
        val seen = history.map { it.video.id }.toSet()
        // Channels by how much they're watched (recent viewing counts more).
        val now = System.currentTimeMillis()
        val channelScore = HashMap<String, Double>()
        val channelName = HashMap<String, String>()
        for (w in history) {
            val cid = w.video.channelId ?: continue
            val ageDays = (now - w.at) / 86_400_000.0
            channelScore[cid] = (channelScore[cid] ?: 0.0) + w.plays * (if (w.length > 0f) (w.position / w.length).coerceIn(0.2f, 1f) else 0.5f) / (1.0 + ageDays / 14.0)
            channelName[cid] = w.video.channel
        }
        val topChannels = channelScore.entries.filter { it.key !in blocked }.sortedByDescending { it.value }.take(8).map { it.key }
        // A search on the latest few things watched, by their channel names and a title word or two.
        val queries = history.take(12).map { it.video.channel }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
        loadingRecs = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val token = runCatching { accessToken() }.getOrNull()
                    val key = apiKey
                    val fromChannels = YouTube.uploads(topChannels, 6, key, if (key == null) token else null)
                    val fromSearch = queries.flatMap { q ->
                        runCatching { YouTube.search(q, key, if (key == null) token else null) }.getOrDefault(emptyList())
                            .filter { it.channelId == null || it.channelId !in topChannels }.take(6)
                    }
                    // Mix: two from the channels you watch, then one new find, and so on.
                    val a = fromChannels.filter { it.id !in seen }.sortedByDescending { it.publishedAt }
                    val b = fromSearch.filter { it.id !in seen }
                    val out = ArrayList<YouTube.Video>()
                    var i = 0
                    var j = 0
                    while (i < a.size || j < b.size) {
                        repeat(2) { if (i < a.size) out += a[i++] }
                        if (j < b.size) out += b[j++]
                    }
                    out.distinctBy { it.id }.filter { keep(it) }.take(40)
                }
            }
            loadingRecs = false
            r.onSuccess { list ->
                store.saveYtRecs(forProfile, YouTube.toJson(list))
                if (forProfile == pid) recommended = list
            }.onFailure { AppLog.warn("YouTube recommendations failed", it) }
        }
    }

    /** Kids' approved channels: their newest videos. */
    var approvedFeed by mutableStateOf<List<YouTube.Video>>(emptyList()); private set
    var loadingApproved by mutableStateOf(false); private set
    private var approvedFor: List<String> = emptyList()
    private var approvedAt = 0L
    /** Channel search, for a grown-up choosing a kids' profile's channels. */
    var channelResults by mutableStateOf<List<YouTube.Channel>>(emptyList()); private set
    var searchingChannels by mutableStateOf(false); private set
    var channelStatus by mutableStateOf<String?>(null); private set

    private var access: GoogleDrive.Tokens? = null
    private var signInJob: Job? = null

    /** Another profile is watching now: their own YouTube (or none). */
    fun useProfile(profileId: String) {
        if (profileId == pid) return
        signInJob?.cancel()
        pid = profileId
        access = null
        code = null
        status = null
        signedIn = store.ytRefreshOf(pid) != null
        email = store.ytEmailOf(pid)
        feed = YouTube.fromJson(store.ytFeedOf(pid))
        history = loadHistory(pid)
        loadLists()
        recommended = YouTube.fromJson(store.ytRecsOf(pid)).filter { keep(it) }
        results = emptyList()
        query = ""
        liked = emptyList()
        playlists = emptyList()
        mineAt = 0L
    }

    /** Lists synced from another device: read them again. */
    fun reload() {
        history = loadHistory(pid)
        loadLists()
        recommended = recommended.filter { keep(it) }
    }

    // ---------------- Watch later, "not interested", recent searches (per profile) ----------------

    var later by mutableStateOf<List<YouTube.Video>>(emptyList()); private set
    private var hidden: Set<String> = emptySet()
    private var blocked: Set<String> = emptySet()
    var recentSearches by mutableStateOf<List<String>>(emptyList()); private set

    init {
        loadLists()
    }

    private fun strings(kind: String): List<String> = runCatching {
        val a = org.json.JSONArray(store.ytData(kind, pid) ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveStrings(kind: String, list: Collection<String>) = store.saveYtData(kind, pid, org.json.JSONArray(list.toList()).toString())

    private fun loadLists() {
        later = YouTube.fromJson(store.ytData("later", pid))
        hidden = strings("hide").toSet()
        blocked = strings("block").toSet()
        recentSearches = strings("recent")
    }

    private fun keep(v: YouTube.Video) = v.id !in hidden && (v.channelId == null || v.channelId !in blocked)

    fun isLater(id: String) = later.any { it.id == id }

    fun toggleLater(v: YouTube.Video) {
        later = if (isLater(v.id)) later.filter { it.id != v.id } else listOf(v) + later
        store.saveYtData("later", pid, YouTube.toJson(later.take(200)))
        store.saveYtData("later_at", pid, System.currentTimeMillis().toString())
    }

    /** "Not interested": never recommend this one again. */
    fun notInterested(v: YouTube.Video) {
        hidden = (hidden + v.id).toList().takeLast(500).toSet()
        saveStrings("hide", hidden)
        recommended = recommended.filter { keep(it) }
        store.saveYtRecs(pid, YouTube.toJson(recommended))
    }

    /** "Fewer from this channel": its videos stop being recommended. */
    fun lessFromChannel(v: YouTube.Video) {
        val cid = v.channelId ?: return notInterested(v)
        blocked = blocked + cid
        saveStrings("block", blocked)
        recommended = recommended.filter { keep(it) }
        store.saveYtRecs(pid, YouTube.toJson(recommended))
    }

    private fun rememberSearch(q: String) {
        recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(10)
        saveStrings("recent", recentSearches)
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        saveStrings("recent", recentSearches)
    }

    /** Search filter: "" any, "long", "live", "week". */
    var searchFilter by mutableStateOf(""); private set
    private var lastKids = false

    fun setFilterAndSearch(filter: String) {
        searchFilter = filter
        if (query.length >= 2) search(query, lastKids)
    }

    // ---------------- Liked videos and your playlists ----------------

    var liked by mutableStateOf<List<YouTube.Video>>(emptyList()); private set
    var playlists by mutableStateOf<List<YouTube.Playlist>>(emptyList()); private set
    private var mineAt = 0L

    fun loadMine(force: Boolean = false) {
        if (!signedIn) return
        if (!force && System.currentTimeMillis() - mineAt < 3_600_000L) return
        mineAt = System.currentTimeMillis()
        val forProfile = pid
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val token = accessToken() ?: throw GoogleDrive.SignedOut()
                    YouTube.liked(token) to runCatching { YouTube.myPlaylists(token) }.getOrDefault(emptyList())
                }
            }
            if (forProfile != pid) return@launch
            r.onSuccess { (l, p) ->
                liked = l
                playlists = p
            }.onFailure { AppLog.warn("YouTube liked/playlists failed", it) }
        }
    }

    // ---------------- A list page: a channel, a playlist, or Watch later ----------------

    var listTitle by mutableStateOf(""); private set
    var listVideos by mutableStateOf<List<YouTube.Video>>(emptyList()); private set
    var listLoading by mutableStateOf(false); private set
    var listStatus by mutableStateOf<String?>(null); private set
    /** A channel page (with Newest / Most popular), else null. */
    var listChannelId by mutableStateOf<String?>(null); private set
    var listPopular by mutableStateOf(false); private set
    var listIsLater by mutableStateOf(false); private set
    private var listJob: Job? = null

    fun openChannel(channelId: String, name: String, kids: Boolean, popular: Boolean = false) {
        listTitle = name
        listChannelId = channelId
        listPopular = popular
        listIsLater = false
        loadList { YouTube.channelVideos(channelId, popular, apiKey, if (apiKey == null) runCatching { accessToken() }.getOrNull() else null, kids) }
    }

    fun openPlaylist(p: YouTube.Playlist) {
        listTitle = p.title
        listChannelId = null
        listIsLater = false
        loadList { YouTube.playlistVideos(p.id, apiKey, runCatching { accessToken() }.getOrNull()) }
    }

    fun openLater() {
        listJob?.cancel()
        listTitle = "Watch later"
        listChannelId = null
        listIsLater = true
        listStatus = null
        listLoading = false
        listVideos = later
    }

    /** Watch later changed while its page is open. */
    fun refreshLaterPage() {
        if (listIsLater) listVideos = later
    }

    private fun loadList(load: () -> List<YouTube.Video>) {
        listJob?.cancel()
        listVideos = emptyList()
        listStatus = null
        listLoading = true
        listJob = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { load() } }
            listLoading = false
            r.onSuccess {
                listVideos = it
                if (it.isEmpty()) listStatus = "No videos here"
            }.onFailure { listStatus = it.message ?: "Couldn't load this" }
        }
    }

    // ---------------- For the player and sharing ----------------

    var skipSponsors by mutableStateOf(store.ytSkipSponsors); private set

    fun toggleSkipSponsors() {
        skipSponsors = !skipSponsors
        store.ytSkipSponsors = skipSponsors
    }

    /** A video's chapters and its skippable sponsor parts (each may be empty). */
    suspend fun extras(id: String): Pair<List<YouTube.Chapter>, List<YouTube.Segment>> = withContext(Dispatchers.IO) {
        val chapters = runCatching {
            if (!canSearch) emptyList() else YouTube.chapters(YouTube.videoInfo(id, apiKey, if (apiKey == null) runCatching { accessToken() }.getOrNull() else null).second)
        }.getOrDefault(emptyList())
        val segments = if (skipSponsors) runCatching { YouTube.sponsorSegments(id) }.getOrDefault(emptyList()) else emptyList()
        chapters to segments
    }

    /** A shared link's video, with its title (or a plain one if YouTube can't say). */
    suspend fun lookup(id: String): YouTube.Video = withContext(Dispatchers.IO) {
        runCatching { YouTube.videoInfo(id, apiKey, if (apiKey == null) runCatching { accessToken() }.getOrNull() else null).first }.getOrNull()
            ?: YouTube.Video(id, "YouTube video", "", "https://i.ytimg.com/vi/$id/hqdefault.jpg", 0L)
    }

    // ---------------- Signing in ----------------

    fun signIn() {
        if (!canSignIn) {
            status = "This copy of Flicker hasn't been set up for Google sign-in yet"
            return
        }
        signInJob?.cancel()
        status = "Getting a code…"
        signInJob = scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { google.startSignIn("${YouTube.SCOPE} email") } }.getOrElse {
                status = "Couldn't reach Google: ${it.message}"
                return@launch
            }
            code = d
            status = null
            var interval = d.intervalSec.coerceAtLeast(3) * 1000L
            while (System.currentTimeMillis() < d.expiresAt) {
                delay(interval)
                val r = withContext(Dispatchers.IO) {
                    runCatching { google.poll(d, YouTube.SCOPE, YouTube.NO_YOUTUBE) }.getOrElse { GoogleDrive.Poll.Waiting }
                }
                when (r) {
                    is GoogleDrive.Poll.Waiting -> Unit
                    is GoogleDrive.Poll.SlowDown -> interval += 5_000L
                    is GoogleDrive.Poll.Failed -> {
                        code = null
                        status = r.reason
                        return@launch
                    }
                    is GoogleDrive.Poll.Done -> {
                        code = null
                        access = r.tokens
                        store.saveYtRefresh(pid, r.tokens.refresh)
                        store.saveYtEmail(pid, r.tokens.email)
                        email = r.tokens.email
                        signedIn = r.tokens.refresh != null
                        status = "✓ Signed in to YouTube" + (r.tokens.email?.let { " as $it" } ?: "")
                        loadFeed(force = true)
                        return@launch
                    }
                }
            }
            code = null
            status = "The code ran out – start again"
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        code = null
        status = null
    }

    fun signOut() {
        val r = store.ytRefreshOf(pid)
        store.saveYtRefresh(pid, null)
        store.saveYtEmail(pid, null)
        store.saveYtFeed(pid, null)
        access = null
        signedIn = false
        email = null
        feed = emptyList()
        status = "Signed out of YouTube on this profile"
        if (r != null) scope.launch(Dispatchers.IO) { google.revoke(r) }
    }

    private fun accessToken(): String? {
        access?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.access }
        val refresh = store.ytRefreshOf(pid) ?: return null
        val t = google.refresh(refresh)
        access = t
        return t.access
    }

    fun clearSearch() {
        query = ""
        results = emptyList()
        status = null
    }

    // ---------------- Kids: approved channels ----------------

    fun searchChannels(q: String) {
        val text = q.trim()
        if (text.length < 2) return
        if (!canSearch) {
            channelStatus = "Searching needs the YouTube key (see README › YouTube)"
            return
        }
        searchingChannels = true
        channelStatus = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { YouTube.searchChannels(text, apiKey, if (apiKey == null) runCatching { accessToken() }.getOrNull() else null) }
            }
            searchingChannels = false
            r.onSuccess {
                channelResults = it
                if (it.isEmpty()) channelStatus = "No channels found for \"$text\""
            }.onFailure { channelStatus = it.message ?: "Couldn't search YouTube" }
        }
    }

    fun clearChannelSearch() {
        channelResults = emptyList()
        channelStatus = null
    }

    /** The newest videos from [channelIds] (every hour, or now when [force] or the list changed). */
    fun loadApproved(channelIds: List<String>, force: Boolean = false) {
        if (channelIds.isEmpty()) {
            approvedFeed = emptyList()
            approvedFor = emptyList()
            return
        }
        if (loadingApproved) return
        if (!force && channelIds == approvedFor && approvedFeed.isNotEmpty() && System.currentTimeMillis() - approvedAt < 3_600_000L) return
        if (channelIds != approvedFor) approvedFeed = emptyList()
        loadingApproved = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val token = if (apiKey == null) runCatching { accessToken() }.getOrNull() else null
                    YouTube.uploads(channelIds.take(40), 8, apiKey, token)
                }
            }
            loadingApproved = false
            r.onSuccess {
                approvedFeed = it
                approvedFor = channelIds
                approvedAt = System.currentTimeMillis()
            }.onFailure { AppLog.warn("Kids' YouTube channels failed", it) }
        }
    }

    // ---------------- Search & subscriptions ----------------

    fun search(q: String, kids: Boolean) {
        val text = q.trim()
        query = text
        lastKids = kids
        if (text.length < 2) {
            results = emptyList()
            return
        }
        if (!canSearch) {
            status = "YouTube search needs the YouTube key (or sign in to YouTube)"
            return
        }
        searching = true
        status = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val token = runCatching { accessToken() }.getOrNull()
                    YouTube.search(text, apiKey, if (apiKey == null) token else null, strict = kids, filter = searchFilter)
                }
            }
            searching = false
            r.onSuccess {
                results = it
                rememberSearch(text)
            }.onFailure {
                status = it.message ?: "Couldn't search YouTube"
                AppLog.warn("YouTube search failed", it)
            }
        }
    }

    /** Your subscriptions' newest videos (every 3 hours, or now when [force]). */
    fun loadFeed(force: Boolean = false) {
        if (!signedIn || loadingFeed) return
        if (!force && feed.isNotEmpty() && System.currentTimeMillis() - store.ytFeedAtOf(pid) < 3 * 3_600_000L) return
        loadingFeed = true
        val forProfile = pid
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val token = accessToken() ?: throw GoogleDrive.SignedOut()
                    try {
                        YouTube.subscriptionsFeed(token)
                    } catch (e: GoogleDrive.Unauthorised) {
                        access = null
                        YouTube.subscriptionsFeed(accessToken() ?: throw GoogleDrive.SignedOut())
                    }
                }
            }
            loadingFeed = false
            // Someone else picked their profile meanwhile: keep it for the profile it was for.
            if (forProfile != pid) {
                r.onSuccess { store.saveYtFeed(forProfile, YouTube.toJson(it.take(120))) }
                return@launch
            }
            r.onSuccess {
                feed = it
                store.saveYtFeed(pid, YouTube.toJson(it.take(120)))
            }.onFailure {
                if (it is GoogleDrive.SignedOut) {
                    signOut()
                    status = "Google signed this device out of YouTube – sign in again"
                } else {
                    if (it.message == YouTube.NO_YOUTUBE) signOut()
                    status = it.message ?: "Couldn't load your subscriptions"
                }
                AppLog.warn("YouTube subscriptions failed", it)
            }
        }
    }
}

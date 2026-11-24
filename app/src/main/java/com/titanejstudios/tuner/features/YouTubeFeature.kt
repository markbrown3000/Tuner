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
        results = emptyList()
        query = ""
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
                    YouTube.search(text, apiKey, if (apiKey == null) token else null, strict = kids)
                }
            }
            searching = false
            r.onSuccess { results = it }.onFailure {
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

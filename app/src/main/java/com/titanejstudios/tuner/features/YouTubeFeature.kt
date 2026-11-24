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
class YouTubeFeature(private val store: Store, private val scope: CoroutineScope) {
    private val google = GoogleDrive(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET)
    private val apiKey: String? = BuildConfig.YOUTUBE_KEY.takeIf { it.isNotBlank() }

    /** Search works (an API key is built in, or you're signed in). */
    val canSearch: Boolean get() = apiKey != null || signedIn
    /** Signing in is possible (the Google sign-in is set up). */
    val canSignIn: Boolean get() = google.configured

    var signedIn by mutableStateOf(store.ytRefresh != null); private set
    var email by mutableStateOf(store.ytEmail); private set
    var code by mutableStateOf<GoogleDrive.DeviceCode?>(null); private set
    var status by mutableStateOf<String?>(null); private set

    var query by mutableStateOf(""); private set
    var results by mutableStateOf<List<YouTube.Video>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var feed by mutableStateOf(YouTube.fromJson(store.ytFeed)); private set
    var loadingFeed by mutableStateOf(false); private set

    private var access: GoogleDrive.Tokens? = null
    private var signInJob: Job? = null

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
                        store.ytRefresh = r.tokens.refresh
                        store.ytEmail = r.tokens.email
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
        val r = store.ytRefresh
        store.ytRefresh = null
        store.ytEmail = null
        store.ytFeed = null
        access = null
        signedIn = false
        email = null
        feed = emptyList()
        status = "Signed out of YouTube on this device"
        if (r != null) scope.launch(Dispatchers.IO) { google.revoke(r) }
    }

    private fun accessToken(): String? {
        access?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.access }
        val refresh = store.ytRefresh ?: return null
        val t = google.refresh(refresh)
        access = t
        return t.access
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
        if (!force && feed.isNotEmpty() && System.currentTimeMillis() - store.ytFeedAt < 3 * 3_600_000L) return
        loadingFeed = true
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
            r.onSuccess {
                feed = it
                store.ytFeed = YouTube.toJson(it.take(120))
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

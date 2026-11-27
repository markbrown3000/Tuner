package com.titanejstudios.tuner

import android.app.Application
import android.content.Intent
import org.json.JSONObject
import android.net.Uri
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import com.titanejstudios.tuner.apps.InstalledApps
import com.titanejstudios.tuner.apps.TvApp
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.HealthCheck
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.VoiceMatch
import com.titanejstudios.tuner.data.WatchStats
import com.titanejstudios.tuner.tv.LiveChannelSync
import com.titanejstudios.tuner.data.WhatsOn
import com.titanejstudios.tuner.ui.SubtitleLook
import com.titanejstudios.tuner.ui.TunerColors
import com.titanejstudios.tuner.data.RecordingPaths
import com.titanejstudios.tuner.data.SeriesLink
import com.titanejstudios.tuner.data.SeriesLinks
import com.titanejstudios.tuner.data.StreamAlternates
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.Profile
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.Reminder
import com.titanejstudios.tuner.data.Repository
import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.SeriesItem
import com.titanejstudios.tuner.data.FilmName
import com.titanejstudios.tuner.data.Taste
import com.titanejstudios.tuner.data.Recommend
import com.titanejstudios.tuner.data.Languages
import com.titanejstudios.tuner.data.CatchupApps
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.data.XmltvChannel
import com.titanejstudios.tuner.data.XmltvParser
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.data.FollowedSeries
import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.SafeMode
import com.titanejstudios.tuner.data.VodCache
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import com.titanejstudios.tuner.data.FamilyPick
import com.titanejstudios.tuner.data.ComingEpisode
import com.titanejstudios.tuner.features.FootballFeature
import com.titanejstudios.tuner.features.PlexFeature
import com.titanejstudios.tuner.features.SelfTest
import com.titanejstudios.tuner.features.UpdateFeature
import com.titanejstudios.tuner.integrations.CinemaLights
import com.titanejstudios.tuner.playback.StreamCushion
import com.titanejstudios.tuner.playback.VolumeLevels
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.ChannelMerge
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.integrations.NasBackup
import com.titanejstudios.tuner.integrations.OpenSubtitles
import com.titanejstudios.tuner.integrations.Tmdb
import com.titanejstudios.tuner.integrations.Omdb
import com.titanejstudios.tuner.integrations.TvSync
import com.titanejstudios.tuner.integrations.AppState
import com.titanejstudios.tuner.integrations.HomeAssistantWebhook
import com.titanejstudios.tuner.integrations.ReminderAlarms
import com.titanejstudios.tuner.integrations.TvShare
import com.titanejstudios.tuner.integrations.Plex
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import com.titanejstudios.tuner.integrations.RemoteHandler
import com.titanejstudios.tuner.playback.MultiViewController
import com.titanejstudios.tuner.playback.PlaybackController
import com.titanejstudios.tuner.recording.RecordingScheduler
import com.titanejstudios.tuner.tv.GuideRefreshWorker
import com.titanejstudios.tuner.tv.HomeScreen
import com.titanejstudios.tuner.ui.hhmm
import com.titanejstudios.tuner.ui.dayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Part of TunerViewModel, split out by subject (functions only; its state stays in the view model).

/**
 * Flicker's own 🎤 Say it: a channel's name or number goes straight to it ("BBC One",
 * "Sky Sports Main Event", "101"); anything else is searched for.
 */
internal fun TunerViewModel.voiceHeard(text: String) {
    // "Record…", "next episode", "what's on BBC One later", "something funny"…
    if (runVoiceCommand(text)) {
        updateSearch("")
        return
    }
    val all = channelsFor(ALL)
    val number = VoiceMatch.number(text)
    val ch = if (number != null) all.firstOrNull { it.number == number } ?: all.getOrNull(number - 1)
    else VoiceMatch.find(all, text, strict = true)
    if (ch != null && isAllowed(ch)) {
        updateSearch("")
        resetTo(Screen.Home)
        play(ch, all)
        showNotice("🎤 ${ch.name}")
        return
    }
    updateSearch(VoiceMatch.clean(text).ifEmpty { text })
}

internal fun TunerViewModel.handleIntent(intent: Intent?) {
    if (intent == null) return
    onUserActivity()
    // Voice / TV search: "play Sky Sports on Tuner", or a search typed on the TV.
    val voiceActions = setOf(
        "android.media.action.MEDIA_PLAY_FROM_SEARCH",
        Intent.ACTION_SEARCH,
        "com.google.android.gms.actions.SEARCH_ACTION",
    )
    // A YouTube link shared to Flicker (from the YouTube app's Share button, say).
    if (intent.action == Intent.ACTION_SEND) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val id = youTubeIdIn(text)
        if (id == null) {
            showNotice("Flicker can play YouTube links shared to it – that wasn't one")
            return
        }
        playSharedYouTube(id)
        return
    }
    if (intent.action in voiceActions) {
        val q = intent.getStringExtra(android.app.SearchManager.QUERY) ?: intent.getStringExtra("query") ?: return
        pendingLink = Uri.Builder().scheme("tuner").authority("voice").appendQueryParameter("q", q).build()
        handlePendingLink()
        return
    }
    val data = intent.data ?: return
    if (data.scheme != "tuner") return
    pendingLink = data
    handlePendingLink()
}

internal fun TunerViewModel.handlePendingLink() {
    val uri = pendingLink ?: return
    val wantsChannel = uri.host == "play" || uri.host == "voice" || uri.getQueryParameter("channel") != null
    if (wantsChannel && allChannels.isEmpty()) return // wait until channels have loaded
    pendingLink = null
    startupHandled = true
    when (uri.host) {
        "play" -> {
            val all = channelsFor(ALL)
            val number = uri.getQueryParameter("number")?.toIntOrNull()
            val name = uri.getQueryParameter("name") ?: uri.getQueryParameter("channel")
            val ch = when {
                number != null -> all.firstOrNull { it.number == number } ?: all.getOrNull(number - 1)
                name != null -> findByName(all, name)
                else -> null
            }
            if (ch != null) {
                resetTo(Screen.Home)
                play(ch, all)
            } else showNotice("Couldn't find channel \"${name ?: number}\"")
        }
        "stop" -> {
            stopPlayback()
            resetTo(Screen.Home)
        }
        "voice" -> {
            val q = uri.getQueryParameter("q").orEmpty()
            if (runVoiceCommand(q)) return
            val all = channelsFor(ALL)
            val number = VoiceMatch.number(q)
            val ch = if (number != null) all.firstOrNull { it.number == number } ?: all.getOrNull(number - 1) else VoiceMatch.find(all, q)
            if (ch != null) {
                resetTo(Screen.Home)
                play(ch, all)
            } else {
                // Not a channel – maybe a programme: show search results for it.
                resetTo(Screen.Home)
                navigate(Screen.Search)
                updateSearch(VoiceMatch.clean(q).ifEmpty { q })
            }
        }
        "open" -> {
            uri.getQueryParameter("channel")?.let { id ->
                val ch = allChannels.firstOrNull { it.id == id }
                if (ch != null) {
                    resetTo(Screen.Home)
                    play(ch, channelsFor(FAVOURITES).ifEmpty { channelsFor(ALL) })
                }
            }
            uri.getQueryParameter("vod")?.let { id ->
                resume[id]?.let {
                    resetTo(Screen.Home)
                    playResume(it)
                }
            }
        }
    }
}

internal fun TunerViewModel.findByName(list: List<Channel>, name: String): Channel? {
    val n = XmltvParser.normalise(name)
    if (n.isEmpty()) return null
    return list.firstOrNull { XmltvParser.normalise(it.name) == n }
        ?: list.firstOrNull { XmltvParser.normalise(it.name).startsWith(n) }
        ?: list.firstOrNull { XmltvParser.normalise(it.name).contains(n) }
}

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
import com.titanejstudios.tuner.TunerViewModel.SaverSlide

// Part of TunerViewModel, split out by subject (functions only; its state stays in the view model).

internal fun TunerViewModel.onUserActivity() {
    lastUserActivity = System.currentTimeMillis()
    buttonPressedAt = lastUserActivity
    if (saverOn) {
        saverOn = false
        askWhoIsWatching()
    }
}

internal fun TunerViewModel.onScreensaverStarted() {
    // Phones lock their screen all the time – only TVs ask.
    if (!isPhone) askProfileOnReturn = true
}

internal fun TunerViewModel.onScreensaverEnded() {
    if (!askProfileOnReturn) return
    askProfileOnReturn = false
    askWhoIsWatching()
}

/** After any screensaver, with more than one profile: pick who's watching again. */
internal fun TunerViewModel.askWhoIsWatching() {
    if (profiles.size < 2) return
    if (screen == Screen.ProfilePicker || screen == Screen.ProfileEdit || screen == Screen.SourceEdit) return
    // Someone's actually watching (the screensaver only came on over a paused or empty screen).
    if (screen == Screen.Player && playback.isPlaying) return
    if (mode is PlayMode.Vod) saveResume(syncHome = true)
    stopPlayback()
    resetTo(Screen.ProfilePicker)
}

internal fun TunerViewModel.toggleTintFromPicture() {
    tintFromPicture = !tintFromPicture
    store.tintFromPicture = tintFromPicture
}

internal fun TunerViewModel.cycleArtSaver() {
    val choices = listOf(5, 10, 20, 0)
    artSaverMinutes = choices[(choices.indexOf(artSaverMinutes) + 1).mod(choices.size)]
    store.artSaverMinutes = artSaverMinutes
}

internal fun TunerViewModel.saverIndexChanged(slide: SaverSlide) {
    saverCurrent = slide
}

/** OK on the screensaver: watch what's showing. */
internal fun TunerViewModel.playSaverSlide() {
    saverOn = false
    // More than one profile: choose who's watching first (that comes before playing anything).
    if (profiles.size > 1) {
        askWhoIsWatching()
        return
    }
    saverCurrent?.play?.invoke()
}

/** Idle for a while with nothing playing: show the artwork screensaver. */
internal fun TunerViewModel.checkSaver() {
    val mins = artSaverMinutes
    if (mins <= 0 || saverOn) return
    val idle = System.currentTimeMillis() - lastUserActivity
    val watching = mode != null && playback.isPlaying
    // Never over a YouTube video (playing or paused – a paused one is still being watched).
    if (screen == Screen.YouTubePlayer || screen == Screen.MultiView) return
    if (idle > mins * 60_000L && !watching && screen != Screen.Player && saverSlides.isNotEmpty()) saverOn = true
}

internal fun TunerViewModel.checkStillWatching() {
    val hours = stillWatchingHours
    val t = System.currentTimeMillis()
    if (stillWatchingAsk) {
        if (t > stillWatchingDeadline) {
            stillWatchingAsk = false
            AppLog.info("Nobody answered \"Are you still watching?\" – stopped")
            sendHa("still_watching_timeout", mapOf("kind" to playKind(mode)))
            stopPlayback()
            goHome()
            showNotice("Stopped – nobody was watching")
        }
        return
    }
    if (hours <= 0 || mode == null || screen != Screen.Player || !playback.isPlaying) return
    if (t - lastUserActivity > hours * 3_600_000L) {
        stillWatchingAsk = true
        stillWatchingDeadline = t + 90_000L
    }
}

internal fun TunerViewModel.keepWatching() {
    stillWatchingAsk = false
    onUserActivity()
}

internal fun TunerViewModel.stopWatchingNow() {
    stillWatchingAsk = false
    stopPlayback()
    goHome()
}

internal fun TunerViewModel.cycleStillWatching() {
    val steps = listOf(0, 2, 3, 4, 6)
    stillWatchingHours = steps[(steps.indexOf(stillWatchingHours).coerceAtLeast(0) + 1) % steps.size]
    store.stillWatchingHours = stillWatchingHours
}

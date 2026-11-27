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

internal fun TunerViewModel.startPhoneSetup() {
    setupOpen = true
    updateServer()
    val server = phoneServer
    phoneUrl = if (server != null && phoneBase != null) "$phoneBase/?t=${server.openSetup()}" else null
}

internal fun TunerViewModel.stopPhoneSetup() {
    setupOpen = false
    phoneServer?.closeSetup()
    phoneUrl = null
    updateServer()
}

/** Runs the server while the setup screen is open, or while the remote is on and Tuner is open. */
internal fun TunerViewModel.updateServer() {
    val wanted = setupOpen || shareMode == "send" || (remoteEnabled && inForeground)
    if (wanted && phoneServer == null) {
        val server = PhoneSetupServer(store, { newSourceId() }, this, app.cacheDir) { event ->
            when (event) {
                is PhoneSetupServer.Event.SourceAdded -> addSourceFromPhone(event.source)
                PhoneSetupServer.Event.SettingsChanged -> {
                    webhookUrl = store.webhookUrl
                    showNotice("Home Assistant webhook saved")
                }
                PhoneSetupServer.Event.Restored -> afterRestore()
                is PhoneSetupServer.Event.VpnAdded -> {
                    val err = vpn.add(event.name, event.text)
                    showNotice(err ?: "🔒 VPN server added – Settings › VPN")
                }
                is PhoneSetupServer.Event.ProfilePhoto -> setProfilePhoto(event.profileId, event.jpeg)
            }
        }
        phoneBase = runCatching { server.start() }.getOrNull()
        phoneServer = server
    } else if (!wanted && phoneServer != null) {
        phoneServer?.stop()
        phoneServer = null
        phoneBase = null
    }
    phoneServer?.remoteEnabled = remoteEnabled
    phoneServer?.shareCode = if (shareMode == "send") shareCode else null
    remoteUrl = if (remoteEnabled && phoneBase != null) "$phoneBase/remote?t=${store.remoteToken}" else null
}

internal fun TunerViewModel.toggleRemote() {
    remoteEnabled = !remoteEnabled
    store.remoteEnabled = remoteEnabled
    updateServer()
    showNotice(if (remoteEnabled) "Phone remote on – scan the code in Settings" else "Phone remote off")
}

// RemoteHandler (called on the main thread by the server)

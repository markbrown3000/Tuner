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

internal fun TunerViewModel.updateSearch(query: String) {
    searchQuery = query
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
        delay(250)
        val q = query.trim()
        if (q.length < 2) {
            searchResults = SearchResults(emptyList(), emptyList())
            searchPlex = emptyList()
            searchYouTube = emptyList()
            return@launch
        }
        searchYouTubeJob?.cancel()
        searchYouTube = emptyList()
        if (youTubeAllowed && !youTubeApprovedOnly && youtube.canSearch) {
            searchYouTubeJob = viewModelScope.launch {
                delay(500) // YouTube searches cost quota: only once typing has paused
                searchYouTube = youtube.quickSearch(q, activeProfile.kids)
            }
        }
        // Plex is searched alongside (it's on the network, so its results can arrive a moment later).
        searchPlexJob?.cancel()
        searchPlex = emptyList()
        if (plexLinked && (!activeProfile.kids || kidsHavePlex)) {
            searchPlexJob = viewModelScope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching { Plex(store.plexClientId).search(plexFeature.servers, q) }.getOrDefault(emptyList())
                }
                searchPlex = if (activeProfile.kids) found.filter { plexFeature.isKidsEntry(it) } else found.take(40)
            }
        }
        val filmPool = if (hasVod) movies.orEmpty() else emptyList()
        val seriesPool = if (hasVod) series.orEmpty() else emptyList()
        val pool = channels.filter { isAllowed(it) }
        val ids = activeSources().map { it.id }
        searchResults = withContext(Dispatchers.IO) {
            val needle = q.lowercase()
            val matchedChannels = pool.filter { it.name.lowercase().contains(needle) }.take(50)
            val byId = pool.associateBy { it.id }
            val t = System.currentTimeMillis()
            val progs = db.searchProgrammes(ids, q, t - 7 * 86_400_000L, 600)
                .mapNotNull { (id, p) -> byId[id]?.let { it to p } }
                .filter { (c, p) -> p.stop > t || Catchup.isAvailable(c, p, t) }
                .distinctBy { (c, p) -> c.id + "@" + p.start }
                .take(200)
            val words = needle.split(Regex("\\s+")).filter { it.isNotBlank() }
            fun hit(name: String) = name.lowercase().let { n -> words.all { it in n } }
            // Exact matches first, then near misses (small typos).
            fun <T> find(pool: List<T>, name: (T) -> String): List<T> {
                val exact = pool.filter { hit(name(it)) }
                if (exact.size >= 40) return exact.take(40)
                val near = pool.asSequence().filter { !hit(name(it)) }
                    .map { it to com.titanejstudios.tuner.data.Fuzzy.score(q, name(it)) }.filter { it.second > 0 }
                    .sortedByDescending { it.second }.map { it.first }.take(40 - exact.size).toList()
                return exact + near
            }
            val films = find(filmPool) { it.name }
            val shows = find(seriesPool) { it.name }
            val chans = if (matchedChannels.size >= 10) matchedChannels
            else matchedChannels + pool.filter { it !in matchedChannels && com.titanejstudios.tuner.data.Fuzzy.matches(q, it.name) }.take(20)
            SearchResults(chans, progs, films, shows)
        }
        // An actor's name: their films you've got ("Tom Hanks").
        val key = tmdbKey
        if (key != null && filmPool.isNotEmpty() && q.contains(' ') && q.length >= 5) {
            val (actor, theirs) = withContext(Dispatchers.IO) {
                val person = Tmdb.findPerson(key, q) ?: return@withContext null to emptyList<VodItem>()
                fun norm(s: String) = FilmName.parse(s).first.lowercase().filter { it.isLetterOrDigit() }
                val by = filmPool.groupBy { norm(it.name) }
                person.second to runCatching { Tmdb.personFilms(key, person.first) }.getOrDefault(emptyList()).mapNotNull { by[norm(it.title)]?.firstOrNull() }
            }
            if (actor != null && theirs.isNotEmpty() && searchQuery.trim() == q) {
                val r = searchResults
                searchResults = SearchResults(r.channels, r.programmes, (theirs + r.films).distinctBy { it.id }.take(50), r.seriesFound, actor)
            }
        }
    }
}

internal fun TunerViewModel.openEpgMapping(channel: Channel) {
    mappingChannel = channel
    epgCandidates = null
    navigate(Screen.EpgMap)
    viewModelScope.launch {
        epgCandidates = withContext(Dispatchers.IO) { db.xmltvChannels(channel.sourceId).distinctBy { it.id } }
    }
}

internal fun TunerViewModel.setEpgOverride(channel: Channel, xmltvId: String?) {
    val updated = epgOverrides.toMutableMap()
    if (xmltvId == null) updated.remove(channel.id) else updated[channel.id] = xmltvId
    epgOverrides = updated
    store.epgOverrides = updated
    back()
    showNotice(if (xmltvId == null) "Back to automatic matching – updating the guide…" else "Guide match saved – updating the guide…")
    sources.firstOrNull { it.id == channel.sourceId }?.let { refresh(listOf(it)) }
}

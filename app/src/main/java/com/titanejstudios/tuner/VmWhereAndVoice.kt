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
import com.titanejstudios.tuner.TunerViewModel.LibraryTitle
import com.titanejstudios.tuner.TunerViewModel.WhereOption
import com.titanejstudios.tuner.TunerViewModel.WhereTo

// Part of TunerViewModel, split out by subject (functions only; its state stays in the view model).

internal fun TunerViewModel.closeWhereTo() {
    whereJob?.cancel()
    whereTo = null
}

internal fun TunerViewModel.whereKey(name: String): String =
    FilmName.parse(name).first.lowercase().replace(Regex("^(the|a|an) "), "").filter { it.isLetterOrDigit() }

/** Everywhere a film or series can be watched: Plex, your provider, recordings, catch-up and the TV guide. */
internal fun TunerViewModel.whereToWatch(query: String) {
    val q = FilmName.parse(query.trim()).first.trim()
    if (q.length < 2) return
    whereJob?.cancel()
    whereTo = WhereTo(q, null)
    if (hasVod) {
        if (movies == null) loadMovies()
        if (series == null) loadSeries()
    }
    val ids = activeSources().map { it.id }
    val byId = channels.filter { isAllowed(it) }.associateBy { it.id }
    val key = whereKey(q)
    whereJob = viewModelScope.launch {
        if (hasVod) withTimeoutOrNull(20_000) { while (movies == null || series == null) delay(300) }
        val filmPool = if (hasVod) movies.orEmpty() else emptyList()
        val showPool = if (hasVod) series.orEmpty() else emptyList()
        val t = System.currentTimeMillis()
        val found = runCatching {
            withContext(Dispatchers.IO) {
                val f = filmPool.filter { whereKey(it.name) == key }.take(3)
                val s = showPool.filter { whereKey(it.name) == key }.take(3)
                val progs = db.searchProgrammes(ids, q, t - 7 * 86_400_000L, 400)
                    .mapNotNull { (id, p) -> byId[id]?.let { it to p } }
                    .filter { (_, p) -> whereKey(p.title) == key || whereKey(p.title).startsWith(key) }
                    .distinctBy { (c, p) -> c.id + "@" + p.start }
                Triple(f, s, progs)
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        val plex = if (plexLinked && (!activeProfile.kids || kidsHavePlex)) withContext(Dispatchers.IO) {
            runCatching { Plex(store.plexClientId).search(plexFeature.servers, q) }.getOrDefault(emptyList())
        }.filter { (it.type == "movie" || it.type == "show") && whereKey(it.title) == key }
            .let { l -> if (activeProfile.kids) l.filter { plexFeature.isKidsEntry(it) } else l }
        else emptyList()
        val out = ArrayList<WhereOption>()
        plex.forEach { e ->
            val what = if (e.type == "show") "series" else "film"
            out += WhereOption("▶ Plex – ${e.library ?: e.server.name}", listOfNotNull(what, e.year, e.rating).joinToString("  ·  ")) {
                closeWhereTo()
                if (e.type == "show") openPlexShow(e) else playPlexEntry(e)
            }
        }
        found?.first?.forEach { f ->
            out += WhereOption("▶ Your provider's films", listOfNotNull(FilmName.parse(f.name).second, f.category).joinToString("  ·  ")) {
                closeWhereTo()
                playMovie(f, fromStart = (resume[f.id]?.positionMs ?: 0L) == 0L)
            }
        }
        found?.second?.forEach { s ->
            out += WhereOption("▶ Your provider's series", s.category) {
                closeWhereTo()
                openSeriesDetail(s)
            }
        }
        recordings.filter { it.status == RecStatus.DONE && whereKey(it.title) == key }.take(3).forEach { r ->
            out += WhereOption("⏺ Your recording", "${dayLabelOf(r.start)} ${hhmmOf(r.start)}") {
                closeWhereTo()
                playRecording(r)
            }
        }
        val progs = found?.third.orEmpty()
        progs.filter { (_, p) -> p.start <= t && p.stop > t }.take(3).forEach { (c, _) ->
            out += WhereOption("📺 On now – ${c.name}", "OK to watch") {
                closeWhereTo()
                play(c, listOf(c))
            }
        }
        progs.filter { (c, p) -> p.stop <= t && Catchup.isAvailable(c, p, t) }.sortedByDescending { it.second.start }.take(3).forEach { (c, p) ->
            out += WhereOption("↺ Catch-up – ${c.name}", "${dayLabelOf(p.start)} ${hhmmOf(p.start)}") {
                closeWhereTo()
                playCatchup(c, p, listOf(c))
            }
        }
        progs.filter { (_, p) -> p.start > t }.sortedBy { it.second.start }.take(4).forEach { (c, p) ->
            val set = recordingFor(c, p) != null
            out += WhereOption(
                (if (set) "⏺ Recording – " else "🗓 Coming up – ") + c.name,
                "${dayLabelOf(p.start)} ${hhmmOf(p.start)}" + if (set) "" else "  ·  OK to record",
            ) {
                closeWhereTo()
                if (!set) recordProgramme(c, p)
            }
        }
        if (whereTo?.title != q) return@launch
        whereTo = WhereTo(q, out)
    }
}

internal fun TunerViewModel.dayLabelOf(t: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = t
    return when (cal.get(java.util.Calendar.DAY_OF_YEAR) - today) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(java.util.Date(t))
    }
}

internal fun TunerViewModel.hhmmOf(t: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(t))

internal fun TunerViewModel.openMood(key: String) {
    if (activeProfile.kids && key != "family") return showNotice("Ask a grown-up – kids have their own films")
    val mood = com.titanejstudios.tuner.data.Moods.byKey(key) ?: return
    if (!hasVod && !plexLinked) return showNotice("Mood picks need films from your provider or Plex")
    libraryMood = mood.key
    libraryShows = false
    library = null
    navigate(Screen.Library)
}

internal fun TunerViewModel.clearMood() {
    libraryMood = null
    library = null
    loadLibrary()
}

/** Whether a title fits the mood being shown (its category, Plex's genres, or TMDB's when looked up). */
internal fun TunerViewModel.fitsMood(t: LibraryTitle, mood: com.titanejstudios.tuner.data.Moods.Mood): Boolean {
    val looked = filmLookups[t.title.trim()]
    val labels = listOfNotNull(t.film?.category) + t.plex?.genres.orEmpty() + looked?.genres.orEmpty()
    val minutes = t.plex?.durationMs?.takeIf { it > 0 }?.let { (it / 60_000).toInt() } ?: looked?.runtimeMinutes
    return com.titanejstudios.tuner.data.Moods.fits(mood, labels, minutes)
}

/** "Record EastEnders", "next episode", "what's on BBC One later"… true if it was one. */
internal fun TunerViewModel.runVoiceCommand(text: String): Boolean {
    val cmd = com.titanejstudios.tuner.data.VoiceCommand.parse(text) ?: return false
    AppLog.info("Voice command: $text")
    when (cmd) {
        is com.titanejstudios.tuner.data.VoiceCommand.Record -> voiceFindProgramme(cmd.what) { c, p ->
            if (recordingFor(c, p) != null) showNotice("⏺ ${p.title} is already set to record")
            else {
                recordProgramme(c, p)
                showNotice("⏺ Recording ${p.title} – ${c.name}, ${dayLabelOf(p.start)} ${hhmmOf(p.start)}")
            }
        }
        is com.titanejstudios.tuner.data.VoiceCommand.Remind -> voiceFindProgramme(cmd.what, upcomingOnly = true) { c, p ->
            if (hasReminder(c, p)) showNotice("⏰ There's already a reminder for ${p.title}")
            else toggleReminder(c, p)
        }
        com.titanejstudios.tuner.data.VoiceCommand.NextEpisode -> when {
            nextEpisode != null -> playNextEpisodeNow()
            mode is PlayMode.Vod -> showNotice("That's the last episode")
            else -> carryOnWatching()
        }
        com.titanejstudios.tuner.data.VoiceCommand.CarryOn -> carryOnWatching()
        com.titanejstudios.tuner.data.VoiceCommand.Stop -> {
            stopPlayback()
            resetTo(Screen.Home)
        }
        com.titanejstudios.tuner.data.VoiceCommand.Surprise -> {
            resetTo(Screen.Home)
            surpriseMe()
        }
        is com.titanejstudios.tuner.data.VoiceCommand.WhatsOn -> {
            val all = channelsFor(ALL)
            val ch = VoiceMatch.find(all, cmd.channel) ?: VoiceMatch.find(all, cmd.channel, strict = false)
            if (ch == null || !isAllowed(ch)) showNotice("🎤 Couldn't find a channel called \"${cmd.channel}\"")
            else showChannelSchedule(ch, cmd.later)
        }
        is com.titanejstudios.tuner.data.VoiceCommand.WhereToWatch -> whereToWatch(cmd.what)
        is com.titanejstudios.tuner.data.VoiceCommand.Mood -> openMood(cmd.key)
        is com.titanejstudios.tuner.data.VoiceCommand.Go -> when (cmd.place) {
            "home" -> resetTo(Screen.Home)
            "guide" -> navigate(Screen.Guide)
            "channels" -> navigate(Screen.Channels)
            "films" -> if (hasVod) navigate(Screen.Movies) else showNotice("No films from your provider")
            "series" -> if (hasVod) navigate(Screen.Series) else showNotice("No series from your provider")
            "recordings" -> navigate(Screen.Recordings)
            "settings" -> navigate(Screen.Settings)
            "sport" -> navigate(Screen.Sports)
            "youtube" -> if (youTubeAllowed) navigate(Screen.YouTube) else showNotice("YouTube is off for this profile")
            "plex" -> if (plexLinked) openPlexLibrary() else showNotice("Plex isn't set up")
            "catchup" -> navigate(Screen.Catchup)
            "whatson" -> openWhatsOn(whatsOnGenre)
            else -> return false
        }
    }
    return true
}

/** The most recent thing in Carry on watching. */
internal fun TunerViewModel.carryOnWatching() {
    val r = myResume.values.maxByOrNull { it.updatedAt }
    if (r == null) showNotice("Nothing to carry on with yet")
    else {
        showNotice("▶ ${r.title}" + (r.subtitle?.let { " – $it" } ?: ""))
        playResume(r)
    }
}

/** The next showing of [what] in the TV guide (on now counts unless [upcomingOnly]). */
internal fun TunerViewModel.voiceFindProgramme(what: String, upcomingOnly: Boolean = false, then: (Channel, Programme) -> Unit) {
    val ids = activeSources().map { it.id }
    val byId = channels.filter { isAllowed(it) }.associateBy { it.id }
    val key = whereKey(what)
    viewModelScope.launch {
        val t = System.currentTimeMillis()
        val hit = runCatching {
            withContext(Dispatchers.IO) {
                db.searchProgrammes(ids, what, t, 300)
                    .mapNotNull { (id, p) -> byId[id]?.let { it to p } }
                    .filter { (_, p) -> (if (upcomingOnly) p.start > t else p.stop > t) && whereKey(p.title).let { it == key || it.startsWith(key) } }
                    .minByOrNull { (_, p) -> p.start }
            }
        }.getOrNull()
        if (hit == null) showNotice("🎤 Couldn't find \"$what\" in the TV guide") else then(hit.first, hit.second)
    }
}

internal fun TunerViewModel.showChannelSchedule(c: Channel, later: Boolean) {
    val t = System.currentTimeMillis()
    // "Later" / "tonight": from 6pm if it's still daytime, otherwise from now.
    val from = if (later) {
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 18); set(java.util.Calendar.MINUTE, 0) }
        maxOf(t, cal.timeInMillis)
    } else t
    val list = programmesFor(c.id).filter { it.stop > from }.take(8)
    if (list.isEmpty()) return showNotice("No guide for ${c.name} " + if (later) "later" else "right now")
    channelSchedule = c to list
}

internal fun TunerViewModel.closeChannelSchedule() {
    channelSchedule = null
}

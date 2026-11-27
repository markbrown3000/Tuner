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

internal fun TunerViewModel.storageOptions(): List<StorageOption> {
    val dirs = app.getExternalFilesDirs("Recordings").filterNotNull() + File(app.filesDir, "Recordings")
    return dirs.mapIndexed { i, d ->
        d.mkdirs()
        val free = runCatching { StatFs(d.path).availableBytes }.getOrDefault(0L)
        val label = when {
            d.path.startsWith(app.filesDir.path) -> "Internal storage (app only)"
            i == 0 -> "Internal storage"
            else -> "External / USB storage ${i}"
        }
        StorageOption(d.path, label, free)
    }.distinctBy { it.path }
}

internal fun TunerViewModel.setRecordingDir(path: String) {
    store.recordingDir = path
    showNotice("New recordings will be saved there")
}

internal fun TunerViewModel.recordingFor(channel: Channel, p: Programme): Recording? =
    recordings.firstOrNull {
        it.channelId == channel.id && it.start <= p.start && it.stop >= p.stop &&
            (it.status == RecStatus.SCHEDULED || it.status == RecStatus.RECORDING)
    }

internal fun TunerViewModel.safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').take(60).ifEmpty { "Recording" }

internal fun TunerViewModel.recordProgramme(channel: Channel, p: Programme) {
    if (recordingFor(channel, p) != null) {
        showNotice("Already set to record")
        return
    }
    val start = maxOf(p.start - 60_000L, System.currentTimeMillis())
    // Sport often overruns (extra time, rain delays) – keep recording for another half hour.
    val extra = if (Sports.classify(p) != null) SportFollow.SPORT_EXTRA_MS else 0L
    createRecording(channel, p.title, start, p.stop + 120_000L + extra, p.category)
}

internal fun TunerViewModel.recordNow(channel: Channel, minutes: Int) {
    val t = System.currentTimeMillis()
    val p = nowNext(channel.id, t).first
    createRecording(channel, p?.title ?: channel.name, t, t + minutes * 60_000L, p?.category)
}

internal fun TunerViewModel.createRecording(channel: Channel, title: String, start: Long, stop: Long, category: String?) {
    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(start))
    val id = "r" + System.currentTimeMillis().toString(36)
    val rec = Recording(
        id = id,
        channelId = channel.id,
        channelName = channel.name,
        title = title,
        start = start,
        stop = stop,
        url = channel.url,
        path = File(recordingDir, "${safeName(title)}_$stamp.ts").path,
        status = RecStatus.SCHEDULED,
        category = category,
    )
    store.updateRecordings { it + rec }
    recordings = store.recordings
    RecordingScheduler.schedule(app, rec)
    showNotice(
        if (start <= System.currentTimeMillis() + 90_000L) "Recording ${rec.title} now"
        else "Will record ${rec.title} at ${hhmm(start + 60_000L)}"
    )
}

internal fun TunerViewModel.stopRecording(rec: Recording) {
    when (rec.status) {
        RecStatus.SCHEDULED -> {
            RecordingScheduler.cancelAlarm(app, rec.id)
            store.updateRecordings { list -> list.map { if (it.id == rec.id) it.copy(status = RecStatus.CANCELLED) else it } }
        }
        RecStatus.RECORDING -> RecordingScheduler.stop(app, rec.id)
    }
    recordings = store.recordings
}

internal fun TunerViewModel.deleteRecording(rec: Recording) {
    if (rec.status == RecStatus.RECORDING) RecordingScheduler.stop(app, rec.id)
    if (rec.status == RecStatus.SCHEDULED) RecordingScheduler.cancelAlarm(app, rec.id)
    runCatching { File(rec.path).delete() }
    store.updateRecordings { list -> list.filter { it.id != rec.id } }
    recordings = store.recordings
}

internal fun TunerViewModel.refreshRecordings() {
    recordings = store.recordings
}

internal fun TunerViewModel.loadRecordingSettings() {
    keptRecordings = store.keptRecordings
    autoDeleteDays = store.recordingAutoDelete
    nasUrl = store.nasUrl
    nasKeepLocal = store.nasKeepLocal
}

internal fun TunerViewModel.toggleKeep(rec: Recording) {
    keptRecordings = if (rec.id in keptRecordings) keptRecordings - rec.id else keptRecordings + rec.id
    store.keptRecordings = keptRecordings
    showNotice(if (rec.id in keptRecordings) "\"${rec.title}\" will be kept" else "\"${rec.title}\" can be tidied away")
}

internal fun TunerViewModel.cycleAutoDelete() {
    val steps = listOf(0, 7, 14, 30, 60)
    autoDeleteDays = steps[(steps.indexOf(autoDeleteDays).coerceAtLeast(0) + 1) % steps.size]
    store.recordingAutoDelete = autoDeleteDays
    viewModelScope.launch {
        val n = withContext(Dispatchers.IO) { com.titanejstudios.tuner.recording.RecordingTidy.cleanUp(store) }
        recordings = store.recordings
        if (n > 0) showNotice("Deleted $n old recording${if (n == 1) "" else "s"}")
    }
}

internal fun TunerViewModel.setNas(url: String?, user: String?, password: String?) {
    store.nasUrl = url?.takeIf { it.isNotBlank() }?.let { com.titanejstudios.tuner.recording.RecordingTidy.folderUrl(it) }
    if (user != null) store.nasUser = user.takeIf { it.isNotBlank() }
    if (password != null) store.nasPassword = password.takeIf { it.isNotEmpty() }
    nasUrl = store.nasUrl
    testNas()
}

internal fun TunerViewModel.testNas() {
    if (nasUrl == null) {
        nasStatus = null
        return
    }
    nasStatus = "Checking…"
    viewModelScope.launch {
        val problem = withContext(Dispatchers.IO) { com.titanejstudios.tuner.recording.RecordingTidy.test(store) }
        nasStatus = if (problem == null) "✓ Connected – finished recordings will be copied there" else "Couldn't reach it: $problem"
    }
}

internal fun TunerViewModel.toggleNasKeepLocal() {
    nasKeepLocal = !nasKeepLocal
    store.nasKeepLocal = nasKeepLocal
}

internal fun TunerViewModel.playRecording(rec: Recording) {
    if (rec.path.startsWith("smb://")) {
        showNotice("This recording was moved to your network drive – watch it from Plex or another app")
        return
    }
    val f = File(rec.path)
    if (!f.exists() || f.length() == 0L) {
        showNotice("Nothing recorded yet – try again in a few seconds")
        return
    }
    mode = PlayMode.RecordingPlay(rec)
    val stillRecording = { store.recordings.any { it.id == rec.id && it.status == RecStatus.RECORDING } }
    playback.play(
        Uri.fromFile(f).toString(),
        live = false,
        metadata = MediaMetadata.Builder().setTitle(rec.title).setArtist(rec.channelName).build(),
        chase = stillRecording,
    )
    navigate(Screen.Player)
}

internal fun TunerViewModel.backupFile(): File = File(recordingDir).parentFile?.let { File(it, "tuner-backup.json") }
    ?: File(app.filesDir, "tuner-backup.json")

internal fun TunerViewModel.backupToStorage() {
    viewModelScope.launch {
        val f = backupFile()
        val ok = withContext(Dispatchers.IO) { runCatching { f.writeText(store.exportBackup()) }.isSuccess }
        showNotice(if (ok) "Backup saved to ${f.path}" else "Couldn't save the backup")
    }
}

internal fun TunerViewModel.restoreFromStorage() {
    viewModelScope.launch {
        val f = backupFile()
        val result = withContext(Dispatchers.IO) {
            runCatching { store.importBackup(f.readText()) }
        }
        if (result.isSuccess) afterRestore() else showNotice("No backup found at ${f.path}")
    }
}

internal fun TunerViewModel.afterRestore() {
    stopPlayback()
    loadSettingsFromStore()
    updates.reload()
    link.reload()
    cloud.reload()
    if (!SafeMode.active) {
        football.reload()
        plexFeature.reload()
    }
    startupHandled = true
    resetTo(Screen.Home)
    viewModelScope.launch {
        loadActive()
        refresh(activeSources())
    }
    RecordingScheduler.rescheduleAll(app)
    runCatching { ReminderAlarms.rescheduleAll(app) }
    showNotice("Settings restored")
}

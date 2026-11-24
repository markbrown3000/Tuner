package com.titanejstudios.tuner.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import java.io.File
import java.util.Locale

/**
 * The main player, shared by the full-screen view and the small previews. Handles HLS fallback,
 * silent reconnects, timeshift to storage, a standby player for instant channel changes,
 * audio/subtitle tracks, stream stats and the system media session.
 */
@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context) {

    class TrackOption(
        val label: String,
        val group: Tracks.Group?,
        val index: Int,
        val selected: Boolean,
        /** False when this TV can't decode it (e.g. DTS or TrueHD with Audio passthrough off). */
        val supported: Boolean = true,
    )

    private val primary: ExoPlayer = PlayerFactory.build(context, bufferBytes = 128 * 1024 * 1024, longPause = true)

    /** The player currently on screen (changes when a standby channel is swapped in). */
    var player: ExoPlayer by mutableStateOf(primary)
        private set

    var isPlaying by mutableStateOf(false); private set
    var isPaused by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var reconnecting by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var timeshiftActive by mutableStateOf(false); private set
    /** Frames per second of the current video (0 until known) – used to match the TV's refresh rate. */
    var videoFrameRate by mutableStateOf(0f); private set

    private val nightMode = NightMode()
    /** Evens out loud adverts and quiet speech. */
    var nightModeOn = false
        set(value) {
            field = value
            nightMode.apply(player.audioSessionId, value, speechOn)
        }
    /** Clearer speech: voices lifted over music and effects. */
    var speechOn = false
        set(value) {
            field = value
            nightMode.apply(player.audioSessionId, nightModeOn, value)
        }
    val nightModeSupported: Boolean get() = nightMode.supported

    private val boost = LoudnessBoost()
    /** Volume level for what's on screen, in dB (see VolumeLevels). */
    var volumeDb = 0
        set(value) {
            field = value
            applyVolume()
        }

    private fun applyVolume() {
        val p = player
        p.volume = VolumeLevels.linear(volumeDb)
        boost.apply(p.audioSessionId, VolumeLevels.boostMb(volumeDb))
    }

    /** Picture quality on streams with several: "auto", "best" or "sd" (576p or less, saves data). */
    var quality = "auto"
        set(value) {
            field = value
            listOfNotNull(primary, standby).forEach { applyQuality(it) }
        }

    private fun applyQuality(p: ExoPlayer) {
        val b = p.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
        when (quality) {
            "best" -> b.setForceHighestSupportedBitrate(true)
            "sd" -> b.setMaxVideoSize(1024, 576)
        }
        p.trackSelectionParameters = b.build()
    }

    /** Subtitles downloaded for the film/episode on screen (OpenSubtitles). */
    private var extraSubtitle: MediaItem.SubtitleConfiguration? = null

    /** Called when a film/episode/catch-up/recording finishes. */
    var onEnded: (() -> Unit)? = null
    /** Told when Tuner quietly fixes a stream (reconnect, backup link) – shown as a short notice. */
    var onRecovery: ((String) -> Unit)? = null
    /** Told (once per channel visit) when a stream stalls, reconnects or needs a backup link. */
    var onStreamTrouble: ((String) -> Unit)? = null
    /** Extra milliseconds to buffer before a channel starts (bigger for channels that keep stalling). */
    var cushionFor: (String) -> Long = { 0L }
    /** True once this channel has had a problem since it was tuned. */
    var hadTrouble = false
        private set

    /** Where timeshift files go (null = timeshift off, plain pause). */
    var timeshiftDir: () -> File? = { null }
    /** Keep the next channel ready in a muted standby player (uses a 2nd provider connection). */
    var fastSwitch = false
        set(value) {
            field = value
            if (!value) releaseStandby()
        }

    private var url: String? = null
    private var live = true
    private var useHls = false
    private var triedHls = false
    private var retries = 0
    private var metadata: MediaMetadata? = null
    private var chase: (() -> Boolean)? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Backup links for the same channel, tried in turn when the main one keeps failing. */
    private var alternates: List<String> = emptyList()
    private var altIndex = 0
    private val stallHandler = Handler(Looper.getMainLooper())
    private val stallCheck = Runnable { onStalled() }

    // Start-up cushion: hold a flaky channel paused until a few seconds are buffered.
    private var streamKey: String? = null
    private var priming = false
    private var primeTargetMs = 0L
    private var primeStarted = 0L
    private val primeHandler = Handler(Looper.getMainLooper())
    private val primeCheck = object : Runnable {
        override fun run() {
            if (!priming) return
            val p = player
            val enough = p.totalBufferedDuration >= primeTargetMs
            val waitedTooLong = SystemClock.uptimeMillis() - primeStarted > primeTargetMs + 8_000
            if (enough || waitedTooLong || p.playbackState == Player.STATE_ENDED || p.playerError != null) {
                endPriming()
                p.play()
            } else {
                primeHandler.postDelayed(this, 200)
            }
        }
    }

    private var standby: ExoPlayer? = null
    private var standbyUrl: String? = null

    private var ts: TimeshiftSession? = null
    private var tsSegmentStartWall = 0L
    private var pausedAtWall = 0L

    private val session: MediaSession?
    private val compatTs = Store(context).streamCompatibility && !com.titanejstudios.tuner.data.SafeMode.active
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null

    val isActive: Boolean get() = url != null
    val currentUrl: String? get() = url

    init {
        attach(primary)
        // A media session lets "Hey Google, pause" and the remote's media keys control Tuner.
        session = try {
            MediaSession.Builder(context, primary).setId("tuner-main").build()
        } catch (e: Exception) {
            null
        }
    }

    /** Listens to a player; events from the standby player are ignored until it's swapped in. */
    private fun attach(p: ExoPlayer) {
        // Remember which decoders are in use – shown in Stream info to help diagnose playback problems.
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (p === player) videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (p === player) audioDecoder = decoderName
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                if (p === player && format.frameRate > 0f) videoFrameRate = format.frameRate
            }
        })
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (p === player) {
                    nightMode.apply(audioSessionId, nightModeOn, speechOn)
                    boost.apply(audioSessionId, VolumeLevels.boostMb(volumeDb))
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (p !== player) return
                isPlaying = playing
                if (playing) {
                    error = null
                    reconnecting = false
                    retries = 0
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (p !== player) return
                isPaused = !playWhenReady && !priming
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (p !== player) {
                    if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) standbyUrl = null
                    return
                }
                isBuffering = state == Player.STATE_BUFFERING || priming
                stallHandler.removeCallbacks(stallCheck)
                // A live stream stuck "buffering" for 12s usually means the server has stalled.
                if (isBuffering && live && ts == null && url != null) stallHandler.postDelayed(stallCheck, STALL_MS)
                if (state == Player.STATE_ENDED && url != null) {
                    val session = ts
                    when {
                        session != null -> if (session.recording) playTimeshiftFrom(playedWall) else goLive()
                        live -> scheduleReconnect()
                        chase?.invoke() == true -> handler.postDelayed({ if (url != null) load(player.currentPosition) }, 1_500)
                        else -> onEnded?.invoke()
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                if (p !== player) {
                    standbyUrl = null
                    return
                }
                handleError(e)
            }
        })
    }

    // ---------------- Starting & stopping ----------------

    fun play(
        url: String,
        live: Boolean,
        startPositionMs: Long = 0L,
        metadata: MediaMetadata? = null,
        chase: (() -> Boolean)? = null,
        alternates: List<String> = emptyList(),
        /** Channel id, so flaky channels get a bigger start-up cushion. */
        key: String? = null,
    ) {
        handler.removeCallbacksAndMessages(null)
        endPriming()
        streamKey = key
        hadTrouble = false
        extraSubtitle = null
        stallHandler.removeCallbacks(stallCheck)
        this.alternates = alternates.filter { it != url }.distinct()
        altIndex = 0
        stopTimeshift()
        this.url = url
        this.live = live
        this.metadata = metadata
        this.chase = chase
        useHls = false
        triedHls = false
        retries = 0
        error = null
        reconnecting = false
        if (live && standbyUrl == url && standby != null) {
            swapToStandby()
            return
        }
        load(startPositionMs)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        endPriming()
        streamKey = null
        extraSubtitle = null
        stallHandler.removeCallbacks(stallCheck)
        stopTimeshift()
        releaseStandby()
        url = null
        reconnecting = false
        error = null
        videoFrameRate = 0f
        player.stop()
        player.clearMediaItems()
    }

    private fun load(startPositionMs: Long) {
        val u = url ?: return
        val b = MediaItem.Builder().setUri(u)
        if (useHls || u.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        metadata?.let { b.setMediaMetadata(it) }
        extraSubtitle?.let { b.setSubtitleConfigurations(listOf(it)) }
        if (startPositionMs > 0) player.setMediaItem(b.build(), startPositionMs) else player.setMediaItem(b.build())
        val cushion = if (live && ts == null) streamKey?.let { cushionFor(it) } ?: 0L else 0L
        if (cushion > 0L) {
            // Load paused, then start once [cushion] ms of video are in hand (see primeCheck).
            priming = true
            primeTargetMs = cushion
            primeStarted = SystemClock.uptimeMillis()
            isBuffering = true
            player.playWhenReady = false
            player.prepare()
            primeHandler.removeCallbacks(primeCheck)
            primeHandler.postDelayed(primeCheck, 200)
        } else {
            endPriming()
            player.prepare()
            player.play()
        }
    }

    private fun endPriming() {
        if (!priming) return
        priming = false
        primeHandler.removeCallbacks(primeCheck)
        isBuffering = player.playbackState == Player.STATE_BUFFERING
    }

    /** Remember (once per channel visit) that this stream misbehaved. */
    private fun reportTrouble() {
        if (hadTrouble) return
        hadTrouble = true
        streamKey?.let { k -> onStreamTrouble?.invoke(k) }
    }

    /** The start-up cushion used for the channel on screen (0 = starts straight away). */
    val cushionMs: Long get() = if (live) streamKey?.let { cushionFor(it) } ?: 0L else 0L

    /** App went to the background: drop live streams and timeshift, pause on-demand ones. */
    fun suspend() {
        handler.removeCallbacksAndMessages(null)
        endPriming()
        stallHandler.removeCallbacks(stallCheck)
        releaseStandby()
        if (url == null) return
        if (live) {
            stopTimeshift()
            player.stop()
        } else player.pause()
    }

    /** App came back: reconnect a live stream at the live edge. */
    fun resumeAfterSuspend() {
        if (url != null && live && player.playbackState == Player.STATE_IDLE) {
            retries = 0
            player.playWhenReady = true
            load(0L)
        }
    }

    // ---------------- Fast channel switching ----------------

    /** Starts the likely next channel muted and without video, so switching to it is instant. */
    fun prepareStandby(nextUrl: String?) {
        if (!fastSwitch || nextUrl == null || ts != null || !live) {
            releaseStandby()
            return
        }
        if (standbyUrl == nextUrl) return
        val sb = standby ?: PlayerFactory.build(context, bufferBytes = 32 * 1024 * 1024, longPause = false).also {
            standby = it
            attach(it)
            if (seamlessFps) it.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            if (audioLang != null || subtitleLang != null) applyLanguages(it)
            applyQuality(it)
        }
        standbyUrl = nextUrl
        sb.volume = 0f
        sb.trackSelectionParameters = sb.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        sb.setMediaItem(PlayerFactory.item(nextUrl, false))
        sb.prepare()
        sb.play()
    }

    private fun swapToStandby() {
        val sb = standby ?: return load(0L)
        endPriming()
        val old = player
        sb.trackSelectionParameters = sb.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        player = sb
        sb.volume = VolumeLevels.linear(volumeDb)
        session?.player = sb
        nightMode.apply(sb.audioSessionId, nightModeOn, speechOn)
        boost.apply(sb.audioSessionId, VolumeLevels.boostMb(volumeDb))
        videoFrameRate = sb.videoFormat?.frameRate?.takeIf { it > 0f } ?: videoFrameRate
        standby = old
        standbyUrl = null
        old.volume = 0f
        old.stop()
        old.clearMediaItems()
        isPlaying = sb.isPlaying
        isPaused = !sb.playWhenReady
        sb.play()
        if (sb.playbackState == Player.STATE_IDLE || sb.playbackState == Player.STATE_ENDED) load(0L)
    }

    private fun releaseStandby() {
        standbyUrl = null
        standby?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    // ---------------- Errors & reconnecting ----------------

    private fun handleError(e: PlaybackException) {
        val u = url ?: return
        val code = e.errorCode
        if (ts != null) {
            goLive()
            return
        }
        when {
            code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
            }
            code in 3000..3999 && !triedHls && !u.contains(".m3u8", true) -> {
                triedHls = true
                useHls = true
                load(0L)
            }
            code in 1000..2999 -> scheduleReconnect()
            else -> {
                AppLog.warn("Can't play ${short(u)}: ${e.errorCodeName}")
                if (!tryAlternate()) error = "Can't play this (${e.errorCodeName})"
            }
        }
    }

    /** Live picture frozen on "buffering": reconnect, and after a few goes try a backup link. */
    private fun onStalled() {
        val u = url ?: return
        if (!live || ts != null || player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
        AppLog.info("Stream stalled, reconnecting: ${short(u)}")
        reportTrouble()
        if (retries >= 2 && tryAlternate()) return
        scheduleReconnect()
    }

    /** Switches to the next backup link for this channel. Returns false when there are none left. */
    private fun tryAlternate(): Boolean {
        if (altIndex >= alternates.size) return false
        val next = alternates[altIndex++]
        AppLog.info("Switching to backup link ${altIndex}/${alternates.size}: ${short(next)}")
        if (live) reportTrouble()
        url = next
        useHls = false
        triedHls = false
        retries = 0
        error = null
        reconnecting = true
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (url != null) load(0L) }, 300)
        onRecovery?.invoke("Trying a backup link for this channel")
        return true
    }

    private fun short(u: String) = u.substringAfter("://").substringBefore('?').let {
        // Hide Xtream username/password in the log.
        val parts = it.split('/')
        if (parts.size >= 4) parts.first() + "/…/" + parts.last() else it
    }

    private fun scheduleReconnect() {
        val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        if (retries == 3 && tryAlternate()) return
        if (retries >= delays.size) {
            reconnecting = false
            if (tryAlternate()) return
            AppLog.warn("Gave up reconnecting: ${url?.let { short(it) }}")
            error = "Lost connection to this stream"
            return
        }
        if (retries == 0) url?.let { AppLog.info("Reconnecting: ${short(it)}") }
        if (live) reportTrouble()
        reconnecting = true
        val delay = delays[retries]
        retries++
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (url != null) load(if (live) 0L else player.currentPosition)
        }, delay)
    }

    private companion object {
        const val STALL_MS = 12_000L
    }

    // ---------------- Timeshift ----------------

    /** Where in real time the picture is when watching timeshifted TV. */
    val playedWall: Long
        get() = if (ts != null && player.playbackState != Player.STATE_IDLE) tsSegmentStartWall + player.currentPosition
        else pausedAtWall

    /** How far behind live you are (0 when watching live). */
    fun behindLiveMs(): Long = if (ts == null) 0L else (System.currentTimeMillis() - playedWall).coerceAtLeast(0L)

    /** Pause live TV: the stream switches to recording into a file so nothing is missed. */
    private fun pauseLive() {
        endPriming()
        val u = url
        val dir = timeshiftDir()
        if (u == null || dir == null) {
            player.pause()
            return
        }
        val session = TimeshiftSession(u, File(dir, "timeshift.ts"))
        handler.removeCallbacksAndMessages(null)
        releaseStandby()
        player.playWhenReady = false
        // One provider connection: the player lets go of it and the recorder takes over.
        player.stop()
        if (!session.start()) {
            // No room on storage – fall back to a plain pause (reconnects on resume).
            return
        }
        ts = session
        timeshiftActive = true
        pausedAtWall = System.currentTimeMillis()
    }

    private fun playTimeshiftFrom(wall: Long) {
        val session = ts ?: return
        val offset = session.offsetFor(wall)
        tsSegmentStartWall = session.wallFor(offset)
        val factory = DataSource.Factory { GrowingFileDataSource(session.file, offset, { session.recording }) }
        val source = ProgressiveMediaSource.Factory(factory, PlayerFactory.extractors(compatTs))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(session.file)))
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    private fun stopTimeshift() {
        ts?.stop()
        ts = null
        timeshiftActive = false
    }

    // ---------------- Pause & seek ----------------

    val canSeek: Boolean get() = ts != null || player.isCurrentMediaItemSeekable
    val positionMs: Long get() = player.currentPosition
    val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun togglePause() {
        if (priming) {
            resume()
            return
        }
        if (live && url != null) {
            val session = ts
            when {
                session == null && player.playWhenReady -> pauseLive()
                session == null -> resume()
                player.playbackState == Player.STATE_IDLE -> playTimeshiftFrom(pausedAtWall)
                player.playWhenReady -> {
                    pausedAtWall = playedWall
                    player.pause()
                }
                else -> player.play()
            }
            return
        }
        if (player.playWhenReady) player.pause() else resume()
    }

    fun pause() {
        if (priming) {
            endPriming()
            player.play()
        }
        if (!isPaused) togglePause()
    }

    fun resume() {
        if (priming) {
            endPriming()
            player.play()
            return
        }
        if (ts != null) {
            if (player.playbackState == Player.STATE_IDLE) playTimeshiftFrom(pausedAtWall) else player.play()
            return
        }
        if (player.playbackState == Player.STATE_IDLE && url != null) {
            player.playWhenReady = true
            load(if (live) 0L else player.currentPosition)
        } else {
            player.play()
        }
    }

    /** Seeks: within timeshift, on-demand, catch-up and HLS live with a DVR window. */
    fun seekBy(deltaMs: Long): Boolean {
        val session = ts
        if (session != null) {
            val from = if (player.playbackState == Player.STATE_IDLE) pausedAtWall else playedWall
            val target = (from + deltaMs).coerceAtLeast(session.startWall)
            if (target >= session.newestWall - 3_000) {
                goLive()
            } else {
                val wasPlaying = player.playbackState == Player.STATE_IDLE || player.playWhenReady
                playTimeshiftFrom(target)
                if (!wasPlaying) {
                    player.pause()
                    pausedAtWall = target
                }
            }
            return true
        }
        if (!player.isCurrentMediaItemSeekable) return false
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val d = durationMs
        if (d > 0) target = target.coerceAtMost(d - 1_000)
        player.seekTo(target)
        return true
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0L))

    fun goLive() {
        if (ts != null) {
            stopTimeshift()
            retries = 0
            player.playWhenReady = true
            load(0L)
            return
        }
        player.seekToDefaultPosition()
        player.play()
    }

    // ---------------- Tracks ----------------

    /**
     * The audio or subtitle tracks in what's playing. With [includeUnsupported], tracks this TV
     * can't decode are listed too (so you can see they exist), marked as not supported.
     */
    fun tracks(type: Int, includeUnsupported: Boolean = false): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                val ok = g.isTrackSupported(i)
                if (!ok && !includeUnsupported) continue
                out += TrackOption(describe(g.getTrackFormat(i), out.size + 1, type), g, i, g.isTrackSelected(i), ok)
            }
        }
        return out
    }

    fun selectTrack(option: TrackOption, type: Int) {
        val g = option.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.index))
            .build()
    }

    fun subtitlesOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    val subtitlesDisabled: Boolean
        get() = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes

    private fun describe(f: Format, n: Int, type: Int): String {
        val language = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            Locale(code).displayLanguage.ifBlank { code }
        }
        val name = f.label ?: language ?: "Track $n"
        val extra = if (type == C.TRACK_TYPE_AUDIO) {
            listOfNotNull(codecName(f.sampleMimeType), channelLayout(f.channelCount)).joinToString(" ")
        } else ""
        return if (extra.isEmpty()) name else "$name  ($extra)"
    }

    // ---------------- Stream info ----------------

    fun streamInfo(): List<String> {
        val lines = ArrayList<String>()
        player.videoFormat?.let { f ->
            val parts = listOfNotNull(
                if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
                if (f.frameRate > 0f) String.format(Locale.US, "%.0f fps", f.frameRate) else null,
                codecName(f.sampleMimeType),
                bitrate(f),
            )
            if (parts.isNotEmpty()) lines += "Video  " + parts.joinToString("  ·  ")
        }
        player.audioFormat?.let { f ->
            val parts = listOfNotNull(
                codecName(f.sampleMimeType),
                channelLayout(f.channelCount),
                if (f.sampleRate > 0) "${f.sampleRate / 1000} kHz" else null,
                bitrate(f),
            )
            if (parts.isNotEmpty()) lines += "Audio  " + parts.joinToString("  ·  ")
        }
        player.videoDecoderCounters?.let { c ->
            c.ensureUpdated()
            lines += "Frames  ${c.renderedOutputBufferCount} shown · ${c.droppedBufferCount} dropped · ${c.skippedOutputBufferCount} skipped"
        }
        videoDecoder?.let { lines += "Video decoder  $it" }
        audioDecoder?.let { lines += "Audio decoder  $it" }
        lines += "Buffered  ${player.totalBufferedDuration / 1000} s"
        if (useHls) lines += "Playing as HLS"
        if (ts != null) lines += "Timeshift  ${behindLiveMs() / 1000} s behind live"
        if (volumeDb != 0) lines += "Volume level  ${VolumeLevels.label(volumeDb)}"
        if (quality != "auto") lines += "Picture quality  " + (if (quality == "best") "Best" else "Data saver (SD)")
        if (cushionMs > 0) lines += "Start-up cushion  ${cushionMs / 1000} s (this channel has dropped out lately)"
        if (fastSwitch) lines += "Fast switching on" + (standbyUrl?.let { " (next channel ready)" } ?: "")
        return lines
    }

    private fun bitrate(f: Format): String? {
        val b = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 } ?: return null
        return if (b >= 1_000_000) String.format(Locale.US, "%.1f Mbps", b / 1_000_000f) else "${b / 1000} kbps"
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }

    private fun channelLayout(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "mono"
        count == 2 -> "stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count ch"
    }

    /** Lets the TV switch frame rate when it can do so without a blank screen (see also display-mode matching). */
    private var seamlessFps = false
    private var audioLang: String? = null
    private var subtitleLang: String? = null

    /** Preferred sound and subtitle languages ("en", …; null = the stream's default). */
    fun setLanguages(audio: String?, subtitles: String?) {
        audioLang = audio
        subtitleLang = subtitles
        listOfNotNull(primary, standby).forEach { applyLanguages(it) }
    }

    private fun applyLanguages(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLang)
            .setPreferredTextLanguage(subtitleLang)
            .build()
    }

    fun setSeamlessFrameRate(on: Boolean) {
        seamlessFps = on
        val strategy = if (on) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        listOfNotNull(primary, standby).forEach { it.videoChangeFrameRateStrategy = strategy }
    }

    /** Adds a downloaded subtitle file (.srt) to the film/episode on screen and switches it on. */
    fun addSubtitleFile(file: File, language: String) {
        if (url == null || live) return
        extraSubtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language)
            .build()
        val pos = player.currentPosition
        load(pos)
    }

    fun release() {
        endPriming()
        boost.release()
        nightMode.release()
        handler.removeCallbacksAndMessages(null)
        stallHandler.removeCallbacks(stallCheck)
        stopTimeshift()
        session?.release()
        listOfNotNull(primary, standby, player).distinct().forEach { it.release() }
    }
}

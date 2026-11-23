package com.titanejstudios.tuner.tv

import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.Http
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.playback.PlayerFactory
import org.json.JSONObject

/**
 * Makes Tuner a "TV input": its channels show up in the TV's own channel list / Live tab, and
 * the TV plays them through this service (Tuner itself doesn't need to be open).
 */
class TunerInputService : TvInputService() {
    override fun onCreateSession(inputId: String): Session = TunerSession(this)
}

@OptIn(UnstableApi::class)
private class TunerSession(private val context: Context) : TvInputService.Session(context) {
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var volume = 1f
    private var url: String? = null
    private var triedHls = false
    private var retries = 0

    private fun ensurePlayer(): ExoPlayer = player ?: PlayerFactory.build(context, 64 * 1024 * 1024, false).also { p ->
        player = p
        p.setVideoSurface(surface)
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                notifyVideoAvailable()
                retries = 0
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
                    Player.STATE_READY -> notifyVideoAvailable()
                    Player.STATE_ENDED -> retry()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.warn("Live tab: couldn't play (${error.errorCodeName})")
                if (!triedHls && error.errorCode in 3000..3999) {
                    triedHls = true
                    start(true)
                } else retry()
            }
        })
    }

    private fun retry() {
        if (retries++ < 4) start(triedHls) else notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
    }

    private fun start(hls: Boolean) {
        val u = url ?: return
        val p = ensurePlayer()
        p.setMediaItem(PlayerFactory.item(u, hls))
        p.prepare()
        p.play()
    }

    override fun onTune(channelUri: Uri): Boolean {
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        val data = try {
            context.contentResolver.query(channelUri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getBlob(0)?.let { String(it, Charsets.UTF_8) } else null
            }
        } catch (e: Exception) {
            AppLog.warn("Live tab: couldn't read channel", e)
            null
        }
        val u = data?.substringAfter('\n', "")?.takeIf { it.isNotBlank() }
        if (u == null) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
            return false
        }
        url = u
        triedHls = false
        retries = 0
        start(false)
        return true
    }

    override fun onSetSurface(surface: Surface?): Boolean {
        this.surface = surface
        player?.setVideoSurface(surface)
        return true
    }

    override fun onSetStreamVolume(volume: Float) {
        this.volume = volume
        player?.volume = volume
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {}

    override fun onRelease() {
        player?.release()
        player = null
    }
}

/** Shown by the TV's channel setup ("Add Tuner channels"): copies favourites in, then closes. */
class LiveSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        if (store.liveTabScope == "off") store.liveTabScope = "favourites"
        Thread {
            runCatching { LiveChannelSync.sync(applicationContext) }
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }.start()
    }
}

/** Copies channels and the next day's guide into the TV's channel database. Run off the main thread. */
object LiveChannelSync {
    private const val MAX_CHANNELS = 600
    private const val MAX_LOGOS = 200

    fun inputId(context: Context): String = TvContract.buildInputId(ComponentName(context, TunerInputService::class.java))

    @Synchronized
    fun sync(context: Context): Int {
        val store = Store(context)
        val resolver = context.contentResolver
        val input = inputId(context)
        val scope = store.liveTabScope
        if (scope == "off") {
            runCatching { resolver.delete(TvContract.buildChannelsUriForInput(input), null, null) }
            store.liveTabRows = "{}"
            return 0
        }
        val db = Database.get(context)
        val sources = store.sources.map { it.id }
        val hiddenGroups = store.hiddenGroups
        val hiddenChannels = store.hiddenChannels
        val dead = store.deadChannels
        var wanted: List<Channel> = db.loadChannels(sources).filter {
            it.group !in hiddenGroups && it.id !in hiddenChannels && it.id !in dead
        }
        if (scope == "favourites") {
            val favOrder = store.profiles.flatMap { store.favourites(it.id) }.distinct().withIndex().associate { it.value to it.index }
            wanted = wanted.filter { it.id in favOrder }.sortedBy { favOrder[it.id] }
        }
        wanted = wanted.distinctBy { it.id }.take(MAX_CHANNELS)

        // What's already there (our rows only).
        val existing = HashMap<String, Long>()
        resolver.query(
            TvContract.buildChannelsUriForInput(input),
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getBlob(1)?.let { String(it, Charsets.UTF_8).substringBefore('\n') } ?: continue
                existing[id] = c.getLong(0)
            }
        }

        val logoState = try { JSONObject(store.liveTabRows) } catch (e: Exception) { JSONObject() }
        val rowIds = LinkedHashMap<String, Long>()
        wanted.forEachIndexed { i, ch ->
            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, input)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, (if (ch.number > 0) ch.number else i + 1).toString())
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, (ch.id + "\n" + ch.url).toByteArray(Charsets.UTF_8))
                put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
            }
            try {
                val row = existing.remove(ch.id)
                if (row != null) {
                    resolver.update(TvContract.buildChannelUri(row), values, null, null)
                    rowIds[ch.id] = row
                } else {
                    resolver.insert(TvContract.Channels.CONTENT_URI, values)?.let { rowIds[ch.id] = ContentUris.parseId(it) }
                }
            } catch (e: Exception) {
                AppLog.warn("Live tab: couldn't save ${ch.name}", e)
            }
        }
        // Remove channels no longer wanted.
        for ((id, row) in existing) {
            runCatching { resolver.delete(TvContract.buildChannelUri(row), null, null) }
            logoState.remove(id)
        }

        // Logos (only when new or changed).
        var logos = 0
        for (ch in wanted) {
            val row = rowIds[ch.id] ?: continue
            val logo = ch.logo?.takeIf { it.startsWith("http") } ?: continue
            if (logoState.optString(ch.id) == logo) continue
            if (logos++ >= MAX_LOGOS) break
            try {
                Http.open(logo).use { input2 ->
                    resolver.openOutputStream(TvContract.buildChannelLogoUri(row))?.use { out -> input2.copyTo(out) }
                }
                logoState.put(ch.id, logo)
            } catch (e: Exception) {
                // A missing logo isn't worth logging every night.
            }
        }
        store.liveTabRows = logoState.toString()

        // Guide for the next 24 hours.
        val now = System.currentTimeMillis()
        val epg = db.loadEpg(sources, now - 3600_000L, now + 24 * 3600_000L, emptyMap())
        for (ch in wanted) {
            val row = rowIds[ch.id] ?: continue
            runCatching { resolver.delete(TvContract.buildProgramsUriForChannel(row), null, null) }
            val progs = epg[ch.id].orEmpty()
            if (progs.isEmpty()) continue
            val rows = progs.map { p ->
                ContentValues().apply {
                    put(TvContract.Programs.COLUMN_CHANNEL_ID, row)
                    put(TvContract.Programs.COLUMN_TITLE, p.title)
                    p.desc?.let { put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, it.take(250)) }
                    put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, p.start)
                    put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, p.stop)
                    p.image?.let { put(TvContract.Programs.COLUMN_POSTER_ART_URI, it) }
                    tvGenre(Genres.of(p.category, p.title, ch.group))?.let {
                        put(TvContract.Programs.COLUMN_CANONICAL_GENRE, TvContract.Programs.Genres.encode(it))
                    }
                }
            }
            runCatching { resolver.bulkInsert(TvContract.Programs.CONTENT_URI, rows.toTypedArray()) }
                .onFailure { AppLog.warn("Live tab: couldn't save the guide for ${ch.name}", it) }
        }
        AppLog.info("Live tab: ${rowIds.size} channels")
        return rowIds.size
    }

    private fun tvGenre(g: String?): String? = when (g) {
        "Sport" -> TvContract.Programs.Genres.SPORTS
        "Film" -> TvContract.Programs.Genres.MOVIES
        "News" -> TvContract.Programs.Genres.NEWS
        "Kids" -> TvContract.Programs.Genres.FAMILY_KIDS
        "Factual" -> TvContract.Programs.Genres.EDUCATION
        "Entertainment" -> TvContract.Programs.Genres.ENTERTAINMENT
        "Drama" -> TvContract.Programs.Genres.DRAMA
        "Music" -> TvContract.Programs.Genres.MUSIC
        else -> null
    }
}

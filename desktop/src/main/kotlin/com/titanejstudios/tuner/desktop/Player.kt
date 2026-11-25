package com.titanejstudios.tuner.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File

/**
 * Video, using VLC's engine. The Windows installer carries its own copy (libvlc and its plugins);
 * without it (Mac/Linux, or a build made without it) an installed VLC is used.
 */
class Player {
    /** False when no VLC engine could be found. Loaded in the background at start (see [warmUp]). */
    val available: Boolean by lazy {
        val t = System.currentTimeMillis()
        findVlc().also { com.titanejstudios.tuner.data.AppLog.info("VLC engine found: $it (${System.currentTimeMillis() - t} ms)") }
    }

    /**
     * True once the video player is made. Until then screens show "Starting the video player…"
     * rather than waiting – making it (loading VLC and its plugins) can take several seconds.
     */
    var ready by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** VLC's engine (the slow part: it loads every plugin), made in the background at start. */
    @Volatile
    var factory: uk.co.caprica.vlcj.factory.MediaPlayerFactory? = null
        private set

    /** Loads VLC's engine off the main thread, so the window opens straight away and Live TV is quick. */
    fun warmUp() {
        Thread({
            runCatching {
                if (!available) return@runCatching
                val t = System.currentTimeMillis()
                factory = uk.co.caprica.vlcj.factory.MediaPlayerFactory("--quiet", "--intf=dummy", "--no-snapshot-preview", "--no-video-title-show")
                com.titanejstudios.tuner.data.AppLog.info("VLC ready (${System.currentTimeMillis() - t} ms)")
                // The video window itself is a Swing component: made on Swing's thread, which is quick now.
                javax.swing.SwingUtilities.invokeLater {
                    runCatching { component }
                    ready = true
                    pending?.let { (u, s) -> pending = null; play(u, s) }
                }
            }.onFailure {
                com.titanejstudios.tuner.data.AppLog.warn("VLC warm-up failed", it)
                javax.swing.SwingUtilities.invokeLater { ready = true }
            }
        }, "vlc-warm-up").apply { isDaemon = true }.start()
    }

    val component: EmbeddedMediaPlayerComponent? get() = componentLazy.value

    private val componentLazy = lazy {
        if (!available) null else runCatching {
            val f = factory
            (if (f != null) EmbeddedMediaPlayerComponent(f, null, null, null, null) else EmbeddedMediaPlayerComponent()).apply {
                // Keys stay with Flicker (Space, arrows, F11), not the video.
                isFocusable = false
                videoSurfaceComponent().isFocusable = false
                attach(this)
            }
        }.onFailure { com.titanejstudios.tuner.data.AppLog.warn("Couldn't start VLC", it) }.getOrNull()
    }

    // What to do at the end of a film and on a double-click, hooked up once the video exists
    // (it's made the first time something plays, so starting Flicker stays quick).
    private var finishedCb: (() -> Unit)? = null
    private var doubleClickCb: (() -> Unit)? = null

    private fun attach(c: EmbeddedMediaPlayerComponent) {
        c.mediaPlayer().events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer?) {
                javax.swing.SwingUtilities.invokeLater { finishedCb?.invoke() }
            }
        })
        c.videoSurfaceComponent().addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) javax.swing.SwingUtilities.invokeLater { doubleClickCb?.invoke() }
            }
        })
    }

    /** Double-click on the video (full screen). */
    fun onDoubleClick(block: () -> Unit) {
        doubleClickCb = block
    }

    private fun findVlc(): Boolean {
        // compose.application.resources.dir is where the installer puts resources/windows.
        val res = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        if (res != null && File(res, "libvlc.dll").exists()) {
            com.titanejstudios.tuner.data.AppLog.info("Using the VLC engine in ${res.absolutePath}")
            runCatching {
                NativeLibrary.addSearchPath("libvlc", res.absolutePath)
                NativeLibrary.addSearchPath("libvlccore", res.absolutePath)
                // libvlc.dll needs libvlccore.dll loaded first (Windows won't look next to it).
                NativeLibrary.getInstance("libvlccore")
                NativeLibrary.getInstance("libvlc")
                return true
            }.onFailure { com.titanejstudios.tuner.data.AppLog.warn("Couldn't load the built-in VLC engine", it) }
        }
        return runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }

    private fun player() = if (ready) component?.mediaPlayer() else null

    /** Plays [url]; [startMs] jumps there once it's going (films you're part way through). */
    fun play(url: String, startMs: Long = 0L) {
        lastUrl = url
        // Still warming up: start it as soon as the player's ready (rather than freezing the window).
        if (!ready) {
            pending = url to startMs
            return
        }
        val opts = mutableListOf(":network-caching=1500", ":http-reconnect")
        if (deinterlace) opts += listOf(":deinterlace=1", ":deinterlace-mode=yadif")
        if (normalise) opts += listOf(":audio-filter=normvol", ":norm-max-level=1.5")
        if (startMs > 0) opts += ":start-time=${startMs / 1000}"
        player()?.media()?.play(url, *opts.toTypedArray())
    }

    fun stop() {
        lastUrl = null
        pending = null
        player()?.controls()?.stop()
    }

    /** Something asked to play before the player was ready. */
    private var pending: Pair<String, Long>? = null

    /** What's playing (null when stopped). */
    var lastUrl: String? = null
        private set

    /**
     * Starts the same thing again from where it was – after the video moves window (the mini
     * player), VLC needs to be given the new place to draw.
     */
    fun restart() {
        val url = lastUrl ?: return
        val pos = if (durationMs > 0) positionMs else 0L
        player()?.controls()?.stop()
        play(url, pos)
    }

    val isMuted: Boolean get() = player()?.audio()?.isMute == true

    /** Tidy up interlaced pictures (old SD channels). */
    var deinterlace = true

    /** Evens out loud and quiet channels. */
    var normalise = false

    // ---- Picture and sound (Settings on the player) ----

    /** (id, name) of each sound track and the one playing. */
    fun audioTracks(): List<Pair<Int, String>> =
        runCatching { player()?.audio()?.trackDescriptions()?.map { it.id() to (it.description() ?: "Track ${it.id()}") } }.getOrNull().orEmpty()

    val audioTrack: Int get() = runCatching { player()?.audio()?.track() }.getOrNull() ?: -1

    fun setAudioTrack(id: Int) {
        player()?.audio()?.setTrack(id)
    }

    /** Subtitles (-1 is "off"). */
    fun subtitleTracks(): List<Pair<Int, String>> =
        runCatching { player()?.subpictures()?.trackDescriptions()?.map { it.id() to (it.description() ?: "Subtitles ${it.id()}") } }.getOrNull().orEmpty()

    val subtitleTrack: Int get() = runCatching { player()?.subpictures()?.track() }.getOrNull() ?: -1

    fun setSubtitleTrack(id: Int) {
        player()?.subpictures()?.setTrack(id)
    }

    /** "Fit" (null), "Fill" (crop to the window's shape) or a fixed shape like "16:9" / "4:3". */
    fun setShape(shape: String?) {
        val v = player()?.video() ?: return
        when (shape) {
            null -> {
                v.setAspectRatio(null)
                v.setCropGeometry(null)
            }
            "Fill" -> {
                val c = component
                val ratio = if (c != null && c.height > 0) "${c.width}:${c.height}" else "16:9"
                v.setAspectRatio(null)
                v.setCropGeometry(ratio)
            }
            else -> {
                v.setCropGeometry(null)
                v.setAspectRatio(shape)
            }
        }
    }

    fun setSpeed(rate: Float) {
        player()?.controls()?.setRate(rate)
    }

    fun togglePause() {
        player()?.controls()?.pause()
    }

    val isPlaying: Boolean get() = player()?.status()?.isPlaying == true

    /** Where it's got to, and how long it is (0 for live TV). */
    val positionMs: Long get() = player()?.status()?.time() ?: 0L
    val durationMs: Long get() = player()?.status()?.length() ?: 0L

    fun seekTo(ms: Long) {
        player()?.controls()?.setTime(ms.coerceAtLeast(0L))
    }

    fun skip(ms: Long) {
        player()?.controls()?.skipTime(ms)
    }

    fun setVolume(v: Int) {
        player()?.audio()?.setVolume(v.coerceIn(0, 150))
    }

    fun toggleMute() {
        player()?.audio()?.mute()
    }

    /** Called when a film or episode reaches the end. */
    fun onFinished(block: () -> Unit) {
        finishedCb = block
    }

    fun release() {
        if (componentLazy.isInitialized()) runCatching { component?.release() }
    }
}

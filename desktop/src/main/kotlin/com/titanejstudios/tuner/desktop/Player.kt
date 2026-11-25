package com.titanejstudios.tuner.desktop

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File

/**
 * Video, using VLC's engine. The Windows installer carries its own copy (libvlc and its plugins);
 * without it (Mac/Linux, or a build made without it) an installed VLC is used.
 */
class Player {
    /** False when no VLC engine could be found. */
    val available: Boolean = findVlc()

    val component: EmbeddedMediaPlayerComponent? by lazy {
        if (!available) null else runCatching {
            EmbeddedMediaPlayerComponent().apply {
                // Keys stay with Flicker (Space, arrows, F11), not the video.
                isFocusable = false
                videoSurfaceComponent().isFocusable = false
            }
        }.getOrNull()
    }

    /** Double-click on the video (full screen). */
    fun onDoubleClick(block: () -> Unit) {
        component?.videoSurfaceComponent()?.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) javax.swing.SwingUtilities.invokeLater { block() }
            }
        })
    }

    private fun findVlc(): Boolean {
        // compose.application.resources.dir is where the installer puts resources/windows.
        val res = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        if (res != null && File(res, "libvlc.dll").exists()) {
            runCatching {
                NativeLibrary.addSearchPath("libvlc", res.absolutePath)
                NativeLibrary.addSearchPath("libvlccore", res.absolutePath)
                // libvlc.dll needs libvlccore.dll loaded first (Windows won't look next to it).
                NativeLibrary.getInstance("libvlccore")
                NativeLibrary.getInstance("libvlc")
                return true
            }
        }
        return runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }

    private fun player() = component?.mediaPlayer()

    /** Plays [url]; [startMs] jumps there once it's going (films you're part way through). */
    fun play(url: String, startMs: Long = 0L) {
        val opts = mutableListOf(":network-caching=1500", ":http-reconnect")
        if (startMs > 0) opts += ":start-time=${startMs / 1000}"
        player()?.media()?.play(url, *opts.toTypedArray())
    }

    fun stop() {
        player()?.controls()?.stop()
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
        player()?.events()?.addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer?) {
                javax.swing.SwingUtilities.invokeLater { block() }
            }
        })
    }

    fun release() {
        runCatching { component?.release() }
    }
}

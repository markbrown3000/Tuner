package com.titanejstudios.tuner.desktop

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** Video, using VLC's engine (VLC has to be installed on the PC – videolan.org). */
class Player {
    /** False when VLC isn't installed (or is the 32-bit version). */
    val available: Boolean = runCatching { NativeDiscovery().discover() }.getOrDefault(false)

    val component: EmbeddedMediaPlayerComponent? by lazy {
        if (!available) null else runCatching { EmbeddedMediaPlayerComponent() }.getOrNull()
    }

    fun play(url: String) {
        component?.mediaPlayer()?.media()?.play(url, ":network-caching=1500", ":http-reconnect")
    }

    fun stop() {
        component?.mediaPlayer()?.controls()?.stop()
    }

    fun setVolume(v: Int) {
        component?.mediaPlayer()?.audio()?.setVolume(v.coerceIn(0, 150))
    }

    fun toggleMute() {
        component?.mediaPlayer()?.audio()?.mute()
    }

    fun release() {
        runCatching { component?.release() }
    }
}

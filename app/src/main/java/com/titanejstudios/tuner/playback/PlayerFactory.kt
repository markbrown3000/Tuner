package com.titanejstudios.tuner.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.titanejstudios.tuner.data.Http
import com.titanejstudios.tuner.data.SafeMode
import com.titanejstudios.tuner.data.Store

/**
 * Renderers that can turn off audio passthrough. With passthrough on, some TVs/soundbars report
 * the audio clock wrongly for Dolby streams, and ExoPlayer then drops every video frame to "keep
 * up" – the picture freezes while the sound carries on. Decoding audio to PCM avoids that.
 */
@OptIn(UnstableApi::class)
private class TunerRenderersFactory(context: Context, private val passthrough: Boolean) :
    DefaultRenderersFactory(context) {

    init {
        // If the TV's preferred decoder fails to start, try the next one instead of giving up.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        if (!passthrough) builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        return LipSyncAudioSink(builder.build())
    }
}

/**
 * Lip-sync fix. Video is timed against the sound's clock, so reporting the clock slightly ahead
 * shows pictures earlier (= sound later), and behind shows them later (= sound earlier).
 */
object LipSync {
    /** Positive = sound later, negative = sound earlier. */
    @Volatile var offsetUs: Long = 0L
}

@OptIn(UnstableApi::class)
private class LipSyncAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else maxOf(0L, p + LipSync.offsetUs)
    }
}

@OptIn(UnstableApi::class)
object PlayerFactory {

    /**
     * @param bufferBytes memory the player may use for buffering
     * @param longPause   keep buffering for longer while paused (main player only)
     */
    fun build(context: Context, bufferBytes: Int, longPause: Boolean, multiView: Boolean = false): ExoPlayer {
        val store = Store(context)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (multiView) 6_000 else 15_000,
                if (multiView) 20_000 else if (longPause) 5 * 60_000 else 50_000,
                1_000,
                if (multiView) 2_000 else 2_500,
            )
            .setTargetBufferBytes(bufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(if (longPause) 30_000 else 0, true)
            .build()
        val player = ExoPlayer.Builder(context, TunerRenderersFactory(context, store.audioPassthrough && !SafeMode.active))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors(store.streamCompatibility && !SafeMode.active))
            )
            .setLoadControl(loadControl)
            .build()
        // Don't ask the TV to change refresh rate mid-stream – on some sets that blanks or freezes the picture.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        return player
    }

    /**
     * Normal mode uses ExoPlayer's standard MPEG-TS handling. Compatibility mode helps streams that
     * never start (no IDR keyframes / no access-unit delimiters) but can freeze on some decoders.
     */
    fun extractors(compatibility: Boolean = false): DefaultExtractorsFactory {
        val f = DefaultExtractorsFactory()
        if (compatibility) {
            f.setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        }
        return f
    }

    fun item(url: String, forceHls: Boolean): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (forceHls || url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }
}

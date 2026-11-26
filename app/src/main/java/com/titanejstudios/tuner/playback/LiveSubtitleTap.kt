package com.titanejstudios.tuner.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.titanejstudios.tuner.subtitles.LiveSubtitles
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passes the sound through untouched, and – while live subtitles are on – hands a 16 kHz mono
 * copy to the speech recogniser. Only decoded sound passes here: with Audio passthrough sending
 * Dolby straight to a soundbar, there's nothing to listen to.
 */
@OptIn(UnstableApi::class)
class LiveSubtitleTap : BaseAudioProcessor() {
    private var channels = 0
    private var rate = 0
    private var phase = 0.0
    private var sum = 0.0
    private var count = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (LiveSubtitles.listening && channels > 0 && rate > 0) {
            runCatching { listen(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)) }
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    private fun listen(buf: ByteBuffer) {
        val frames = buf.remaining() / (2 * channels)
        if (frames == 0) return
        val target = minOf(rate, 16_000)
        val step = rate.toDouble() / target
        val out = ShortArray(frames * target / rate + 2)
        var o = 0
        for (f in 0 until frames) {
            var s = 0
            for (c in 0 until channels) s += buf.short.toInt()
            sum += s.toDouble() / channels
            count++
            phase += 1.0
            if (phase >= step) {
                phase -= step
                if (o < out.size) out[o++] = (sum / count).toInt().coerceIn(-32768, 32767).toShort()
                sum = 0.0
                count = 0
            }
        }
        if (o > 0) LiveSubtitles.hear(out.copyOf(o), target)
    }

    override fun onFlush() {
        phase = 0.0
        sum = 0.0
        count = 0
        if (LiveSubtitles.listening) LiveSubtitles.restart()
    }
}

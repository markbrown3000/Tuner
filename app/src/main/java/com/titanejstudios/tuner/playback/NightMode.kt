package com.titanejstudios.tuner.playback

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import com.titanejstudios.tuner.data.AppLog

/**
 * "Night mode" for sound: a compressor that lifts quiet speech and tames loud adverts and
 * explosions, plus a limiter. Works when Tuner decodes the audio itself (audio passthrough off).
 */
class NightMode {
    private var effect: DynamicsProcessing? = null
    private var session = 0
    var supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        private set

    /** Attach to [audioSessionId] when [on], otherwise remove the effect. */
    fun apply(audioSessionId: Int, on: Boolean) {
        if (!on || audioSessionId == 0 || !supported) {
            release()
            return
        }
        if (effect != null && session == audioSessionId) return
        release()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,          // channels (applied to all)
                false, 0,   // pre-EQ
                true, 1,    // multi-band compressor, one band = whole range
                false, 0,   // post-EQ
                true,       // limiter
            ).build()
            val dp = DynamicsProcessing(0, audioSessionId, config)
            // Compress everything above -32 dB at 4:1, then lift the result back up by 9 dB.
            dp.setMbcBandAllChannelsTo(
                0,
                DynamicsProcessing.MbcBand(true, 20_000f, 8f, 250f, 4f, -32f, 8f, -90f, 1f, 0f, 9f),
            )
            // Hard ceiling so nothing jumps out.
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -3f, 0f))
            dp.enabled = true
            effect = dp
            session = audioSessionId
        } catch (e: Throwable) {
            supported = false
            AppLog.warn("Night mode sound isn't available on this TV", e)
            release()
        }
    }

    fun release() {
        try {
            effect?.release()
        } catch (_: Throwable) {
        }
        effect = null
        session = 0
    }
}

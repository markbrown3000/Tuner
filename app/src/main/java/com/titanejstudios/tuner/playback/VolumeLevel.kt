package com.titanejstudios.tuner.playback

import android.media.audiofx.LoudnessEnhancer
import com.titanejstudios.tuner.data.AppLog
import kotlin.math.pow

/**
 * Per-channel volume levelling. Quieter = the player's own volume; louder = Android's
 * LoudnessEnhancer on the player's audio session (only works when Tuner decodes the sound,
 * i.e. with audio passthrough off).
 */
object VolumeLevels {
    val steps = listOf(-9, -6, -3, 0, 3, 6, 9)

    /** Player volume (0–1) for a level: below 0 dB turns down, 0 dB and above stays at full. */
    fun linear(db: Int): Float = if (db >= 0) 1f else 10.0.pow(db / 20.0).toFloat()

    /** Extra loudness in millibels for levels above 0 dB. */
    fun boostMb(db: Int): Int = if (db > 0) db * 100 else 0

    fun next(db: Int): Int {
        val i = steps.indexOf(db)
        return if (i < 0) 0 else steps[(i + 1) % steps.size]
    }

    fun label(db: Int): String = when {
        db == 0 -> "Normal"
        db > 0 -> "Louder +$db dB"
        else -> "Quieter $db dB"
    }
}

/** Wraps a LoudnessEnhancer tied to one audio session. */
class LoudnessBoost {
    private var effect: LoudnessEnhancer? = null
    private var session = 0
    private var gain = 0

    fun apply(audioSessionId: Int, gainMb: Int) {
        if (gainMb <= 0 || audioSessionId == 0) {
            release()
            return
        }
        if (effect != null && session == audioSessionId && gain == gainMb) return
        release()
        try {
            val e = LoudnessEnhancer(audioSessionId)
            e.setTargetGain(gainMb)
            e.enabled = true
            effect = e
            session = audioSessionId
            gain = gainMb
        } catch (t: Throwable) {
            AppLog.warn("Volume boost isn't available on this TV", t)
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
        gain = 0
    }
}

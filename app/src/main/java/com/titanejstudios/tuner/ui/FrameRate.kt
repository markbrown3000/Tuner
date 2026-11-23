package com.titanejstudios.tuner.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Match the TV's refresh rate to the video (e.g. 50 Hz for UK broadcasts, 60 Hz for US, 24 Hz for
 * films) so motion is smooth instead of judders. Only switches between modes with the same
 * resolution, and restores the TV's normal mode when you stop watching.
 */
object FrameRateMatcher {
    private var appliedFor = -1f

    fun apply(activity: Activity, fps: Float) {
        if (fps <= 0f) {
            reset(activity)
            return
        }
        if (abs(fps - appliedFor) < 0.01f) return
        val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null)
            ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val best = pick(candidates.map { it.refreshRate }, fps) ?: return
        val mode = candidates.first { it.refreshRate == best }
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId != mode.modeId) {
            attrs.preferredDisplayModeId = mode.modeId
            activity.window.attributes = attrs
        }
        appliedFor = fps
    }

    fun reset(activity: Activity) {
        if (appliedFor < 0f) return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = 0
        activity.window.attributes = attrs
        appliedFor = -1f
    }

    /** Best refresh rate for [fps]: an exact whole multiple (25→50, 23.976→23.976/47.95), lowest first. */
    fun pick(rates: List<Float>, fps: Float): Float? {
        val matches = rates.filter { r ->
            val ratio = r / fps
            val n = ratio.roundToInt()
            n in 1..4 && abs(ratio - n) < 0.005f * n
        }
        return matches.minOrNull()?.let { lowest ->
            // Prefer 50 over 25, 60 over 30 – most TVs handle the higher rate better.
            matches.filter { it >= 47f }.minOrNull() ?: lowest
        }
    }
}

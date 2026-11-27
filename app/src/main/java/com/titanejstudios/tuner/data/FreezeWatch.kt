package com.titanejstudios.tuner.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Notices when Flicker's screen stops responding (the main thread is stuck for 3 seconds or more)
 * and writes down what it was doing – the main thread's stack – in the problem log. Android only
 * reports a freeze after 5 seconds and only sometimes; this catches the shorter ones too.
 */
object FreezeWatch {
    private const val STUCK_MS = 3_000L
    private const val CHECK_MS = 1_000L

    @Volatile private var lastTick = 0L
    @Volatile private var started = false
    /** One entry per freeze, not one a second while it lasts. */
    @Volatile private var reported = false

    fun start() {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        lastTick = SystemClock.uptimeMillis()
        Thread({
            while (true) {
                main.post { lastTick = SystemClock.uptimeMillis() }
                val before = SystemClock.uptimeMillis()
                try {
                    Thread.sleep(CHECK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val after = SystemClock.uptimeMillis()
                // This thread overslept too: Android paused the whole app (in the background), not a freeze.
                if (after - before > CHECK_MS + 1_500) {
                    lastTick = after
                    continue
                }
                val stuck = after - lastTick
                if (stuck >= STUCK_MS) {
                    if (!reported) {
                        reported = true
                        runCatching { report(stuck) }
                    }
                } else if (reported) {
                    reported = false
                    AppLog.info("The screen is responding again")
                }
            }
        }, "flicker-freeze-watch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
    }

    private fun report(stuckMs: Long) {
        val frames = Looper.getMainLooper().thread.stackTrace
        AppLog.warn(
            "The screen stopped responding for ${stuckMs / 1000}+ s while: ${SafeMode.doing}\n" +
                interesting(frames.map { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }).joinToString("\n") { "  at $it" },
        )
    }

    /** Flicker's own lines first (they say what it was doing), then the rest, 25 lines at most. */
    fun interesting(frames: List<String>): List<String> {
        val ours = frames.indexOfFirst { it.startsWith("com.titanejstudios") }
        val from = if (ours > 6) ours - 6 else 0
        return frames.drop(from).take(25)
    }
}

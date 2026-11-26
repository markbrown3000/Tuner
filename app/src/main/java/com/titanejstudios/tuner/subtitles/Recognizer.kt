package com.titanejstudios.tuner.subtitles

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.titanejstudios.tuner.data.AppLog
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The few Vosk functions used, from the downloaded libvosk.so (not shipped in the APK). */
interface VoskLib : Library {
    fun vosk_set_log_level(level: Int)
    fun vosk_model_new(path: String): Pointer?
    fun vosk_model_free(model: Pointer)
    fun vosk_recognizer_new(model: Pointer, sampleRate: Float): Pointer?
    fun vosk_recognizer_accept_waveform_s(recognizer: Pointer, data: ShortArray, length: Int): Int
    fun vosk_recognizer_result(recognizer: Pointer): String?
    fun vosk_recognizer_partial_result(recognizer: Pointer): String?
    fun vosk_recognizer_reset(recognizer: Pointer)
    fun vosk_recognizer_free(recognizer: Pointer)
}

/**
 * Turns the player's sound into words on its own thread. The audio tap hands over 16 kHz mono
 * chunks; if this falls behind (a slow moment), old sound is dropped rather than drifting late.
 */
internal object Recognizer {
    private class Chunk(val samples: ShortArray, val rate: Int)

    private val RESET = Chunk(ShortArray(0), 0)
    private val STOP = Chunk(ShortArray(0), -1)
    private val queue = LinkedBlockingQueue<Chunk>(40)
    @Volatile private var thread: Thread? = null

    private var lib: VoskLib? = null
    private var model: Pointer? = null
    private var recognizer: Pointer? = null
    private var rate = 0
    private var lastPartialAt = 0L

    /** From the audio thread: never blocks. */
    fun offer(samples: ShortArray, rate: Int) {
        if (!queue.offer(Chunk(samples, rate))) {
            queue.clear()
            queue.offer(RESET)
        }
        ensureRunning()
    }

    /** Seek or new channel: start afresh. */
    fun reset() {
        queue.clear()
        queue.offer(RESET)
    }

    /** Turned off: free the model's memory. */
    fun stop() {
        queue.clear()
        queue.offer(STOP)
    }

    @Synchronized
    private fun ensureRunning() {
        if (thread?.isAlive == true) return
        thread = Thread({ loop() }, "live-subtitles").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun loop() {
        try {
            while (true) {
                val c = queue.poll(30, TimeUnit.SECONDS)
                when {
                    // No sound for a while (paused, stopped): free the memory until it's needed again.
                    c == null || c === STOP -> {
                        free()
                        LiveSubtitles.clear()
                        if (c == null || queue.isEmpty()) return
                    }
                    c === RESET -> {
                        recognizer?.let { lib?.vosk_recognizer_reset(it) }
                        LiveSubtitles.clear()
                    }
                    LiveSubtitles.listening -> feed(c)
                }
            }
        } catch (e: Throwable) {
            AppLog.warn("Live subtitles stopped", e)
            free()
        } finally {
            thread = null
        }
    }

    private fun feed(c: Chunk) {
        val r = recognizerFor(c.rate) ?: return
        val l = lib ?: return
        if (l.vosk_recognizer_accept_waveform_s(r, c.samples, c.samples.size) != 0) {
            LiveSubtitles.finished(text(l.vosk_recognizer_result(r), "text"))
        } else {
            val now = System.currentTimeMillis()
            if (now - lastPartialAt > 250) {
                lastPartialAt = now
                LiveSubtitles.partial(text(l.vosk_recognizer_partial_result(r), "partial"))
            }
        }
    }

    private fun recognizerFor(sampleRate: Int): Pointer? {
        if (recognizer != null && sampleRate == rate) return recognizer
        val l = lib ?: run {
            val file = LiveSubtitles.engineFile()
            if (!file.exists()) return null
            try {
                Native.load(file.absolutePath, VoskLib::class.java).also {
                    it.vosk_set_log_level(-1)
                    lib = it
                }
            } catch (e: Throwable) {
                AppLog.warn("Live subtitles: the speech engine wouldn't start", e)
                LiveSubtitles.engineFailed(e.message ?: e.javaClass.simpleName)
                return null
            }
        }
        val m = model ?: run {
            val started = System.currentTimeMillis()
            val loaded = l.vosk_model_new(LiveSubtitles.modelDir().absolutePath) ?: run {
                AppLog.warn("Live subtitles: the speech model wouldn't load")
                return null
            }
            AppLog.info("Live subtitles: speech model loaded in ${System.currentTimeMillis() - started} ms")
            model = loaded
            loaded
        }
        recognizer?.let { l.vosk_recognizer_free(it) }
        recognizer = l.vosk_recognizer_new(m, sampleRate.toFloat())
        rate = sampleRate
        return recognizer
    }

    private fun free() {
        val l = lib ?: return
        recognizer?.let { l.vosk_recognizer_free(it) }
        recognizer = null
        model?.let { l.vosk_model_free(it) }
        model = null
        rate = 0
    }

    private fun text(json: String?, key: String): String =
        runCatching { JSONObject(json ?: return "").optString(key) }.getOrDefault("")
}

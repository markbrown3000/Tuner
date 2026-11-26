package com.titanejstudios.tuner.subtitles

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Http
import com.titanejstudios.tuner.data.Lite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Live subtitles: words made from the sound as you watch, on the device itself (Vosk speech
 * recognition, English). Not in the APK – the speech model and engine are an optional download
 * from Settings › Watching, and only offered on devices fast enough to run them alongside video.
 */
object LiveSubtitles {

    sealed class Status {
        /** This device is too slow / 32-bit / short of memory. */
        data class NotAvailable(val reason: String) : Status()
        object NotDownloaded : Status()
        data class Downloading(val percent: Int, val step: String) : Status()
        object Ready : Status()
        data class Failed(val message: String) : Status()
    }

    var status: Status by mutableStateOf(Status.NotDownloaded); private set
    /** Shown in the player (the user turned them on and they're downloaded). */
    var on by mutableStateOf(false); private set
    /** The words to show, and when they last changed. */
    var caption by mutableStateOf(""); internal set
    var captionAt by mutableLongStateOf(0L); internal set

    /** Read by the audio tap on every buffer, so a plain volatile. */
    @Volatile var listening = false; private set

    /** About how much is downloaded, for the Settings row. */
    const val DOWNLOAD_MB = 55

    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val ENGINE_VERSION = "0.3.47"
    private val MODEL_URLS = listOf(
        "https://alphacephei.com/vosk/models/$MODEL_NAME.zip",
        // A copy can be attached to a GitHub release tagged "live-subtitles", in case their site moves.
        "https://github.com/markbrown3000/Tuner/releases/download/live-subtitles/$MODEL_NAME.zip",
    )
    // The speech engine for Android, straight from Maven Central (only this device's part is kept).
    private val ENGINE_URLS = listOf(
        "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/$ENGINE_VERSION/vosk-android-$ENGINE_VERSION.aar",
        "https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/$ENGINE_VERSION/vosk-android-$ENGINE_VERSION.aar",
        "https://github.com/markbrown3000/Tuner/releases/download/live-subtitles/vosk-android-$ENGINE_VERSION.aar",
    )

    private const val PREFS = "live_subtitles"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private lateinit var appContext: Context
    private var abi: String? = null

    internal fun dir(ctx: Context = appContext) = File(ctx.filesDir, "live_subtitles")
    internal fun modelDir(ctx: Context = appContext) = File(dir(ctx), "model")
    internal fun engineFile(ctx: Context = appContext) = File(dir(ctx), "libvosk.so")
    private fun readyMark(ctx: Context) = File(dir(ctx), "ready")

    fun init(context: Context) {
        appContext = context.applicationContext
        val (ok, reason) = runCatching { checkDevice(context) }.getOrDefault(false to "couldn't check this device")
        status = when {
            !ok -> Status.NotAvailable(reason)
            readyMark(context).exists() -> Status.Ready
            else -> Status.NotDownloaded
        }
        on = status == Status.Ready && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
        listening = on
    }

    val supported: Boolean get() = status !is Status.NotAvailable
    val ready: Boolean get() = status == Status.Ready

    /** Whether live subtitles can run here, and why not. 64-bit, 4+ cores and 2 GB or more. */
    private fun checkDevice(context: Context): Pair<Boolean, String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
        val is64 = android.os.Process.is64Bit() && abi != null
        val reason = whyNot(is64, Lite.lowMemoryDevice, info.totalMem, Runtime.getRuntime().availableProcessors())
        return (reason == null) to (reason ?: "")
    }

    /** null = fine; otherwise the reason, in plain words. */
    fun whyNot(is64Bit: Boolean, lowMemory: Boolean, totalBytes: Long, cores: Int): String? = when {
        !is64Bit -> "it needs a newer, faster TV box"
        lowMemory || (totalBytes in 1 until 1_700_000_000L) -> "it needs at least 2 GB of memory"
        cores in 1..3 -> "it needs a faster processor"
        else -> null
    }

    /** Turns them on or off in the player (remembered). */
    fun setOn(context: Context, value: Boolean) {
        on = value && ready
        listening = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        if (!on) {
            caption = ""
            Recognizer.stop()
        }
    }

    /** The engine wouldn't start: stop trying until it's downloaded again. */
    internal fun engineFailed(message: String) {
        listening = false
        on = false
        caption = ""
        status = Status.Failed("the speech engine wouldn't start ($message)")
        runCatching { readyMark(appContext).delete() }
    }

    // --- Download -------------------------------------------------------------------------------

    fun download(context: Context, onDone: (String) -> Unit = {}) {
        if (!supported || ready || downloadJob?.isActive == true) return
        val ctx = context.applicationContext
        downloadJob = scope.launch {
            val tmp = File(ctx.cacheDir, "live_subtitles").apply { deleteRecursively(); mkdirs() }
            try {
                val base = dir(ctx).apply { deleteRecursively(); mkdirs() }
                val abi = abi ?: throw IOException("this device isn't supported")
                status = Status.Downloading(0, "speech engine")
                val aar = fetch(ENGINE_URLS, File(tmp, "engine.aar")) { p -> status = Status.Downloading(p / 5, "speech engine") }
                if (!unzip(aar, "jni/$abi/libvosk.so") { _ -> engineFile(ctx) }) throw IOException("the speech engine for $abi wasn't in the download")
                aar.delete()
                status = Status.Downloading(20, "English speech model")
                val zip = fetch(MODEL_URLS, File(tmp, "model.zip")) { p -> status = Status.Downloading(20 + p * 3 / 4, "English speech model") }
                status = Status.Downloading(95, "unpacking")
                val model = modelDir(ctx)
                val got = unzip(zip, null) { name ->
                    // "vosk-model-small-en-us-0.15/am/final.mdl" -> model/am/final.mdl
                    val rel = name.substringAfter('/', "")
                    if (rel.isEmpty() || rel.contains("..")) null else File(model, rel)
                }
                if (!got || !File(model, "am").exists()) throw IOException("the speech model download wasn't complete")
                zip.delete()
                readyMark(ctx).writeText("$MODEL_NAME $ENGINE_VERSION")
                status = Status.Ready
                AppLog.info("Live subtitles downloaded (" + (base.walkTopDown().sumOf { it.length() } / 1_000_000) + " MB)")
                setOn(ctx, true)
                onDone("💬 Live subtitles are ready – they're on in the player's Subtitles menu")
            } catch (e: Throwable) {
                AppLog.warn("Live subtitles download failed", e)
                dir(ctx).deleteRecursively()
                status = Status.Failed(e.message ?: "download failed")
                onDone("Live subtitles couldn't download – " + (e.message ?: "try again later"))
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    /** Takes it off the device again. */
    fun remove(context: Context) {
        setOn(context, false)
        downloadJob?.cancel()
        dir(context).deleteRecursively()
        if (supported) status = Status.NotDownloaded
    }

    /** Space used on the device, in MB. */
    fun sizeMb(context: Context): Long = dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000

    private fun fetch(urls: List<String>, to: File, progress: (Int) -> Unit): File {
        var last: Throwable? = null
        for (url in urls) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", Http.USER_AGENT)
                }
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} from ${URL(url).host}")
                val total = conn.contentLengthLong
                var got = 0L
                var shown = -1
                conn.inputStream.use { input ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val p = if (total > 0) (got * 100 / total).toInt() else 0
                            if (p != shown) { shown = p; progress(p) }
                        }
                    }
                }
                if (total > 0 && got < total) throw IOException("the download stopped early")
                return to
            } catch (e: Throwable) {
                last = e
                AppLog.warn("Live subtitles: couldn't get ${URL(url).host}", e)
            }
        }
        throw IOException(last?.message ?: "couldn't download")
    }

    /** Unzips [only] that entry (or every entry when null) to wherever [target] says. */
    private fun unzip(zip: File, only: String?, target: (String) -> File?): Boolean {
        var any = false
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || (only != null && e.name != only)) continue
                val f = target(e.name) ?: continue
                f.parentFile?.mkdirs()
                f.outputStream().use { z.copyTo(it) }
                any = true
            }
        }
        return any
    }

    /** From the audio tap. */
    fun hear(samples: ShortArray, rate: Int) = Recognizer.offer(samples, rate)

    /** Seek or new channel. */
    fun restart() = Recognizer.reset()

    // --- Showing the words ----------------------------------------------------------------------

    private val history = StringBuilder()

    /** A finished sentence: kept for the next few seconds' captions. */
    internal fun finished(text: String) {
        if (text.isBlank()) return
        if (history.isNotEmpty()) history.append(' ')
        history.append(text)
        if (history.length > 300) history.delete(0, history.length - 300)
        show(history.toString())
    }

    /** The words still being heard. */
    internal fun partial(text: String) {
        show(if (text.isBlank()) history.toString() else (history.toString() + " " + text).trim())
    }

    internal fun clear() {
        history.setLength(0)
        caption = ""
    }

    private fun show(all: String) {
        caption = tail(all, 90)
        captionAt = System.currentTimeMillis()
    }

    /** The last [max] characters, starting at a whole word. */
    fun tail(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.substring(t.length - max)
        val space = cut.indexOf(' ')
        return if (space in 0 until cut.length - 1) cut.substring(space + 1) else cut
    }
}

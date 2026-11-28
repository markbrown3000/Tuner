package com.titanejstudios.tuner.desktop

import com.titanejstudios.tuner.data.AppLog
import org.json.JSONObject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Up to four channels at once (multi-view). Each has its own little VLC player. */
class MultiPlayers(private val main: Player) {
    private val slots = arrayOfNulls<EmbeddedMediaPlayerComponent>(4)

    fun component(i: Int): EmbeddedMediaPlayerComponent? {
        if (!main.available) return null
        slots[i]?.let { return it }
        return runCatching {
            // Shares the main player's engine (making another one loads every plugin again).
            (main.factory?.let { EmbeddedMediaPlayerComponent(it, null, null, null, null) } ?: EmbeddedMediaPlayerComponent()).apply {
                isFocusable = false
                videoSurfaceComponent().isFocusable = false
            }
        }.onFailure { AppLog.warn("Multi-view: couldn't start player ${i + 1}", it) }.getOrNull()?.also { slots[i] = it }
    }

    fun play(i: Int, url: String, withSound: Boolean) {
        val p = component(i)?.mediaPlayer() ?: return
        p.media().play(url, ":network-caching=2000", ":http-reconnect")
        p.audio().isMute = !withSound
    }

    /** Only one has sound: the one you clicked. */
    fun soundFrom(i: Int) {
        for (k in slots.indices) slots[k]?.mediaPlayer()?.audio()?.isMute = k != i
    }

    fun stop(i: Int) {
        slots[i]?.mediaPlayer()?.controls()?.stop()
    }

    fun stopAll() {
        for (k in slots.indices) stop(k)
    }

    fun release() {
        for (k in slots.indices) runCatching { slots[k]?.release() }
    }
}

/** A programme to record (from the guide), and what happened to it. */
class PcRecording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    var file: String,
    /** "scheduled", "recording", "done" or "failed". */
    var state: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("c", channelId).put("cn", channelName).put("t", title)
        .put("s", start).put("e", stop).put("f", file).put("st", state)

    companion object {
        fun fromJson(o: JSONObject) = PcRecording(
            o.optString("id"), o.optString("c"), o.optString("cn"), o.optString("t"),
            o.optLong("s"), o.optLong("e"), o.optString("f"), o.optString("st", "scheduled"),
        )
    }
}

/**
 * Records live TV to a file with VLC (no picture on screen), so it handles every kind of stream
 * the player does. Recordings run while Flicker is open (it can be minimised).
 */
class Recorder(private val main: Player) {
    private var factory: MediaPlayerFactory? = null
    private val running = HashMap<String, MediaPlayer>()

    /** Videos\Flicker, or the folder chosen in Settings › Recordings (a network drive works). */
    var folder: File = File(System.getProperty("user.home"), "Videos" + File.separator + "Flicker").apply { mkdirs() }

    fun start(r: PcRecording, url: String): Boolean {
        if (!main.available) return false
        return runCatching {
            val f = main.factory ?: factory ?: MediaPlayerFactory("--no-video-title-show").also { factory = it }
            val p = f.mediaPlayers().newMediaPlayer()
            val dst = r.file.replace("\\", "/")
            p.media().play(url, ":sout=#std{access=file,mux=ts,dst=\"$dst\"}", ":sout-keep", ":network-caching=3000", ":http-reconnect")
            running[r.id] = p
            AppLog.info("Recording ${r.title} to ${r.file}")
            true
        }.onFailure { AppLog.warn("Couldn't start recording ${r.title}", it) }.getOrDefault(false)
    }

    fun stop(id: String) {
        running.remove(id)?.let { p ->
            runCatching {
                p.controls().stop()
                p.release()
            }
        }
    }

    fun isRunning(id: String) = id in running

    fun release() {
        running.keys.toList().forEach { stop(it) }
        runCatching { factory?.release() }
    }
}

/** New versions of Flicker for Windows from the GitHub releases (the same ones as the TV's). */
object Updater {
    const val REPO = "titanejstudios/Flicker"

    class Release(val version: String, val msiUrl: String, val notes: String?)

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    /** True when [a] is a newer version than [b]. */
    fun newer(a: String, b: String): Boolean {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** The latest release with a Windows installer, or null. Blocking. */
    fun latest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Flicker-PC")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".msi", ignoreCase = true)) {
                    return Release(o.optString("tag_name").removePrefix("v"), a.optString("browser_download_url"), o.optString("body").ifBlank { null })
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the installer ([progress] 0–1). Blocking. */
    fun download(r: Release, progress: (Float) -> Unit): File {
        val out = File(System.getProperty("java.io.tmpdir"), "Flicker-${r.version}.msi")
        var url = r.msiUrl
        // GitHub sends downloads on to another address.
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Flicker-PC")
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: throw IllegalStateException("GitHub didn't say where the download is")
                c.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("GitHub said $code")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        if (total > 0) progress(got.toFloat() / total)
                    }
                }
            }
            c.disconnect()
            return out
        }
        throw IllegalStateException("Too many redirects")
    }

    /**
     * Starts the installer (it replaces this version) – Flicker then closes, and a little script
     * opens the new Flicker once the installer has finished.
     */
    fun install(file: File) {
        val exe = System.getProperty("jpackage.app-path")
        val script = File(System.getProperty("java.io.tmpdir"), "flicker-update.cmd")
        script.writeText(
            listOfNotNull(
                "@echo off",
                // Give Flicker a moment to close first, so its files can be replaced.
                "timeout /t 3 /nobreak >nul",
                "start \"\" /wait msiexec /i \"${file.absolutePath}\" /passive /norestart",
                exe?.takeIf { it.isNotBlank() }?.let { "start \"\" \"$it\"" },
                "del \"%~f0\"",
            ).joinToString("\r\n"),
        )
        // "start" takes the window's title first, in quotes.
        ProcessBuilder("cmd", "/c", "start", "\"Flicker update\"", "/min", "cmd", "/c", script.absolutePath).start()
    }
}

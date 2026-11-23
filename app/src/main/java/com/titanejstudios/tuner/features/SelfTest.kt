package com.titanejstudios.tuner.features

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.view.Display
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.Football
import com.titanejstudios.tuner.data.HealthCheck
import com.titanejstudios.tuner.data.Http
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.SafeMode
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.integrations.HomeAssistantWebhook
import com.titanejstudios.tuner.integrations.Plex
import com.titanejstudios.tuner.playback.NightMode
import com.titanejstudios.tuner.recording.RecordingTidy
import com.titanejstudios.tuner.tv.LiveChannelSync
import com.titanejstudios.tuner.tv.TunerDream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * Settings › Help › Check everything: tries each part of Tuner on this TV and says, in plain
 * English, what works and what doesn't. Runs off the main thread; each check is independent and
 * a failure in one never stops the rest.
 */
class SelfTest(private val context: Context, private val store: Store) {

    enum class State { Pass, Warn, Fail, Info }

    class Result(val name: String, val state: State, val detail: String)

    /** What the view model knows right now (taken on the main thread before the checks start). */
    class Snapshot(
        val sources: List<Source>,
        val channels: List<Channel>,
        val epg: Map<String, List<Programme>>,
        val favourites: List<String>,
        val recordingDir: String,
        val webServerRunning: Boolean,
        val remoteUrl: String?,
        val webhookUrl: String?,
        val liveTabScope: String,
        val nightModeOn: Boolean,
        val frameRateMatch: Boolean,
        val lipSyncSet: Boolean,
    )

    /** Every check, in order. [onResult] is called (on the calling thread) after each one. */
    fun runAll(snap: Snapshot, onResult: (Result) -> Unit) {
        val checks: List<Pair<String, () -> Result>> = listOf(
            "Safe mode" to { safeMode() },
            "Playlists" to { playlists(snap) },
            "Channels" to { channels(snap) },
            "TV guide" to { guide(snap) },
            "Test stream" to { stream(snap) },
            "Recording storage" to { storage(snap) },
            "Recording timer" to { alarms() },
            "Night mode sound" to { nightMode(snap) },
            "Frame-rate matching" to { frameRate(snap) },
            "Lip sync" to { lipSync(snap) },
            "TV's Live tab" to { liveTab(snap) },
            "Screensaver" to { screensaver() },
            "Voice search" to { voice() },
            "Phone remote" to { webServer(snap) },
            "Home Assistant" to { homeAssistant(snap) },
            "Network drive" to { nas() },
            "Plex" to { plex() },
            "Football scores" to { football() },
            "App updates" to { updates() },
            "Film info (TMDB)" to { tmdb() },
            "Subtitles online" to { subtitles() },
            "Keep TVs in sync" to { sync() },
        )
        for ((name, test) in checks) {
            val r = try {
                test()
            } catch (e: Throwable) {
                Result(name, State.Fail, "The check itself failed: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}")
            }
            onResult(r)
        }
    }

    // ---------------- Individual checks ----------------

    private fun safeMode(): Result {
        val crash = SafeMode.lastCrash
        return when {
            SafeMode.active -> Result("Safe mode", State.Warn, "ON – extras are switched off after crashes." + (crash?.let { " Last crash: $it" } ?: ""))
            crash != null -> Result("Safe mode", State.Info, "Off. Last crash (${ago(SafeMode.lastCrashAt)}): $crash")
            else -> Result("Safe mode", State.Pass, "Off – no crashes recorded")
        }
    }

    private fun playlists(snap: Snapshot): Result {
        if (snap.sources.isEmpty()) return Result("Playlists", State.Fail, "No playlist added – Settings › Add a playlist")
        val lines = ArrayList<String>()
        var failed = 0
        for (s in snap.sources) {
            val msg = try {
                when (s) {
                    is Source.Xtream -> XtreamClient.accountSummary(s)
                    is Source.M3u -> Http.open(s.playlistUrl.trim()).use { input ->
                        val head = ByteArray(7)
                        val n = input.read(head)
                        if (n > 0 && String(head, 0, n, Charsets.US_ASCII).startsWith("#EXTM3U")) "Playlist downloads OK"
                        else "Downloaded, but it doesn't look like an M3U playlist"
                    }
                }
            } catch (e: Exception) {
                failed++
                "FAILED – ${e.message ?: e.javaClass.simpleName}"
            }
            lines += "${s.name}: $msg"
        }
        val state = when {
            failed == snap.sources.size -> State.Fail
            failed > 0 -> State.Warn
            else -> State.Pass
        }
        return Result("Playlists", state, lines.joinToString("\n"))
    }

    private fun channels(snap: Snapshot): Result {
        val db = Database.get(context)
        val saved = db.channelCount()
        val progs = db.programmeCount()
        val size = File(context.getDatabasePath("tuner.db").path).length()
        val detail = "${snap.channels.size} channels showing · $saved saved · $progs programmes · database ${mb(size)}"
        return Result("Channels", if (snap.channels.isEmpty()) State.Fail else State.Pass, detail)
    }

    private fun guide(snap: Snapshot): Result {
        if (snap.channels.isEmpty()) return Result("TV guide", State.Fail, "No channels to check")
        val now = System.currentTimeMillis()
        val withNow = snap.channels.count { c -> snap.epg[c.id]?.any { it.start <= now && it.stop > now } == true }
        val pct = withNow * 100 / snap.channels.size
        val latestEnd = snap.epg.values.mapNotNull { list -> list.lastOrNull()?.stop }.maxOrNull() ?: 0L
        val ahead = if (latestEnd > now) (latestEnd - now) / 3_600_000L else 0L
        val ages = snap.sources.map { s ->
            val t = store.lastRefresh(s.id)
            "${s.name} updated ${if (t == 0L) "never" else ago(t)}"
        }
        val oldest = snap.sources.minOfOrNull { store.lastRefresh(it.id) } ?: 0L
        val state = when {
            withNow == 0 -> State.Fail
            pct < 50 || now - oldest > 36 * 3_600_000L -> State.Warn
            else -> State.Pass
        }
        val tip = if (pct < 50) "\nTip: channels without a guide can be matched by hand – hold OK on a channel › Fix TV guide match." else ""
        return Result("TV guide", state, "$pct% of channels have something on now · guide runs $ahead hours ahead\n" + ages.joinToString("\n") + tip)
    }

    private fun stream(snap: Snapshot): Result {
        val byId = snap.channels.associateBy { it.id }
        val target = snap.favourites.firstNotNullOfOrNull { byId[it] } ?: snap.channels.firstOrNull()
            ?: return Result("Test stream", State.Fail, "No channels to try")
        val t0 = System.currentTimeMillis()
        val ok = HealthCheck.isAlive(target.url)
        val ms = System.currentTimeMillis() - t0
        return if (ok) Result("Test stream", State.Pass, "${target.name} answered in ${String.format(Locale.UK, "%.1f", ms / 1000.0)} s")
        else Result("Test stream", State.Fail, "${target.name} didn't send any video. The provider may be down, or the account is already in use on another device.")
    }

    private fun storage(snap: Snapshot): Result {
        val dir = File(snap.recordingDir)
        dir.mkdirs()
        val probe = File(dir, ".tuner-write-test")
        val writable = try {
            probe.writeText("ok")
            probe.delete()
            true
        } catch (e: Exception) {
            false
        }
        val free = try {
            StatFs(dir.path).availableBytes
        } catch (e: Exception) {
            0L
        }
        return when {
            !writable -> Result("Recording storage", State.Fail, "Can't write to ${dir.path}. Pick another place in Recordings › Storage.")
            free < 2_000_000_000L -> Result("Recording storage", State.Warn, "Only ${mb(free)} free – an hour of HD is about 2–3 GB")
            else -> Result("Recording storage", State.Pass, "${mb(free)} free")
        }
    }

    private fun alarms(): Result {
        if (Build.VERSION.SDK_INT < 31) return Result("Recording timer", State.Pass, "Recordings can wake Tuner on time")
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (am.canScheduleExactAlarms()) Result("Recording timer", State.Pass, "Recordings can wake Tuner on time")
        else Result(
            "Recording timer", State.Fail,
            "Android isn't letting Tuner set exact alarms, so recordings may start late. " +
                "Settings › Apps › Special app access › Alarms & reminders › Tuner › Allow.",
        )
    }

    private fun nightMode(snap: Snapshot): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Result("Night mode sound", State.Warn, "Needs Android 9 or newer")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val session = am.generateAudioSessionId()
        val nm = NightMode()
        nm.apply(session, true)
        val ok = nm.supported
        nm.release()
        val onOff = if (snap.nightModeOn) "On" else "Off"
        return if (ok) Result("Night mode sound", State.Pass, "Works on this TV ($onOff). Only when Audio passthrough is off.")
        else Result("Night mode sound", State.Fail, "This TV refused the sound compressor – leave Night mode off.")
    }

    private fun frameRate(snap: Snapshot): Result {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return Result("Frame-rate matching", State.Warn, "Couldn't read the screen's modes")
        val rates = display.supportedModes.map { Math.round(it.refreshRate * 100) / 100f }.distinct().sorted()
        val list = rates.joinToString(", ") { if (it % 1f == 0f) it.toInt().toString() else String.format(Locale.UK, "%.2f", it) } + " Hz"
        val onOff = if (snap.frameRateMatch) "On" else "Off"
        return if (rates.size > 1) Result("Frame-rate matching", State.Pass, "TV offers $list ($onOff)")
        else Result("Frame-rate matching", State.Warn, "TV only offers $list, so matching can't change anything – leave it off")
    }

    private fun lipSync(snap: Snapshot): Result =
        Result(
            "Lip sync", State.Info,
            (if (snap.lipSyncSet) "Adjusted on some channels. " else "Not adjusted. ") +
                "This can only be checked by eye – watch someone talking and use the player menu › Lip sync.",
        )

    private fun liveTab(snap: Snapshot): Result {
        if (snap.liveTabScope == "off") return Result("TV's Live tab", State.Info, "Turned off (Settings › Google TV)")
        val inputId = LiveChannelSync.inputId(context)
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val listed = tim?.tvInputList?.any { it.id == inputId } == true
        val count = try {
            context.contentResolver.query(TvContract.buildChannelsUriForInput(inputId), arrayOf(TvContract.Channels._ID), null, null, null)
                ?.use { it.count } ?: 0
        } catch (e: Exception) {
            -1
        }
        return when {
            !listed -> Result("TV's Live tab", State.Warn, "The TV doesn't list Tuner as a TV source. Some Google TV models hide other apps' channels.")
            count <= 0 -> Result("TV's Live tab", State.Warn, "Tuner is a TV source, but no channels have been copied yet – they're added 20 seconds after a refresh.")
            else -> Result("TV's Live tab", State.Pass, "$count channels in the TV's own list")
        }
    }

    private fun screensaver(): Result {
        val current = Settings.Secure.getString(context.contentResolver, "screensaver_components").orEmpty()
        val ours = ComponentName(context, TunerDream::class.java).flattenToString()
        return if (current.contains(context.packageName)) Result("Screensaver", State.Pass, "Tuner is the screensaver")
        else Result(
            "Screensaver", State.Info,
            "Not selected. Google TV only allows it from a computer:\nadb shell settings put secure screensaver_components $ours",
        )
    }

    private fun voice(): Result {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        return if (found) Result("Voice search", State.Info, "Tuner accepts \"play … on Tuner\". Google decides whether to send it here.")
        else Result("Voice search", State.Warn, "Tuner isn't registered for voice requests")
    }

    private fun webServer(snap: Snapshot): Result {
        if (!snap.webServerRunning) return Result("Phone remote", State.Info, "Off (Settings › Phone remote)")
        val ok = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8765), 1_500) }
            true
        } catch (e: Exception) {
            false
        }
        return if (ok) Result("Phone remote", State.Pass, snap.remoteUrl ?: "Running")
        else Result("Phone remote", State.Fail, "Turned on, but nothing is answering on port 8765")
    }

    private fun homeAssistant(snap: Snapshot): Result {
        val url = snap.webhookUrl ?: return Result("Home Assistant", State.Info, "No webhook set")
        return try {
            val code = HomeAssistantWebhook.test(url)
            if (code in 200..299) Result("Home Assistant", State.Pass, "Webhook answered (a \"test\" event was sent)")
            else Result("Home Assistant", State.Fail, "Home Assistant answered HTTP $code – check the webhook address")
        } catch (e: Exception) {
            Result("Home Assistant", State.Fail, "Couldn't reach it: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun nas(): Result {
        if (store.nasUrl.isNullOrBlank()) return Result("Network drive", State.Info, "Not set")
        val problem = RecordingTidy.test(store)
        return if (problem == null) Result("Network drive", State.Pass, "Connected and writable")
        else Result("Network drive", State.Fail, problem)
    }

    private fun plex(): Result {
        val token = store.plexToken ?: return Result("Plex", State.Info, "Not linked")
        val servers = Plex(store.plexClientId).servers(token)
        return if (servers.isEmpty()) Result("Plex", State.Warn, "Linked, but no Plex server answered on this network")
        else Result("Plex", State.Pass, "Found " + servers.joinToString { it.name })
    }

    private fun football(): Result {
        val key = store.footballKey ?: return Result("Football scores", State.Info, "No football-data.org key set")
        val n = Football.today(key).size
        return Result("Football scores", State.Pass, "Key works · $n matches today")
    }

    private fun updates(): Result {
        val canInstall = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
        val allow = if (canInstall) "Tuner may install updates." else "Android will ask once to let Tuner install updates – say yes."
        val repo = store.updateRepo ?: return Result("App updates", State.Info, "$allow No GitHub repo set (uploading from a computer still works).")
        val rel = com.titanejstudios.tuner.apps.Updater.latest(repo)
        return if (rel == null) Result("App updates", State.Warn, "$allow No release with an APK in $repo")
        else Result("App updates", State.Pass, "$allow Latest on GitHub: ${rel.version}")
    }

    private fun tmdb(): Result {
        val key = store.tmdbKey ?: return Result("Film info (TMDB)", State.Info, "No key set (Settings › Films & series)")
        val info = com.titanejstudios.tuner.integrations.Tmdb.lookup(key, "The Matrix (1999)", isSeries = false)
        return if (info != null) Result("Film info (TMDB)", State.Pass, "Key works (found ${info.title})")
        else Result("Film info (TMDB)", State.Warn, "Key accepted, but a test search found nothing")
    }

    private fun subtitles(): Result {
        val key = store.openSubtitlesKey ?: return Result("Subtitles online", State.Info, "No key set (Settings › Films & series)")
        val hits = com.titanejstudios.tuner.integrations.OpenSubtitles.search(key, "The Matrix", "en", 1999, null, null)
        return Result("Subtitles online", State.Pass, "Key works (${hits.size} results for a test search; downloads aren't used)")
    }

    private fun sync(): Result {
        if (!store.syncEnabled) return Result("Keep TVs in sync", State.Info, "Off")
        return Result("Keep TVs in sync", State.Pass, com.titanejstudios.tuner.integrations.TvSync(store).check())
    }

    // ---------------- Helpers ----------------

    private fun mb(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.UK, "%.1f GB", bytes / 1e9)
        else -> String.format(Locale.UK, "%.0f MB", bytes / 1e6)
    }

    private fun ago(t: Long): String {
        val mins = (System.currentTimeMillis() - t) / 60_000L
        return when {
            mins < 2 -> "just now"
            mins < 90 -> "$mins minutes ago"
            mins < 48 * 60 -> "${mins / 60} hours ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }
}

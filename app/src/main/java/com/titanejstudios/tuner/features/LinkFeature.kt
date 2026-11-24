package com.titanejstudios.tuner.features

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.LinkServer
import com.titanejstudios.tuner.integrations.LinkSync
import com.titanejstudios.tuner.integrations.TvShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Linked devices: the TVs and phones in the house that know each other. Lets you send what's
 * playing to another device and carry on a film or episode where you left off on another one.
 */
class LinkFeature(
    private val context: Context,
    private val store: Store,
    private val scope: CoroutineScope,
    private val phoneMode: () -> Boolean,
    private val onPlay: (from: String, json: JSONObject) -> Boolean,
    private val onSynced: (Set<String>) -> Unit,
    /** This device's remote controls, for a linked phone to use. */
    override val remote: com.titanejstudios.tuner.integrations.RemoteHandler? = null,
) : LinkServer.Host {

    class Peer(val id: String, val name: String, val phone: Boolean, val host: String, val port: Int)

    var linked by mutableStateOf(store.linkKey != null); private set
    /** Linked devices seen on the Wi-Fi just now. */
    var peers by mutableStateOf<List<Peer>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    /** The 4-digit code while this device is waiting to be linked. */
    var codeShown by mutableStateOf<String?>(null); private set
    /** Devices showing a code, while this one is looking for them. */
    var codeDevices by mutableStateOf<List<TvShare.Found>>(emptyList()); private set
    var status by mutableStateOf<String?>(null); private set
    var lastSync by mutableStateOf(0L); private set
    var name by mutableStateOf(store.linkName.ifBlank { defaultName() }); private set

    private val server = LinkServer(this)
    private val network = TvShare(context, LinkServer.SERVICE_TYPE)
    private val pairing = TvShare(context, PAIR_TYPE)
    private var syncJob: Job? = null
    private var searchJob: Job? = null
    private var advertisedPort = 0

    // ---- What other devices ask of this one ----
    override val key: String? get() = store.linkKey
    override val deviceId: String get() = store.linkDeviceId
    override val deviceName: String get() = name
    override val isPhone: Boolean get() = phoneMode()
    override val pairCode: String? get() = codeShown

    override fun keyForPairing(): String {
        val k = store.linkKey ?: newKey().also { store.linkKey = it }
        if (!linked) {
            linked = true
            start()
        }
        status = "✓ Linked – the other device can now see this one"
        return k
    }

    override fun stateJson(): String = LinkSync.snapshot(store).put("id", deviceId).put("name", name).toString()

    override fun play(from: String, json: JSONObject): Boolean = onPlay(from, json)

    override fun poked() = syncSoon(500L)

    // ---- Running ----

    /** Starts answering other devices and announcing this one. Safe to call again. */
    fun start() {
        if (!linked) return
        runCatching {
            val port = server.start()
            if (advertisedPort != port) {
                network.advertise(deviceId, port)
                advertisedPort = port
            }
        }.onFailure { AppLog.warn("Couldn't start linked devices", it) }
    }

    /** After settings were restored or copied from another TV (which brings its link key along). */
    fun reload() {
        linked = store.linkKey != null
        name = store.linkName.ifBlank { defaultName() }
        if (linked) {
            start()
            syncSoon(1_000L)
        }
    }

    fun stop() {
        advertisedPort = 0
        network.stop()
        pairing.stop()
        server.stop()
    }

    /** Looks for linked devices on the Wi-Fi for a few seconds. */
    fun search(then: (() -> Unit)? = null) {
        val k = store.linkKey ?: return
        start()
        searchJob?.cancel()
        searching = true
        val found = LinkedHashMap<String, Peer>()
        network.discover { f ->
            if (f.name == deviceId) return@discover
            scope.launch {
                val info = withContext(Dispatchers.IO) { LinkServer.hello(f.host, f.port, k) } ?: return@launch
                val id = info.optString("id")
                if (id.isEmpty() || id == deviceId) return@launch
                found[id] = Peer(id, info.optString("name", f.name), info.optBoolean("phone"), f.host, f.port)
                peers = found.values.sortedBy { it.name.lowercase() }
            }
        }
        searchJob = scope.launch {
            delay(5_000)
            network.stopDiscovery()
            searching = false
            peers = found.values.sortedBy { it.name.lowercase() }
            then?.invoke()
        }
    }

    // ---- Carry on where you left off ----

    /** Pulls lists and resume points from every linked device in a moment. */
    fun syncSoon(delayMs: Long = 3_000L) {
        if (!linked) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            if (peers.isEmpty()) {
                search { scope.launch { pullAll() } }
            } else pullAll()
        }
    }

    private suspend fun pullAll() {
        val k = store.linkKey ?: return
        val changed = HashSet<String>()
        for (p in peers) {
            val r = withContext(Dispatchers.IO) { runCatching { LinkSync.apply(store, LinkServer.state(p.host, p.port, k)) } }
            r.onSuccess { changed += it }.onFailure { AppLog.warn("Couldn't reach ${p.name}", it) }
        }
        lastSync = System.currentTimeMillis()
        if (changed.isNotEmpty()) onSynced(changed)
    }

    /** Something was just stopped here: tell the others to pull it. */
    fun tellOthers() {
        val k = store.linkKey ?: return
        val list = peers
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) { list.forEach { LinkServer.poke(it.host, it.port, k) } }
    }

    // ---- Send to TV ----

    /** Sends [what] to [peer]; [onDone] gets null when it's playing there, or what went wrong. */
    fun send(peer: Peer, what: JSONObject, onDone: (String?) -> Unit) {
        val k = store.linkKey ?: return onDone("This device isn't linked")
        scope.launch {
            val status = withContext(Dispatchers.IO) { runCatching { LinkServer.sendPlay(peer.host, peer.port, k, name, what) }.getOrDefault(-1) }
            onDone(
                when (status) {
                    200 -> null
                    409 -> "${peer.name} couldn't find that on its own channel list"
                    403 -> "${peer.name} isn't linked any more – link them again in Settings › Linked devices"
                    else -> "${peer.name} didn't answer – is Flicker open on it?"
                }
            )
        }
    }

    // ---- The phone remote (controlling a linked TV) ----

    /** The TVs among the linked devices found. */
    val tvs: List<Peer> get() = peers.filter { !it.phone }

    fun remoteKey(tv: Peer, code: Int) {
        val k = store.linkKey ?: return
        scope.launch(Dispatchers.IO) { LinkServer.remoteKey(tv.host, tv.port, k, code) }
    }

    fun remotePlay(tv: Peer, channelId: String) {
        val k = store.linkKey ?: return
        scope.launch(Dispatchers.IO) { LinkServer.remotePlay(tv.host, tv.port, k, channelId) }
    }

    suspend fun remoteState(tv: Peer): JSONObject? {
        val k = store.linkKey ?: return null
        return withContext(Dispatchers.IO) { LinkServer.remoteState(tv.host, tv.port, k) }
    }

    suspend fun remoteChannels(tv: Peer): org.json.JSONArray? {
        val k = store.linkKey ?: return null
        return withContext(Dispatchers.IO) { LinkServer.remoteChannels(tv.host, tv.port, k) }
    }

    // ---- Pairing ----

    /** "Show a code here": another device types it in to link with this one. */
    fun showCode() {
        stopPairing()
        val code = (SecureRandom().nextInt(9000) + 1000).toString()
        codeShown = code
        status = null
        server.resetPairing()
        runCatching {
            val port = server.start()
            pairing.advertise(name, port)
        }.onFailure { status = "Couldn't start: ${it.message}" }
    }

    /** "Enter a code": find the devices showing one. */
    fun findCodes() {
        stopPairing()
        status = "Looking for devices showing a code…"
        val list = LinkedHashMap<String, TvShare.Found>()
        pairing.discover { f ->
            list[f.host + ":" + f.port] = f
            codeDevices = list.values.toList()
            status = null
        }
    }

    fun enterCode(device: TvShare.Found, code: String, onDone: (Boolean) -> Unit) {
        status = "Linking…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LinkServer.pair(device.host, device.port, code) } }
            r.onSuccess { (k, other) ->
                store.linkKey = k
                linked = true
                stopPairing()
                status = "✓ Linked with ${other.ifBlank { device.name }}"
                start()
                search { syncSoon(500L) }
                onDone(true)
            }.onFailure {
                status = it.message ?: "Couldn't link"
                onDone(false)
            }
        }
    }

    fun stopPairing() {
        codeShown = null
        codeDevices = emptyList()
        pairing.stop()
    }

    fun rename(newName: String) {
        val n = newName.trim().take(40)
        store.linkName = n
        name = n.ifBlank { defaultName() }
    }

    /** Stops this device being linked (it gets a new key next time). */
    fun unlink() {
        stop()
        store.linkKey = null
        linked = false
        peers = emptyList()
        status = "Not linked"
    }

    private fun defaultName(): String {
        val own = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return own?.takeIf { it.isNotBlank() } ?: (if (phoneMode()) "${Build.MODEL}" else "TV")
    }

    private fun newKey(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        const val PAIR_TYPE = "_flickerpair._tcp."
    }
}

package com.titanejstudios.tuner.features

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tuner's own VPN, using WireGuard config files from your VPN provider (Surfshark, ProtonVPN,
 * Mullvad…). Only Tuner's traffic goes through it, and never your home network (so Plex on
 * your own server stays direct and fast). The rest of the TV is untouched.
 */
class VpnFeature(private val context: Context, private val store: Store, private val scope: CoroutineScope) {
    class Server(val name: String, val text: String) {
        /** "uk-lon.prod.surfshark.com" from the config's Endpoint line. */
        val endpoint: String? get() = Regex("(?im)^\\s*Endpoint\\s*=\\s*([^:\\s]+)").find(text)?.groupValues?.get(1)
    }

    var servers by mutableStateOf(load()); private set
    var active by mutableStateOf(store.vpnActive); private set
    var enabled by mutableStateOf(store.vpnEnabled); private set
    var killSwitch by mutableStateOf(store.vpnKillSwitch); private set
    var connected by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    /** Android's "allow Tuner to set up a VPN" screen, waiting to be shown (the activity shows it). */
    var permissionIntent by mutableStateOf<Intent?>(null); private set

    private val backend by lazy { GoBackend(context.applicationContext) }
    private val tunnel = object : Tunnel {
        override fun getName() = "tuner"
        override fun onStateChange(newState: Tunnel.State) {
            scope.launch { connected = newState == Tunnel.State.UP }
        }
    }

    private fun load(): List<Server> = try {
        val arr = JSONArray(store.vpnServers ?: "[]")
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Server(o.getString("name"), o.getString("text")) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("name", it.name).put("text", it.text)) }
        store.vpnServers = arr.toString()
    }

    /** Adds a WireGuard config (from the phone page). Returns an error to show, or null. */
    fun add(name: String, text: String): String? {
        val clean = text.replace("\r", "").trim()
        if (!clean.contains("[Interface]", ignoreCase = true) || !clean.contains("[Peer]", ignoreCase = true)) {
            return "That isn't a WireGuard config file – it should have [Interface] and [Peer] sections"
        }
        if (!Regex("(?im)^\\s*PrivateKey\\s*=\\s*\\S+").containsMatchIn(clean)) {
            return "The file has no PrivateKey. On Surfshark, create a key pair first (Manual setup › WireGuard › I don't have a key pair), then download the location again"
        }
        runCatching { parse(clean) }.onFailure { return "Couldn't read the file: ${it.message}" }
        val server = Server(name.trim().ifEmpty { Server(name, clean).endpoint ?: "VPN ${servers.size + 1}" }, clean)
        servers = servers.filter { it.name != server.name } + server
        save()
        if (active == null) choose(server.name)
        return null
    }

    fun remove(name: String) {
        servers = servers.filter { it.name != name }
        save()
        if (active == name) {
            choose(servers.firstOrNull()?.name)
            if (servers.isEmpty()) turnOff()
        }
    }

    fun choose(name: String?) {
        active = name
        store.vpnActive = name
        if (enabled && name != null) connect()
    }

    fun toggleKillSwitch() {
        killSwitch = !killSwitch
        store.vpnKillSwitch = killSwitch
    }

    fun turnOn() {
        enabled = true
        store.vpnEnabled = true
        connect()
    }

    fun turnOff() {
        enabled = false
        store.vpnEnabled = false
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
            connected = false
            status = "Off"
        }
    }

    /** After Android's "allow VPN" screen. */
    fun permissionResult(granted: Boolean) {
        permissionIntent = null
        if (granted) connect() else {
            status = "Android needs your OK to run the VPN – press Connect to ask again"
        }
    }

    /** Connects with the chosen server (asks Android's permission first, once). */
    fun connect() {
        val server = servers.firstOrNull { it.name == active } ?: servers.firstOrNull()
        if (server == null) {
            status = "Add a server first – from your phone (Settings › Set up from your phone)"
            return
        }
        val ask = VpnService.prepare(context)
        if (ask != null) {
            permissionIntent = ask
            return
        }
        status = "Connecting to ${server.name}…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { backend.setState(tunnel, Tunnel.State.UP, parse(server.text)) }
            }
            result.onSuccess {
                connected = true
                status = "Connected · ${server.name}"
            }.onFailure {
                AppLog.warn("VPN: couldn't connect", it)
                connected = false
                status = "Couldn't connect: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    /** At start-up: reconnect if it was on (without asking – if Android needs asking, it waits for Settings). */
    fun autoStart() {
        if (enabled && servers.isNotEmpty() && VpnService.prepare(context) == null) connect()
    }

    /**
     * The provider's file, changed so only Tuner uses the tunnel, and home-network addresses
     * (192.168…, 10…, 172.16…) stay outside it.
     */
    private fun parse(text: String): Config {
        val lines = text.lines().toMutableList()
        val iface = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        lines.removeAll { it.trim().startsWith("IncludedApplications", ignoreCase = true) || it.trim().startsWith("ExcludedApplications", ignoreCase = true) }
        if (iface >= 0) lines.add(iface + 1, "IncludedApplications = ${context.packageName}")
        val fixed = lines.map { l ->
            val m = Regex("(?i)^\\s*AllowedIPs\\s*=\\s*(.*)$").find(l) ?: return@map l
            val ips = m.groupValues[1].split(',').map { it.trim() }
            if ("0.0.0.0/0" in ips) "AllowedIPs = " + (PUBLIC_V4 + ips.filter { it != "0.0.0.0/0" }).joinToString(", ") else l
        }
        return Config.parse(fixed.joinToString("\n").byteInputStream())
    }

    companion object {
        /** Every IPv4 address except home networks (the WireGuard app's "Exclude private IPs"). */
        private val PUBLIC_V4 = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2",
            "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10",
            "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
            "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12",
            "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4",
        )
    }
}

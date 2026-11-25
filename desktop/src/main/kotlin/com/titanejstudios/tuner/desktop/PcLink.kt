package com.titanejstudios.tuner.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.titanejstudios.tuner.data.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * The PC as one of the house's linked devices: the phone's remote and "Watch on…" find it on
 * the Wi-Fi, and the PC can send what it's playing to the TV. The household key comes from your
 * TV through the Google Drive sync, so there's nothing to pair.
 */
class PcLink(private val config: Config) {
    /** What the PC does when asked (called on the UI thread). */
    interface Host {
        fun deviceName(): String
        fun remoteState(): JSONObject
        fun remoteChannels(): JSONArray
        fun remoteKey(code: Int)
        fun remotePlay(channelId: String)
        fun play(from: String, json: JSONObject): Boolean
        fun stateJson(): JSONObject
    }

    class Peer(val id: String, val name: String, val phone: Boolean, val host: String, val port: Int)

    private var server: HttpServer? = null
    private var dns: JmDNS? = null
    var host: Host? = null
    val deviceId: String get() = "pc-" + config.deviceId
    val key: String? get() = config.linkKey

    /** Starts listening and tells the network (once there's a household key). */
    @Synchronized
    fun start() {
        val k = key ?: return
        if (server != null || k.isBlank()) return
        runCatching {
            val s = HttpServer.create(InetSocketAddress(0), 0)
            s.executor = Executors.newFixedThreadPool(3)
            s.createContext("/link/") { ex -> handle(ex) }
            s.start()
            server = s
            val addr = lanAddress()
            val d = JmDNS.create(addr, "flicker-pc")
            d.registerService(ServiceInfo.create("_flickerlink._tcp.local.", deviceId, s.address.port, "Flicker PC"))
            dns = d
            AppLog.info("Linked devices: listening on ${addr?.hostAddress}:${s.address.port}")
        }.onFailure { AppLog.warn("Linked devices: couldn't start", it) }
    }

    fun stop() {
        runCatching { dns?.unregisterAllServices(); dns?.close() }
        runCatching { server?.stop(0) }
        dns = null
        server = null
    }

    /** The house Wi-Fi address (not a VPN or virtual adapter). */
    private fun lanAddress(): InetAddress? {
        val all = NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .filterNot { n -> listOf("virtual", "vmware", "hyper-v", "vpn", "wireguard", "tap", "vethernet").any { n.displayName.lowercase().contains(it) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
        return all.firstOrNull { it.hostAddress.startsWith("192.168.") } ?: all.firstOrNull() ?: runCatching { InetAddress.getLocalHost() }.getOrNull()
    }

    private fun handle(ex: HttpExchange) {
        try {
            val q = query(ex.requestURI.rawQuery)
            val h = host
            if (h == null || key == null || q["k"] != key) return respond(ex, 403, "{}")
            val body = if (ex.requestMethod == "POST") ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) } else ""
            when (ex.requestURI.path) {
                "/link/hello" -> respond(ex, 200, onUi { JSONObject().put("id", deviceId).put("name", h.deviceName()).put("phone", false) }.toString())
                "/link/state" -> respond(ex, 200, onUi { h.stateJson() }.toString())
                "/link/poke" -> respond(ex, 200, "{}")
                "/link/play" -> {
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return respond(ex, 400, "{}")
                    val ok = onUi { h.play(q["from"].orEmpty(), json) }
                    respond(ex, if (ok) 200 else 409, "{}")
                }
                "/link/remote/state" -> respond(ex, 200, onUi { h.remoteState() }.toString())
                "/link/remote/channels" -> respond(ex, 200, onUi { h.remoteChannels() }.toString())
                "/link/remote/key" -> {
                    q["c"]?.toIntOrNull()?.let { c -> javax.swing.SwingUtilities.invokeLater { h.remoteKey(c) } }
                    respond(ex, 200, "{}")
                }
                "/link/remote/play" -> {
                    q["id"]?.let { id -> javax.swing.SwingUtilities.invokeLater { h.remotePlay(id) } }
                    respond(ex, 200, "{}")
                }
                else -> respond(ex, 404, "{}")
            }
        } catch (e: Exception) {
            AppLog.warn("Linked devices: request failed", e)
            runCatching { respond(ex, 500, "{}") }
        }
    }

    /** Runs [block] on the UI thread and waits for the answer. */
    private fun <T> onUi(block: () -> T): T {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun respond(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ---------------- Finding the TV, and sending to it ----------------

    /** The TVs and phones on the Wi-Fi (a few seconds). Blocking. */
    fun findPeers(): List<Peer> {
        val k = key ?: return emptyList()
        val d = dns ?: return emptyList()
        val found = runCatching { d.list("_flickerlink._tcp.local.", 4_000) }.getOrDefault(emptyArray())
        return found.mapNotNull { info ->
            if (info.name == deviceId) return@mapNotNull null
            val h = info.inet4Addresses.firstOrNull()?.hostAddress ?: return@mapNotNull null
            val o = runCatching { JSONObject(call("http://$h:${info.port}/link/hello?k=${enc(k)}", null)) }.getOrNull() ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            if (id == deviceId) null else Peer(id, o.optString("name", info.name), o.optBoolean("phone"), h, info.port)
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** Sends something to play on [peer] (the same JSON as the TV's "Send to"). Blocking. */
    fun send(peer: Peer, what: JSONObject, from: String): Boolean {
        val k = key ?: return false
        return runCatching {
            call("http://${peer.host}:${peer.port}/link/play?k=${enc(k)}&from=${enc(from)}", what.toString())
            true
        }.getOrDefault(false)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3_000
            c.readTimeout = 8_000
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}

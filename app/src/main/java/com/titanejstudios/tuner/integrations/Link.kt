package com.titanejstudios.tuner.integrations

import android.os.Handler
import android.os.Looper
import com.titanejstudios.tuner.data.AppLog
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Linked devices: every Flicker in the house that shares one secret key can see the others on the
 * Wi-Fi, send what's playing to them ("Send to TV") and swap where you got to in films and episodes.
 *
 * Each device runs this tiny web server while Flicker is open and announces it on the network.
 * Every request carries the household key; pairing a new device hands the key over after the
 * 4-digit code shown on the other device has been typed in.
 */
class LinkServer(private val handler: Host) {

    /** What the server asks the app for. Called on the main thread. */
    interface Host {
        val key: String?
        val deviceId: String
        val deviceName: String
        val isPhone: Boolean
        /** The 4-digit code while "Link a device" is showing one here, else null. */
        val pairCode: String?
        /** Gives out the household key (making one first if this device has none yet). */
        fun keyForPairing(): String
        /** Favourites, My list, resume points… as JSON (see LinkSync). */
        fun stateJson(): String
        /** Another device sent something to play here. Returns false if it can't be played. */
        fun play(from: String, json: JSONObject): Boolean
        /** Another device just stopped watching something: pull its resume points now. */
        fun poked()
        /** The phone remote: this device's remote controls (channels, what's on, key presses). */
        val remote: RemoteHandler?
    }

    private var socket: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private var pairFailures = 0

    val port: Int get() = socket?.localPort ?: 0
    val isRunning: Boolean get() = socket?.isClosed == false

    fun start(): Int {
        if (isRunning) return port
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        socket = s
        thread(name = "link-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
        return s.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    fun resetPairing() {
        pairFailures = 0
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val req = read(input) ?: return
                val out = s.getOutputStream()
                when (req.path) {
                    "/link/pair" -> {
                        val code = onMain { handler.pairCode }
                        if (code != null && req.query["code"] == code && pairFailures < 5) {
                            val key = onMain { handler.keyForPairing() } ?: return respond(out, 500, "{}")
                            val name = onMain { handler.deviceName }.orEmpty()
                            respond(out, 200, JSONObject().put("key", key).put("name", name).toString())
                        } else {
                            pairFailures++
                            respond(out, 403, "{\"error\":\"Wrong code\"}")
                        }
                    }
                    "/link/state" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        respond(out, 200, onMain { handler.stateJson() } ?: "{}")
                    }
                    "/link/play" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        val json = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
                            ?: return respond(out, 400, "{}")
                        val ok = onMain { handler.play(req.query["from"].orEmpty(), json) } == true
                        respond(out, if (ok) 200 else 409, "{}")
                    }
                    "/link/poke" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        onMain { handler.poked() }
                        respond(out, 200, "{}")
                    }
                    "/link/remote/state" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        respond(out, 200, onMain { handler.remote?.stateJson() } ?: "{}")
                    }
                    "/link/remote/channels" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        respond(out, 200, onMain { handler.remote?.channelsJson() } ?: "[]")
                    }
                    "/link/remote/key" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        val code = req.query["c"]?.toIntOrNull()
                        if (code != null) main.post { handler.remote?.key(code) }
                        respond(out, 200, "{}")
                    }
                    "/link/remote/play" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        val id = req.query["id"]
                        if (id != null) main.post { handler.remote?.play(id) }
                        respond(out, 200, "{}")
                    }
                    "/link/hello" -> {
                        if (!allowed(req)) return respond(out, 403, "{}")
                        val info = onMain {
                            JSONObject().put("id", handler.deviceId).put("name", handler.deviceName).put("phone", handler.isPhone).toString()
                        }
                        respond(out, 200, info ?: "{}")
                    }
                    else -> respond(out, 404, "{}")
                }
            } catch (e: Exception) {
                AppLog.warn("Linked device request failed", e)
            }
        }
    }

    private fun allowed(req: Req): Boolean {
        val mine = onMain { handler.key } ?: return false
        return req.query["k"] == mine
    }

    private class Req(val path: String, val query: Map<String, String>, val body: ByteArray)

    private fun read(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (head.size() > 16_000) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val target = lines.first().split(" ").getOrNull(1) ?: return null
        var length = 0
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("Content-Length", true)) length = line.substring(i + 1).trim().toIntOrNull() ?: 0
        }
        if (length > 2_000_000) return null
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) break
            got += n
        }
        val query = if ('?' in target) target.substringAfter('?').split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        } else emptyMap()
        return Req(target.substringBefore('?'), query, body)
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error"
        }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun <T> onMain(block: () -> T): T? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        main.post {
            try {
                result = block()
            } catch (e: Exception) {
                AppLog.warn("Linked device request failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    companion object {
        const val SERVICE_TYPE = "_flickerlink._tcp."

        private fun call(url: String, method: String = "GET", body: String? = null, timeout: Int = 6_000): Pair<Int, String> {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout
                readTimeout = timeout
                requestMethod = method
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                return code to text
            } finally {
                conn.disconnect()
            }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Pairs with a device showing [code]: returns (household key, its name). Blocking. */
        fun pair(host: String, port: Int, code: String): Pair<String, String> {
            val (status, text) = call("http://$host:$port/link/pair?code=${enc(code)}")
            if (status == 403) throw IllegalArgumentException("Wrong code – check the number on the other device")
            if (status != 200) throw IllegalStateException("The other device said $status")
            val o = JSONObject(text)
            return o.getString("key") to o.optString("name")
        }

        /** The device's id, name and whether it's a phone, or null if it isn't one of ours. Blocking. */
        fun hello(host: String, port: Int, key: String): JSONObject? = runCatching {
            val (status, text) = call("http://$host:$port/link/hello?k=${enc(key)}", timeout = 3_000)
            if (status == 200) JSONObject(text) else null
        }.getOrNull()

        /** The other device's lists and resume points. Blocking; throws if it can't be reached. */
        fun state(host: String, port: Int, key: String): JSONObject {
            val (status, text) = call("http://$host:$port/link/state?k=${enc(key)}")
            if (status != 200) throw IllegalStateException("The other device said $status")
            return JSONObject(text)
        }

        /** Tells the other device to pull our resume points now. Blocking; failures are ignored. */
        fun poke(host: String, port: Int, key: String) {
            runCatching { call("http://$host:$port/link/poke?k=${enc(key)}", timeout = 3_000) }
        }

        // ---- The phone remote ----

        fun remoteKey(host: String, port: Int, key: String, code: Int) {
            runCatching { call("http://$host:$port/link/remote/key?k=${enc(key)}&c=$code", timeout = 3_000) }
        }

        fun remotePlay(host: String, port: Int, key: String, channelId: String) {
            runCatching { call("http://$host:$port/link/remote/play?k=${enc(key)}&id=${enc(channelId)}", timeout = 4_000) }
        }

        fun remoteState(host: String, port: Int, key: String): JSONObject? = runCatching {
            val (status, text) = call("http://$host:$port/link/remote/state?k=${enc(key)}", timeout = 3_000)
            if (status == 200) JSONObject(text) else null
        }.getOrNull()

        fun remoteChannels(host: String, port: Int, key: String): org.json.JSONArray? = runCatching {
            val (status, text) = call("http://$host:$port/link/remote/channels?k=${enc(key)}", timeout = 10_000)
            if (status == 200) org.json.JSONArray(text) else null
        }.getOrNull()

        /** Asks the other device to play [what]. Blocking; true when it started. */
        fun sendPlay(host: String, port: Int, key: String, from: String, what: JSONObject): Int {
            val (status, _) = call("http://$host:$port/link/play?k=${enc(key)}&from=${enc(from)}", "POST", what.toString(), timeout = 8_000)
            return status
        }
    }
}

package com.titanejstudios.tuner.integrations

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.titanejstudios.tuner.data.AppLog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A tiny web server that runs only while the "Set up from your phone" screen is open.
 * Scan the QR code, then add playlists, set the Home Assistant webhook, or back up / restore
 * from your phone's browser. Every request must carry the random token from the QR code.
 */
/** What the phone remote can ask the app (always called on the main thread). */
interface RemoteHandler {
    fun channelsJson(): String
    fun stateJson(): String
    fun key(code: Int)
    fun play(channelId: String)
    /** Programmes matching [query] as JSON. Called on the server's own thread (reads snapshots only). */
    fun searchJson(query: String): String
    /** Record (or with [series], series-link) a programme; returns a message for the phone. */
    fun record(channelId: String, start: Long, series: Boolean): String
    /** An uploaded APK to install. */
    fun installApk(file: java.io.File)
    /**
     * What's on as JSON for Home Assistant ("what football's on tonight?"): [kind] is sport, film,
     * news, kids… (or empty for anything), [time] is now, tonight, 6pm…10pm or tomorrow.
     * Called on the server's own thread (reads snapshots only).
     */
    fun whatsOnJson(kind: String, time: String): String
}

class PhoneSetupServer(
    private val store: Store,
    private val newSourceId: () -> String,
    private val remote: RemoteHandler,
    /** Where an uploaded app update is saved. */
    private val uploadDir: java.io.File? = null,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        class SourceAdded(val source: Source) : Event()
        object SettingsChanged : Event()
        object Restored : Event()
        class VpnAdded(val name: String, val text: String) : Event()
        /** A profile picture chosen on the phone (a JPEG). */
        class ProfilePhoto(val profileId: String, val jpeg: ByteArray) : Event()
    }

    /** One-off code for the setup pages; null while the setup screen isn't open. */
    @Volatile
    var setupToken: String? = null
        private set

    /** Permanent code for the phone remote (so it can be bookmarked). */
    @Volatile
    var remoteToken: String = store.remoteToken

    @Volatile
    var remoteEnabled: Boolean = false

    /** Code shown on this TV while "Copy to another TV" is open (null = sharing off). */
    @Volatile var shareCode: String? = null
        set(value) {
            field = value
            shareFailures = 0
        }
    @Volatile private var shareFailures = 0

    val port: Int get() = server?.localPort ?: 8765

    fun openSetup(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val t = (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        setupToken = t
        return t
    }

    fun closeSetup() {
        setupToken = null
    }

    val isRunning: Boolean get() = server?.isClosed == false
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    /** Starts the server and returns its base address, e.g. http://192.168.1.20:8765 (null if offline). */
    fun start(): String? {
        val ip = localIp() ?: return null
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(8765))
        } catch (e: Exception) {
            socket.bind(InetSocketAddress(0))
        }
        server = socket
        thread(name = "phone-setup", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(client) }
            }
        }
        return "http://$ip:${socket.localPort}"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun post(e: Event) = main.post { onEvent(e) }

    // ---------------- HTTP ----------------

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: ByteArray,
        /** Large uploads (app updates) go straight to a file instead of memory. */
        val bodyFile: java.io.File? = null,
    )

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 20_000
                val input = BufferedInputStream(s.getInputStream())
                val req = readRequest(input) ?: return
                val out = s.getOutputStream()
                val form = if (req.method == "POST") parseForm(String(req.body, Charsets.UTF_8)) else emptyMap()
                val given = req.query["t"] ?: form["t"]
                if (req.path == "/share.json") {
                    val code = shareCode
                    if (code != null && req.query["t"] == code) {
                        respond(out, 200, "application/json", store.exportBackup())
                    } else {
                        // Five wrong guesses and the code stops working.
                        if (code != null && ++shareFailures >= 5) shareCode = null
                        respond(out, 403, "text/plain", "Wrong code")
                    }
                    return
                }
                val isRemoteRoute = req.path == "/remote" || req.path.startsWith("/api/") || req.path == "/log.txt" || req.path == "/update"
                if (isRemoteRoute) {
                    if (!remoteEnabled || given != remoteToken) {
                        req.bodyFile?.delete()
                        respond(out, 403, "text/html", page("Remote is off", "<p>Turn on <b>Phone remote</b> in Flicker's Settings, then scan its QR code.</p>"))
                        return
                    }
                    if (req.path == "/log.txt") {
                        respond(out, 200, "text/plain", AppLog.read().ifEmpty { "No problems logged." })
                        return
                    }
                    routeRemote(req, out)
                    return
                }
                val setup = setupToken
                if (setup == null || given != setup) {
                    respond(out, 403, "text/html", page("Expired link", "<p>Open <b>Set up from your phone</b> on the TV and scan the QR code again.</p>"))
                    return
                }
                route(req, form, out)
            } catch (e: Exception) {
                Log.w("TunerPhone", "Request failed", e)
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (headerBytes.size() > 32_000) return null
        }
        val lines = headerBytes.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val target = parts[1]
        var length = 0
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("Content-Length", true)) {
                length = line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        val path0 = target.substringBefore('?')
        if (path0 == "/api/install" && uploadDir != null) {
            if (length <= 0 || length > 300_000_000) return null
            val f = java.io.File(uploadDir, "upload.apk")
            f.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, left))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            val query = if ('?' in target) parseForm(target.substringAfter('?')) else emptyMap()
            return Request(method, path0, query, ByteArray(0), f)
        }
        if (length > 5_000_000) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val path = target.substringBefore('?')
        val query = if ('?' in target) parseForm(target.substringAfter('?')) else emptyMap()
        return Request(method, path, query, body)
    }

    private fun parseForm(s: String): Map<String, String> =
        s.split('&').filter { it.isNotEmpty() }.associate {
            val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            k to v
        }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\nCache-Control: no-store\r\n$extraHeaders\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun route(req: Request, form: Map<String, String>, out: OutputStream) {
        when ("${req.method} ${req.path}") {
            "GET /" -> respond(out, 200, "text/html", mainPage())
            "GET /backup.json" -> respond(
                out, 200, "application/json", store.exportBackup(),
                "Content-Disposition: attachment; filename=\"tuner-backup.json\"\r\n",
            )
            "POST /m3u" -> {
                val url = form["url"].orEmpty().trim()
                if (url.isEmpty()) return respond(out, 200, "text/html", page("Missing URL", "<p>Enter the playlist URL.</p>" + back()))
                val src = Source.M3u(
                    newSourceId(), form["name"].orEmpty().trim().ifEmpty { "Playlist" }, url,
                    form["epg"].orEmpty().trim().ifEmpty { null },
                )
                post(Event.SourceAdded(src))
                respond(out, 200, "text/html", page("Added ✓", "<p><b>${esc(src.name)}</b> is loading on the TV now.</p>" + back()))
            }
            "POST /xtream" -> {
                val server = form["server"].orEmpty().trim()
                val user = form["user"].orEmpty().trim()
                val pass = form["pass"].orEmpty()
                if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    return respond(out, 200, "text/html", page("Missing details", "<p>Server, username and password are all needed.</p>" + back()))
                }
                val src = Source.Xtream(
                    newSourceId(), form["name"].orEmpty().trim().ifEmpty { "Provider" }, server, user, pass,
                    form["hls"] == "on", form["epg"].orEmpty().trim().ifEmpty { null },
                )
                post(Event.SourceAdded(src))
                respond(out, 200, "text/html", page("Added ✓", "<p><b>${esc(src.name)}</b> is loading on the TV now.</p>" + back()))
            }
            "POST /settings" -> {
                store.webhookUrl = form["webhook"].orEmpty().trim().ifEmpty { null }
                post(Event.SettingsChanged)
                respond(out, 200, "text/html", page("Saved ✓", "<p>Home Assistant webhook updated.</p>" + back()))
            }
            "POST /vpn" -> {
                val text = form["conf"].orEmpty()
                if (!text.contains("[Interface]", ignoreCase = true)) {
                    return respond(out, 200, "text/html", page("Not a WireGuard file", "<p>Choose the .conf file you downloaded from your VPN provider (it starts with [Interface]).</p>" + back()))
                }
                post(Event.VpnAdded(form["name"].orEmpty(), text))
                respond(out, 200, "text/html", page("Sent ✓", "<p>The VPN server is on the TV now – turn it on in <b>Settings › VPN</b>.</p>" + back()))
            }
            "POST /photo" -> {
                val id = form["profile"].orEmpty()
                val data = form["photo"].orEmpty().substringAfter("base64,", "")
                val bytes = runCatching { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }.getOrNull()
                val profile = store.profiles.firstOrNull { it.id == id }
                if (profile == null || bytes == null || bytes.size < 100) {
                    return respond(out, 200, "text/html", page("No photo", "<p>Choose a profile and a photo first.</p>" + back()))
                }
                post(Event.ProfilePhoto(id, bytes))
                respond(out, 200, "text/html", page("Sent ✓", "<p><b>${esc(profile.name)}</b>'s photo is on the TV now.</p>" + back()))
            }
            "POST /restore" -> {
                val json = form["json"].orEmpty()
                try {
                    store.importBackup(json)
                    post(Event.Restored)
                    respond(out, 200, "text/html", page("Restored ✓", "<p>Your settings are back. The TV is re-loading your playlists.</p>" + back()))
                } catch (e: Exception) {
                    respond(out, 200, "text/html", page("Couldn't restore", "<p>${esc(e.message ?: "Not a valid backup")}</p>" + back()))
                }
            }
            else -> respond(out, 404, "text/html", page("Not found", back()))
        }
    }


    // ---------------- Phone remote ----------------

    /** Runs [block] on the main thread and waits for the answer. */
    private fun <T> onMain(block: () -> T): T? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        main.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun routeRemote(req: Request, out: OutputStream) {
        when ("${req.method} ${req.path}") {
            "GET /remote" -> respond(out, 200, "text/html", remotePage())
            "GET /api/channels" -> respond(out, 200, "application/json", onMain { remote.channelsJson() } ?: "[]")
            "GET /api/state" -> respond(out, 200, "application/json", onMain { remote.stateJson() } ?: "{}")
            "POST /api/key" -> {
                val code = req.query["k"]?.toIntOrNull()
                if (code != null) main.post { remote.key(code) }
                respond(out, 200, "application/json", "{\"ok\":true}")
            }
            "POST /api/play" -> {
                val id = req.query["id"]
                if (id != null) main.post { remote.play(id) }
                respond(out, 200, "application/json", "{\"ok\":true}")
            }
            // Searching reads a snapshot of the guide, so it runs here rather than holding up the TV's screen.
            "GET /api/search" -> respond(out, 200, "application/json", runCatching { remote.searchJson(req.query["q"].orEmpty()) }.getOrDefault("[]"))
            "GET /api/whatson" -> respond(
                out, 200, "application/json",
                runCatching { remote.whatsOnJson(req.query["kind"].orEmpty(), req.query["when"].orEmpty()) }
                    .getOrDefault("{\"items\":[],\"speech\":\"Sorry, I couldn't read the TV guide.\"}"),
            )
            "POST /api/record" -> {
                val id = req.query["id"]
                val start = req.query["start"]?.toLongOrNull()
                val msg = if (id != null && start != null) onMain { remote.record(id, start, req.query["series"] == "1") } else null
                respond(out, 200, "application/json", org.json.JSONObject().put("msg", msg ?: "Couldn't do that").toString())
            }
            "GET /update" -> respond(out, 200, "text/html", updatePage())
            "POST /api/install" -> {
                val f = req.bodyFile
                if (f == null || f.length() < 1000) {
                    respond(out, 400, "application/json", "{\"msg\":\"Nothing was uploaded\"}")
                } else {
                    main.post { remote.installApk(f) }
                    respond(out, 200, "application/json", "{\"msg\":\"Uploaded – confirm the update on the TV\"}")
                }
            }
            else -> respond(out, 404, "application/json", "{}")
        }
    }

    private fun updatePage(): String {
        val t = remoteToken
        return page(
            "Update Flicker",
            """<p>Pick the new <b>app-release.apk</b> (in Android Studio: <i>app/build/outputs/apk/release/</i>) and press Upload. The TV will ask you to confirm.</p>
<input type="file" id="f" accept=".apk"><br><br><button onclick="up()">Upload to the TV</button><p id="s"></p>
<script>function up(){var f=document.getElementById('f').files[0];if(!f)return;var s=document.getElementById('s');s.textContent='Uploading…';
fetch('/api/install?t=$t',{method:'POST',body:f}).then(function(r){return r.json()}).then(function(j){s.textContent=j.msg}).catch(function(){s.textContent='Upload failed'})}</script>""",
        )
    }

    private fun remotePage(): String {
        val t = remoteToken
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<meta name="apple-mobile-web-app-capable" content="yes"><title>Flicker remote</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{font-family:system-ui,sans-serif;background:#0f1318;color:#f1f4f8;margin:0;padding:14px;max-width:520px;margin:auto}
.now{background:#181d24;border-radius:14px;padding:12px 14px;margin-bottom:12px;min-height:58px}
.now b{font-size:17px}.now span{color:#9aa4b2;font-size:14px;display:block;margin-top:3px}
.tabs{display:flex;gap:8px;margin-bottom:12px}.tabs button{flex:1}
button{background:#232a35;color:#fff;border:0;border-radius:14px;font-size:18px;padding:14px 0;touch-action:manipulation}
button:active{background:#3b82f6}
.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:10px 0}
.pad .ok{background:#3b82f6;font-weight:600}.row{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:10px}
.small{font-size:15px}
input{width:100%;padding:12px;border-radius:10px;border:1px solid #2b3440;background:#181d24;color:#fff;font-size:16px;margin-bottom:10px}
.ch{display:flex;align-items:center;gap:10px;padding:10px;border-radius:10px;background:#181d24;margin-bottom:6px}
.ch img{width:52px;height:34px;object-fit:contain;background:#222;border-radius:6px}
.ch .n{color:#9aa4b2;width:34px;font-size:14px}.ch .t{flex:1;min-width:0}
.ch .t div{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.ch .p{color:#9aa4b2;font-size:13px}
#list{display:none}
</style></head><body>
<div class="now" id="now"><b>Flicker</b><span>Connecting…</span></div>
<div class="tabs"><button onclick="tab('keys')">Remote</button><button onclick="tab('list')">Channels</button><button onclick="tab('guide')">Record</button></div>
<div id="keys">
<div class="pad">
<button class="small" onclick="k(82)">Menu</button><button onclick="k(19)">▲</button><button class="small" onclick="k(172)">Guide</button>
<button onclick="k(21)">◀</button><button class="ok" onclick="k(23)">OK</button><button onclick="k(22)">▶</button>
<button class="small" onclick="k(4)">Back</button><button onclick="k(20)">▼</button><button class="small" onclick="k(85)">⏯</button>
</div>
<div class="row"><button class="small" onclick="k(166)">CH +</button><button class="small" onclick="k(167)">CH −</button>
<button class="small" onclick="k(89)">⏪</button><button class="small" onclick="k(90)">⏩</button></div>
<div class="row" id="digits"></div>
</div>
<div id="list"><input id="q" placeholder="Search channels or what's on" oninput="draw()"><div id="chs"></div></div>
<div id="guide" style="display:none"><input id="gq" placeholder="Search the TV guide to record…" oninput="clearTimeout(window.gt);window.gt=setTimeout(gs,350)"><div id="gr"></div><p class="p" id="gm"></p></div>
<script>
var T='$t', chans=[];
function k(c){fetch('/api/key?t='+T+'&k='+c,{method:'POST'}).then(function(){setTimeout(state,400)});if(navigator.vibrate)navigator.vibrate(15)}
function play(id){fetch('/api/play?t='+T+'&id='+encodeURIComponent(id),{method:'POST'}).then(function(){setTimeout(state,800)});tab('keys')}
function pi(i){play(chans[i].id)}
function tab(n){['keys','list','guide'].forEach(function(x){document.getElementById(x).style.display=n==x?'block':'none'});if(n=='list')load()}
var progs=[];
function gs(){var q=document.getElementById('gq').value;if(q.length<2){document.getElementById('gr').innerHTML='';return}
 fetch('/api/search?t='+T+'&q='+encodeURIComponent(q)).then(function(r){return r.json()}).then(function(j){progs=j;var h='';
  j.forEach(function(p,i){h+='<div class="ch"><div class="t"><div>'+esc(p.title)+'</div><div class="p">'+esc(p.ch)+' · '+esc(p.when)+(p.rec?' · ● set':'')+'</div></div>'+
   '<button class="small" style="padding:8px 10px" onclick="rec('+i+',0)">●</button><button class="small" style="padding:8px 10px" onclick="rec('+i+',1)">🔁</button></div>'});
  document.getElementById('gr').innerHTML=h||'<p>Nothing found</p>'})}
function rec(i,s){var p=progs[i];fetch('/api/record?t='+T+'&id='+encodeURIComponent(p.id)+'&start='+p.start+'&series='+s,{method:'POST'}).then(function(r){return r.json()}).then(function(j){document.getElementById('gm').textContent=j.msg;gs()})}
function esc(s){return (s||'').replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function load(){fetch('/api/channels?t='+T).then(function(r){return r.json()}).then(function(j){chans=j;draw()})}
function draw(){var q=document.getElementById('q').value.toLowerCase(),h='';
 chans.forEach(function(c,i){if(q&&(c.name+' '+(c.now||'')).toLowerCase().indexOf(q)<0)return;
  h+='<div class="ch" onclick="pi('+i+')"><span class="n">'+c.n+'</span>'+(c.logo?'<img src="'+esc(c.logo)+'">':'')+
  '<div class="t"><div>'+esc(c.name)+'</div><div class="p">'+esc(c.now||'')+'</div></div></div>'});
 document.getElementById('chs').innerHTML=h||'<p>No channels</p>'}
function state(){fetch('/api/state?t='+T).then(function(r){return r.json()}).then(function(s){
 document.getElementById('now').innerHTML='<b>'+esc(s.title||'Flicker')+'</b><span>'+esc(s.detail||'')+'</span>'}).catch(function(){})}
var d='';for(var i=1;i<=9;i++)d+='<button class="small" onclick="k('+(7+i)+')">'+i+'</button>';d+='<button class="small" onclick="k(7)">0</button>';
document.getElementById('digits').innerHTML=d;document.getElementById('digits').style.gridTemplateColumns='repeat(5,1fr)';
state();setInterval(state,4000);
</script></body></html>"""
    }

    // ---------------- Pages ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun back() = "<p><a href=\"/?t=$setupToken\">← Back</a></p>"

    private fun page(title: String, body: String) = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Flicker – $title</title>
<style>
body{font-family:system-ui,sans-serif;background:#0f1318;color:#f1f4f8;margin:0;padding:20px;max-width:560px}
h1{font-size:24px}h2{font-size:18px;margin-top:28px;color:#9aa4b2}
input,textarea{width:100%;box-sizing:border-box;padding:12px;margin:6px 0 12px;border-radius:8px;border:1px solid #2b3440;background:#181d24;color:#fff;font-size:16px}
button,.btn{display:inline-block;background:#3b82f6;color:#fff;border:0;border-radius:24px;padding:12px 22px;font-size:16px;text-decoration:none}
label{font-size:14px;color:#9aa4b2}.card{background:#181d24;border-radius:12px;padding:16px;margin:12px 0}
a{color:#7ab0ff}.row{display:flex;gap:8px;align-items:center}.row input{width:auto;margin:0}
</style></head><body><h1>$title</h1>$body</body></html>"""

    private fun profileOptions(): String =
        store.profiles.joinToString("") { "<option value=\"${esc(it.id)}\">${esc(it.name)}</option>" }

    private fun mainPage(): String {
        val webhook = esc(store.webhookUrl ?: "")
        val t = setupToken
        return page(
            "Flicker setup",
            """
<p>Anything you save here goes straight to the TV.</p>
<h2>Add an M3U playlist</h2>
<form class="card" method="post" action="/m3u?t=$t">
<label>Name</label><input name="name" placeholder="Main provider">
<label>Playlist URL</label><input name="url" type="url" required placeholder="http://…/playlist.m3u">
<label>TV guide (XMLTV) URL – optional</label><input name="epg" type="url">
<button>Add playlist</button></form>

<h2>Add an Xtream Codes login</h2>
<form class="card" method="post" action="/xtream?t=$t">
<label>Name</label><input name="name" placeholder="Provider">
<label>Server</label><input name="server" required placeholder="http://provider.com:8080">
<label>Username</label><input name="user" required autocomplete="off">
<label>Password</label><input name="pass" type="password" required autocomplete="off">
<div class="row"><input type="checkbox" name="hls" id="hls"><label for="hls">Use HLS streams instead of MPEG-TS</label></div><br>
<label>TV guide URL override – optional</label><input name="epg" type="url">
<button>Add login</button></form>

<h2>Home Assistant</h2>
<form class="card" method="post" action="/settings?t=$t">
<label>Webhook URL (Flicker sends what's playing here)</label>
<input name="webhook" value="$webhook" placeholder="http://homeassistant.local:8123/api/webhook/tuner">
<button>Save</button></form>

<h2>VPN (WireGuard)</h2>
<form class="card" method="post" action="/vpn?t=$t" id="vf">
<p style="color:#9aa4b2;font-size:14px">Surfshark: log in at surfshark.com › VPN › Manual setup › Desktop or mobile › WireGuard. Create a key pair, then on Locations download a server (e.g. United Kingdom). Choose that file here.</p>
<label>Name (optional)</label><input name="name" placeholder="UK – London">
<input type="file" accept=".conf,text/plain" id="vfile">
<textarea name="conf" id="vconf" rows="4" placeholder="…or paste the file's text here"></textarea>
<button>Send to the TV</button></form>
<script>
document.getElementById('vfile').addEventListener('change',function(e){
 var f=e.target.files[0]; if(!f) return; var r=new FileReader();
 r.onload=function(){document.getElementById('vconf').value=r.result}; r.readAsText(f);
});
</script>

<h2>Profile photo</h2>
<form class="card" method="post" action="/photo?t=$t" id="pf">
<label>Profile</label>
<select name="profile" style="width:100%;padding:12px;margin:6px 0 12px;border-radius:8px;background:#181d24;color:#fff;border:1px solid #2b3440;font-size:16px">${profileOptions()}</select>
<input type="file" accept="image/*" id="pfile">
<img id="pprev" style="display:none;width:96px;height:96px;border-radius:48px;object-fit:cover;margin:6px 0">
<input type="hidden" name="photo" id="pdata">
<button>Send to the TV</button></form>
<script>
document.getElementById('pfile').addEventListener('change',function(e){
 var f=e.target.files[0]; if(!f) return; var img=new Image();
 img.onload=function(){
  var s=Math.min(img.width,img.height), c=document.createElement('canvas'); c.width=c.height=384;
  c.getContext('2d').drawImage(img,(img.width-s)/2,(img.height-s)/2,s,s,0,0,384,384);
  var d=c.toDataURL('image/jpeg',0.86); document.getElementById('pdata').value=d;
  var p=document.getElementById('pprev'); p.src=d; p.style.display='block';
 };
 img.src=URL.createObjectURL(f);
});
</script>

<h2>Backup</h2>
<div class="card"><a class="btn" href="/backup.json?t=$t">Download backup</a>
<p style="color:#9aa4b2;font-size:14px">Playlists, profiles, favourites, hidden groups, PIN, reminders and guide fixes.</p></div>

<h2>Restore</h2>
<form class="card" method="post" action="/restore?t=$t" id="rf">
<input type="file" accept=".json,application/json" id="file">
<textarea name="json" id="json" rows="4" placeholder="…or paste a backup here"></textarea>
<button>Restore</button></form>
<script>
document.getElementById('file').addEventListener('change',function(e){
 var f=e.target.files[0]; if(!f) return; var r=new FileReader();
 r.onload=function(){document.getElementById('json').value=r.result}; r.readAsText(f);
});
</script>
""",
        )
    }

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }

        fun qrCode(text: String, size: Int = 480): Bitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}

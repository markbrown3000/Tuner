package com.titanejstudios.tuner.integrations

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google sign-in for TVs (type a short code on your phone) and one small file in a "Flicker"
 * folder in your own Google Drive. The permission (drive.file) only lets Flicker see files it
 * made itself – nothing else in your Drive – and they count against your own Drive storage.
 * (TV sign-in turns down the hidden "app data" folder permission, so it's an ordinary folder.)
 */
class GoogleDrive(private val clientId: String, private val clientSecret: String) {

    class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresAt: Long, val intervalSec: Int)

    class Tokens(val access: String, val refresh: String?, val expiresAt: Long, val email: String?, val scope: String? = null) {
        /** Google lets you untick permissions on its sign-in page: did the Drive one get through? */
        val hasDrive: Boolean get() = scope == null || scope.contains(SCOPE)
    }

    /** "Waiting for you to approve", or a final answer. */
    sealed class Poll {
        object Waiting : Poll()
        object SlowDown : Poll()
        class Done(val tokens: Tokens) : Poll()
        class Failed(val reason: String) : Poll()
    }

    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    // ---------------- Sign-in (OAuth 2.0 for TVs) ----------------

    /** Starts the TV sign-in for [scope] (Drive for sync, or YouTube). */
    fun startSignIn(scope: String = "$SCOPE email"): DeviceCode {
        val (code, body) = post(
            "https://oauth2.googleapis.com/device/code",
            mapOf("client_id" to clientId, "scope" to scope),
        )
        val o = JSONObject(body)
        if (code != 200) throw IllegalStateException(o.optString("error_description").ifBlank { o.optString("error", "Google said $code") })
        return DeviceCode(
            o.getString("device_code"),
            o.getString("user_code"),
            o.optString("verification_url").ifBlank { o.optString("verification_uri", "https://www.google.com/device") },
            System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000,
            o.optInt("interval", 5),
        )
    }

    /** [required]: the permission that must have been ticked on Google's page. */
    fun poll(d: DeviceCode, required: String = SCOPE, missing: String = NO_DRIVE): Poll {
        val (code, body) = post(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "client_id" to clientId, "client_secret" to clientSecret, "device_code" to d.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        val o = JSONObject(body)
        if (code == 200) {
            val t = tokens(o, null)
            if (t.scope != null && !t.scope.contains(required)) {
                // Signed in, but the box for what we need was left unticked.
                t.refresh?.let { revoke(it) }
                return Poll.Failed(missing)
            }
            return Poll.Done(t)
        }
        return when (o.optString("error")) {
            "authorization_pending" -> Poll.Waiting
            "slow_down" -> Poll.SlowDown
            "access_denied" -> Poll.Failed("Sign-in was cancelled")
            "expired_token" -> Poll.Failed("The code ran out – start again")
            else -> Poll.Failed(o.optString("error_description").ifBlank { o.optString("error", "Google said $code") })
        }
    }

    /** A fresh access token from the long-lived refresh token. */
    fun refresh(refreshToken: String): Tokens {
        val (code, body) = post(
            "https://oauth2.googleapis.com/token",
            mapOf("client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to refreshToken, "grant_type" to "refresh_token"),
        )
        val o = JSONObject(body)
        if (code != 200) {
            if (o.optString("error") == "invalid_grant") throw SignedOut()
            throw IllegalStateException(o.optString("error_description").ifBlank { "Google said $code" })
        }
        return tokens(o, refreshToken)
    }

    /** Thrown when Google no longer accepts the sign-in (revoked, or the password changed). */
    class SignedOut : Exception("Signed out of Google – sign in again")

    fun revoke(token: String) {
        runCatching { post("https://oauth2.googleapis.com/revoke", mapOf("token" to token)) }
    }

    private fun tokens(o: JSONObject, oldRefresh: String?): Tokens {
        val email = o.optString("id_token").takeIf { it.isNotBlank() }?.let { emailFrom(it) }
        return Tokens(
            o.getString("access_token"),
            o.optString("refresh_token").takeIf { it.isNotBlank() } ?: oldRefresh,
            System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000,
            email,
            o.optString("scope").takeIf { it.isNotBlank() },
        )
    }

    /** The email address inside Google's ID token (its middle part is base64 JSON). */
    private fun emailFrom(idToken: String): String? = runCatching {
        val part = idToken.split('.')[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optString("email").takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------------- The sync file in the app data folder ----------------

    /** The sync file's id and contents, or null if there isn't one yet. */
    fun read(access: String): Pair<String, String>? {
        val q = URLEncoder.encode("name='$FILE' and trashed=false", "UTF-8")
        val list = JSONObject(get("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,modifiedTime)&orderBy=modifiedTime%20desc&pageSize=5", access))
        val id = list.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        return id to get("https://www.googleapis.com/drive/v3/files/$id?alt=media", access)
    }

    /** The "Flicker" folder in My Drive (made the first time). */
    private fun folder(access: String): String {
        val q = URLEncoder.encode("name='$FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        JSONObject(get("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&pageSize=1", access))
            .optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        val meta = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString()
        val (code, body) = send("https://www.googleapis.com/drive/v3/files?fields=id", "POST", access, "application/json; charset=UTF-8", meta.toByteArray())
        if (code !in 200..299) throw IllegalStateException("Google Drive said $code: " + googleMessage(body))
        return JSONObject(body).getString("id")
    }

    /** Saves the sync file (creating it the first time). Returns its id. */
    fun write(access: String, id: String?, text: String): String {
        if (id != null) {
            val (code, body) = send("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", "PATCH", access, "application/json", text.toByteArray())
            if (code in 200..299) return id
            if (code != 404) throw IllegalStateException("Google Drive said $code: " + googleMessage(body))
        }
        val boundary = "flicker" + System.currentTimeMillis()
        val meta = JSONObject().put("name", FILE).put("description", "Flicker keeps your devices in step with this file – please don't delete it.")
            .put("parents", org.json.JSONArray().put(folder(access))).toString()
        val multipart = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n$text\r\n--$boundary--"
        val (code, body) = send(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "POST", access,
            "multipart/related; boundary=$boundary", multipart.toByteArray(),
        )
        if (code !in 200..299) throw IllegalStateException("Google Drive said $code: " + googleMessage(body))
        return JSONObject(body).getString("id")
    }

    // ---------------- HTTP ----------------

    private fun open(url: String, method: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
        connectTimeout = 10_000
        readTimeout = 20_000
    }

    private fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = open(url, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        return finish(c) { c.outputStream.use { it.write(body.toByteArray()) } }
    }

    private fun get(url: String, access: String): String {
        val c = open(url, "GET")
        c.setRequestProperty("Authorization", "Bearer $access")
        val (code, body) = finish(c) {}
        if (code == 401) throw Unauthorised()
        if (code == 403 && "insufficient" in body.lowercase()) throw NoDrivePermission()
        if (code !in 200..299) throw IllegalStateException("Google Drive said $code: " + googleMessage(body))
        return body
    }

    private fun send(url: String, method: String, access: String, type: String, bytes: ByteArray): Pair<Int, String> {
        val c = open(url, method)
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $access")
        c.setRequestProperty("Content-Type", type)
        val r = finish(c) { c.outputStream.use { it.write(bytes) } }
        if (r.first == 401) throw Unauthorised()
        if (r.first == 403 && "insufficient" in r.second.lowercase()) throw NoDrivePermission()
        return r
    }

    private fun finish(c: HttpURLConnection, write: () -> Unit): Pair<Int, String> = try {
        write()
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        code to text
    } finally {
        c.disconnect()
    }

    /** Just Google's "message" from an error reply, not the whole JSON. */
    private fun googleMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: body.take(120)

    /** Signed in without the Google Drive permission (its box was unticked on Google's page). */
    class NoDrivePermission : Exception(NO_DRIVE)

    /** The access token has run out (refresh it and try again). */
    class Unauthorised : Exception("Google needs a fresh sign-in token")

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val FILE = "flicker-sync.json"
        const val FOLDER = "Flicker"
        const val NO_DRIVE = "Google didn't give Flicker the Google Drive permission – the Drive box was probably left unticked. " +
            "Sign in again and tick it (or press Select all) on Google's page."
    }
}

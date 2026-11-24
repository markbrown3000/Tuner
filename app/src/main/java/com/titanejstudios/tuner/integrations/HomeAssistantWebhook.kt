package com.titanejstudios.tuner.integrations

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends events to a Home Assistant webhook (Settings › Home Assistant), e.g.
 *   http://homeassistant.local:8123/api/webhook/tuner
 *
 * Events: playing, stopped, programme_changed, reminder, recording_started, recording_finished,
 * paused, resumed, lights (state dim / restore – see CinemaLights) and test.
 */
object HomeAssistantWebhook {
    private val executor = Executors.newSingleThreadExecutor()

    /** Sends a "test" event and waits for the answer. Returns the HTTP status code (throws if unreachable). */
    fun test(url: String): Int {
        val body = JSONObject().put("event", "test").put("message", "Hello from Flicker's self-test")
            .put("time", System.currentTimeMillis() / 1000)
        val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    fun send(url: String?, event: String, data: Map<String, Any?>) {
        if (url.isNullOrBlank()) return
        val body = JSONObject().put("event", event)
        for ((k, v) in data) body.put(k, v)
        body.put("time", System.currentTimeMillis() / 1000)
        executor.execute {
            try {
                val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("TunerHA", "Webhook failed: ${e.message}")
            }
        }
    }
}

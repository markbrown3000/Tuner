package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plex linking (plex.tv/link PIN) and the "Continue on Plex" list.
 * Moved out of TunerViewModel in 5.3; playing a Plex item still happens in the view model,
 * because it shares the player with everything else.
 */
class PlexFeature(private val store: Store, private val scope: CoroutineScope) {
    var linked by mutableStateOf(store.plexToken != null); private set
    var code by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var items by mutableStateOf<List<Plex.Item>>(emptyList()); private set
    private var servers: List<Plex.Server> = emptyList()
    private var job: Job? = null

    private fun client() = Plex(store.plexClientId)

    /** Shows a code to type in at plex.tv/link, then waits for it to be used. */
    fun link() {
        job?.cancel()
        code = null
        status = "Getting a code from Plex…"
        job = scope.launch {
            val plex = client()
            val pin = withContext(Dispatchers.IO) { runCatching { plex.createPin() } }.getOrElse {
                status = "Couldn't reach Plex: ${it.message}"
                return@launch
            }
            code = pin.code
            status = "On your phone or computer, go to plex.tv/link and enter the code"
            repeat(150) {
                delay(2_000)
                val token = withContext(Dispatchers.IO) { runCatching { plex.checkPin(pin) }.getOrNull() }
                if (token != null) {
                    store.plexToken = token
                    linked = true
                    code = null
                    status = "Linked ✓"
                    job = null
                    refresh(force = true)
                    return@launch
                }
            }
            code = null
            status = "The code expired – try again"
        }
    }

    fun unlink() {
        job?.cancel()
        store.plexToken = null
        linked = false
        code = null
        items = emptyList()
        servers = emptyList()
        status = "Plex unlinked"
    }

    /** Re-reads Continue watching from each server. */
    fun refresh(force: Boolean = false) {
        val token = store.plexToken ?: return
        if (!force && job?.isActive == true) return
        job = scope.launch {
            val plex = client()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (servers.isEmpty() || force) servers = plex.servers(token)
                    plex.onDeck(servers)
                }.onFailure { AppLog.warn("Plex: couldn't load Continue watching", it) }.getOrNull()
            }
            if (result != null) {
                items = result
                if (force) {
                    status = if (servers.isEmpty()) "Linked, but no Plex server answered on this network"
                    else "Linked ✓ · ${servers.joinToString { it.name }}"
                }
            }
        }
    }

    /** Settings were restored from a backup or another TV. */
    fun reload() {
        linked = store.plexToken != null
        servers = emptyList()
        items = emptyList()
        if (linked) refresh(force = true)
    }

    /** Tells Plex where you got to (so its own apps carry on from there). */
    fun reportProgress(item: Plex.Item, stopped: Boolean, positionMs: Long, durationMs: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching { client().timeline(item, if (stopped) "stopped" else "playing", positionMs, durationMs) }
        }
    }
}

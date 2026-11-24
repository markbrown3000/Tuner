package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.BuildConfig
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.GoogleDrive
import com.titanejstudios.tuner.integrations.LinkSync
import com.titanejstudios.tuner.integrations.TvSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sync between houses: every device signed in to the same Google account shares one small file
 * in a "Flicker" folder in that account's own Google Drive (Flicker can only see files it made). Each device writes
 * its lists there and takes what's newer from the others – where you got to in films and
 * episodes, My list, followed series, your teams, learned intros, and (if you like) favourites.
 */
class CloudSyncFeature(
    private val store: Store,
    private val scope: CoroutineScope,
    private val deviceId: () -> String,
    private val deviceName: () -> String,
    private val isPhone: () -> Boolean,
    private val isForeground: () -> Boolean,
    private val onSynced: (Set<String>) -> Unit,
) {
    /** Another device in the sync file. */
    class Device(val id: String, val name: String, val house: String, val phone: Boolean, val seenAt: Long)

    private val drive = GoogleDrive(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET)

    /** This copy of Flicker was built with Google sign-in set up. */
    val available: Boolean get() = drive.configured

    var signedIn by mutableStateOf(store.gdriveRefresh != null); private set
    var email by mutableStateOf(store.gdriveEmail); private set
    /** While signing in: the code to type on google.com/device. */
    var code by mutableStateOf<GoogleDrive.DeviceCode?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var lastSync by mutableStateOf(store.cloudSyncedAt); private set
    var syncing by mutableStateOf(false); private set
    var house by mutableStateOf(store.cloudHouse); private set
    var shareFavourites by mutableStateOf(store.cloudShareFavourites); private set

    private var access: GoogleDrive.Tokens? = null
    private var signInJob: Job? = null
    private var syncJob: Job? = null
    private var loopJob: Job? = null

    // ---------------- Signing in ----------------

    fun signIn() {
        if (!available) {
            status = "This copy of Flicker hasn't been set up for Google sign-in yet"
            return
        }
        signInJob?.cancel()
        status = "Getting a code…"
        signInJob = scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { drive.startSignIn() } }.getOrElse {
                status = "Couldn't reach Google: ${it.message}"
                return@launch
            }
            code = d
            status = null
            var interval = d.intervalSec.coerceAtLeast(3) * 1000L
            while (System.currentTimeMillis() < d.expiresAt) {
                delay(interval)
                when (val r = withContext(Dispatchers.IO) { runCatching { drive.poll(d) }.getOrElse { GoogleDrive.Poll.Waiting } }) {
                    is GoogleDrive.Poll.Waiting -> Unit
                    is GoogleDrive.Poll.SlowDown -> interval += 5_000L
                    is GoogleDrive.Poll.Failed -> {
                        code = null
                        status = r.reason
                        return@launch
                    }
                    is GoogleDrive.Poll.Done -> {
                        code = null
                        access = r.tokens
                        store.gdriveRefresh = r.tokens.refresh
                        store.gdriveEmail = r.tokens.email
                        email = r.tokens.email
                        signedIn = r.tokens.refresh != null
                        status = "✓ Signed in" + (r.tokens.email?.let { " as $it" } ?: "")
                        AppLog.info("Signed in to Google Drive sync")
                        syncNow()
                        start()
                        return@launch
                    }
                }
            }
            code = null
            status = "The code ran out – start again"
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        code = null
        status = null
    }

    fun signOut() {
        val r = store.gdriveRefresh
        loopJob?.cancel()
        syncJob?.cancel()
        store.gdriveRefresh = null
        store.gdriveEmail = null
        store.gdriveFileId = null
        access = null
        signedIn = false
        email = null
        devices = emptyList()
        status = "Signed out – this device no longer syncs"
        if (r != null) scope.launch(Dispatchers.IO) { drive.revoke(r) }
    }

    fun changeHouse(name: String) {
        val n = name.trim().take(30).ifBlank { "Home" }
        house = n
        store.cloudHouse = n
        syncSoon(1_000L)
    }

    fun toggleShareFavourites() {
        shareFavourites = !shareFavourites
        store.cloudShareFavourites = shareFavourites
        syncSoon(1_000L)
    }

    // ---------------- Syncing ----------------

    /** Every 10 minutes while Flicker is open (and once now). */
    fun start() {
        if (!signedIn || !available) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            delay(3_000)
            while (true) {
                if (isForeground()) syncOnce()
                delay(10 * 60_000L)
            }
        }
    }

    /** Something changed here (stopped a film, changed My list…): sync in a moment. */
    fun syncSoon(delayMs: Long = 8_000L) {
        if (!signedIn || !available) return
        // Never stop a sync half way (that showed "Couldn't sync: … was cancelled"): go again after it.
        if (syncing) {
            syncAgain = true
            return
        }
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            syncOnce()
        }
    }

    fun syncNow() = syncSoon(0L)

    private var syncAgain = false

    private suspend fun syncOnce() {
        if (syncing) {
            syncAgain = true
            return
        }
        syncing = true
        try {
            val (changed, others) = withContext(Dispatchers.IO) { roundTrip() }
            devices = others
            lastSync = System.currentTimeMillis()
            store.cloudSyncedAt = lastSync
            if (status?.startsWith("✓") != true) status = null
            if (changed.isNotEmpty()) {
                AppLog.info("Synced from other houses: " + changed.joinToString())
                onSynced(changed)
            }
        } catch (e: GoogleDrive.SignedOut) {
            signOut()
            status = "Google signed this device out – sign in again"
        } catch (e: GoogleDrive.NoDrivePermission) {
            // Signed in without the Drive box ticked: start again so it can be ticked.
            signOut()
            status = GoogleDrive.NO_DRIVE
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Signed out or closed while syncing – not a problem worth showing.
            throw e
        } catch (e: Exception) {
            status = "Couldn't sync: ${e.message ?: e.javaClass.simpleName}"
            AppLog.warn("Google Drive sync failed", e)
        } finally {
            syncing = false
            if (syncAgain) {
                syncAgain = false
                syncSoon(3_000L)
            }
        }
    }

    /** Read the file, take what's newer from the other devices, write ours back. Blocking. */
    private fun roundTrip(): Pair<Set<String>, List<Device>> {
        val token = accessToken()
        val found: Pair<String, String>? = try {
            drive.read(token)
        } catch (e: GoogleDrive.Unauthorised) {
            access = null
            drive.read(accessToken())
        }
        val fileId: String? = found?.first
        val text: String = found?.second ?: "{}"
        val root = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val all = root.optJSONObject("devices") ?: JSONObject()
        val me = deviceId()
        val now = System.currentTimeMillis()
        val changed = HashSet<String>()
        val seen = ArrayList<Device>()
        for (id in all.keys().asSequence().toList()) {
            val d = all.optJSONObject(id) ?: continue
            val at = d.optLong("at")
            // Devices not heard from in 60 days are dropped from the file.
            if (now - at > 60L * 86_400_000L) {
                all.remove(id)
                continue
            }
            if (id == me) continue
            val theirHouse = d.optString("house", "Home")
            seen += Device(id, d.optString("name"), theirHouse, d.optBoolean("phone"), at)
            val snap = d.optJSONObject("snapshot") ?: continue
            // Each house keeps its own favourites unless you've chosen to share them.
            if (!theirHouse.equals(house, true) && !shareFavourites) snap.optJSONObject("sections")?.remove(TvSync.FAVOURITES)
            changed += LinkSync.apply(store, snap)
        }
        all.put(
            me,
            JSONObject().put("name", deviceName()).put("house", house).put("phone", isPhone()).put("at", now)
                .put("snapshot", LinkSync.snapshot(store)),
        )
        root.put("app", "Flicker").put("v", 1).put("devices", all)
        val id = drive.write(accessToken(), fileId ?: store.gdriveFileId, root.toString())
        store.gdriveFileId = id
        return changed to seen.sortedWith(compareBy<Device>({ !it.house.equals(house, true) }, { it.house.lowercase() }, { it.name.lowercase() }))
    }

    private fun accessToken(): String {
        access?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.access }
        val refresh = store.gdriveRefresh ?: throw GoogleDrive.SignedOut()
        val t = drive.refresh(refresh)
        access = t
        return t.access
    }

    /** Settings restored from a backup: nothing to do (sign-in is per device), but keep going. */
    fun reload() {
        signedIn = store.gdriveRefresh != null
        email = store.gdriveEmail
        house = store.cloudHouse
        shareFavourites = store.cloudShareFavourites
    }
}

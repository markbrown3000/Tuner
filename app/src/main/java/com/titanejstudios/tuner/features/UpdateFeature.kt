package com.titanejstudios.tuner.features

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.apps.Updater
import com.titanejstudios.tuner.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Checking a GitHub repo's releases for a newer APK and installing it. Moved out of TunerViewModel in 5.3. */
class UpdateFeature(private val context: Context, private val store: Store, private val scope: CoroutineScope) {
    var repo by mutableStateOf(store.updateRepo); private set
    var status by mutableStateOf<String?>(null); private set
    private var available: Updater.Release? = null

    val installedVersion: String get() = Updater.installedVersion(context)

    /** Settings were restored from a backup or another TV. */
    fun reload() {
        repo = store.updateRepo
    }

    fun changeRepo(value: String?) {
        repo = value?.trim()?.removePrefix("https://github.com/")?.trim('/')?.takeIf { it.contains('/') }
        store.updateRepo = repo
        check()
    }

    fun check() {
        val r = repo ?: return
        status = "Checking GitHub…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.latest(r) } }
            result.onSuccess { rel ->
                available = rel?.takeIf { Updater.newer(it.version, installedVersion) }
                status = when {
                    rel == null -> "No release with an APK found in $r"
                    available != null -> "Version ${rel.version} is available – press Install"
                    else -> "You're up to date ($installedVersion)"
                }
            }.onFailure { status = "Couldn't check: ${it.message}" }
        }
    }

    fun install() {
        val rel = available
        if (rel == null) {
            check()
            return
        }
        status = "Downloading ${rel.version}…"
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(context.cacheDir, "update.apk")
                    Updater.download(rel.apkUrl, f)
                    Updater.install(context, f)
                }.exceptionOrNull()
            }
            status = if (err == null) "Confirm the update on screen" else "Update failed: ${err.message}"
        }
    }
}

package com.titanejstudios.tuner.tv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.HealthCheck
import com.titanejstudios.tuner.data.Repository
import com.titanejstudios.tuner.data.SeriesLinks
import com.titanejstudios.tuner.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Refreshes every playlist and guide overnight (around 4am) so the guide is never stale. */
class GuideRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tid = android.os.Process.myTid()
        val oldPriority = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val store = Store(applicationContext)
            val repo = Repository(applicationContext, store)
            for (src in store.sources) {
                runCatching { repo.refreshSource(src) }.onFailure { AppLog.warn("Overnight update failed for ${src.name}", it) }
            }
            if (store.liveTabScope != "off") {
                runCatching { LiveChannelSync.sync(applicationContext) }.onFailure { AppLog.warn("Live tab update failed", it) }
            }
            runCatching { com.titanejstudios.tuner.recording.RecordingTidy.cleanUp(store) }
            runCatching { applySeriesLinks(store) }.onFailure { AppLog.warn("Series link check failed", it) }
            if (store.healthNightly) runCatching { nightlyHealthCheck(store) }
            runCatching { tidyDatabase(store) }.onFailure { AppLog.warn("Guide tidy-up failed", it) }
            if (store.nasBackup && !store.nasUrl.isNullOrBlank()) {
                runCatching {
                    val name = android.provider.Settings.Global.getString(applicationContext.contentResolver, "device_name") ?: android.os.Build.MODEL
                    com.titanejstudios.tuner.integrations.NasBackup.save(store, name)
                }.onFailure { AppLog.warn("Backup to the network drive failed", it) }
            }
        } finally {
            android.os.Process.setThreadPriority(oldPriority)
        }
        Result.success()
    }

    /** Clears programmes older than any catch-up window; compacts the database about once a week. */
    private fun tidyDatabase(store: Store) {
        val now = System.currentTimeMillis()
        val compact = now - store.dbTidyAt > 7 * 86_400_000L
        val removed = Database.get(applicationContext).tidy(before = now - 8 * 86_400_000L, compact = compact)
        if (compact) store.dbTidyAt = now
        if (removed > 0 || compact) AppLog.info("Guide tidy-up: removed $removed old programmes" + (if (compact) ", database compacted" else ""))
    }

    /** Sets recordings for any new episodes of linked series now the guide is fresh. */
    private fun applySeriesLinks(store: Store) {
        if (store.seriesLinks.isEmpty()) return
        val db = Database.get(applicationContext)
        val ids = store.sources.map { it.id }
        val channels = db.loadChannels(ids)
        val now = System.currentTimeMillis()
        val epg = db.loadEpg(ids, now, now + 40 * 3600_000L, emptyMap())
        SeriesLinks.apply(applicationContext, store, channels, epg)
    }

    /** Checks favourites (or every visible channel) and records which ones are offline. */
    private fun nightlyHealthCheck(store: Store) {
        val db = Database.get(applicationContext)
        val all = db.loadChannels(store.sources.map { it.id })
        val hiddenGroups = store.hiddenGroups
        val hiddenChannels = store.hiddenChannels
        val targets = if (store.healthScope == "all") {
            all.filter { it.group !in hiddenGroups && it.id !in hiddenChannels }
        } else {
            val favs = store.allFavourites()
            all.filter { it.id in favs }
        }
        // Background work is limited to ~10 minutes, so stop after 7 and only update what was checked.
        val deadline = System.currentTimeMillis() + 7 * 60_000L
        var done = 0
        val dead = HealthCheck.run(targets, { isStopped || System.currentTimeMillis() > deadline }) { d, _, _ -> done = d }
        val checked = targets.take(done).map { it.id }.toSet()
        store.deadChannels = (store.deadChannels - checked) + dead
        store.healthCheckedAt = System.currentTimeMillis()
    }

    companion object {
        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 4)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val request = PeriodicWorkRequestBuilder<GuideRefreshWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("guide-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

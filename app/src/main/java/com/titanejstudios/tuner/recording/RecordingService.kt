package com.titanejstudios.tuner.recording

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.titanejstudios.tuner.R
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.HomeAssistantWebhook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service that runs scheduled recordings, even with Tuner closed. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tuner:recording")
            .apply { setReferenceCounted(false) }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Tuner is recording")
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting…"), type)
        wakeLock?.acquire(12 * 3600_000L)

        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent?.action == ACTION_STOP) {
            if (id != null) stopFlags[id]?.set(true)
        } else if (id != null) {
            begin(id)
        }
        if (jobs.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun begin(id: String) {
        if (jobs.containsKey(id)) return
        val store = Store(this)
        val rec = store.recordings.firstOrNull { it.id == id } ?: return
        if (rec.status != RecStatus.SCHEDULED && rec.status != RecStatus.RECORDING) return
        if (rec.stop <= System.currentTimeMillis()) {
            setStatus(store, id, RecStatus.FAILED, "Missed – the TV was off or asleep")
            HomeAssistantWebhook.send(
                store.webhookUrl, "recording_failed",
                mapOf("title" to rec.title, "channel" to rec.channelName, "bytes" to 0, "success" to false, "reason" to "missed"),
            )
            return
        }
        val flag = AtomicBoolean(false)
        stopFlags[id] = flag
        jobs[id] = scope.launch {
            val wait = rec.start - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            setStatus(store, id, RecStatus.RECORDING, null)
            updateNotification()
            HomeAssistantWebhook.send(
                store.webhookUrl, "recording_started",
                mapOf("title" to rec.title, "channel" to rec.channelName, "category" to rec.category),
            )
            val progress = Recorder.Progress()
            try {
                Recorder.record(rec.url, File(rec.path), rec.stop, { flag.get() }, progress)
                setStatus(store, id, if (progress.bytes > 0) RecStatus.DONE else RecStatus.FAILED,
                    if (progress.bytes > 0) null else "Nothing was received from the stream")
            } catch (e: Exception) {
                setStatus(store, id, if (progress.bytes > 0) RecStatus.DONE else RecStatus.FAILED, e.message)
            }
            val ok = progress.bytes > 0
            HomeAssistantWebhook.send(
                store.webhookUrl, if (ok) "recording_finished" else "recording_failed",
                mapOf("title" to rec.title, "channel" to rec.channelName, "bytes" to progress.bytes, "success" to ok),
            )
            if (!ok) AppLog.warn("Recording failed: ${rec.title} on ${rec.channelName}")
            if (ok) Store(this@RecordingService).recordings.firstOrNull { it.id == id }?.let { RecordingTidy.upload(store, it) }
            jobs.remove(id)
            stopFlags.remove(id)
            withContext(Dispatchers.Main) {
                updateNotification()
                finishIfIdle()
            }
        }
    }

    private fun setStatus(store: Store, id: String, status: String, error: String?) {
        store.updateRecordings { list -> list.map { if (it.id == id) it.copy(status = status, error = error) else it } }
    }

    private fun updateNotification() {
        val store = Store(this)
        val active = store.recordings.filter { it.status == RecStatus.RECORDING }
        val text = if (active.isEmpty()) "Waiting for the next recording" else active.joinToString(" · ") { it.title }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun finishIfIdle() {
        if (jobs.isNotEmpty()) return
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "recording_id"
        const val ACTION_STOP = "com.titanejstudios.tuner.STOP_RECORDING"
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 4242
    }
}

/** Schedules recordings with exact alarms so they start even when Tuner isn't open. */
object RecordingScheduler {

    private fun pending(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, RecordingAlarmReceiver::class.java).putExtra(RecordingService.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, rec: Recording) {
        val wakeAt = rec.start - 60_000L
        if (wakeAt <= System.currentTimeMillis() + 5_000L) {
            startNow(context, rec.id)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending(context, rec.id))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending(context, rec.id))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending(context, rec.id))
        }
    }

    fun cancelAlarm(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
    }

    fun startNow(context: Context, id: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java).putExtra(RecordingService.EXTRA_ID, id),
        )
    }

    fun stop(context: Context, id: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_STOP)
                .putExtra(RecordingService.EXTRA_ID, id),
        )
    }

    /** After a reboot: re-arm alarms and resume anything that was mid-recording. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (r in Store(context).recordings) {
            if (r.stop <= now) continue
            when (r.status) {
                RecStatus.SCHEDULED -> schedule(context, r)
                RecStatus.RECORDING -> startNow(context, r.id)
            }
        }
    }
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(RecordingService.EXTRA_ID)?.let { RecordingScheduler.startNow(context, it) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            runCatching { RecordingScheduler.rescheduleAll(context) }
            runCatching { com.titanejstudios.tuner.integrations.ReminderAlarms.rescheduleAll(context) }
            // Settings › When the TV turns on, open Tuner. Android only lets an app open itself from
            // the background with "Display over other apps" allowed.
            runCatching {
                if (com.titanejstudios.tuner.data.Store(context).openOnBoot &&
                    (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context))
                ) {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { context.startActivity(it) }
                }
            }.onFailure { com.titanejstudios.tuner.data.AppLog.warn("Couldn't open Tuner at start-up", it) }
        }
    }
}

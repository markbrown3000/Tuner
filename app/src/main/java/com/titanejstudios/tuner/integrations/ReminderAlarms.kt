package com.titanejstudios.tuner.integrations

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.titanejstudios.tuner.data.Reminder
import com.titanejstudios.tuner.data.Store

/** Whether Tuner is on screen (set by the app; false when the app isn't running). */
object AppState {
    @Volatile var foreground = false
}

/**
 * Reminders also go to Home Assistant when Tuner is closed, so your phone can buzz:
 * an alarm fires 2 minutes before the programme and posts a "reminder" event.
 */
object ReminderAlarms {
    private const val EXTRA_CHANNEL = "channel"
    private const val EXTRA_START = "start"

    private fun pending(context: Context, r: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        (r.channelId + r.start).hashCode(),
        Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_CHANNEL, r.channelId)
            .putExtra(EXTRA_START, r.start),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, r: Reminder) {
        if (Store(context).webhookUrl.isNullOrBlank()) return
        val at = r.start - 2 * 60_000L
        if (at <= System.currentTimeMillis()) return
        // Inexact is fine for a phone alert and needs no special permission.
        runCatching {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, r))
        }
    }

    fun cancel(context: Context, r: Reminder) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context, r)) }
    }

    fun rescheduleAll(context: Context) {
        Store(context).reminders.forEach { schedule(context, it) }
    }

    /** Link Home Assistant can open on the TV to go straight to the channel. */
    fun deepLink(channelId: String): String = "tuner://open?channel=" + Uri.encode(channelId)

    fun payload(r: Reminder): Map<String, Any?> = mapOf(
        "title" to r.title,
        "channel" to r.channelName,
        "start" to r.start / 1000,
        "link" to deepLink(r.channelId),
    )

    internal fun fire(context: Context, channelId: String, start: Long) {
        if (AppState.foreground) return // the app shows its own pop-up and tells Home Assistant
        val store = Store(context)
        val r = store.reminders.firstOrNull { it.channelId == channelId && it.start == start } ?: return
        HomeAssistantWebhook.send(store.webhookUrl, "reminder", payload(r) + ("app_open" to false))
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ch = intent.getStringExtra("channel") ?: return
        val start = intent.getLongExtra("start", 0L)
        val done = goAsync()
        Thread {
            try {
                ReminderAlarms.fire(context.applicationContext, ch, start)
                Thread.sleep(3_000) // let the webhook go out
            } finally {
                done.finish()
            }
        }.start()
    }
}

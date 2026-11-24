package com.titanejstudios.tuner.tv

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.titanejstudios.tuner.MainActivity
import com.titanejstudios.tuner.R
import com.titanejstudios.tuner.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget (phones): what's on now on your first four favourites. Tap one to watch it.
 * Flicker saves the next few hours of the guide for it, so it stays right while Flicker is closed.
 */
class NowWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private val ROWS = listOf(
            Triple(R.id.row1, R.id.name1, R.id.prog1),
            Triple(R.id.row2, R.id.name2, R.id.prog2),
            Triple(R.id.row3, R.id.name3, R.id.prog3),
            Triple(R.id.row4, R.id.name4, R.id.prog4),
        )

        /** Redraws every Flicker widget (call after saving a new snapshot). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, NowWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        /** The snapshot Flicker saves for the widget: favourites with their programmes from now on. */
        fun snapshot(channels: List<Triple<String, String, List<Triple<Long, Long, String>>>>): String {
            val arr = JSONArray()
            for ((id, name, progs) in channels) {
                val p = JSONArray()
                progs.forEach { (start, stop, title) -> p.put(JSONObject().put("s", start).put("e", stop).put("t", title)) }
                arr.put(JSONObject().put("id", id).put("name", name).put("p", p))
            }
            return arr.toString()
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_now)
            val now = System.currentTimeMillis()
            val hhmm = SimpleDateFormat("HH:mm", Locale.UK)
            val channels = runCatching { JSONArray(Store(context).widgetSnapshot) }.getOrNull() ?: JSONArray()

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.header, open)
            views.setViewVisibility(R.id.empty, if (channels.length() == 0) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.empty, open)
            views.setTextViewText(R.id.updated, hhmm.format(Date(now)))

            ROWS.forEachIndexed { i, (row, name, prog) ->
                val c = channels.optJSONObject(i)
                if (c == null) {
                    views.setViewVisibility(row, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(row, View.VISIBLE)
                views.setTextViewText(name, c.optString("name"))
                val progs = c.optJSONArray("p") ?: JSONArray()
                var current: JSONObject? = null
                var next: JSONObject? = null
                for (j in 0 until progs.length()) {
                    val p = progs.optJSONObject(j) ?: continue
                    if (p.optLong("s") <= now && now < p.optLong("e")) current = p
                    else if (p.optLong("s") > now && next == null) next = p
                }
                views.setTextViewText(
                    prog,
                    when {
                        current != null -> current.optString("t") + "  ·  until " + hhmm.format(Date(current.optLong("e")))
                        next != null -> "Next " + hhmm.format(Date(next.optLong("s"))) + "  " + next.optString("t")
                        else -> "Tap to watch"
                    },
                )
                val watch = Intent(Intent.ACTION_VIEW, Uri.parse("tuner://open?channel=" + Uri.encode(c.optString("id"))), context, MainActivity::class.java)
                views.setOnClickPendingIntent(
                    row,
                    PendingIntent.getActivity(context, 100 + i, watch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
        }
    }
}

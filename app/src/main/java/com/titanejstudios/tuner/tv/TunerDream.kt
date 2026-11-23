package com.titanejstudios.tuner.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Screensaver: a big clock and what's on now on your favourites, a page at a time. The whole
 * block drifts around the screen so nothing burns in.
 */
class TunerDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var block: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var rows: LinearLayout
    private var data: List<Pair<Channel, Programme>> = emptyList()
    private var page = 0
    private var loadedAt = 0L
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", Locale.UK)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text(96f, Color.WHITE, bold = true)
        date = text(26f, 0xFFAAB6DA.toInt())
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
        }
        block.addView(clock)
        block.addView(date)
        block.addView(rows)
        root.addView(block, FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        tick()
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        clock.text = timeFmt.format(Date(now))
        date.text = dateFmt.format(Date(now))
        if (now - loadedAt > 5 * 60_000L) {
            loadedAt = now
            Thread {
                val d = runCatching { load(now) }.getOrDefault(emptyList())
                handler.post {
                    data = d
                    showPage()
                }
            }.start()
        } else {
            page++
            showPage()
        }
        drift()
        handler.postDelayed({ tick() }, 15_000)
    }

    private fun load(now: Long): List<Pair<Channel, Programme>> {
        val store = Store(applicationContext)
        val db = Database.get(applicationContext)
        val ids = store.sources.map { it.id }
        val favs = store.profiles.flatMap { store.favourites(it.id) }.distinct()
        val all = db.loadChannels(ids)
        val byId = all.associateBy { it.id }
        val chosen = favs.mapNotNull { byId[it] }.ifEmpty { all.take(30) }.take(30)
        val epg = db.loadEpg(ids, now, now + 3600_000L, emptyMap())
        return chosen.mapNotNull { c -> HomeFeed.nowOn(epg[c.id], now)?.let { c to it } }
    }

    private fun showPage() {
        rows.removeAllViews()
        if (data.isEmpty()) return
        val pages = (data.size + 5) / 6
        val start = (page % pages) * 6
        for ((c, p) in data.drop(start).take(6)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(text(26f, Color.WHITE, bold = true).apply { text = p.title; maxLines = 1 })
            row.addView(text(19f, 0xFFAAB6DA.toInt()).apply { text = "${c.name}  ·  until ${timeFmt.format(Date(p.stop))}"; maxLines = 1 })
            row.addView(progress(((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)))
            rows.addView(row)
        }
    }

    private fun progress(f: Float): FrameLayout {
        val bar = FrameLayout(this)
        bar.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(2).toFloat() }
        val fill = FrameLayout(this)
        fill.background = GradientDrawable().apply { setColor(0xFF1F7BFF.toInt()); cornerRadius = dp(2).toFloat() }
        bar.addView(fill, FrameLayout.LayoutParams((dp(360) * f).toInt(), dp(4)))
        bar.layoutParams = LinearLayout.LayoutParams(dp(360), dp(4)).apply { topMargin = dp(6) }
        return bar
    }

    /** Move the block to a new spot (stays on screen). */
    private fun drift() {
        val parent = block.parent as? FrameLayout ?: return
        val w = parent.width - block.width
        val h = parent.height - block.height
        if (w <= 0 || h <= 0) return
        block.animate().x(Random.nextInt(dp(40), maxOf(dp(41), w - dp(40))).toFloat())
            .y(Random.nextInt(dp(30), maxOf(dp(31), h - dp(30))).toFloat())
            .setDuration(2_000).start()
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

package com.titanejstudios.tuner.data

import org.json.JSONObject
import java.util.Calendar

/**
 * When you watch which live channel: seconds per channel for each hour, weekdays and weekends
 * kept apart. { "d18": { "bbc1": 5400 }, "e10": { "skysports": 7200 } }.
 * Used to offer "your usual" channel when Flicker opens at a time you normally watch it.
 */
object Habits {
    /** A slot this full (20 hours) is halved, so new habits take over from old ones. */
    private const val SLOT_CAP = 72_000L
    /** At least this much (summed over the weeks) before a channel counts as "usual" for an hour. */
    private const val MIN_SECONDS = 2_400L

    fun slot(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val weekend = c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        return (if (weekend) "e" else "d") + c.get(Calendar.HOUR_OF_DAY)
    }

    private fun neighbour(slot: String, by: Int): String {
        val h = (slot.drop(1).toInt() + by + 24) % 24
        return slot.take(1) + h
    }

    fun add(json: String, t: Long, channelId: String, seconds: Long): String {
        val root = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val key = slot(t)
        val s = root.optJSONObject(key) ?: JSONObject().also { root.put(key, it) }
        s.put(channelId, s.optLong(channelId) + seconds)
        var total = 0L
        for (k in s.keys()) total += s.optLong(k)
        if (total > SLOT_CAP) {
            val keys = s.keys().asSequence().toList()
            for (k in keys) {
                val v = s.optLong(k) / 2
                if (v < 60) s.remove(k) else s.put(k, v)
            }
        }
        return root.toString()
    }

    /** The channel you usually watch around [t], if there's a clear favourite for that time. */
    fun usual(json: String, t: Long): String? {
        val root = try { JSONObject(json) } catch (e: Exception) { return null }
        val key = slot(t)
        val score = HashMap<String, Long>()
        fun addSlot(k: String, weight: Long) {
            val s = root.optJSONObject(k) ?: return
            for (ch in s.keys()) score[ch] = (score[ch] ?: 0L) + s.optLong(ch) * weight
        }
        addSlot(key, 2)
        addSlot(neighbour(key, -1), 1)
        addSlot(neighbour(key, 1), 1)
        val here = root.optJSONObject(key) ?: return null
        val best = score.maxByOrNull { it.value } ?: return null
        val total = score.values.sum()
        if (here.optLong(best.key) < MIN_SECONDS) return null
        if (best.value * 10 < total * 4) return null // needs at least 40% of the viewing then
        return best.key
    }
}

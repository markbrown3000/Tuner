package com.titanejstudios.tuner.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Watch time per day, by channel and by kind of programme. Stored as JSON:
 * { "20261122": { "c": { "BBC One": 3600 }, "g": { "Sport": 1800 } } }. Keeps 35 days.
 */
object WatchStats {
    private const val KEEP_DAYS = 35

    class Summary(
        val totalSeconds: Long,
        /** Last 7 days, oldest first: label ("Mon") to seconds. */
        val days: List<Pair<String, Long>>,
        val channels: List<Pair<String, Long>>,
        val genres: List<Pair<String, Long>>,
        val previousWeekSeconds: Long,
        /** Programmes, films and videos watched most this week. */
        val titles: List<Pair<String, Long>> = emptyList(),
    )

    /** Adds time to a programme / film / video title for one day. */
    fun addTitle(json: String, day: String, title: String, seconds: Long): String {
        val root = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val d = root.optJSONObject(day) ?: JSONObject().also { root.put(day, it) }
        val t = d.optJSONObject("t") ?: JSONObject().also { d.put("t", it) }
        val k = title.take(80)
        if (t.length() < 200 || t.has(k)) t.put(k, t.optLong(k) + seconds)
        return root.toString()
    }

    fun dayKey(t: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    /** Adds [seconds] for one day; returns the new JSON (old days trimmed). */
    fun add(json: String, day: String, channel: String, genre: String?, seconds: Long): String {
        val root = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val d = root.optJSONObject(day) ?: JSONObject().also { root.put(day, it) }
        val c = d.optJSONObject("c") ?: JSONObject().also { d.put("c", it) }
        c.put(channel, c.optLong(channel) + seconds)
        val g = d.optJSONObject("g") ?: JSONObject().also { d.put("g", it) }
        val gk = genre?.takeIf { it.isNotBlank() } ?: "Other"
        g.put(gk, g.optLong(gk) + seconds)
        // Trim
        val keys = root.keys().asSequence().toList().sorted()
        if (keys.size > KEEP_DAYS) keys.take(keys.size - KEEP_DAYS).forEach { root.remove(it) }
        return root.toString()
    }

    /** Two devices' stats added together (a kid's week across every TV). */
    fun merge(a: String, b: String): String {
        val ra = try { JSONObject(a) } catch (e: Exception) { JSONObject() }
        val rb = try { JSONObject(b) } catch (e: Exception) { return ra.toString() }
        for (day in rb.keys()) {
            val db = rb.optJSONObject(day) ?: continue
            val da = ra.optJSONObject(day) ?: JSONObject().also { ra.put(day, it) }
            for (part in listOf("c", "g", "t")) {
                val pb = db.optJSONObject(part) ?: continue
                val pa = da.optJSONObject(part) ?: JSONObject().also { da.put(part, it) }
                for (k in pb.keys()) pa.put(k, pa.optLong(k) + pb.optLong(k))
            }
        }
        return ra.toString()
    }

    fun summary(json: String, now: Long): Summary {
        val root = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val label = SimpleDateFormat("EEE", Locale.UK)
        val days = ArrayList<Pair<String, Long>>()
        val channels = HashMap<String, Long>()
        val genres = HashMap<String, Long>()
        val titles = HashMap<String, Long>()
        val thisWeek = ArrayList<String>()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        repeat(7) {
            val key = dayKey(cal.timeInMillis)
            thisWeek += key
            val d = root.optJSONObject(key)
            var total = 0L
            d?.optJSONObject("c")?.let { c ->
                for (k in c.keys()) {
                    val v = c.optLong(k)
                    total += v
                    channels[k] = (channels[k] ?: 0L) + v
                }
            }
            d?.optJSONObject("g")?.let { g -> for (k in g.keys()) genres[k] = (genres[k] ?: 0L) + g.optLong(k) }
            d?.optJSONObject("t")?.let { t -> for (k in t.keys()) titles[k] = (titles[k] ?: 0L) + t.optLong(k) }
            days += label.format(cal.time) to total
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        // The 7 days before that, for "up/down on last week".
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -13)
        var previous = 0L
        repeat(7) {
            root.optJSONObject(dayKey(cal.timeInMillis))?.optJSONObject("c")?.let { c -> for (k in c.keys()) previous += c.optLong(k) }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return Summary(
            totalSeconds = days.sumOf { it.second },
            days = days,
            channels = channels.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
            genres = genres.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
            previousWeekSeconds = previous,
            titles = titles.entries.sortedByDescending { it.value }.take(15).map { it.key to it.value },
        )
    }

    fun hoursText(seconds: Long): String {
        val m = seconds / 60
        return when {
            m < 1 -> "0m"
            m < 60 -> "${m}m"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}

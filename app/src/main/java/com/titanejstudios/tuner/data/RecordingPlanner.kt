package com.titanejstudios.tuner.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The week's recordings by day, with clashes (recordings that overlap) picked out. */
object RecordingPlanner {

    class Day(val label: String, val recordings: List<Recording>)

    /** Everything still to come (or recording now), grouped "Today", "Tomorrow", "Wed 25 Nov"… */
    fun days(all: List<Recording>, now: Long): List<Day> {
        val upcoming = all.filter {
            (it.status == RecStatus.SCHEDULED || it.status == RecStatus.RECORDING) && it.stop > now
        }.sortedBy { it.start }
        if (upcoming.isEmpty()) return emptyList()
        val today = dayIndex(now)
        val fmt = SimpleDateFormat("EEE d MMM", Locale.UK)
        return upcoming.groupBy { dayIndex(maxOf(it.start, now)) }.toSortedMap().map { (day, list) ->
            val label = when (day - today) {
                0L -> "Today"
                1L -> "Tomorrow"
                else -> fmt.format(Date(list.first().start))
            }
            Day(label, list)
        }
    }

    /**
     * For each recording, the other recordings it overlaps with. Most IPTV accounts allow only one
     * or two streams at once, so overlaps (and watching another channel meanwhile) can fail.
     * Two recordings of the same channel share one stream, so they don't clash.
     */
    fun clashes(all: List<Recording>, now: Long): Map<String, List<Recording>> {
        val live = all.filter {
            (it.status == RecStatus.SCHEDULED || it.status == RecStatus.RECORDING) && it.stop > now
        }.sortedBy { it.start }
        val out = HashMap<String, MutableList<Recording>>()
        for (i in live.indices) {
            val a = live[i]
            for (j in i + 1 until live.size) {
                val b = live[j]
                if (b.start >= a.stop) break
                if (a.channelId == b.channelId) continue
                out.getOrPut(a.id) { ArrayList() }.add(b)
                out.getOrPut(b.id) { ArrayList() }.add(a)
            }
        }
        return out
    }

    /** Most streams needed at the same moment by the recordings in [list] (distinct channels). */
    fun peakStreams(list: List<Recording>): Int {
        var best = 0
        for (r in list) {
            val at = r.start
            val n = list.filter { it.start <= at && it.stop > at }.map { it.channelId }.distinct().size
            if (n > best) best = n
        }
        return best
    }

    private fun dayIndex(t: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Days since the epoch, counted at local noon so clock changes don't matter.
        return (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L
    }
}

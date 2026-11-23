package com.titanejstudios.tuner.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds catch-up (archive) URLs from a per-channel template.
 *
 * Supported placeholders (with or without a leading $):
 *  {utc} {start}            programme start, unix seconds   – or {utc:YmdHMS} to format it
 *  {end} {utcend}           programme end, unix seconds     – or {end:YmdHMS}
 *  {lutc} {now} {timestamp} current time, unix seconds
 *  {duration}               length in seconds – {duration:60} divides (minutes)
 *  {offset}                 seconds since the programme started
 *  {Y} {m} {d} {H} {M} {S}  programme start date/time parts
 */
object Catchup {
    private val placeholder = Regex("""\$?\{([A-Za-z]+)(?::([^}]*))?\}""")

    fun isAvailable(channel: Channel, p: Programme, now: Long): Boolean =
        channel.hasCatchup && p.start < now && p.start >= now - channel.catchupDays * 86_400_000L

    fun url(channel: Channel, p: Programme, now: Long): String? {
        val template = channel.catchupTemplate ?: return null
        val tz = channel.catchupTz?.takeIf { it.isNotBlank() }?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        val startSec = p.start / 1000
        val endSec = p.stop / 1000
        val nowSec = now / 1000
        return placeholder.replace(template) { m ->
            val raw = m.groupValues[1]
            val arg = m.groupValues[2]
            when (raw) {
                "Y" -> part(p.start, tz, Calendar.YEAR, 4)
                "m" -> part(p.start, tz, Calendar.MONTH, 2)
                "d" -> part(p.start, tz, Calendar.DAY_OF_MONTH, 2)
                "H" -> part(p.start, tz, Calendar.HOUR_OF_DAY, 2)
                "M" -> part(p.start, tz, Calendar.MINUTE, 2)
                "S" -> part(p.start, tz, Calendar.SECOND, 2)
                else -> when (raw.lowercase(Locale.ROOT)) {
                    "utc", "start" -> if (arg.isNotEmpty() && arg.toLongOrNull() == null) format(p.start, arg, tz) else startSec.toString()
                    "end", "utcend" -> if (arg.isNotEmpty() && arg.toLongOrNull() == null) format(p.stop, arg, tz) else endSec.toString()
                    "lutc", "now", "timestamp" -> nowSec.toString()
                    "duration" -> ((endSec - startSec) / (arg.toLongOrNull()?.takeIf { it > 0 } ?: 1L)).toString()
                    "offset" -> (nowSec - startSec).coerceAtLeast(0).toString()
                    else -> m.value
                }
            }
        }
    }

    private fun part(t: Long, tz: TimeZone, field: Int, width: Int): String {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = t
        var v = c.get(field)
        if (field == Calendar.MONTH) v += 1
        return v.toString().padStart(width, '0')
    }

    /** Formats with letters Y m d H M S; anything else is copied as-is. */
    private fun format(t: Long, pattern: String, tz: TimeZone): String {
        val sb = StringBuilder()
        for (ch in pattern) {
            sb.append(
                when (ch) {
                    'Y' -> part(t, tz, Calendar.YEAR, 4)
                    'm' -> part(t, tz, Calendar.MONTH, 2)
                    'd' -> part(t, tz, Calendar.DAY_OF_MONTH, 2)
                    'H' -> part(t, tz, Calendar.HOUR_OF_DAY, 2)
                    'M' -> part(t, tz, Calendar.MINUTE, 2)
                    'S' -> part(t, tz, Calendar.SECOND, 2)
                    else -> ch.toString()
                }
            )
        }
        return sb.toString()
    }

    // ---- Template builders used by the parsers ----

    fun shiftTemplate(url: String): String = url + (if ('?' in url) "&" else "?") + "utc={utc}&lutc={lutc}"

    fun flussonicTemplate(url: String): String {
        val base = url.substringBefore('?')
        val dir = base.substringBeforeLast('/')
        val last = base.substringAfterLast('/')
        return if (last.endsWith(".m3u8", true)) {
            "$dir/${last.substringBeforeLast('.')}-{utc}-{duration}.m3u8"
        } else {
            "$dir/timeshift_abs-{utc}.ts"
        }
    }

    private val xtreamLive = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""")

    fun xtreamTemplateFromLiveUrl(url: String): String? {
        val m = xtreamLive.find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        return xtreamTemplate(host, user, pass, id)
    }

    fun xtreamTemplate(base: String, user: String, pass: String, streamId: String): String =
        "$base/timeshift/$user/$pass/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$streamId.ts"
}

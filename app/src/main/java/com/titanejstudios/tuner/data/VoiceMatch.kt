package com.titanejstudios.tuner.data

import java.util.Locale

/** Turns "Hey Google, play Sky Sports Main Event on Tuner" into a channel. */
object VoiceMatch {
    private val filler = Regex(
        "\\b(hey google|ok google|please|play|watch|put on|switch to|turn on|open|tune to|go to|the channel|channel|on tuner|in tuner|tuner|live)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val words = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "first" to "1", "second" to "2",
    )

    fun clean(query: String): String {
        var q = query.lowercase(Locale.ROOT).replace(filler, " ")
        q = q.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { words[it] ?: it }
        return q.trim()
    }

    /** A number on its own ("101") means a channel number. */
    fun number(query: String): Int? = clean(query).toIntOrNull()

    fun find(channels: List<Channel>, query: String): Channel? {
        val q = clean(query)
        if (q.isEmpty()) return null
        val key = StreamAlternates.nameKey(q)
        if (key.isEmpty()) return null
        // Exact name first (ignoring HD/FHD), then starts-with, then contains – shortest name wins ties.
        val keyed = channels.map { it to StreamAlternates.nameKey(it.name) }
        keyed.firstOrNull { it.second == key }?.let { return it.first }
        keyed.filter { it.second.startsWith(key) }.minByOrNull { it.second.length }?.let { return it.first }
        keyed.filter { it.second.contains(key) }.minByOrNull { it.second.length }?.let { return it.first }
        // "BBC 1" vs "BBC One"
        val alt = key.replace("1", "one").replace("2", "two").replace("3", "three").replace("4", "four")
        if (alt != key) keyed.firstOrNull { it.second == alt || it.second.startsWith(alt) }?.let { return it.first }
        return null
    }
}

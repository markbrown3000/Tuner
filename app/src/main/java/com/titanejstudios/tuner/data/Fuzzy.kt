package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * Forgiving search: every word you type has to be in the name, but small typos are fine
 * ("strictley" finds Strictly, "eastenders" finds EastEnders, "lord rings" finds The Lord of the Rings).
 */
object Fuzzy {
    private fun clean(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** True when each word of [query] is in [text], allowing one or two letters wrong in longer words. */
    fun matches(query: String, text: String): Boolean = score(query, text) > 0

    /** 0 = no match; higher = closer (whole words and starts score more). */
    fun score(query: String, text: String): Int {
        val q = clean(query).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty()) return 0
        val t = clean(text)
        val joined = t.replace(" ", "")
        val words = t.split(' ').filter { it.isNotEmpty() }
        var total = 0
        for (w in q) {
            val s = when {
                words.any { it == w } -> 4
                words.any { it.startsWith(w) } -> 3
                w in joined -> 2
                w.length >= 4 && words.any { close(w, it.take(w.length + 1)) || close(w, it) } -> 1
                else -> return 0
            }
            total += s
        }
        if (t.startsWith(clean(query))) total += 3
        return total
    }

    /** At most one mistake (two for long words): a letter wrong, missing, extra or swapped. */
    fun close(a: String, b: String): Boolean {
        val limit = if (a.length >= 8) 2 else 1
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        return distance(a, b) <= limit
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}

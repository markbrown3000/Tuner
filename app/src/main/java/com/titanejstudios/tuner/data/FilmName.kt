package com.titanejstudios.tuner.data

/** Tidies IPTV film names ("EN - Home Alone (1990) 4K") into a title and year for matching. */
object FilmName {
    private val prefix = Regex("^\\s*((\\[[^\\]]{1,10}]|\\|[^|]{1,10}\\||[A-Z]{2,4}\\s*[|:\\-–])\\s*)+")
    private val tags = Regex(
        "(\\s*[\\[(]?\\b(4k|uhd|fhd|hd|sd|hdr|hdr10|dv|1080p|720p|2160p|multi[- ]?sub|subbed|dubbed|imax|remastered)\\b[\\])]?)+\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val year = Regex("[\\[(]?\\b((19|20)\\d\\d)\\b[\\])]?")

    /** "EN - Home Alone (1990) 4K" → ("Home Alone", "1990"). */
    fun parse(name: String): Pair<String, String?> {
        var t = prefix.replace(name, "")
        t = tags.replace(t, "")
        // The year is the last one in the name ("Blade Runner 2049 (2017)" keeps 2049 in the title).
        val y = year.findAll(t).lastOrNull()
        var found: String? = null
        if (y != null && (y.value.startsWith("(") || y.value.startsWith("[") || y.range.last >= t.trimEnd().length - 1)) {
            // Only take a year that's bracketed or at the very end, and not the whole name ("1917").
            val rest = t.removeRange(y.range).trim()
            if (rest.isNotEmpty()) {
                found = y.groupValues[1]
                t = rest
            }
        }
        t = tags.replace(t, "").trim().trim('-', '–', '|', ':', ' ').trim()
        return t to found
    }
}

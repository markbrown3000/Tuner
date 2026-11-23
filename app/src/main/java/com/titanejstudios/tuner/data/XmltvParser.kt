package com.titanejstudios.tuner.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Streaming XMLTV parser. Guide files can be hundreds of MB, so nothing is held in memory:
 * each matching programme is handed to [onProgramme] as soon as it's read.
 */
object XmltvParser {

    private val withOffset = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val noOffset = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val countryPrefix = Regex("^[a-z]{2,3}\\s*[:|-]\\s*") // "UK: ", "US | "
    private val nonAlnum = Regex("[^a-z0-9]")

    /** Loose name key: "BBC One HD" and "bbc one" both become "bbcone". */
    fun normalise(name: String): String {
        var n = name.lowercase(Locale.ROOT)
            .replace(countryPrefix, "")
            .replace(nonAlnum, "")
        for (suffix in listOf("uhd", "fhd", "hd", "sd", "4k")) {
            if (n.length > suffix.length + 2 && n.endsWith(suffix)) {
                n = n.dropLast(suffix.length)
                break
            }
        }
        return n
    }

    /**
     * @param byEpgId     lowercase tvg-id -> playlist channel ids
     * @param byName      normalised channel name -> playlist channel ids
     * @param catchupFrom per-channel earlier start for channels with catch-up
     * @param onChannel   every <channel> in the file (id, first display name)
     * @param onProgramme each kept programme with the playlist channel id it belongs to
     */
    fun parse(
        input: InputStream,
        byEpgId: Map<String, List<String>>,
        byName: Map<String, List<String>>,
        from: Long,
        to: Long,
        catchupFrom: Map<String, Long> = emptyMap(),
        onChannel: (String, String) -> Unit = { _, _ -> },
        /** The guide's logo for a channel (called with the playlist channel ids it matched). */
        onChannelLogo: (List<String>, String) -> Unit = { _, _ -> },
        onProgramme: (String, Programme) -> Unit,
    ) {
        val earliest = minOf(from, catchupFrom.values.minOrNull() ?: from)
        val xmlToTargets = HashMap<String, List<String>>()
        var kept = 0

        var channelId: String? = null
        val displayNames = ArrayList<String>()
        var channelIcon: String? = null

        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var progTitle: String? = null
        var progDesc: String? = null
        var progCategory: String? = null
        var progImage: String? = null

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            channelId = parser.getAttributeValue(null, "id")
                            displayNames.clear()
                            channelIcon = null
                        }
                        "display-name" -> if (channelId != null) displayNames += text(parser)
                        "programme" -> {
                            progChannel = parser.getAttributeValue(null, "channel")
                            progStart = parseTime(parser.getAttributeValue(null, "start"))
                            progStop = parseTime(parser.getAttributeValue(null, "stop"))
                            progTitle = null
                            progDesc = null
                            progCategory = null
                            progImage = null
                        }
                        "icon" -> {
                            val src = parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.startsWith("http") }
                            if (progChannel != null) {
                                if (progImage == null) progImage = src
                            } else if (channelId != null && channelIcon == null) {
                                channelIcon = src
                            }
                        }
                        "title" -> if (progChannel != null && progTitle == null) progTitle = text(parser)
                        "desc" -> if (progChannel != null && progDesc == null) progDesc = text(parser)
                        "category" -> if (progChannel != null && progCategory == null) progCategory = text(parser)
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = channelId
                            if (id != null) {
                                onChannel(id, displayNames.firstOrNull() ?: id)
                                val targets = LinkedHashSet<String>()
                                byEpgId[id.lowercase(Locale.ROOT)]?.let { targets.addAll(it) }
                                for (n in displayNames) byName[normalise(n)]?.let { targets.addAll(it) }
                                if (targets.isNotEmpty()) {
                                    xmlToTargets[id] = targets.toList()
                                    channelIcon?.let { onChannelLogo(targets.toList(), it) }
                                }
                            }
                            channelId = null
                        }
                        "programme" -> {
                            val ch = progChannel
                            val title = progTitle
                            if (ch != null && title != null && progStart > 0 && progStop > progStart &&
                                progStop > earliest && progStart < to
                            ) {
                                val targets = xmlToTargets[ch] ?: byEpgId[ch.lowercase(Locale.ROOT)]
                                if (targets != null) {
                                    val p = Programme(
                                        progStart, progStop, title,
                                        progDesc?.takeIf { it.isNotBlank() },
                                        progCategory?.takeIf { it.isNotBlank() },
                                        progImage,
                                    )
                                    for (t in targets) {
                                        if (progStop > (catchupFrom[t] ?: from)) {
                                            onProgramme(t, p)
                                            kept++
                                        }
                                    }
                                }
                            }
                            progChannel = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Truncated or slightly broken guide: keep whatever was read so far.
            if (kept == 0) throw e
        }
    }

    /** Convenience wrapper that collects everything into a map (used in tests). */
    fun parseToMap(
        input: InputStream,
        byEpgId: Map<String, List<String>>,
        byName: Map<String, List<String>>,
        from: Long,
        to: Long,
        catchupFrom: Map<String, Long> = emptyMap(),
    ): Map<String, List<Programme>> {
        val result = HashMap<String, ArrayList<Programme>>()
        parse(input, byEpgId, byName, from, to, catchupFrom) { id, p -> result.getOrPut(id) { ArrayList() }.add(p) }
        return result.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
    }

    private fun text(parser: XmlPullParser): String = try {
        parser.nextText().trim()
    } catch (e: Exception) {
        ""
    }

    fun parseTime(value: String?): Long {
        val t = value?.trim() ?: return 0
        if (t.length < 14) return 0
        return try {
            val rest = t.substring(14).trim()
            if (rest.startsWith("+") || rest.startsWith("-")) {
                withOffset.parse(t.substring(0, 14) + " " + rest)?.time ?: 0
            } else {
                noOffset.parse(t.substring(0, 14))?.time ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }
}

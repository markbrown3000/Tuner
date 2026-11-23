package com.titanejstudios.tuner.data

import java.io.InputStream

object M3uParser {

    data class Result(val channels: List<Channel>, val epgUrl: String?)

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** Streams the playlist line by line so very large playlists don't need to fit in memory as text. */
    fun parse(input: InputStream, sourceId: String = ""): Result {
        val channels = ArrayList<Channel>()
        val seenIds = HashMap<String, Int>()
        val groupOrder = HashMap<String, Int>()
        var epgUrl: String? = null
        var attrs: Map<String, String> = emptyMap()
        var name: String? = null
        var extGroup: String? = null

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim().removePrefix("﻿")
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXTM3U") -> {
                        val a = attributes(line)
                        epgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])
                            ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    line.startsWith("#EXTINF") -> {
                        attrs = attributes(line)
                        name = nameAfterComma(line)
                        extGroup = null
                    }
                    line.startsWith("#EXTGRP:") -> extGroup = line.substringAfter(':').trim()
                    line.startsWith("#") -> Unit // #EXTVLCOPT etc.
                    else -> {
                        val channelName = name?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: line.substringAfterLast('/')
                        val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                            ?: extGroup?.takeIf { it.isNotBlank() }
                            ?: "Uncategorised"
                        val baseId = "$sourceId/m:$group|$channelName"
                        val n = (seenIds[baseId] ?: 0) + 1
                        seenIds[baseId] = n
                        val (days, template) = catchup(attrs, line)
                        channels += Channel(
                            id = if (n == 1) baseId else "$baseId#$n",
                            name = channelName,
                            group = group,
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            url = line,
                            epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            sourceId = sourceId,
                            catchupDays = days,
                            catchupTemplate = template,
                            number = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"])?.trim()?.toIntOrNull() ?: 0,
                            groupPos = groupOrder.getOrPut(group) { groupOrder.size },
                        )
                        attrs = emptyMap()
                        name = null
                        extGroup = null
                    }
                }
            }
        }
        return Result(channels, epgUrl)
    }

    /** Works out catch-up support from catchup / catchup-days / catchup-source attributes. */
    private fun catchup(attrs: Map<String, String>, url: String): Pair<Int, String?> {
        val mode = (attrs["catchup"] ?: attrs["catchup-type"])?.trim()?.lowercase()
        val source = attrs["catchup-source"]?.trim()?.takeIf { it.isNotEmpty() }
        var days = (attrs["catchup-days"] ?: attrs["timeshift"] ?: attrs["tvg-rec"])?.trim()?.toIntOrNull() ?: 0
        fun resolve(src: String) = if (src.startsWith("http", true)) src else url + src
        val template: String? = when (mode) {
            null, "" -> if (days > 0 && source != null) resolve(source) else null
            "default" -> source?.let { resolve(it) } ?: Catchup.shiftTemplate(url)
            "append" -> source?.let { url + it }
            "shift", "archive" -> Catchup.shiftTemplate(url)
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> Catchup.flussonicTemplate(url)
            "xc" -> Catchup.xtreamTemplateFromLiveUrl(url)
            else -> source?.let { resolve(it) }
        }
        if (template != null && days <= 0) days = 7
        return if (template == null) 0 to null else days to template
    }

    private fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The display name is after the first comma that follows the last quoted attribute. */
    private fun nameAfterComma(line: String): String {
        val lastQuote = line.lastIndexOf('"')
        val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
        return if (comma >= 0) line.substring(comma + 1).trim() else ""
    }
}

package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * The UK broadcasters' own apps (BBC iPlayer, ITVX, Channel 4, My5). Their programmes can only
 * be played in their own apps, so Tuner opens the right app – searching for the programme when
 * the app allows it – for a programme on one of their channels.
 */
object CatchupApps {
    class Provider(
        val name: String,
        /** Known package names, best first (they differ between phone and TV versions). */
        val packages: List<String>,
        /** Matches the app's name on the TV, for versions with other package names. */
        val label: Regex,
        /** Matches the broadcaster's channels. */
        val channels: Regex,
    )

    val all = listOf(
        Provider(
            "BBC iPlayer",
            listOf("uk.co.bbc.iplayer", "bbc.iplayer.android"),
            Regex("iplayer", RegexOption.IGNORE_CASE),
            Regex("\\bbbc\\b|cbbc|cbeebies"),
        ),
        Provider(
            "ITVX",
            listOf("com.itv.itvx", "air.ITVMobilePlayer", "com.itv.loveislandapp"),
            Regex("^itv", RegexOption.IGNORE_CASE),
            Regex("\\bitv|\\bcitv\\b"),
        ),
        Provider(
            "Channel 4",
            listOf("com.channel4.ondemand", "com.channel4.androidtv"),
            Regex("^(channel ?4|all ?4|4)$", RegexOption.IGNORE_CASE),
            Regex("channel ?4|\\be4\\b|\\bmore ?4\\b|\\bfilm ?4\\b|4seven"),
        ),
        Provider(
            "My5",
            listOf("com.channel5.my5", "com.channel5.my5.androidtv"),
            Regex("^(my ?5|channel ?5|5)$", RegexOption.IGNORE_CASE),
            Regex("channel ?5|\\b5 ?star\\b|\\b5 ?usa\\b|\\b5 ?action\\b|\\b5 ?select\\b|^5\\b"),
        ),
    )

    /** "UK: BBC One HD" → BBC iPlayer. Null for other channels. */
    fun forChannel(channelName: String): Provider? {
        val n = channelName.lowercase(Locale.ROOT)
            .replace(Regex("^\\s*([a-z]{2,3}\\s*[|:\\-–]\\s*)+"), "")
            .trim()
        return all.firstOrNull { it.channels.containsMatchIn(n) }
    }
}

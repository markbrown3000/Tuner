package com.titanejstudios.backupguide

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Backup TV guide – personal use only. The part with no Android in it, shared by the TV app (via
 * [BackupGuide]) and the PC app: the free sources, matching channels, and filling gaps.
 * To remove it, see README.md in this module.
 */
object BackupGuideCore {
    /** One free guide. [urls] are tried in order until one answers. */
    class Source(val key: String, val name: String, val about: String, val urls: List<String>)

    val SOURCES = listOf(
        Source(
            "epgtalk_uk", "EPGTalk UK", "UK channels, including Sky",
            listOf("https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/UK_guide.xml.gz"),
        ),
        Source(
            "freeview", "Freeview-EPG", "UK free-to-air channels",
            listOf("https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml"),
        ),
        Source(
            "epgtalk_us", "EPGTalk US", "US channels",
            listOf("https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz"),
        ),
        Source(
            "epgpw_hk", "epg.pw Hong Kong", "Hong Kong channels, including Now Sports",
            listOf("https://epg.pw/xmltv/epg_HK.xml.gz", "https://epg.pw/xmltv/epg_HK.xml"),
        ),
    )

    /** A channel of yours: its id, the guide id from the playlist (tvg-id), and its name. */
    class Channel(val id: String, val epgId: String?, val name: String)

    class Programme(val start: Long, val stop: Long, val title: String, val desc: String?, val category: String?, val image: String?)

    /** A channel in one of the sources (for picking a match by hand). */
    class SourceChannel(val id: String, val name: String)

    /** The settings a fill run uses. [remembered] is updated with what worked. */
    class Settings(val custom: List<String>, val overrides: Map<String, String>, val remembered: MutableMap<String, String>)

    /** The name of a source, for "BBC One · from Freeview-EPG". */
    fun sourceName(key: String): String = SOURCES.firstOrNull { it.key == key }?.name ?: if (key.startsWith("custom")) "your own guide" else key

    /** The sources in use, built-in then your own. */
    fun sources(custom: List<String>): List<Source> = SOURCES + custom.mapIndexed { i, url ->
        Source("custom$i", "Your guide ${i + 1}", url.substringAfter("://").substringBefore('/'), listOf(url))
    }

    // ---------------- Matching ----------------

    private val wordNumbers = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )
    private val countryPrefix = Regex("^\\s*(uk|gb|us|usa|hk|ie|ca|au)\\s*[:|\\-]+\\s*", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val quality = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "576p", "50fps", "backup", "vip", "raw")

    /**
     * A name cleaned up for matching: "UK: BBC One HD (1080p)" and "BBC 1" both become "bbc1";
     * "+1" channels stay different ("itv1+1" → "itv1plus1").
     */
    fun cleanName(name: String): String {
        var n = name.lowercase(Locale.ROOT)
            .replace(countryPrefix, "")
            .replace(bracketed, " ")
            .replace("&", " and ")
            .replace("+", " plus ")
        val words = n.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            .filter { it !in quality }
            .map { wordNumbers[it] ?: it }
        n = words.joinToString("")
        return n
    }

    // ---------------- Filling the guide ----------------

    /**
     * Reads the sources and hands back programmes for your channels where your provider's guide
     * has none. [covered] gives a channel's saved programmes as start/stop pairs (sorted by start);
     * [open] downloads an address; [add] saves a programme. Returns how many were added.
     */
    fun fill(
        settings: Settings,
        channels: List<Channel>,
        from: Long,
        to: Long,
        open: (String) -> InputStream,
        covered: (String) -> LongArray?,
        add: (String, Programme) -> Unit,
        log: (String) -> Unit,
        newParser: () -> XmlPullParser,
        saveChannels: (String, List<SourceChannel>) -> Unit,
    ): Int {
        if (channels.isEmpty()) return 0
        val overrides = settings.overrides
        val remembered = settings.remembered
        // Which source each channel takes its backup guide from (by hand, else what worked before).
        val owner = HashMap<String, String>()
        for (c in channels) {
            val o = overrides[c.id]
            if (o == "off") continue
            (o ?: remembered[c.id])?.substringBefore('|')?.let { owner[c.id] = it }
        }
        val byEpgId = HashMap<String, MutableList<Channel>>()
        val byName = HashMap<String, MutableList<Channel>>()
        for (c in channels) {
            if (overrides[c.id] == "off") continue
            c.epgId?.takeIf { it.isNotBlank() }?.let { byEpgId.getOrPut(it.trim().lowercase()) { ArrayList() } += c }
            cleanName(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() } += c }
        }
        val coveredCache = HashMap<String, LongArray>()
        fun free(channelId: String, start: Long, stop: Long): Boolean {
            val iv = coveredCache.getOrPut(channelId) { covered(channelId) ?: LongArray(0) }
            // Binary search for the last saved programme starting before this one ends.
            var lo = 0
            var hi = iv.size / 2 - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (iv[mid * 2] < stop) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            // Any saved programme overlapping [start, stop) means it's not a gap. Look back a few.
            var i = found
            while (i >= 0 && i >= found - 3) {
                if (iv[i * 2 + 1] > start) return false
                i--
            }
            return true
        }
        val added = HashMap<String, MutableList<LongArray>>() // what this run added, so sources don't overlap
        var total = 0
        for (src in sources(settings.custom)) {
            val started = System.currentTimeMillis()
            val seenChannels = ArrayList<SourceChannel>()
            var n = 0
            val ok = src.urls.any { url ->
                runCatching {
                    open(url).use { input ->
                        n = readSource(newParser(), input, src, channels, byEpgId, byName, overrides, remembered, owner, from, to, seenChannels) { cid, p ->
                            val mine = added.getOrPut(cid) { ArrayList() }
                            if (!free(cid, p.start, p.stop) || mine.any { it[0] < p.stop && it[1] > p.start }) false
                            else {
                                mine += longArrayOf(p.start, p.stop)
                                add(cid, p)
                                total++
                                true
                            }
                        }
                    }
                    true
                }.onFailure { log("Backup guide: ${src.name} couldn't be read from $url (${it.message ?: it.javaClass.simpleName})") }.getOrDefault(false)
            }
            if (ok) {
                saveChannels(src.key, seenChannels)
                log("Backup guide: ${src.name} – $n programmes added in ${(System.currentTimeMillis() - started) / 1000}s")
            }
        }
        return total
    }

    private val withOffset = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val noOffset = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return runCatching {
            synchronized(this) { if (t.length > 14 && t.contains(' ')) withOffset.parse(t)!!.time else noOffset.parse(t.take(14))!!.time }
        }.getOrNull()
    }

    /** Reads one XMLTV file: matches its channels to yours, then passes on their programmes. */
    private fun readSource(
        parser: XmlPullParser,
        input: InputStream,
        src: Source,
        channels: List<Channel>,
        byEpgId: Map<String, List<Channel>>,
        byName: Map<String, List<Channel>>,
        overrides: Map<String, String>,
        remembered: MutableMap<String, String>,
        owner: HashMap<String, String>,
        from: Long,
        to: Long,
        seen: MutableList<SourceChannel>,
        /** Returns whether it was used (only gaps are filled). */
        onProgramme: (String, Programme) -> Boolean,
    ): Int {
        val stream = java.io.BufferedInputStream(input, 64 * 1024)
        // .gz files (and servers that gzip without saying so).
        stream.mark(2)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        val body: InputStream = if (b0 == 0x1f && b1 == 0x8b) java.util.zip.GZIPInputStream(stream, 64 * 1024) else stream
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(body, null)

        // Guide channel id → which of your channels it fills.
        var targets: HashMap<String, List<Channel>>? = null
        val xmlNames = HashMap<String, MutableList<String>>()
        fun buildTargets(): HashMap<String, List<Channel>> {
            val t = HashMap<String, MutableList<Channel>>()
            // By hand first, then what worked before, then guide id, then cleaned name.
            for (c in channels) {
                val o = overrides[c.id] ?: continue
                if (o == "off" || o.substringBefore('|') != src.key) continue
                t.getOrPut(o.substringAfter('|').lowercase()) { ArrayList() } += c
            }
            val placed = t.values.flatten().map { it.id }.toHashSet()
            for (c in channels) {
                if (c.id in placed || overrides[c.id] != null) continue
                val r = remembered[c.id] ?: continue
                if (r.substringBefore('|') != src.key) continue
                val xid = r.substringAfter('|').lowercase()
                if (xmlNames.containsKey(xid)) {
                    t.getOrPut(xid) { ArrayList() } += c
                    placed += c.id
                }
            }
            for ((xid, names) in xmlNames) {
                val hits = LinkedHashSet<Channel>()
                byEpgId[xid]?.let { hits += it }
                if (hits.isEmpty()) for (nm in names) byName[cleanName(nm)]?.let { hits += it }
                for (c in hits) {
                    if (c.id in placed) continue
                    // A channel already filled from another source stays with that one.
                    val own = owner[c.id]
                    if (own != null && own != src.key) continue
                    t.getOrPut(xid) { ArrayList() } += c
                    placed += c.id
                }
            }
            return HashMap(t)
        }

        var n = 0
        var chanId: String? = null
        var p: Array<Any?>? = null // start, stop, channel, title, desc, category, image
        var field: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        chanId = parser.getAttributeValue(null, "id")?.trim()
                        chanId?.let { xmlNames.getOrPut(it.lowercase()) { ArrayList() } }
                    }
                    "display-name" -> if (chanId != null && p == null) { field = "name"; text.setLength(0) }
                    "programme" -> {
                        if (targets == null) targets = buildTargets()
                        val cid = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                        val start = time(parser.getAttributeValue(null, "start"))
                        val stop = time(parser.getAttributeValue(null, "stop"))
                        p = if (cid != null && start != null && stop != null && stop > from && start < to && targets!!.containsKey(cid)) {
                            arrayOf(start, stop, cid, null, null, null, null)
                        } else null
                    }
                    "title", "desc", "category" -> if (p != null && p!![when (parser.name) { "title" -> 3; "desc" -> 4; else -> 5 }] == null) {
                        field = parser.name; text.setLength(0)
                    }
                    "icon" -> if (p != null && p!![6] == null) p!![6] = parser.getAttributeValue(null, "src")
                }
                XmlPullParser.TEXT -> if (field != null) text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> if (field == "name") {
                        chanId?.let { id ->
                            val nm = text.toString().trim()
                            if (nm.isNotEmpty()) {
                                xmlNames.getOrPut(id.lowercase()) { ArrayList() } += nm
                                if (seen.size < 8000 && seen.none { it.id == id }) seen += SourceChannel(id, nm)
                            }
                        }
                        field = null
                    }
                    "channel" -> chanId = null
                    "title" -> if (field == "title") { p?.set(3, text.toString().trim()); field = null }
                    "desc" -> if (field == "desc") { p?.set(4, text.toString().trim()); field = null }
                    "category" -> if (field == "category") { p?.set(5, text.toString().trim()); field = null }
                    "programme" -> {
                        val q = p
                        if (q != null && !(q[3] as String?).isNullOrBlank()) {
                            val prog = Programme(q[0] as Long, q[1] as Long, q[3] as String, q[4] as String?, q[5] as String?, q[6] as String?)
                            for (c in targets!![q[2] as String].orEmpty()) {
                                if (onProgramme(c.id, prog)) {
                                    n++
                                    // This source worked for this channel: it keeps it from now on.
                                    owner[c.id] = src.key
                                    remembered[c.id] = src.key + "|" + q[2]
                                }
                            }
                        }
                        p = null
                    }
                }
            }
            event = parser.next()
        }
        return n
    }

    fun readList(s: String?): List<String> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun readMap(s: String?): Map<String, String> = runCatching {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** A source's channels saved as JSON (for choosing a match by hand). */
    fun channelsJson(list: List<SourceChannel>): String {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.id).put(it.name)) }
        return a.toString()
    }

    fun channelsFromJson(s: String): List<SourceChannel> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i -> a.getJSONArray(i).let { SourceChannel(it.getString(0), it.getString(1)) } }
    }.getOrDefault(emptyList())
}

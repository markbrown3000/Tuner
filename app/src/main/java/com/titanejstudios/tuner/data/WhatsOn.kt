package com.titanejstudios.tuner.data

import java.util.Calendar
import java.util.Locale

/** Groups programmes into broad kinds, from the guide's category – or the channel's group when there isn't one. */
object Genres {
    val all = listOf("Sport", "Film", "News", "Kids", "Factual", "Entertainment", "Drama", "Music")

    fun of(category: String?, title: String, channelGroup: String?): String? {
        fromText(category)?.let { return it }
        if (Sports.classify(Programme(0, 0, title, null, category)) != null) return "Sport"
        return fromText(channelGroup)
    }

    private fun fromText(s: String?): String? {
        val c = s?.lowercase(Locale.ROOT) ?: return null
        return when {
            "sport" in c || "football" in c || "soccer" in c || "racing" in c || "golf" in c || "boxing" in c -> "Sport"
            "film" in c || "movie" in c || "cinema" in c -> "Film"
            "news" in c || "weather" in c -> "News"
            "child" in c || "kid" in c || "animation" in c || "cartoon" in c || "junior" in c -> "Kids"
            "doc" in c || "factual" in c || "nature" in c || "history" in c || "science" in c -> "Factual"
            "music" in c -> "Music"
            "comedy" in c || "entertainment" in c || "game show" in c || "reality" in c || "quiz" in c -> "Entertainment"
            "drama" in c || "series" in c || "soap" in c || "crime" in c -> "Drama"
            else -> null
        }
    }
}

/** "What's on": programmes airing at a chosen time (now, tonight at 9…), optionally one kind only. */
object WhatsOn {
    class Slot(val label: String, val at: Long)

    fun slots(now: Long): List<Slot> {
        val out = arrayListOf(Slot("Now", now))
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        for (h in listOf(18, 20, 21, 22)) {
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > now + 30 * 60_000L) out += Slot(if (h == 21) "Tonight 9pm" else "${h - 12}pm", cal.timeInMillis)
        }
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 20)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        out += Slot("Tomorrow 8pm", cal.timeInMillis)
        return out
    }

    /** One programme on the What's On list. */
    class Entry(
        val channel: Channel,
        val programme: Programme,
        /** What follows it on that channel. */
        val next: Programme?,
        /** "Also on ITV1 +1 at 22:00". */
        val alsoOn: String?,
        /** Why it's near the top ("You watch a lot of drama"). */
        val reason: String?,
        val yourTeam: Boolean,
        val favourite: Boolean,
    )

    class Result(
        /** Starting in the next half hour (only when looking at "now"). */
        val soon: List<Entry>,
        val main: List<Entry>,
    )

    private val clutterName = Regex(
        "(?i)(\\bqvc\\b|ideal world|jewellery|\\bgems\\b|\\btjc\\b|shop|teleshopping|\\bxxx\\b|adult|babestation|\\bradio\\b|\\bfm\\b|18\\s*\\+|\\bpay ?per ?view\\b|\\bppv\\b)"
    )
    private val plusOne = Regex("(?i)\\s*\\+\\s*1(?![0-9])")

    /** Channels in another language or for another country (by their group, tag or name). */
    private val worldGroup = Regex(
        "(?i)\\b(asian|asia|bollywood|desi|indian?|hindi|punjabi|tamil|telugu|malayalam|kannada|marathi|gujarati|bengali|bangla|urdu|" +
            "pakistan|arabic|arab|african?|nollywood|turk|turkish|kurd|persian|farsi|polish|polska|romania|russian|ukrain|albania|greek|" +
            "portug|brazil|latino|spanish|french|german|italian|dutch|nordic|swedish|norwegian|danish|finnish|ex-?yu|balkan|" +
            "chinese|japan|korean|filipino|pinoy|thai|vietnam|somali|caribbean|hebrew)\\b"
    )
    private val worldName = Regex(
        "(?i)^\\W*(zee|star (plus|gold|bharat|life|jalsha|vijay|pravah|maa|utsav)|colors|sony (sab|max|tv|aath|yay|pal|wah|marathi)|" +
            "b4u|&tv|and tv|&pictures|aaj tak|abp|ndtv|ptc|ary|geo|hum|express news|samaa|dunya|bol |mbc|rotana|al jazeera arabic|" +
            "zing|9x|mastii|sangeet|dharm|aastha|sanskar|ishara|dangal|shemaroo|bflix|ptv|channel 44|kalaignar|sun tv|ktv|asianet|" +
            "rishtey|zindagi|sahara|news18|republic|times now|india today|dd )(?![a-z])"
    )

    /** Another country's or language's channel ("Tidy" hides them, apart from your favourites). */
    fun isWorld(c: Channel): Boolean {
        Languages.tagOf(c)?.let { if (!it.english) return true }
        return worldGroup.containsMatchIn(c.group) || worldName.containsMatchIn(c.name)
    }

    /** Shopping, adult and radio channels – hidden from What's On unless you ask for everything. */
    fun isClutter(c: Channel): Boolean = clutterName.containsMatchIn(c.name) || clutterName.containsMatchIn(c.group)

    fun isPlusOne(c: Channel): Boolean = plusOne.containsMatchIn(c.name)

    /** "ITV1 +1 HD" → the key of "ITV1". */
    private fun baseKey(c: Channel): String = ChannelMerge.key(c.name.replace(plusOne, " "))

    /**
     * What's on at [at] (with "starting soon" when [at] is now), in "for you" order: shows you
     * follow or like first, then favourites, then by how well each suits your viewing.
     */
    fun build(
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        at: Long,
        isNow: Boolean,
        genre: String?,
        favourites: Set<String>,
        favouritesOnly: Boolean,
        tidy: Boolean,
        taste: Taste,
        teams: Set<String>,
        max: Int = 300,
    ): Result {
        val seen = HashSet<String>()
        // Where each programme is shown on a +1 channel (base channel key + title → +1 channel).
        val plusOnes = HashMap<String, Pair<Channel, Programme>>()
        if (tidy) {
            for (c in channels) {
                if (!isPlusOne(c)) continue
                val list = epg[c.id] ?: continue
                for (p in list) if (p.stop > at && p.start < at + 6 * 3_600_000L) plusOnes.putIfAbsent(baseKey(c) + "|" + p.title.lowercase(Locale.ROOT), c to p)
            }
        }
        val baseKeys = if (tidy) channels.filter { !isPlusOne(it) }.map { ChannelMerge.key(it.name) }.toSet() else emptySet()

        fun entry(c: Channel, p: Programme): Entry? {
            if (p.title.isBlank() || p.title.equals(c.name, ignoreCase = true)) return null
            val kind = Genres.of(p.category, p.title, c.group)
            if (genre != null && kind != genre) return null
            if (!seen.add(p.title.lowercase(Locale.ROOT) + "|" + p.start)) return null
            val list = epg[c.id]
            val next = list?.firstOrNull { it.start >= p.stop }
            val also = plusOnes[ChannelMerge.key(c.name) + "|" + p.title.lowercase(Locale.ROOT)]
                ?.takeIf { it.second.start > p.start }
                ?.let { (pc, pp) -> "Also on ${pc.name} at ${hhmm(pp.start)}" }
            val team = teams.any { SportFollow.mentions(p.title, it) }
            return Entry(c, p, next, also, null, team, c.id in favourites)
        }

        val soon = ArrayList<Entry>()
        val main = ArrayList<Pair<Entry, Double>>()
        for (c in channels) {
            if (favouritesOnly && c.id !in favourites) continue
            if (tidy && isClutter(c)) continue
            // Tidy also keeps to your own country's channels (favourites always stay).
            if (tidy && c.id !in favourites && isWorld(c)) continue
            // A +1 channel's programmes show as "Also on … +1" on the main channel instead.
            if (tidy && isPlusOne(c) && baseKey(c) in baseKeys) continue
            val list = epg[c.id] ?: continue
            val p = list.firstOrNull { it.start <= at && it.stop > at }
            if (p != null) {
                entry(c, p)?.let { e ->
                    val (fit, why) = taste.score(p.title, Genres.of(p.category, p.title, c.group), at)
                    var score = fit
                    if (e.yourTeam) score += 20.0
                    if (e.favourite) score += 3.0
                    if (p.isNew || p.isPremiere) score += 1.5
                    if (Sports.classify(p, c.group) != null && Sports.looksLive(p)) score += 1.0
                    main += Entry(e.channel, e.programme, e.next, e.alsoOn, why, e.yourTeam, e.favourite) to score
                }
            }
            if (isNow && soon.size < 60) {
                val up = list.firstOrNull { it.start > at && it.start <= at + 30 * 60_000L }
                if (up != null) entry(c, up)?.let { soon += it }
            }
            if (main.size >= max) break
        }
        // Stable: equal scores keep the channel order.
        val ordered = main.sortedByDescending { it.second }.map { it.first }
        val soonOrdered = soon.sortedWith(compareByDescending<Entry> { it.yourTeam }.thenByDescending { it.favourite }.thenBy { it.programme.start })
        return Result(soonOrdered.take(40), ordered)
    }

    private fun hhmm(t: Long): String = java.text.SimpleDateFormat("HH:mm", Locale.UK).format(java.util.Date(t))

    /** Midnight [days] from today, plus [minuteOfDay]. */
    fun timeOf(days: Int, minuteOfDay: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_YEAR, days)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis + minuteOfDay * 60_000L
    }

    /** "Today", "Tomorrow", "Thu 27". */
    fun dayName(days: Int, now: Long): String = when (days) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE d", Locale.UK).format(java.util.Date(timeOf(days, 12 * 60, now)))
    }

    fun list(
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        at: Long,
        genre: String?,
        favourites: Set<String>,
        favouritesOnly: Boolean,
        max: Int = 300,
    ): List<HomeFeed.Pick> {
        val favs = ArrayList<HomeFeed.Pick>()
        val rest = ArrayList<HomeFeed.Pick>()
        val seen = HashSet<String>()
        for (c in channels) {
            val isFav = c.id in favourites
            if (favouritesOnly && !isFav) continue
            val p = epg[c.id]?.firstOrNull { it.start <= at && it.stop > at } ?: continue
            if (p.title.isBlank() || p.title.equals(c.name, ignoreCase = true)) continue
            if (genre != null && Genres.of(p.category, p.title, c.group) != genre) continue
            // Same programme on several copies of a channel (HD/FHD) – show it once.
            if (!seen.add(p.title.lowercase(Locale.ROOT) + "|" + p.start)) continue
            (if (isFav) favs else rest) += HomeFeed.Pick(c, p)
            if (favs.size + rest.size >= max) break
        }
        return favs + rest
    }
}

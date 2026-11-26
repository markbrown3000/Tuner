package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * Things you can say to Flicker's 🎤 besides a channel or a search:
 * "record EastEnders", "next episode", "what's on BBC One later", "where can I watch Dune",
 * "remind me about Match of the Day", "go to the guide", "something funny", "surprise me".
 */
sealed class VoiceCommand {
    class Record(val what: String) : VoiceCommand()
    class Remind(val what: String) : VoiceCommand()
    object NextEpisode : VoiceCommand()
    /** [later]: "later" / "tonight" (otherwise now and next). */
    class WhatsOn(val channel: String, val later: Boolean) : VoiceCommand()
    class WhereToWatch(val what: String) : VoiceCommand()
    class Go(val place: String) : VoiceCommand()
    class Mood(val key: String) : VoiceCommand()
    object Surprise : VoiceCommand()
    object Stop : VoiceCommand()
    object CarryOn : VoiceCommand()

    companion object {
        private val lead = Regex("^(hey google|ok google|alexa|flicker|please|can you|could you|i want to|i'd like to|let's)[ ,]+")
        private val trail = Regex("[ ,]+(please|on flicker|in flicker|for me)$")

        private fun tidy(text: String): String {
            var t = text.lowercase(Locale.ROOT).replace(Regex("[?!.]"), " ").replace('’', '\'').trim()
            t = t.replace(Regex("\\s+"), " ")
            var before: String
            do {
                before = t
                t = t.replace(lead, "").replace(trail, "").trim()
            } while (t != before)
            return t
        }

        private val places = mapOf(
            "home" to "home", "the home screen" to "home", "home screen" to "home",
            "guide" to "guide", "the guide" to "guide", "tv guide" to "guide", "the tv guide" to "guide",
            "channels" to "channels", "the channels" to "channels",
            "films" to "films", "movies" to "films", "the films" to "films",
            "series" to "series", "tv shows" to "series", "box sets" to "series",
            "recordings" to "recordings", "my recordings" to "recordings",
            "settings" to "settings", "sport" to "sport", "sports" to "sport",
            "youtube" to "youtube", "plex" to "plex", "catch up" to "catchup", "catch-up" to "catchup",
            "my list" to "mylist", "what's on" to "whatson", "whats on" to "whatson",
        )

        fun parse(text: String): VoiceCommand? {
            val t = tidy(text)
            if (t.isEmpty()) return null
            Regex("^(record|tape)( the)? (.+)$").find(t)?.let { m -> return Record(m.groupValues[3].trim()) }
            Regex("^remind me (about|to watch|when) (.+?)( is on| starts)?$").find(t)?.let { m -> return Remind(m.groupValues[2].trim()) }
            if (Regex("^(play |watch |put on |start )?(the )?next (episode|one|part)$").matches(t) || t == "skip to the next episode") return NextEpisode
            if (t in setOf("carry on", "carry on watching", "continue", "continue watching", "resume", "keep watching")) return CarryOn
            if (t in setOf("stop", "stop watching", "stop playing", "turn it off")) return Stop
            if (t in setOf("surprise me", "pick a film", "pick something", "choose something", "pick a film for me")) return Surprise
            Regex("^where (can|do|could) (i|we) (watch|see|find|stream|get) (.+)$").find(t)?.let { m -> return WhereToWatch(m.groupValues[4].trim()) }
            Regex("^(where's|where is) (.+) (on|showing)$").find(t)?.let { m -> return WhereToWatch(m.groupValues[2].trim()) }
            Regex("^what's (on|showing)( on)? (.+?)( later| tonight| this evening| next)?$").find(t.replace("what is", "what's").replace("whats", "what's"))?.let { m ->
                val ch = m.groupValues[3].trim()
                // "what's on tonight" is What's On, not a channel called "tonight".
                if (ch in setOf("tonight", "now", "later", "tv", "telly", "the telly", "the tv")) return Go("whatson")
                return WhatsOn(ch, m.groupValues[4].isNotBlank() && m.groupValues[4].trim() != "next")
            }
            Regex("^(go to|open|show me|show|take me to)( the)? (.+)$").find(t)?.let { m ->
                val p = m.groupValues[3].trim()
                places[p]?.let { return Go(it) }
                places["the $p"]?.let { return Go(it) }
            }
            Moods.fromSpeech(t)?.let { return Mood(it.key) }
            return null
        }
    }
}

package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * "What are you in the mood for?" – films picked by feel rather than by category. A film matches a
 * mood by its provider category, its Plex/TMDB genres or its title words; "Short tonight" goes by
 * running time when it's known.
 */
object Moods {
    class Mood(
        val key: String,
        val emoji: String,
        val label: String,
        /** Words in a category or genre that fit. */
        val genres: List<String>,
        /** Said out loud: "something funny", "I want a scary film". */
        val spoken: List<String>,
        /** Fits only if the category doesn't also say one of these ("family" isn't horror). */
        val not: List<String> = emptyList(),
        /** Under this many minutes, when the length is known (0 = any length). */
        val maxMinutes: Int = 0,
    )

    val all = listOf(
        Mood("funny", "😂", "Something funny", listOf("comedy", "comedies", "sitcom", "funny", "parody", "spoof"), listOf("funny", "comedy", "a laugh", "make me laugh", "something light")),
        Mood("feelgood", "🥰", "Feel-good", listOf("family", "romance", "romantic", "musical", "feel good", "feel-good", "animation", "comedy"), listOf("feel good", "feel-good", "cheer me up", "happy", "uplifting", "cosy", "cozy"), not = listOf("horror", "thriller", "war", "crime")),
        Mood("scary", "😱", "Scary", listOf("horror", "terror", "slasher", "supernatural", "zombie", "ghost"), listOf("scary", "horror", "frighten", "spooky", "creepy")),
        Mood("edge", "😬", "Edge of your seat", listOf("thriller", "suspense", "mystery", "crime", "heist", "spy"), listOf("thriller", "tense", "edge of my seat", "suspense", "gripping", "mystery")),
        Mood("action", "💥", "Big action", listOf("action", "adventure", "martial arts", "superhero", "war", "western"), listOf("action", "explosions", "big action", "adventure", "fighting")),
        Mood("think", "🧠", "Make me think", listOf("documentary", "docu", "drama", "biography", "biopic", "history", "historical", "science fiction", "sci-fi", "sci fi"), listOf("make me think", "thought provoking", "thought-provoking", "documentary", "clever", "true story")),
        Mood("cry", "😢", "A good cry", listOf("drama", "romance", "romantic", "biography", "biopic", "war"), listOf("a good cry", "make me cry", "sad", "weepie", "tear jerker", "tearjerker", "emotional"), not = listOf("comedy", "horror")),
        Mood("family", "👨‍👩‍👧", "With the kids", listOf("family", "kids", "children", "animation", "animated", "cartoon", "disney", "pixar"), listOf("for the kids", "with the kids", "family film", "family", "kids film", "cartoon", "animated"), not = listOf("horror", "adult", "18+")),
        Mood("short", "⏱", "Short tonight", emptyList(), listOf("short", "something short", "quick film", "not too long", "under an hour and a half"), maxMinutes = 100),
        Mood("scifi", "🚀", "Out of this world", listOf("science fiction", "sci-fi", "sci fi", "scifi", "fantasy", "space"), listOf("sci-fi", "sci fi", "science fiction", "space", "fantasy", "aliens")),
    )

    fun byKey(key: String): Mood? = all.firstOrNull { it.key == key }

    /** The mood in what was said ("something funny please"), or null. */
    fun fromSpeech(text: String): Mood? {
        val t = text.lowercase(Locale.ROOT)
        val wanting = Regex("\\b(something|anything|a film|film|movie|in the mood|fancy|feel like|want|put on|show me)\\b").containsMatchIn(t)
        if (!wanting) return null
        // The longest phrase that fits wins ("feel good" over "good").
        return all.flatMap { m -> m.spoken.map { m to it } }
            .filter { (_, phrase) -> Regex("\\b" + Regex.escape(phrase) + "\\b").containsMatchIn(t) }
            .maxByOrNull { it.second.length }?.first
    }

    /**
     * Whether a film fits: [labels] are its category and genres (any case), [minutes] its length
     * when known.
     */
    fun fits(mood: Mood, labels: List<String>, minutes: Int?): Boolean {
        if (mood.maxMinutes > 0) return minutes != null && minutes in 1..mood.maxMinutes
        val text = labels.joinToString(" | ").lowercase(Locale.ROOT)
        if (mood.not.any { it in text }) return false
        return mood.genres.any { g -> Regex("\\b" + Regex.escape(g) + "\\b").containsMatchIn(text) }
    }
}

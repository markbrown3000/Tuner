package com.titanejstudios.tuner.integrations

/**
 * "Cinema lights": tells Home Assistant when to dim the room and when to bring the lights back.
 * Tuner sends a `lights` event with state "dim" or "restore"; one small automation in Home
 * Assistant does the rest (see the README).
 */
object CinemaLights {
    val scopes = listOf("off", "films", "films_series", "everything")

    fun label(scope: String): String = when (scope) {
        "films" -> "Films"
        "films_series" -> "Films and series"
        "everything" -> "Everything (live TV too)"
        else -> "Off"
    }

    fun next(scope: String): String = scopes[(scopes.indexOf(scope).coerceAtLeast(0) + 1) % scopes.size]

    /**
     * What's on screen, boiled down: [kind] is "film", "episode", "plex", "live", "catchup",
     * "recording" or null when nothing is playing.
     */
    fun shouldDim(scope: String, kind: String?, fullScreen: Boolean, paused: Boolean): Boolean {
        if (kind == null || !fullScreen || paused) return false
        return when (scope) {
            "films" -> kind == "film" || kind == "plex"
            "films_series" -> kind == "film" || kind == "episode" || kind == "plex"
            "everything" -> true
            else -> false
        }
    }
}

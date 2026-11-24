package com.titanejstudios.tuner.integrations

import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Carry on where you left off between linked devices (TV ↔ phone) over the Wi-Fi.
 * The same lists as the network-drive sync: favourites, My list, followed series and resume
 * points, plus where each series' intro ends. Lists: the newest change wins. Resume points and
 * intros: merged item by item. Each device pulls from the others, so nothing is ever pushed.
 */
object LinkSync {
    const val INTROS = "intros"
    const val TEAMS = "teams"
    const val STARTED = "started"
    private const val DONE = "done"

    /** Remembers that [contentId] was finished (keeps the newest 200). */
    fun markDone(store: Store, contentId: String, at: Long = System.currentTimeMillis()) {
        val all = store.resumeDone + (contentId to at.toString())
        store.resumeDone = all.entries.sortedByDescending { it.value.toLongOrNull() ?: 0L }.take(200).associate { it.key to it.value }
    }

    /** This device's lists, for another device to pull. */
    fun snapshot(store: Store): JSONObject {
        val sections = JSONObject()
        val favs = JSONObject()
        for (p in store.profiles) favs.put(p.id, JSONArray(store.favourites(p.id)))
        sections.put(TvSync.FAVOURITES, JSONObject().put("at", store.syncStamp(TvSync.FAVOURITES)).put("data", favs))
        sections.put(TvSync.WATCHLIST, JSONObject().put("at", store.syncStamp(TvSync.WATCHLIST)).put("data", store.watchlistJson))
        sections.put(TvSync.FOLLOWED, JSONObject().put("at", store.syncStamp(TvSync.FOLLOWED)).put("data", store.followedSeriesJson))
        val resume = JSONArray()
        store.resume.values.forEach { resume.put(TvSync.resumeToJson(it)) }
        sections.put(TvSync.RESUME, JSONObject().put("data", resume))
        sections.put(INTROS, JSONObject(store.introEnds))
        // Each profile's own Continue watching, by profile name (ids differ between devices).
        val started = JSONObject()
        for (p in store.profiles) {
            val keys = store.started(p.id)
            if (keys.isNotEmpty()) started.put(p.name.lowercase().trim(), JSONArray(keys.toList()))
        }
        sections.put(STARTED, started)
        sections.put(
            TEAMS,
            JSONObject().put("at", store.syncStamp(TEAMS)).put("names", JSONArray(store.footballTeams.toList()))
                .put("ids", JSONObject(store.footballTeamIds)),
        )
        sections.put(DONE, JSONObject(store.resumeDone))
        return JSONObject().put("app", "Flicker").put("v", 1).put("sections", sections)
    }

    /** Takes whatever is newer from another device's [remote] snapshot. Returns the sections that changed here. */
    fun apply(store: Store, remote: JSONObject): Set<String> {
        val sections = remote.optJSONObject("sections") ?: return emptySet()
        val changed = HashSet<String>()

        // Favourites: only for profiles this device has (profile ids match after "Copy to another TV").
        sections.optJSONObject(TvSync.FAVOURITES)?.let { r ->
            val data = r.optJSONObject("data") ?: JSONObject()
            val theirStamp = r.optLong("at")
            val mine = store.syncStamp(TvSync.FAVOURITES)
            val hereEmpty = store.profiles.all { store.favourites(it.id).isEmpty() }
            if (theirStamp > mine || (mine == 0L && hereEmpty && data.length() > 0)) {
                val ids = store.profiles.map { it.id }.toSet()
                var any = false
                for (pid in data.keys()) {
                    if (pid !in ids) continue
                    val arr = data.optJSONArray(pid) ?: continue
                    store.setFavourites(pid, (0 until arr.length()).map { arr.getString(it) })
                    any = true
                }
                if (any) {
                    store.setSyncStamp(TvSync.FAVOURITES, theirStamp)
                    changed += TvSync.FAVOURITES
                }
            }
        }

        // My list and followed series (JSON text).
        for ((section, read, write) in listOf(
            Triple(TvSync.WATCHLIST, { store.watchlistJson }, { v: String -> store.watchlistJson = v }),
            Triple(TvSync.FOLLOWED, { store.followedSeriesJson }, { v: String -> store.followedSeriesJson = v }),
        )) {
            val r = sections.optJSONObject(section) ?: continue
            val theirs = r.optString("data", "[]")
            val theirStamp = r.optLong("at")
            val mine = store.syncStamp(section)
            val hereEmpty = read().let { it.isBlank() || it == "[]" }
            if ((theirStamp > mine || (mine == 0L && hereEmpty)) && theirs != read() && theirs != "[]") {
                write(theirs)
                store.setSyncStamp(section, theirStamp)
                changed += section
            }
        }

        // Finished on the other device: drop it here too.
        sections.optJSONObject(DONE)?.let { o ->
            val mineDone = store.resumeDone
            val add = HashMap<String, String>()
            for (k in o.keys()) {
                val t = o.optString(k).toLongOrNull() ?: continue
                if ((mineDone[k]?.toLongOrNull() ?: 0L) < t) add[k] = t.toString()
            }
            if (add.isNotEmpty()) store.resumeDone = mineDone + add
        }
        val done = store.resumeDone

        // Resume points: the newest entry for each film or episode (unless it's been finished since).
        sections.optJSONObject(TvSync.RESUME)?.optJSONArray("data")?.let { a ->
            val theirs = HashMap<String, ResumeEntry>()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { TvSync.resumeFromJson(it) }?.let { theirs[it.contentId] = it }
            val mine = store.resume.filter { (id, e) -> (done[id]?.toLongOrNull() ?: 0L) < e.updatedAt }
            theirs.entries.removeAll { (id, e) -> (done[id]?.toLongOrNull() ?: 0L) >= e.updatedAt }
            val merged = TvSync.mergeResume(mine, theirs)
            if (merged != store.resume) {
                store.resume = merged
                changed += TvSync.RESUME
            }
        }

        // Your teams (My teams): the newest choice wins.
        sections.optJSONObject(TEAMS)?.let { r ->
            val theirStamp = r.optLong("at")
            val mine = store.syncStamp(TEAMS)
            val names = r.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            if ((theirStamp > mine || (mine == 0L && store.footballTeams.isEmpty())) && names.isNotEmpty() && names != store.footballTeams) {
                store.footballTeams = names
                val ids = HashMap(store.footballTeamIds)
                r.optJSONObject("ids")?.let { o -> for (k in o.keys()) ids[k] = o.optString(k) }
                store.footballTeamIds = ids.filterKeys { it in names }
                store.setSyncStamp(TEAMS, theirStamp)
                changed += TEAMS
            }
        }

        // What each profile has started: added to the profile of the same name here.
        sections.optJSONObject(STARTED)?.let { o ->
            for (p in store.profiles) {
                val arr = o.optJSONArray(p.name.lowercase().trim()) ?: continue
                val theirs = (0 until arr.length()).map { arr.getString(it) }.toSet()
                val mine = store.started(p.id)
                if (!mine.containsAll(theirs)) {
                    store.setStarted(p.id, (mine + theirs).toList().takeLast(300).toSet())
                    changed += STARTED
                }
            }
        }

        // Intros: fill in series this device hasn't learned yet.
        sections.optJSONObject(INTROS)?.let { o ->
            val mine = store.introEnds
            val add = HashMap<String, String>()
            for (k in o.keys()) if (k !in mine) add[k] = o.optString(k)
            if (add.isNotEmpty()) {
                store.introEnds = mine + add
                changed += INTROS
            }
        }
        return changed
    }
}

@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.R
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.FAVOURITES
import com.titanejstudios.tuner.RECENT
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.apps.TvApp
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.ResumeEntry
import kotlinx.coroutines.delay

/** What the big area at the top describes – whatever tile is highlighted. */
private class HeroInfo(
    val title: String,
    val subtitle: String,
    val description: String?,
    val channel: Channel?,
    val image: String?,
    /** A wide (16:9) picture fills the background; a poster is shown whole on the right instead. */
    val wide: Boolean = true,
    /** Small coloured line above the title ("NEW ON PLEX · FILM", "● LIVE · YOUR TEAM"). */
    val label: String? = null,
    val labelColor: Color = Color(0xFFFFB648),
    val score: String? = null,
)

/** Home's rows. The order and which are shown can be changed in Settings › Home rows. */
enum class HomeRow(val title: String) {
    Continue("Carry on watching"),
    YourShows("Your shows tonight"),
    Sport("Sport on now"),
    Trending("Trending this week"),
    PlexNew("New on Plex"),
    TopPicks("Top picks for you"),
    Missed("Missed it?"),
    YouTube("New on your YouTube"),
    YouTubeForYou("Recommended on YouTube"),
    Because("Because you watched"),
    Moods("In the mood for…"),
    Favourites("Favourites"),
    Recordings("Recordings"),
    NewEpisodes("New episodes"),
    FamilyPicks("Family picks"),
    Collections("Your collections"),
    Bookmarks("Bookmarks"),
    ComingSoon("Coming soon"),
    MyList("My list"),
    Apps("Your apps"),
    All("All channels"),
}

/** One tile on Home – live TV, Plex, a film, a recording… all drawn the same way. */
private class HomeCard(
    val key: String,
    val tag: String?,
    val tagBg: Color,
    val tagFg: Color,
    val title: String,
    val line1: String?,
    val line2: String?,
    val image: String?,
    /** The picture is a tall poster: shown whole at the side, not cropped to fill. */
    val poster: Boolean,
    val channel: Channel?,
    val progress: Float?,
    val progressColor: Color,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val hero: HeroInfo,
    /** Mood tiles: a few posters fanned out, over the mood's own colour. */
    val collage: List<String> = emptyList(),
    val glow: Color? = null,
    val emoji: String? = null,
)

/** Each mood's colour for its tile. */
private val moodGlow = mapOf(
    "funny" to Color(0xFFF59E0B), "feelgood" to Color(0xFFEC4899), "scary" to Color(0xFF7F1D1D), "edge" to Color(0xFF0F766E),
    "action" to Color(0xFFEA580C), "think" to Color(0xFF4338CA), "cry" to Color(0xFF2563EB), "family" to Color(0xFF16A34A),
    "short" to Color(0xFF9333EA), "scifi" to Color(0xFF0891B2),
)

private val TILE_W = 214.dp
private val TILE_H = 140.dp
private val PlexGold = Color(0xFFE5A00D)
private val LiveRed = Color(0xFFE5213B)
private val DoneGreen = Color(0xFF2FBF71)

private fun timeLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else "$m min left"
}

private fun channelCard(vm: TunerViewModel, c: Channel, list: List<Channel>, now: Long, followed: Set<String>): HomeCard {
    val (cur, next) = vm.nowNext(c.id, now)
    val score: String? = null
    val yourTeam = cur != null && followed.any { com.titanejstudios.tuner.data.SportFollow.mentions(cur.title, it) }
    val sport = cur != null && com.titanejstudios.tuner.data.Sports.classify(cur, c.group) != null
    val tag = when {
        score != null -> "LIVE  $score"
        sport && cur != null && com.titanejstudios.tuner.data.Sports.looksLive(cur) -> "LIVE"
        cur != null -> "ON NOW"
        else -> null
    }
    return HomeCard(
        key = "ch" + c.id,
        tag = tag,
        tagBg = if (tag?.startsWith("LIVE") == true) LiveRed else Color(0xCC050816),
        tagFg = if (tag?.startsWith("LIVE") == true) Color.White else TunerColors.NowLine,
        title = cur?.title ?: c.name,
        line1 = c.name + (cur?.let { " · until ${hhmm(it.stop)}" } ?: ""),
        line2 = next?.let { "Next: ${it.title}" },
        image = cur?.image,
        poster = false,
        channel = c,
        progress = cur?.let { progressOf(it, now) },
        progressColor = TunerColors.Accent,
        onClick = { vm.play(c, list) },
        onLongClick = null,
        hero = HeroInfo(
            title = cur?.title ?: c.name,
            subtitle = buildString {
                append(c.name)
                if (cur != null) append("  ·  ${hhmm(cur.start)} – ${hhmm(cur.stop)}")
                if (next != null) append("  ·  Next: ${next.title}")
            },
            description = cur?.desc,
            channel = c,
            image = cur?.image,
            label = when {
                yourTeam -> "● LIVE · YOUR TEAM"
                tag?.startsWith("LIVE") == true -> "● LIVE"
                cur != null -> "ON NOW"
                else -> null
            },
            labelColor = if (tag?.startsWith("LIVE") == true) Color(0xFFFF5A6E) else Color(0xFFFFB648),
            score = score,
        ),
    )
}

private fun plexItemCard(vm: TunerViewModel, item: com.titanejstudios.tuner.integrations.Plex.Item, onMenu: () -> Unit): HomeCard {
    val left = if (item.durationMs > 0) timeLeft(item.durationMs - item.viewOffsetMs) else null
    return HomeCard(
        key = "px" + item.server.machineId + item.ratingKey,
        tag = "PLEX", tagBg = PlexGold, tagFg = Color.Black,
        title = item.title,
        line1 = item.subtitle,
        line2 = left,
        image = item.image,
        poster = false,
        channel = null,
        progress = if (item.durationMs > 0) item.viewOffsetMs.toFloat() / item.durationMs else null,
        progressColor = PlexGold,
        onClick = { vm.playPlex(item) },
        onLongClick = onMenu,
        hero = HeroInfo(item.title, listOfNotNull(item.subtitle, left, "on Plex").joinToString("  ·  "), null, null, item.image,
            label = "CONTINUE ON PLEX"),
    )
}

private fun resumeCard(vm: TunerViewModel, e: ResumeEntry): HomeCard {
    val left = if (e.durationMs > 0) timeLeft(e.durationMs - e.positionMs) else null
    return HomeCard(
        key = "rs" + e.contentId,
        tag = if (e.seriesId != null) "SERIES" else "FILM", tagBg = TunerColors.Accent, tagFg = Color.White,
        title = e.title,
        line1 = e.subtitle ?: "Films & TV",
        line2 = left,
        image = e.poster,
        poster = true,
        channel = null,
        progress = if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null,
        progressColor = TunerColors.Accent,
        onClick = { vm.playResume(e) },
        onLongClick = null,
        hero = HeroInfo(e.title, listOfNotNull(e.subtitle, left).joinToString("  ·  "), null, null, e.poster, wide = false,
            label = "CARRY ON WATCHING"),
    )
}

private fun plexEntryCard(vm: TunerViewModel, e: com.titanejstudios.tuner.integrations.Plex.Entry, tag: String = "NEW"): HomeCard {
    val what = if (e.isShow) "TV" else "Film"
    return HomeCard(
        key = (if (tag == "NEW") "pn" else "pl") + e.server.machineId + e.ratingKey,
        tag = tag, tagBg = PlexGold, tagFg = Color.Black,
        title = e.title,
        line1 = listOfNotNull(e.year, what, e.rating).joinToString(" · "),
        line2 = e.library,
        image = e.art ?: e.image,
        poster = e.art == null,
        channel = null,
        progress = null,
        progressColor = PlexGold,
        onClick = { if (e.isShow) vm.openPlexShow(e) else vm.playPlexEntry(e) },
        onLongClick = null,
        hero = HeroInfo(
            e.title,
            listOfNotNull(e.year, what, e.rating, e.library).joinToString("  ·  "),
            e.summary, null, e.art ?: e.image, wide = e.art != null,
            label = (if (tag == "NEW") "NEW ON PLEX · " else "ON PLEX · ") + what.uppercase(),
        ),
    )
}

private fun trendingCard(vm: TunerViewModel, item: com.titanejstudios.tuner.TrendingItem): HomeCard {
    val t = item.trend
    val where = when {
        item.plex != null -> "On your Plex" + (item.plex.library?.let { " · $it" } ?: "")
        item.film != null -> "In Films & TV"
        item.series != null -> "In Series"
        else -> "Not in your library · OK for info and trailer"
    }
    val what = if (t.isTv) "TV" else "Film"
    val score = vm.trendScore(item)
    return HomeCard(
        key = "tr" + t.rank + t.title,
        tag = "#${t.rank} TRENDING",
        tagBg = if (item.available) Color(0xFFFFB648) else Color(0xCC050816),
        tagFg = if (item.available) Color.Black else Color.White,
        title = t.title,
        line1 = listOfNotNull(t.year, what, score).joinToString(" · "),
        line2 = where,
        image = t.backdrop ?: t.poster,
        poster = t.backdrop == null,
        channel = null,
        progress = null,
        progressColor = TunerColors.Accent,
        onClick = { vm.openTrending(item) },
        onLongClick = null,
        hero = HeroInfo(
            t.title,
            listOfNotNull(t.year, what, score, where).joinToString("  ·  "),
            t.overview, null, t.backdrop ?: t.poster, wide = t.backdrop != null,
            label = "#${t.rank} TRENDING THIS WEEK",
        ),
    )
}

private fun filmCard(vm: TunerViewModel, m: com.titanejstudios.tuner.data.VodItem): HomeCard = HomeCard(
    key = "fm" + m.id,
    tag = "FILM", tagBg = TunerColors.Accent, tagFg = Color.White,
    title = m.name,
    line1 = listOfNotNull(m.category, m.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }).joinToString(" · "),
    line2 = null,
    image = m.poster,
    poster = true,
    channel = null,
    progress = null,
    progressColor = TunerColors.Accent,
    onClick = { vm.playMovie(m, fromStart = false) },
    onLongClick = null,
    hero = HeroInfo(m.name, m.category, null, null, m.poster, wide = false, label = "LIKE THE LAST FILM YOU WATCHED"),
)

/** A programme you switched off half-way: finish it on catch-up. */
private fun unfinishedCard(vm: TunerViewModel, u: com.titanejstudios.tuner.Unfinished): HomeCard {
    val p = u.programme
    val ch = vm.allChannelsById(u.channelId)
    val name = ch?.name ?: ""
    return HomeCard(
        key = "un" + u.channelId + "|" + p.start,
        tag = "FINISH IT", tagBg = TunerColors.Accent, tagFg = Color.White,
        title = p.title,
        line1 = "$name · ${dayLabel(p.start)} ${hhmm(p.start)}",
        line2 = "Carry on from ${(u.positionMs / 60_000)} min · hold to forget",
        image = null,
        poster = false,
        channel = ch,
        progress = (u.positionMs.toFloat() / (p.stop - p.start).coerceAtLeast(1L)).coerceIn(0f, 1f),
        progressColor = TunerColors.Accent,
        onClick = { vm.finishUnfinished(u) },
        onLongClick = { vm.forgetUnfinished(u) },
        hero = HeroInfo(p.title, "$name · you stopped ${u.positionMs / 60_000} minutes in – carry on with catch-up", null, ch, null, label = "FINISH WHAT YOU STARTED"),
    )
}

/** A YouTube video watched part way: carry on from where you stopped. */
private fun youTubeResumeCard(vm: TunerViewModel, w: com.titanejstudios.tuner.features.YouTubeFeature.Watched): HomeCard {
    val v = w.video
    val left = ((w.length - w.position) / 60f).toInt().coerceAtLeast(1)
    return HomeCard(
        key = "ytr" + v.id,
        tag = "YOUTUBE", tagBg = Color(0xE6FF0033), tagFg = Color.White,
        title = v.title,
        line1 = v.channel,
        line2 = "$left min left",
        image = v.thumb,
        poster = false,
        channel = null,
        progress = if (w.length > 0f) (w.position / w.length).coerceIn(0f, 1f) else null,
        progressColor = Color(0xFFFF3B30),
        onClick = { vm.playYouTube(v) },
        onLongClick = { vm.youtube.forget(v.id) },
        hero = HeroInfo(v.title, v.channel + "  ·  $left min left  ·  hold OK to remove", null, null, v.thumb, label = "CARRY ON · YOUTUBE"),
    )
}

/** A video from a channel you subscribe to on YouTube (or picked for you). */
private fun youTubeCard(vm: TunerViewModel, v: com.titanejstudios.tuner.integrations.YouTube.Video, label: String = "NEW ON YOUR YOUTUBE"): HomeCard = HomeCard(
    key = "yt" + v.id,
    tag = "YOUTUBE", tagBg = Color(0xE6FF0033), tagFg = Color.White,
    title = v.title,
    line1 = v.channel,
    line2 = com.titanejstudios.tuner.integrations.YouTube.age(v.publishedAt),
    image = v.thumb,
    poster = false,
    channel = null,
    progress = null,
    progressColor = TunerColors.Accent,
    onClick = { vm.playYouTube(v) },
    onLongClick = null,
    hero = HeroInfo(v.title, v.channel + "  ·  " + com.titanejstudios.tuner.integrations.YouTube.age(v.publishedAt), null, null, v.thumb, label = label),
)

/** Missed it? A programme from the last week you'd probably like, on catch-up. */
private fun missedCard(vm: TunerViewModel, pick: HomeFeed.Pick): HomeCard {
    val p = pick.programme
    return HomeCard(
        key = "mi" + pick.channel.id + "|" + p.start,
        tag = "CATCH-UP", tagBg = Color(0xCC050816), tagFg = TunerColors.NowLine,
        title = p.title,
        line1 = "${pick.channel.name} · ${dayLabel(p.start)} ${hhmm(p.start)}",
        line2 = pick.reason,
        image = p.image,
        poster = false,
        channel = pick.channel,
        progress = null,
        progressColor = TunerColors.Accent,
        onClick = { vm.playMissed(pick) },
        onLongClick = null,
        hero = HeroInfo(p.title, "${pick.channel.name}  ·  ${dayLabel(p.start)} ${hhmm(p.start)}  ·  on catch-up", p.desc, pick.channel, p.image,
            label = pick.reason?.uppercase() ?: "MISSED IT?"),
    )
}

private fun recordingCard(vm: TunerViewModel, r: Recording): HomeCard {
    val live = r.status == RecStatus.RECORDING
    return HomeCard(
        key = "rec" + r.id,
        tag = if (live) "● RECORDING" else "RECORDED", tagBg = if (live) LiveRed else DoneGreen, tagFg = Color.White,
        title = r.title,
        line1 = "${dayLabel(r.start)} ${hhmm(r.start)} · ${r.channelName}",
        line2 = null,
        image = null,
        poster = false,
        channel = vm.allChannelsById(r.channelId),
        progress = null,
        progressColor = TunerColors.Accent,
        onClick = { vm.playRecording(r) },
        onLongClick = null,
        hero = HeroInfo(r.title, "${r.channelName} · ${dayLabel(r.start)} ${hhmm(r.start)}", null, vm.allChannelsById(r.channelId), null,
            label = if (live) "● RECORDING NOW" else "RECORDED"),
    )
}

private fun watchCard(vm: TunerViewModel, w: WatchItem, badge: String, removable: Boolean): HomeCard = HomeCard(
    key = "w" + w.id,
    tag = badge, tagBg = TunerColors.Accent, tagFg = Color.White,
    title = w.name,
    line1 = (if (w.isSeries) "Series" else "Film") + " · " + w.category,
    line2 = if (removable) "Hold OK to remove" else null,
    image = w.image,
    poster = true,
    channel = null,
    progress = null,
    progressColor = TunerColors.Accent,
    onClick = { vm.openWatchItem(w) },
    onLongClick = if (removable) ({
        if (w.isSeries) w.toSeries()?.let { vm.toggleWatchlist(it) } else w.toVod()?.let { vm.toggleWatchlist(it) }
    }) else null,
    hero = HeroInfo(w.name, (if (w.isSeries) "Series" else "Film") + "  ·  ${w.category}", w.plot, null, w.image, wide = false, label = badge.uppercase()),
)

/** Family picks: "FROM MARK". Hold OK to take it off everyone's Home. */
private fun pickedCard(vm: TunerViewModel, p: com.titanejstudios.tuner.data.FamilyPick): HomeCard {
    val w = p.item
    val label = "FROM ${p.from.uppercase()}"
    return HomeCard(
        key = "fp" + w.id,
        tag = label, tagBg = Color(0xFF7E57C2), tagFg = Color.White,
        title = w.name,
        line1 = (if (w.isSeries) "Series" else "Film") + " · picked by ${p.from}",
        line2 = "Hold OK to remove",
        image = w.image,
        poster = true,
        channel = null,
        progress = null,
        progressColor = TunerColors.Accent,
        onClick = { vm.openWatchItem(w) },
        onLongClick = { vm.removeFamilyPick(w.id) },
        hero = HeroInfo(w.name, "👪 ${p.from} thinks everyone should watch this", w.plot, null, w.image, wide = false, label = label),
    )
}

/** Coming soon: the next episode of a series you follow, and when. */
private fun comingCard(vm: TunerViewModel, c: com.titanejstudios.tuner.data.ComingEpisode): HomeCard {
    val day = runCatching {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).parse(c.date)!!
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date())
        val tomorrow = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date(System.currentTimeMillis() + 86_400_000L))
        when (c.date) {
            today -> "TODAY"
            tomorrow -> "TOMORROW"
            else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(d).uppercase()
        }
    }.getOrDefault(c.date)
    val ep = "S${c.season} E${c.episode}" + (c.name?.let { "  $it" } ?: "")
    return HomeCard(
        key = "cs" + c.item.id,
        tag = day, tagBg = if (day == "TODAY") Color(0xFF2E7D32) else TunerColors.Accent, tagFg = Color.White,
        title = c.item.name,
        line1 = ep,
        line2 = "New episode",
        image = c.item.image,
        poster = true,
        channel = null,
        progress = null,
        progressColor = TunerColors.Accent,
        onClick = { vm.openWatchItem(c.item) },
        onLongClick = null,
        hero = HeroInfo(c.item.name, "📅 $ep", c.item.plot, null, c.item.image, wide = false, label = "NEW EPISODE · $day"),
    )
}

private fun pickCard(vm: TunerViewModel, pick: HomeFeed.Pick, channels: List<Channel>, now: Long, onMenu: (() -> Unit)? = null): HomeCard {
    val p = pick.programme
    val upcoming = p.start > now
    return HomeCard(
        key = "pk" + pick.channel.id + "|" + p.start,
        tag = if (upcoming) "STARTS ${hhmm(p.start)}" else "ON NOW",
        tagBg = if (upcoming) TunerColors.Accent else Color(0xCC050816),
        tagFg = if (upcoming) Color.White else TunerColors.NowLine,
        title = p.title,
        line1 = pick.channel.name + (if (upcoming) "" else " · until ${hhmm(p.stop)}"),
        line2 = if (upcoming) (if (vm.hasReminder(pick.channel, p)) "⏰ Reminder set" else "OK sets a reminder") else pick.reason,
        image = p.image,
        poster = false,
        channel = pick.channel,
        progress = if (upcoming) null else progressOf(p, now),
        progressColor = TunerColors.Accent,
        onClick = {
            if (upcoming && p.start - vm.now > 2 * 60_000L) vm.toggleReminder(pick.channel, p) else vm.play(pick.channel, channels)
        },
        onLongClick = onMenu,
        hero = HeroInfo(
            p.title,
            "${pick.channel.name}  ·  " + (if (upcoming) "Starts ${hhmm(p.start)}" else "On now, until ${hhmm(p.stop)}"),
            p.desc, pick.channel, p.image, label = pick.reason?.uppercase() ?: "TOP PICK FOR YOU",
        ),
    )
}

/** Sky/Plex-style Home: the highlighted item's picture behind the menu, quick buttons, and rows of tiles. */
@Composable
fun HomeScreen(vm: TunerViewModel) {
    BackHandler(enabled = vm.mode != null && !vm.canGoBack) { vm.stopPlayback() }
    if (vm.activeProfile.kids) {
        KidsHome(vm)
        return
    }

    val now = vm.now
    val followed = vm.football.teams
    val allChannels = remember(vm.channels, vm.unlocked, vm.lockedGroups) { vm.channelsFor(ALL) }
    val recent = remember(vm.recents, vm.channels, vm.unlocked) { vm.channelsFor(RECENT).take(8) }
    val favourites = remember(vm.favourites, vm.channels, vm.unlocked) { vm.channelsFor(FAVOURITES).take(30) }
    // iPlayer, ITVX, Channel 4 and My5 first in Your apps.
    val apps = remember(vm.tvApps, vm.hiddenApps) {
        vm.visibleApps().sortedBy { a -> if (com.titanejstudios.tuner.data.CatchupApps.all.any { it.label.containsMatchIn(a.label.trim()) || a.packageName in it.packages }) 0 else 1 }
    }
    var plexMenu by remember { mutableStateOf<com.titanejstudios.tuner.integrations.Plex.Item?>(null) }
    var pickMenu by remember { mutableStateOf<HomeFeed.Pick?>(null) }
    LaunchedEffect(vm.movies != null, vm.activeProfile.id) {
        vm.loadBecause()
        vm.loadTrending(force = vm.movies != null && vm.trending.none { it.film != null })
    }

    // Every row's tiles (minute by minute, as programmes change).
    val minute = now / 60_000
    val cards: Map<HomeRow, List<HomeCard>> = remember(
        minute, recent, favourites, allChannels, vm.sportNow, vm.topPicks, vm.plexItems, vm.plexRecent, vm.started, vm.activeProfile.id,
        vm.yourShows, vm.plexFeature.similar, vm.becauseFilms, vm.trending, vm.moodPicks,
        vm.resume, vm.recordings, vm.followedSeries, vm.watchlist, vm.reminders,
        vm.missed, vm.unfinished, vm.youtube.feed, vm.youtube.history, vm.youtube.recommended,
        vm.familyPicks, vm.comingSoon, vm.collections, vm.bookmarks,
    ) {
        buildMap {
            put(HomeRow.Continue, buildList {
                vm.finishable.take(4).forEach { add(unfinishedCard(vm, it)) }
                vm.myPlexItems.take(8).forEach { item -> add(plexItemCard(vm, item) { plexMenu = item }) }
                if (vm.hasVod) vm.myResume.values.sortedByDescending { it.updatedAt }.take(8).forEach { add(resumeCard(vm, it)) }
                if (vm.youTubeAllowed) vm.youtube.history.filter { it.unfinished }.take(4).forEach { add(youTubeResumeCard(vm, it)) }
                recent.forEach { add(channelCard(vm, it, recent, now, followed)) }
            })
            put(HomeRow.Sport, vm.sportNow.map { channelCard(vm, it, vm.sportNow, now, followed) })
            put(HomeRow.PlexNew, vm.plexRecent.map { plexEntryCard(vm, it) })
            put(HomeRow.Trending, vm.trending.map { trendingCard(vm, it) })
            put(HomeRow.TopPicks, vm.topPicks.let { picks -> val chans = picks.map { it.channel }; picks.map { pk -> pickCard(vm, pk, chans, now) { pickMenu = pk } } })
            put(HomeRow.YourShows, vm.yourShows.let { picks -> val chans = picks.map { it.channel }; picks.map { pk -> pickCard(vm, pk, chans, now) { pickMenu = pk } } })
            put(HomeRow.Missed, vm.missed.map { missedCard(vm, it) })
            if (!vm.activeProfile.kids) put(HomeRow.YouTube, vm.youtube.feed.take(20).map { youTubeCard(vm, it) })
            if (vm.youTubeAllowed && !vm.youTubeApprovedOnly) put(HomeRow.YouTubeForYou, vm.youtube.recommended.take(20).map { youTubeCard(vm, it, "FOR YOU") })
            put(HomeRow.Because, buildList {
                vm.plexFeature.similar.take(20).forEach { add(plexEntryCard(vm, it, "LIKE THIS")) }
                if (vm.hasVod) vm.becauseFilms.forEach { m -> add(filmCard(vm, m)) }
            })
            put(HomeRow.Favourites, favourites.map { channelCard(vm, it, favourites, now, followed) })
            put(HomeRow.Recordings, vm.recordings.filter { it.status == RecStatus.DONE || it.status == RecStatus.RECORDING }
                .sortedByDescending { it.start }.take(15).map { recordingCard(vm, it) })
            if (vm.hasVod) {
                put(HomeRow.NewEpisodes, vm.newEpisodeSeries.map { watchCard(vm, it.series, "${it.newEpisodes} new", removable = false) })
                put(HomeRow.MyList, vm.watchlist.take(30).map { watchCard(vm, it, if (it.isSeries) "Series" else "Film", removable = true) })
                put(HomeRow.FamilyPicks, vm.familyPicksForMe.map { pickedCard(vm, it) })
                put(HomeRow.Collections, vm.collections.map { (name, list) ->
                    HomeCard(
                        key = "col$name", tag = "${list.size}", tagBg = TunerColors.Accent, tagFg = Color.White,
                        title = "📚 $name", line1 = list.take(3).joinToString(", ") { it.name }, line2 = "Your collection",
                        image = list.firstOrNull()?.image, poster = true, channel = null, progress = null, progressColor = TunerColors.Accent,
                        onClick = { vm.showCollection(name) }, onLongClick = null,
                        hero = HeroInfo(name, "Your collection · ${list.size} films and series", list.joinToString(", ") { it.name }.take(300), null, list.firstOrNull()?.image, wide = false, label = "COLLECTION"),
                    )
                })
                put(HomeRow.Bookmarks, vm.bookmarks.take(20).map { b ->
                    val at = "${b.posMs / 60_000}:${"%02d".format((b.posMs / 1000) % 60)}"
                    HomeCard(
                        key = "bm" + b.id, tag = "🔖 $at", tagBg = TunerColors.Accent, tagFg = Color.White,
                        title = b.title, line1 = b.subtitle, line2 = "Hold OK to remove",
                        image = b.poster, poster = true, channel = null, progress = null, progressColor = TunerColors.Accent,
                        onClick = { vm.playBookmark(b) }, onLongClick = { vm.deleteBookmark(b.id) },
                        hero = HeroInfo(b.title, "🔖 Bookmarked at $at" + (b.subtitle?.let { "  ·  $it" } ?: ""), null, null, b.poster, wide = false, label = "BOOKMARK"),
                    )
                })
                put(HomeRow.ComingSoon, vm.comingSoonNow.take(20).map { comingCard(vm, it) })
            }
            // In the mood for…: films picked by feel (Plex and your provider together).
            if (vm.hasVod || vm.plexLinked) {
                val moods = if (vm.activeProfile.kids) com.titanejstudios.tuner.data.Moods.all.filter { it.key == "family" } else com.titanejstudios.tuner.data.Moods.all
                put(HomeRow.Moods, moods.map { m ->
                    val picks = vm.moodPicks[m.key].orEmpty()
                    HomeCard(
                        key = "mood" + m.key, tag = null, tagBg = TunerColors.AccentSoft, tagFg = Color.White,
                        title = m.label,
                        line1 = picks.firstOrNull()?.let { "Like ${it.first}" } ?: "Films that fit the mood",
                        line2 = null,
                        image = null, poster = false, channel = null, progress = null, progressColor = TunerColors.Accent,
                        onClick = { vm.openMood(m.key) }, onLongClick = null,
                        hero = HeroInfo(
                            title = m.label,
                            subtitle = if (picks.isEmpty()) "Films picked by mood – Plex and your provider together"
                            else "Like " + picks.joinToString(", ") { it.first },
                            description = null, channel = null, image = picks.firstOrNull()?.second, wide = true, label = "IN THE MOOD FOR",
                        ),
                        collage = picks.map { it.second }, glow = moodGlow[m.key], emoji = m.emoji,
                    )
                })
            }
            put(HomeRow.All, allChannels.take(40).map { channelCard(vm, it, allChannels, now, followed) })
        }.let { all ->
            // Lite mode: shorter rows, so fewer pictures are held at once.
            val limit = com.titanejstudios.tuner.data.Lite.rowLimit
            if (limit >= 40) all else all.mapValues { it.value.take(limit) }
        }
    }

    // Row order: yours (Settings › Home rows), then – if switched on – sport first when your team
    // or a big event is on, films and Plex first in the evening.
    val hour = remember(minute) { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val weekend = remember(minute) {
        java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK).let { it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY }
    }
    val rows: List<HomeRow> = run {
        val saved = vm.homeRowOrder.mapNotNull { n -> HomeRow.entries.firstOrNull { it.name == n } }
        val order = (saved + HomeRow.entries.filter { it !in saved }).toMutableList()
        if (vm.homeSmart) {
            val sportCards = cards[HomeRow.Sport].orEmpty()
            val teamOn = sportCards.any { it.hero.label?.contains("YOUR TEAM") == true }
            if (sportCards.isNotEmpty() && (teamOn || (weekend && hour in 12..19))) {
                order.remove(HomeRow.Sport)
                order.add(0, HomeRow.Sport)
            } else if (hour >= 18 || hour < 2) {
                val evening = listOf(HomeRow.Trending, HomeRow.PlexNew, HomeRow.MyList, HomeRow.NewEpisodes).filter { it in order }
                order.removeAll(evening)
                val at = order.indexOf(HomeRow.Continue) + 1
                order.addAll(at, evening)
            }
        }
        order.filter { it.name !in vm.homeRowsHidden }
            .filter { if (it == HomeRow.Apps) apps.isNotEmpty() else cards[it].orEmpty().isNotEmpty() }
    }

    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    val firstCardHero = rows.firstOrNull()?.let { cards[it]?.firstOrNull()?.hero }
    val h = hero ?: firstCardHero

    val firstTile = remember { FocusRequester() }
    LaunchedEffect(rows.isNotEmpty()) {
        delay(80)
        runCatching { firstTile.requestFocus() }
    }

    // The background takes on the highlighted picture's colours, fading gently between them.
    val bgTop = rememberPictureTint(h?.image, vm.tintFromPicture)
    val context = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, TunerColors.BackgroundBottom)))) {
        // TVs differ in how tall the screen is in Android's units (often 540, sometimes 720), so
        // the top area takes what's left after the menu, the quick buttons and one full row of tiles.
        val safeBanner = if (vm.safeMode && !vm.safeModeBannerHidden) 70.dp else 0.dp
        val heroHeight = (maxHeight - 44.dp - 52.dp - 8.dp - safeBanner - 52.dp - 10.dp - (TILE_H + 46.dp) - 18.dp)
            .coerceIn(118.dp, 232.dp)
        val roomy = heroHeight >= 190.dp
        if (compactLayout(vm)) {
            PhoneHomeBody(vm, h, rows, cards, apps, allChannels, firstTile)
            return@BoxWithConstraints
        }
        // The highlighted item's wide picture, behind the menu and fading into the background.
        if (h?.image != null && h.wide) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).height(heroHeight + 150.dp)) {
                // Fades from one picture to the next rather than jumping.
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(h.image).crossfade(if (com.titanejstudios.tuner.data.Lite.on) 0 else 500).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bgTop, bgTop.copy(alpha = 0.75f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to bgTop)))
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 22.dp)) {
            TopNav(vm, Screen.Home)
            Spacer(Modifier.height(8.dp))
            if (vm.safeMode && !vm.safeModeBannerHidden) {
                SafeModeBanner(vm)
                Spacer(Modifier.height(8.dp))
            }

            // The big area: what's highlighted, plus the live picture if something's playing.
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                Column(Modifier.fillMaxWidth(0.56f).fillMaxHeight()) {
                    h?.label?.let {
                        Text(it, color = h.labelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        h?.title ?: vm.playing?.name ?: if (vm.status != null || !vm.initialLoadDone) "Getting things ready…" else "Welcome",
                        color = Color.White, fontSize = if (roomy) 36.sp else 30.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = if (roomy) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = if (roomy) 40.sp else 34.sp,
                    )
                    h?.score?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(horizontal = 14.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // An update in the background no longer takes over this line: everything works meanwhile,
                    // so it's a quiet note underneath (it only takes the line when there are no channels yet).
                    val updating = vm.status
                    Text(
                        if (updating != null && allChannels.isEmpty()) "⟳ $updating" else h?.subtitle ?: "${allChannels.size} channels",
                        color = Color(0xFFD4DDF5), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (updating != null && allChannels.isNotEmpty()) {
                        Text("⟳ $updating", color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    AutoScrollText(h?.description ?: "", Modifier.weight(1f).fillMaxWidth(), fontSize = 15.sp)
                }
                when {
                    vm.mode != null -> MiniPreview(
                        vm, Modifier.align(Alignment.TopEnd).height(heroHeight.coerceAtMost(144.dp)).aspectRatio(16f / 9f),
                    )
                    h?.image != null && !h.wide -> AsyncImage(
                        model = h.image, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
                    )
                    h?.image == null && h?.channel != null -> ChannelLogo(
                        h.channel.logo, h.channel.name, Modifier.align(Alignment.TopEnd).padding(top = 12.dp).size(width = 200.dp, height = 100.dp),
                    )
                }
            }

            // Quick buttons (they scroll sideways when there are a lot).
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // The channel you normally watch at this time (the news at 6, say).
                vm.usualOffer?.let { u -> item { PillButton("★  Your usual: ${u.name}", { vm.playUsual() }, compact = true, selected = true) } }
                // Your team: match day (watch it), or the next fixture.
                val md = vm.matchDay
                if (md != null) item { PillButton(fixturePill(md, matchDay = true, emoji = vm.football.sportOf(md.match.team).emoji), { vm.openMatchDay(md) }, compact = true, selected = true) }
                else vm.nextTeamFixture?.let { f -> item { PillButton(fixturePill(f, emoji = vm.football.sportOf(f.match.team).emoji), { vm.navigate(Screen.Teams) }, compact = true) } }
                vm.lastChannel?.takeIf { it.id != vm.usualOffer?.id }?.let { last -> item { PillButton("▶  ${last.name}", { vm.play(last, allChannels) }, compact = true) } }
                item { PillButton("TV Guide", { vm.navigate(Screen.Channels) }, compact = true) }
                item { PillButton("Sport", { vm.navigate(Screen.Sports) }, compact = true) }
                if (vm.christmasSeason) item { PillButton("🎄 Christmas TV", { vm.navigate(Screen.Christmas) }, compact = true) }
                item { PillButton("▶ YouTube", { vm.navigate(Screen.YouTube) }, compact = true) }
                item { PillButton("Search", { vm.navigate(Screen.Search) }, compact = true) }
                item { PillButton("🎤  Say it", { vm.startVoiceSearch() }, compact = true) }
            }
            Spacer(Modifier.height(10.dp))

            if (rows.isEmpty()) {
                val status = vm.status
                when {
                    status != null || !vm.initialLoadDone -> SkeletonRows(status ?: "Loading your channels…")
                    vm.error != null -> CenterMessage(vm.error ?: "")
                    vm.sources.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // A new device: sign in to Google and the playlist from your other devices loads itself.
                        Text("No channels yet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Already use Flicker on another device? Sign in with the same Google account and your playlist, " +
                                "favourites and guide settings come across by themselves.",
                            color = TunerColors.TextSecondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 640.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("🔑 Sign in with Google", { vm.navigate(Screen.Cloud) })
                            PillButton("+ Add a playlist", { vm.navigate(Screen.Settings) })
                        }
                    }
                    else -> CenterMessage("No channels to show – check Settings › Playlists")
                }
            } else {
                // Scrolling down the rows keeps each row's title in view above the highlighted tile.
                val outerSpec = LocalBringIntoViewSpec.current
                val headroom = with(LocalDensity.current) { 40.dp.toPx() }
                val rowsSpec = remember(outerSpec, headroom) { rowTitleSpec(outerSpec, headroom) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowsSpec) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 40.dp),
                    ) {
                        rows.forEachIndexed { i, row ->
                            item(key = row.name) {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides outerSpec) {
                                    Column {
                                        val because = vm.plexFeature.similarTo ?: vm.becauseFilmOf
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (row == HomeRow.Because && because != null) "Because you watched $because" else row.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
                                            // Plex answers after YouTube and your own films: say more are on the way.
                                            if (row == HomeRow.Continue && vm.plexLinked && vm.plexFeature.firstLoad) LoadingMore("Loading Plex…")
                                        }
                                        val m = if (i == 0) Modifier.focusRequester(firstTile) else Modifier
                                        if (row == HomeRow.Apps) {
                                            AppRail(vm, apps, m) { a ->
                                                hero = HeroInfo(a.label, "Open ${a.label}  ·  hold OK to hide it from Home", null, null, null, label = "YOUR APPS")
                                            }
                                        } else {
                                            HomeCardRail(cards[row].orEmpty(), m) { hero = it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pickMenu?.let { pk ->
        val p = pk.programme
        val upcoming = p.start > System.currentTimeMillis()
        MenuDialog(
            title = p.title,
            subtitle = listOfNotNull(pk.channel.name + " · " + hhmm(p.start), pk.reason).joinToString("  ·  "),
            items = buildList {
                add(MenuItem(if (upcoming) "Watch the channel now" else "Watch") { vm.play(pk.channel, vm.channelsFor(ALL)) })
                if (upcoming) add(MenuItem(if (vm.hasReminder(pk.channel, p)) "Cancel reminder" else "⏰ Remind me") { vm.toggleReminder(pk.channel, p) })
                if (!vm.activeProfile.kids && p.stop > System.currentTimeMillis() && vm.recordingFor(pk.channel, p) == null) add(MenuItem("● Record") { vm.recordProgramme(pk.channel, p) })
                add(MenuItem("👍 More like this") { vm.rateProgramme(pk.channel, p, like = true) })
                add(MenuItem("👎 Not for me") { vm.rateProgramme(pk.channel, p, like = false) })
            },
            onDismiss = { pickMenu = null },
        )
    }

    plexMenu?.let { item ->
        MenuDialog(
            title = item.title,
            subtitle = item.subtitle,
            items = buildList {
                add(MenuItem("Resume") { vm.playPlex(item) })
                add(MenuItem("Play from the start") { vm.playPlex(item, fromStart = true) })
                if (vm.ownContinue) add(MenuItem("Remove from my Continue watching", vm.activeProfile.name) { vm.forgetStarted(plexTitle = item.title) })
                add(MenuItem("Open the Plex app") { vm.openPlexApp() })
            },
            onDismiss = { plexMenu = null },
        )
    }
}

/** A small "Loading Plex…" with a spinner beside a row's title. */
@Composable
private fun LoadingMore(text: String) {
    Spacer(Modifier.width(14.dp))
    androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = TunerColors.TextSecondary, strokeWidth = 2.dp)
    Spacer(Modifier.width(8.dp))
    Text(text, color = TunerColors.TextSecondary, fontSize = 14.sp)
}

@Composable
private fun HomeCardRail(cards: List<HomeCard>, firstModifier: Modifier, small: Boolean = false, onFocus: (HeroInfo) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    // Which tile you're on, and whether this row has the highlight at all.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var rowHasFocus by remember { mutableStateOf(false) }
    val startOfRow = remember { FocusRequester() }
    val firstKey = cards.firstOrNull()?.key
    var lastFirst by remember { mutableStateOf(firstKey) }
    // Tiles that load later (Plex, your provider) can arrive in front of ones already showing
    // (YouTube). The row would otherwise stay on the tile you were on and slide right; if you
    // hadn't moved along the row yet, go back to its start and highlight the new first tile.
    LaunchedEffect(firstKey) {
        val old = lastFirst
        lastFirst = firstKey
        if (old == null || old == firstKey || firstKey == null) return@LaunchedEffect
        if (focusedKey != null && focusedKey != old) return@LaunchedEffect
        runCatching { state.scrollToItem(0) }
        if (rowHasFocus) {
            delay(40)
            runCatching { startOfRow.requestFocus() }
        }
    }
    // On the first or second tile, the row always shows from its start. Otherwise a row that had
    // tiles added in front (or came back scrolled) is left with the first tile half cut off.
    var focusedIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(focusedIndex, firstKey) {
        if (focusedIndex !in 0..1) return@LaunchedEffect
        delay(150) // after the highlight's own scroll
        if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) runCatching { state.animateScrollToItem(0) }
    }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(if (small) 10.dp else 14.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        modifier = Modifier.onFocusChanged { rowHasFocus = it.hasFocus },
    ) {
        items(cards.size, key = { cards[it].key }) { i ->
            val card = cards[i]
            HomeTile(card, if (i == 0) firstModifier.focusRequester(startOfRow) else Modifier, { h ->
                focusedKey = card.key
                focusedIndex = cards.indexOfFirst { it.key == card.key }
                onFocus(h)
            }, small)
        }
    }
}

/** Home on a phone held upright: a featured picture, quick buttons, then rows of smaller tiles. */
@Composable
private fun PhoneHomeBody(
    vm: TunerViewModel,
    h: HeroInfo?,
    rows: List<HomeRow>,
    cards: Map<HomeRow, List<HomeCard>>,
    apps: List<TvApp>,
    allChannels: List<Channel>,
    firstTile: FocusRequester,
) {
    val featured = rows.firstOrNull()?.let { cards[it]?.firstOrNull() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo_icon), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Flicker", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                if (vm.vpn.connected) Text("🔒  ", fontSize = 16.sp)
                Box(Modifier.tvClickable({ vm.navigate(Screen.ProfilePicker) })) {
                    ProfileAvatar(vm.activeProfile, 34.dp, vm.profiles.indexOfFirst { it.id == vm.activeProfile.id })
                }
            }
        }
        if (vm.safeMode && !vm.safeModeBannerHidden) item(key = "safe") { Box(Modifier.padding(horizontal = 12.dp)) { SafeModeBanner(vm) } }
        // The featured item: the first tile of the first row, big.
        val hero = featured?.hero ?: h
        if (hero != null) item(key = "hero") {
            Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                    .background(TunerColors.Panel).tvClickable({ featured?.onClick?.invoke() }, featured?.onLongClick),
            ) {
                when {
                    hero.image != null && hero.wide -> AsyncImage(model = hero.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    hero.image != null -> AsyncImage(
                        model = hero.image, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(2f / 3f),
                    )
                    hero.channel != null -> ChannelLogo(hero.channel.logo, hero.channel.name, Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(width = 160.dp, height = 80.dp))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xEE050A1E))))
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    hero.label?.let { Text(it, color = hero.labelColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1) }
                    Text(hero.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
                    hero.score?.let { Text(it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Text(hero.subtitle, color = Color(0xFFD4DDF5), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item(key = "quick") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                vm.usualOffer?.let { u -> item { PillButton("★  Your usual: ${u.name}", { vm.playUsual() }, compact = true, selected = true) } }
                val md = vm.matchDay
                if (md != null) item { PillButton(fixturePill(md, matchDay = true, emoji = vm.football.sportOf(md.match.team).emoji), { vm.openMatchDay(md) }, compact = true, selected = true) }
                else vm.nextTeamFixture?.let { f -> item { PillButton(fixturePill(f, emoji = vm.football.sportOf(f.match.team).emoji), { vm.navigate(Screen.Teams) }, compact = true) } }
                if (vm.christmasSeason) item { PillButton("🎄 Christmas TV", { vm.navigate(Screen.Christmas) }, compact = true) }
                if (vm.link.linked && !vm.activeProfile.kids) item { PillButton("📺 TV remote", { vm.navigate(Screen.Remote) }, compact = true) }
                item { PillButton("▶ YouTube", { vm.navigate(Screen.YouTube) }, compact = true) }
                vm.lastChannel?.takeIf { it.id != vm.usualOffer?.id }?.let { last -> item { PillButton("▶  ${last.name}", { vm.play(last, allChannels) }, compact = true) } }
                if (vm.link.linked) item { PillButton("📺 TV remote", { vm.navigate(Screen.Remote) }, compact = true) }
                if (vm.downloads.isNotEmpty()) item { PillButton("⬇ Downloads", { vm.navigate(Screen.Downloads) }, compact = true) }
                item { PillButton("Sport", { vm.navigate(Screen.Sports) }, compact = true) }
                item { PillButton("What's On", { vm.openWhatsOn(vm.whatsOnGenre) }, compact = true) }
                item { PillButton("Catch-up", { vm.navigate(Screen.Catchup) }, compact = true) }
                item { PillButton("🎤  Say it", { vm.startVoiceSearch() }, compact = true) }
            }
        }
        if (rows.isEmpty()) item(key = "empty") {
            Text(
                vm.status?.let { "⟳ $it" } ?: if (vm.sources.isEmpty()) "No channels yet – add a playlist in More › Playlists" else "Loading…",
                color = TunerColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp),
            )
        }
        rows.forEachIndexed { i, row ->
            item(key = "row" + row.name) {
                Column {
                    val because = vm.plexFeature.similarTo ?: vm.becauseFilmOf
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (row == HomeRow.Because && because != null) "Because you watched $because" else row.title,
                            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                        )
                        if (row == HomeRow.Continue && vm.plexLinked && vm.plexFeature.firstLoad) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { LoadingMore("Loading Plex…") }
                    }
                    val m = if (i == 0) Modifier.focusRequester(firstTile) else Modifier
                    if (row == HomeRow.Apps) AppRail(vm, apps, m) { }
                    else HomeCardRail(cards[row].orEmpty(), m, small = true) { }
                }
            }
        }
    }
}

@Composable
private fun HomeTile(c: HomeCard, modifier: Modifier, onFocus: (HeroInfo) -> Unit, small: Boolean = false) {
    FocusSurface(
        memoryKey = "home|${c.key}",
        onClick = c.onClick,
        onLongClick = c.onLongClick,
        onFocused = { onFocus(c.hero) },
        baseColor = TunerColors.Panel,
        shape = RoundedCornerShape(12.dp),
        zoom = true,
        modifier = modifier.size(width = if (small) 172.dp else TILE_W, height = if (small) 120.dp else TILE_H),
    ) { focused ->
        val sub = if (focused) Color(0xEEFFFFFF) else Color(0xFFC3CDEA)
        if (c.glow != null) {
            MoodTileArt(c, focused, small)
            return@FocusSurface
        }
        if (c.poster && c.image != null) {
            // Posters are shown whole beside the text, never cropped to fill a wide tile.
            Row(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = c.image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(100.dp).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                    c.tag?.let { TileTag(it, c.tagBg, c.tagFg, Modifier) }
                    Spacer(Modifier.weight(1f))
                    Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                    c.line1?.let { Text(it, color = sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp) }
                    c.line2?.let { Text(it, color = sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    c.progress?.let { ProgressBar(it, Modifier.padding(top = 5.dp).fillMaxWidth(), color = if (focused) Color.White else c.progressColor) }
                }
            }
            return@FocusSurface
        }
        if (c.image != null) {
            AsyncImage(model = c.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (c.channel != null) {
            ChannelLogo(c.channel.logo, c.channel.name, Modifier.align(Alignment.TopCenter).padding(top = 26.dp).size(width = 96.dp, height = 46.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 0.62f to Color(0xE6050A1E), 1f to Color(0xF2050A1E))))
        if (c.image != null && c.channel != null) {
            ChannelLogo(
                c.channel.logo, c.channel.name,
                Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x99050816), RoundedCornerShape(6.dp)).padding(3.dp).size(width = 52.dp, height = 28.dp),
            )
        }
        c.tag?.let { TileTag(it, c.tagBg, c.tagFg, Modifier.align(Alignment.TopStart).padding(6.dp)) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.line1?.let { Text(it, color = sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            c.line2?.let { Text(it, color = sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            c.progress?.let { ProgressBar(it, Modifier.padding(top = 5.dp).fillMaxWidth(), color = if (focused) Color.White else c.progressColor) }
        }
    }
}

/**
 * A mood tile: a wide picture from a film that fits, washed with the mood's colour from the left,
 * and the mood's name in big type. With no film to show, just the colour.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MoodTileArt(c: HomeCard, focused: Boolean, small: Boolean) {
    val glow = c.glow ?: TunerColors.Accent
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(glow, Color(0xFF0B1030)))))
    c.collage.firstOrNull()?.let { pic ->
        AsyncImage(model = pic, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // The mood's colour from the left, fading out across the picture.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to glow.copy(alpha = if (focused) 0.80f else 0.92f), 0.55f to glow.copy(alpha = 0.35f), 1f to Color.Transparent),
            ),
        )
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3050A1E))))
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            c.title, color = Color.White, fontSize = if (small) 16.sp else 21.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (small) 18.sp else 23.sp,
        )
        c.line1?.let { Text(it, color = Color(0xEEFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun TileTag(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp),
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Settings › Home rows: show, hide and reorder Home's rows. */
@Composable
fun HomeRowsScreen(vm: TunerViewModel) {
    val saved = vm.homeRowOrder.mapNotNull { n -> HomeRow.entries.firstOrNull { it.name == n } }
    val order = saved + HomeRow.entries.filter { it !in saved }
    val hidden = vm.homeRowsHidden
    var moving by remember { mutableStateOf<HomeRow?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    fun move(row: HomeRow, to: Int) {
        val list = order.toMutableList()
        list.remove(row)
        list.add(to.coerceIn(0, list.size), row)
        vm.saveHomeRows(list.map { it.name }, hidden)
    }
    val paneA: @Composable (Modifier) -> Unit = { pane ->
        Column(pane) {
            Text("Home rows – ${vm.activeProfile.name}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("OK switches a row on or off. Hold OK to move it.", color = TunerColors.TextSecondary, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(order.size, key = { order[it].name }) { i ->
                    val row = order[i]
                    val shown = row.name !in hidden
                    FocusSurface(
                        onClick = { vm.saveHomeRows(order.map { it.name }, if (shown) hidden + row.name else hidden - row.name) },
                        onLongClick = { moving = row },
                        baseColor = if (shown) TunerColors.Panel else Color(0x66000000),
                        modifier = Modifier.fillMaxWidth().height(48.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) { focused ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = TunerColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.width(30.dp))
                            Text(row.title, color = if (shown || focused) Color.White else TunerColors.TextSecondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (shown) "Shown" else "Hidden", color = if (shown) Color(0xFF7EF0A8) else TunerColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    val paneB: @Composable (Modifier) -> Unit = { pane ->
        Column(pane, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusSurface(
                onClick = { vm.toggleHomeSmart() },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { _ ->
                Column(Modifier.padding(18.dp)) {
                    Text("Change with the time of day", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sport moves to the top when your teams are playing (and on weekend afternoons); films and Plex come first in the evening. Rows you've hidden stay hidden.",
                        color = TunerColors.TextSecondary, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(if (vm.homeSmart) "On" else "Off", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (vm.homeSmart) TunerColors.Accent else Color(0x33FFFFFF)).padding(horizontal = 14.dp, vertical = 5.dp))
                }
            }
            PillButton("Back to the standard order", { vm.saveHomeRows(emptyList(), emptySet()) }, compact = true)
        }
    }
    // Phones held upright: one above the other instead of side by side.
    if (narrowScreen()) {
        Column(Modifier.fillMaxSize().skyBackground().padding(14.dp)) {
            paneA(Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            paneB(Modifier.fillMaxWidth().weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize().skyBackground().padding(horizontal = 56.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
            paneA(Modifier.width(600.dp))
            paneB(Modifier.weight(1f).padding(top = 70.dp))
        }
    }
    moving?.let { row ->
        val i = order.indexOf(row)
        MenuDialog(
            title = "Move \"${row.title}\"",
            items = buildList {
                if (i > 0) add(MenuItem("▲ Up one") { move(row, i - 1) })
                if (i < order.size - 1) add(MenuItem("▼ Down one") { move(row, i + 1) })
                if (i > 0) add(MenuItem("⤒ To the top") { move(row, 0) })
                if (i < order.size - 1) add(MenuItem("⤓ To the bottom") { move(row, order.size - 1) })
            },
            onDismiss = { moving = null },
        )
    }
}

@Composable
private fun ChannelRail(
    vm: TunerViewModel,
    channels: List<Channel>,
    firstModifier: Modifier,
    onFocus: (Channel) -> Unit,
    badge: String? = null,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(channels.size, key = { channels[it].id }) { i ->
            val c = channels[i]
            ChannelTile(
                vm = vm,
                channel = c,
                onClick = { vm.play(c, channels) },
                onFocused = { onFocus(c) },
                badge = badge,
                modifier = if (i == 0) firstModifier else Modifier,
            )
        }
    }
}

@Composable
private fun RecordingRail(vm: TunerViewModel, list: List<Recording>, firstModifier: Modifier, onFocus: (Recording) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val r = list[i]
            FocusSurface(
                memoryKey = "rec|${r.id}",
                onClick = { vm.playRecording(r) },
                onFocused = { onFocus(r) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { focused ->
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        if (r.status == RecStatus.RECORDING) "● Recording now" else "${dayLabel(r.start)} ${hhmm(r.start)}",
                        color = if (r.status == RecStatus.RECORDING) TunerColors.NowLine else TunerColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(r.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.channelName, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Up/down on Home: always leave [headroom] pixels above the highlighted tile, so the row's title
 * ("Continue watching"…) isn't cut off at the top.
 */
private fun rowTitleSpec(outer: BringIntoViewSpec, headroom: Float) = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = outer.scrollAnimationSpec

    // The highlighted row always sits at the top (just under its title), so the row above is
    // scrolled right out of view instead of peeking out half cut off under the quick buttons.
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - headroom
}

/** Films and series (My list, New episodes) as wide picture tiles. */
@Composable
private fun WatchRail(
    vm: TunerViewModel,
    list: List<Pair<WatchItem, String>>,
    firstModifier: Modifier,
    onFocus: (WatchItem) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list.size, key = { list[it].first.id }) { i ->
            val (w, badge) = list[i]
            FocusSurface(
                memoryKey = "watch|${w.id}",
                onClick = { vm.openWatchItem(w) },
                onLongClick = {
                    if (w.isSeries) w.toSeries()?.let { vm.toggleWatchlist(it) } else w.toVod()?.let { vm.toggleWatchlist(it) }
                },
                onFocused = { onFocus(w) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { _ ->
                if (w.image != null) {
                    AsyncImage(model = w.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD050816)))))
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(6.dp)).background(TunerColors.Accent).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    w.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
                )
            }
        }
    }
}

/** Shown on Home after Tuner restarted itself in safe mode. */
@Composable
private fun SafeModeBanner(vm: TunerViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF5A3A0A)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Safe mode – Flicker closed unexpectedly, so the newer extras are switched off", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                vm.lastCrash?.let { "Last problem: $it" } ?: "Night mode, lip sync, frame-rate matching, Live tab, Plex and scores are off for now.",
                color = Color(0xFFFFE2B0),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        PillButton("Check everything", { vm.openSelfTest() }, compact = true)
        Spacer(Modifier.width(8.dp))
        PillButton("Turn off", { vm.leaveSafeMode() }, compact = true)
        Spacer(Modifier.width(8.dp))
        PillButton("Hide", { vm.safeModeBannerHidden = true }, compact = true)
    }
}

@Composable
private fun ResumeRail(vm: TunerViewModel, list: List<ResumeEntry>, firstModifier: Modifier, onFocus: (ResumeEntry) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(list.size, key = { list[it].contentId }) { i ->
            val e = list[i]
            FocusSurface(
                memoryKey = "resume|${e.contentId}",
                onClick = { vm.playResume(e) },
                onFocused = { onFocus(e) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { _ ->
                if (e.poster != null) {
                    AsyncImage(model = e.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD050816)))))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
                    Text(e.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.durationMs > 0) ProgressBar(e.positionMs.toFloat() / e.durationMs, Modifier.padding(top = 4.dp).fillMaxWidth())
                }
            }
        }
    }
}

/** "Top picks": programme pictures where the guide has them, otherwise the channel logo. */
@Composable
private fun PickRail(vm: TunerViewModel, picks: List<HomeFeed.Pick>, firstModifier: Modifier, onFocus: (HomeFeed.Pick) -> Unit) {
    val channels = remember(picks) { picks.map { it.channel } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(picks.size, key = { picks[it].channel.id + "|" + picks[it].programme.start }) { i ->
            val pick = picks[i]
            val p = pick.programme
            val upcoming = p.start > vm.now
            FocusSurface(
                memoryKey = "pick|${pick.channel.id}|${p.start}",
                onClick = {
                    if (upcoming && p.start - vm.now > 2 * 60_000L) vm.toggleReminder(pick.channel, p)
                    else vm.play(pick.channel, channels)
                },
                onFocused = { onFocus(pick) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { focused ->
                if (p.image != null) {
                    AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    ChannelLogo(pick.channel.logo, pick.channel.name, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(width = 110.dp, height = 60.dp))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (focused) Color(0x88000000) else Color(0xDD050816)))))
                Text(
                    if (upcoming) "Starts ${hhmm(p.start)}" else "ON NOW",
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA050816), RoundedCornerShape(bottomEnd = 8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (upcoming) Color.White else TunerColors.NowLine,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(p.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pick.channel.name, color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AppRail(vm: TunerViewModel, apps: List<TvApp>, firstModifier: Modifier, onFocus: (TvApp) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(apps.size, key = { apps[it].packageName }) { i ->
            AppTile(vm, apps[i], (if (i == 0) firstModifier else Modifier).size(width = 192.dp, height = 108.dp)) { onFocus(apps[i]) }
        }
    }
}

/** An app's TV banner (or its icon and name). OK opens it; holding OK hides/shows it on Home. */
@Composable
fun AppTile(vm: TunerViewModel, app: TvApp, modifier: Modifier, dimmed: Boolean = false, onFocus: () -> Unit = {}) {
    FocusSurface(
        memoryKey = "app|${app.packageName}",
        onClick = { vm.launchApp(app) },
        onLongClick = { vm.toggleHideApp(app) },
        onFocused = onFocus,
        baseColor = Color(0xFF1B2440),
        shape = RoundedCornerShape(12.dp),
        zoom = true,
        modifier = modifier,
    ) { focused ->
        val banner = app.banner
        val icon = app.icon
        if (banner != null) {
            Image(bitmap = banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(6.dp))
                Text(app.label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (dimmed) Box(Modifier.fillMaxSize().background(Color(0xAA000000)))
        if (focused && banner != null) {
            Text(
                app.label,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC050816)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** A simple, big-tile home for kids profiles: their channels, and how much TV time is left today. */
@Composable
private fun KidsHome(vm: TunerViewModel) {
    val channels = remember(vm.channels, vm.unlocked, vm.activeProfile) { vm.channelsFor(ALL) }
    // Carry on watching: only what this child started themselves, on channels they're allowed.
    val minute = vm.now / 60_000
    val carryOn = remember(minute, channels, vm.started, vm.resume, vm.plexItems, vm.unfinished, vm.recents, vm.activeProfile.id, vm.youtube.history) {
        val allowed = channels.map { it.id }.toSet()
        val recent = vm.channelsFor(RECENT).filter { it.id in allowed }.take(8)
        buildList {
            vm.finishable.filter { it.channelId in allowed }.take(4).forEach { add(unfinishedCard(vm, it)) }
            vm.myPlexItems.take(8).forEach { item -> add(plexItemCard(vm, item) {}) }
            if (vm.hasVod) vm.myResume.values.sortedByDescending { it.updatedAt }.take(8).forEach { add(resumeCard(vm, it)) }
            if (vm.youTubeAllowed) vm.youtube.history.filter { it.unfinished }.take(4).forEach { add(youTubeResumeCard(vm, it)) }
            recent.forEach { add(channelCard(vm, it, recent, vm.now, emptySet())) }
        }
    }
    val ytCards = remember(vm.youtube.feed, vm.youtube.approvedFeed, vm.activeProfile) {
        when {
            !vm.youTubeAllowed -> emptyList()
            vm.youTubeApprovedOnly -> vm.youtube.approvedFeed.take(20).map { youTubeCard(vm, it) }
            else -> vm.youtube.feed.take(20).map { youTubeCard(vm, it) }
        }
    }
    val ytKids = remember { vm.youTubeKidsPackage != null }
    val sections = listOf("Carry on watching" to carryOn, (if (vm.youTubeApprovedOnly) "New from your YouTube channels" else "New on your YouTube") to ytCards).filter { it.second.isNotEmpty() }
    val first = remember { FocusRequester() }
    LaunchedEffect(channels.isNotEmpty(), sections.isNotEmpty()) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().skyBackground()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 22.dp)) {
            TopNav(vm, Screen.Home)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hi ${vm.activeProfile.name}! 🧸", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                PillButton("🎤  Say it", { vm.startVoiceSearch() }, compact = true)
                Spacer(Modifier.width(10.dp))
                if (vm.youTubeAllowed) PillButton("▶ YouTube", { vm.navigate(Screen.YouTube) }, compact = true)
                if (ytKids && vm.youTubeAllowed) {
                    Spacer(Modifier.width(10.dp))
                    PillButton("YouTube Kids", { vm.openYouTubeKids() }, compact = true)
                }
                Spacer(Modifier.weight(1f))
                val left = vm.timeLeftSeconds()
                if (left != Long.MAX_VALUE) {
                    val mins = (left.coerceAtLeast(0) + 59) / 60
                    Text(
                        if (mins <= 0) "No TV time left today" else "⏱ $mins minute${if (mins == 1L) "" else "s"} of TV left today",
                        color = if (mins <= 10) Color(0xFFFFC857) else TunerColors.TextSecondary,
                        fontSize = 20.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (channels.isEmpty()) {
                CenterMessage(if (!vm.initialLoadDone || vm.status != null) "⟳  Loading channels…" else "No channels picked for this profile yet – ask a grown-up to choose some in Settings › Profiles")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    sections.forEachIndexed { si, (title, row) ->
                        item(key = "section-$title", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    items(row.size, key = { row[it].key }) { i ->
                                        HomeTile(row[i], if (si == 0 && i == 0) Modifier.focusRequester(first) else Modifier, onFocus = {})
                                    }
                                }
                                if (si == sections.lastIndex) {
                                    Spacer(Modifier.height(10.dp))
                                    Text("Your channels", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    items(channels.size, key = { channels[it].id }) { i ->
                        val c = channels[i]
                        ChannelTile(
                            vm = vm,
                            channel = c,
                            onClick = { vm.play(c, channels) },
                            onFocused = {},
                            modifier = (if (i == 0 && sections.isEmpty()) Modifier.focusRequester(first) else Modifier).size(width = 270.dp, height = 160.dp),
                        )
                    }
                }
            }
        }
    }
}


/** Grey tile shapes that gently pulse while Home's rows load, instead of a "Loading…" message. */
@Composable
private fun SkeletonRows(status: String) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("⟳  $status", color = TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 1)
        repeat(2) {
            Box(Modifier.size(width = 170.dp, height = 18.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.10f * alpha)))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(5) {
                    Box(Modifier.size(width = TILE_W, height = TILE_H).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.09f * alpha)))
                }
            }
        }
    }
}


/** "⚽ Newcastle v Arsenal · Sat 17:30 · Sky Sports Main Event" for Home's quick buttons. */
private fun fixturePill(f: com.titanejstudios.tuner.TeamFixture, matchDay: Boolean = false, emoji: String = "⚽"): String {
    val m = f.match
    val now = System.currentTimeMillis()
    val dayFmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.UK)
    val day = when (dayFmt.format(java.util.Date(m.kickoff))) {
        dayFmt.format(java.util.Date(now)) -> "Today"
        dayFmt.format(java.util.Date(now + 86_400_000L)) -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE", java.util.Locale.UK).format(java.util.Date(m.kickoff))
    }
    val time = when {
        m.live || (f.programme != null && f.programme.start <= now && m.kickoff <= now) -> "on now"
        m.timeKnown -> "$day ${hhmm(m.kickoff)}"
        else -> day
    }
    val more = if (f.channels.size > 1) " +${f.channels.size - 1}" else ""
    return (if (matchDay) "$emoji  Match day: " else "$emoji  ") + f.title + " · " + time + (f.channel?.let { " · ${it.name}$more" } ?: "")
}

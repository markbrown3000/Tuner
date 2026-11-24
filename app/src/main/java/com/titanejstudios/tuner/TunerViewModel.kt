package com.titanejstudios.tuner

import android.app.Application
import android.content.Intent
import org.json.JSONObject
import android.net.Uri
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import com.titanejstudios.tuner.apps.InstalledApps
import com.titanejstudios.tuner.apps.TvApp
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.HealthCheck
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.VoiceMatch
import com.titanejstudios.tuner.data.WatchStats
import com.titanejstudios.tuner.tv.LiveChannelSync
import com.titanejstudios.tuner.data.WhatsOn
import com.titanejstudios.tuner.ui.SubtitleLook
import com.titanejstudios.tuner.ui.TunerColors
import com.titanejstudios.tuner.data.RecordingPaths
import com.titanejstudios.tuner.data.SeriesLink
import com.titanejstudios.tuner.data.SeriesLinks
import com.titanejstudios.tuner.data.StreamAlternates
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.Profile
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.Reminder
import com.titanejstudios.tuner.data.Repository
import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.SeriesItem
import com.titanejstudios.tuner.data.FilmName
import com.titanejstudios.tuner.data.Taste
import com.titanejstudios.tuner.data.Recommend
import com.titanejstudios.tuner.data.Languages
import com.titanejstudios.tuner.data.CatchupApps
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.data.XmltvChannel
import com.titanejstudios.tuner.data.XmltvParser
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.data.FollowedSeries
import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.SafeMode
import com.titanejstudios.tuner.data.VodCache
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import com.titanejstudios.tuner.features.FootballFeature
import com.titanejstudios.tuner.features.PlexFeature
import com.titanejstudios.tuner.features.SelfTest
import com.titanejstudios.tuner.features.UpdateFeature
import com.titanejstudios.tuner.integrations.CinemaLights
import com.titanejstudios.tuner.playback.StreamCushion
import com.titanejstudios.tuner.playback.VolumeLevels
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.ChannelMerge
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.integrations.NasBackup
import com.titanejstudios.tuner.integrations.OpenSubtitles
import com.titanejstudios.tuner.integrations.Tmdb
import com.titanejstudios.tuner.integrations.TvSync
import com.titanejstudios.tuner.integrations.AppState
import com.titanejstudios.tuner.integrations.HomeAssistantWebhook
import com.titanejstudios.tuner.integrations.ReminderAlarms
import com.titanejstudios.tuner.integrations.TvShare
import com.titanejstudios.tuner.integrations.Plex
import com.titanejstudios.tuner.data.Football
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import com.titanejstudios.tuner.integrations.RemoteHandler
import com.titanejstudios.tuner.playback.MultiViewController
import com.titanejstudios.tuner.playback.PlaybackController
import com.titanejstudios.tuner.recording.RecordingScheduler
import com.titanejstudios.tuner.tv.GuideRefreshWorker
import com.titanejstudios.tuner.tv.HomeScreen
import com.titanejstudios.tuner.ui.hhmm
import com.titanejstudios.tuner.ui.dayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen {
    Home, Channels, Guide, Player, Search, Movies, Series, SeriesDetail, Settings, SourceEdit, Manage,
    ProfilePicker, ProfileEdit, PhoneSetup, EpgMap, Recordings, MultiView, Sports, Apps, Log,
    WhatsOn, Stats, ShareTv, Catchup, Categories, Plex, Football, Update, SelfTest, PlexLibrary, PlexShow, HomeRows, Languages, Recommendations, Vpn,
    Link, Downloads, Teams, Remote, Christmas,
}

const val FAVOURITES = "★ Favourites"
const val RECENT = "Recently watched"
const val ALL = "All channels"
const val ALL_SOURCES = "all"

/** Presses of ▲▼ closer together than this count as "flicking through" channels. */
private const val ZAP_RAPID_MS = 700L
/** How long after the last quick press the channel is actually tuned. */
private const val ZAP_SETTLE_MS = 450L

/** What the player is showing. */
sealed class PlayMode {
    data class Live(val channel: Channel) : PlayMode()
    data class CatchupPlay(val channel: Channel, val programme: Programme) : PlayMode()
    data class Vod(
        val contentId: String,
        val title: String,
        val subtitle: String?,
        val poster: String?,
        val url: String,
        val seriesId: String?,
    ) : PlayMode()
    data class RecordingPlay(val recording: Recording) : PlayMode()
}

class PinPrompt(val title: String, val onEntered: (String) -> Unit)

class SearchResults(
    val channels: List<Channel>,
    val programmes: List<Pair<Channel, Programme>>,
    val films: List<VodItem> = emptyList(),
    val seriesFound: List<SeriesItem> = emptyList(),
)

/** One of your teams' fixtures, and which of your channels shows it (if the guide says yet). */
/** Tracks how long a live programme has been watched (for "finish what you started"). */
private class LiveWatch(val channelId: String, val start: Long, var seconds: Long, var lastSeen: Long)

class TeamFixture(
    val match: Football.Match,
    val channel: Channel?,
    val programme: Programme?,
    /** The guide reaches that day, so "no channel" really means none of yours shows it. */
    val guideCoversIt: Boolean,
)

/** One programme in the Christmas TV planner. [section] is "specials", "films" or "sport". */
class FestiveItem(val channel: Channel, val programme: Programme, val section: String)

/** A programme you switched off half-way, and where you got to in it. */
class Unfinished(val channelId: String, val programme: Programme, val positionMs: Long, val at: Long)

/** A trending film or show, and where you have it. */
class TrendingItem(
    val trend: com.titanejstudios.tuner.integrations.Tmdb.Trend,
    val plex: com.titanejstudios.tuner.integrations.Plex.Entry?,
    val film: VodItem?,
    val series: SeriesItem?,
) {
    val available: Boolean get() = plex != null || film != null || series != null
}

/** A film that's in more than one place (Plex Films and Films 4K, or IPTV too): which to play. */
class PlexCopyChoice(
    val title: String,
    val copies: List<com.titanejstudios.tuner.integrations.Plex.Entry>,
    val fromStart: Boolean,
    /** The IPTV copy, when the choice came from Films & TV. */
    val iptv: VodItem?,
)

/** A place recordings can be saved (internal storage or a USB drive). */
class StorageOption(val path: String, val label: String, val freeBytes: Long)

class TunerViewModel(private val app: Application) : AndroidViewModel(app), RemoteHandler {
    private val store = Store(app)
    private val db = Database.get(app)
    private val repo = Repository(app, store)
    val playback = PlaybackController(app)
    val multi = MultiViewController(app)

    // ---------------- Navigation ----------------

    private val stack = mutableStateListOf(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun navigate(to: Screen) {
        if (to == Screen.Settings && activeProfile.kids && pin != null) {
            requestPin { if (stack.last() != to) stack.add(to); afterNavigation() }
            return
        }
        if (stack.last() != to) stack.add(to)
        afterNavigation()
    }

    fun back() {
        if (stack.size <= 1) return
        val left = stack.removeAt(stack.size - 1)
        when (left) {
            Screen.Player -> when (mode) {
                is PlayMode.Vod -> { saveResume(syncHome = true); stopPlayback() }
                is PlayMode.CatchupPlay, is PlayMode.RecordingPlay -> stopPlayback()
                else -> Unit
            }
            Screen.MultiView -> {
                // Leaving multi-view: carry on full screen with the channel you were listening to.
                val ch = multi.slots.getOrNull(multi.audioSlot)?.channel
                multi.release()
                if (ch != null) {
                    startLive(ch)
                    if (stack.last() != Screen.Player) stack.add(Screen.Player)
                }
            }
            Screen.PhoneSetup -> stopPhoneSetup()
            Screen.ShareTv -> stopShare()
            else -> Unit
        }
        afterNavigation()
    }

    private fun resetTo(to: Screen) {
        if (screen == Screen.PhoneSetup) stopPhoneSetup()
        if (screen == Screen.ShareTv) stopShare()
        stack.clear()
        stack.add(to)
        afterNavigation()
    }

    /** Live TV keeps playing in the preview on Channels/Guide; anything else stops it. */
    private fun afterNavigation() {
        if (mode != null && screen !in setOf(Screen.Player, Screen.Channels, Screen.Guide, Screen.Home)) {
            if (mode is PlayMode.Vod) saveResume(syncHome = true)
            stopPlayback()
        }
    }

    /** Back to the Home screen from anywhere (runs the normal "leaving" steps on the way). */
    fun goHome() {
        var guard = 0
        while (stack.size > 1 && stack.last() != Screen.Home && guard++ < 20) back()
        if (stack.last() != Screen.Home) resetTo(Screen.Home)
    }

    fun openGuide() {
        if (stack.contains(Screen.Guide)) {
            while (stack.last() != Screen.Guide) stack.removeAt(stack.size - 1)
            afterNavigation()
        } else navigate(Screen.Guide)
    }

    // ---------------- Playlists & channels ----------------

    var sources by mutableStateOf<List<Source>>(emptyList()); private set
    var activeSourceId by mutableStateOf(ALL_SOURCES); private set
    var editingSource by mutableStateOf<Source?>(null); private set

    /** Every channel in the active playlist(s), including hidden ones. */
    var allChannels by mutableStateOf<List<Channel>>(emptyList()); private set
    /** Channels that aren't hidden. */
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    /** Every group in the user's order, including hidden ones (for the Manage screen). */
    var allGroups by mutableStateOf<List<String>>(emptyList()); private set
    var groupNames by mutableStateOf(listOf(FAVOURITES, RECENT, ALL)); private set
    var epg by mutableStateOf<Map<String, List<Programme>>>(emptyMap()); private set
    private var selectedGroupState by mutableStateOf(ALL)
    /** The TV Guide category being shown – remembered so Tuner reopens where you were. */
    var selectedGroup: String
        get() = selectedGroupState
        set(value) {
            if (selectedGroupState == value) return
            selectedGroupState = value
            store.setLastGroup(activeProfile.id, value)
        }

    var favourites by mutableStateOf<List<String>>(emptyList()); private set
    var favouriteSet by mutableStateOf<Set<String>>(emptySet()); private set
    var recents by mutableStateOf<List<String>>(emptyList()); private set
    var hiddenGroups by mutableStateOf<Set<String>>(emptySet()); private set
    var hiddenChannels by mutableStateOf<Set<String>>(emptySet()); private set
    var lockedGroups by mutableStateOf<Set<String>>(emptySet()); private set
    private var groupOrder: List<String> = emptyList()

    // Pre-sorted lists so switching category is a lookup, not a filter over every channel.
    private var allowedAll: List<Channel> = emptyList()
    private var byGroup: Map<String, List<Channel>> = emptyMap()
    private var byId: Map<String, Channel> = emptyMap()
    /** Same-named channels, for backup links when a stream fails. Built off the main thread. */
    private var altIndex: Map<String, List<Channel>> = emptyMap()

    /** Home rows worked out in the background (they look at every channel's guide). */
    var sportNow by mutableStateOf<List<Channel>>(emptyList()); private set
    var topPicks by mutableStateOf<List<HomeFeed.Pick>>(emptyList()); private set
    private var homeFeedJob: Job? = null

    var status by mutableStateOf<String?>(null); private set
    /** False until the saved channels have been read for the first time after start-up. */
    var initialLoadDone by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var now by mutableLongStateOf(System.currentTimeMillis()); private set

    private var refreshJob: Job? = null
    private var noticeJob: Job? = null

    // ---------------- Profiles ----------------

    var profiles by mutableStateOf<List<Profile>>(emptyList()); private set
    var activeProfile by mutableStateOf(Profile(Store.MAIN_PROFILE, "Everyone")); private set
    var editingProfile by mutableStateOf<Profile?>(null); private set
    var timesUp by mutableStateOf(false); private set

    // ---------------- Playback state ----------------

    var mode by mutableStateOf<PlayMode?>(null); private set
    var previousChannel by mutableStateOf<Channel?>(null); private set
    /** The channel to put focus back on when returning to a list. */
    var focusChannelId by mutableStateOf<String?>(null); private set
    var inPip by mutableStateOf(false)
    private var zapList: List<Channel> = emptyList()
    private var episodeQueue: List<Episode> = emptyList()
    private var lastAnnouncedProgramme: Long = 0L

    val playing: Channel?
        get() = when (val m = mode) {
            is PlayMode.Live -> m.channel
            is PlayMode.CatchupPlay -> m.channel
            else -> null
        }

    // ---------------- PIN, reminders, search, VOD, recordings, settings ----------------

    var pin by mutableStateOf<String?>(null); private set
    var unlocked by mutableStateOf(false); private set
    var pinPrompt by mutableStateOf<PinPrompt?>(null); private set

    var reminders by mutableStateOf<List<Reminder>>(emptyList()); private set
    var dueReminder by mutableStateOf<Reminder?>(null); private set

    var searchQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf(SearchResults(emptyList(), emptyList())); private set
    /** Plex films and shows matching the search. */
    var searchPlex by mutableStateOf<List<Plex.Entry>>(emptyList()); private set
    private var searchPlexJob: Job? = null
    private val startOverOffered = HashSet<String>()
    private var liveSinceId: String? = null
    private var liveSince = 0L
    var plexCopyChoice by mutableStateOf<PlexCopyChoice?>(null); private set
    var preferPlexFilms by mutableStateOf(store.preferPlexFilms); private set
    private var searchJob: Job? = null
    var homeRowOrder by mutableStateOf(store.homeRowOrder); private set
    var homeRowsHidden by mutableStateOf(store.homeRowsHidden); private set
    var homeSmart by mutableStateOf(store.homeSmart); private set
    var voiceSearchPending by mutableStateOf(false)
    var tintFromPicture by mutableStateOf(store.tintFromPicture); private set
    /** "tv" or "phone" (asked when Tuner first opens on something that isn't a TV). */
    var deviceMode by mutableStateOf(
        store.deviceMode.ifEmpty {
            val ui = app.getSystemService(android.app.UiModeManager::class.java)
            if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "tv".also { store.deviceMode = it } else ""
        }
    ); private set
    val isPhone: Boolean get() = deviceMode == "phone"
    /** Still to ask "TV or phone?". */
    val askDevice: Boolean get() = deviceMode.isEmpty()

    fun chooseDevice(mode: String) {
        deviceMode = mode
        store.deviceMode = mode
    }

    /** Tuner's own VPN (WireGuard). */
    val vpn = com.titanejstudios.tuner.features.VpnFeature(app, store, viewModelScope)
    /** Films and episodes saved on a phone for watching offline. */
    val downloader = com.titanejstudios.tuner.features.DownloadsFeature(app, store)
    val downloads: List<com.titanejstudios.tuner.features.DownloadEntry> get() = downloader.items
    /** Linked TVs and phones: send to TV, carry on where you left off. */
    val link = com.titanejstudios.tuner.features.LinkFeature(
        app, store, viewModelScope,
        phoneMode = { isPhone },
        onPlay = { from, json -> handleLinkPlay(from, json) },
        onSynced = { changed -> applyLinkChanges(changed) },
        remote = this,
    )
    /** What this profile likes, learned from what it watches. */
    private var taste: Taste = Taste.fromJson(store.taste(store.activeProfileId))
    private val pendingTaste = HashMap<Pair<String, String?>, Long>()
    var learnTaste by mutableStateOf(store.learnTaste); private set
    /** "Your shows tonight" on Home. */
    var yourShows by mutableStateOf<List<HomeFeed.Pick>>(emptyList()); private set
    /** Films like the last one you watched from your provider, and its name. */
    var becauseFilms by mutableStateOf<List<VodItem>>(emptyList()); private set
    var becauseFilmOf by mutableStateOf<String?>(null); private set
    /** This week's trending films and TV, with where you can watch each (Plex, your provider) if you have it. */
    var trending by mutableStateOf<List<TrendingItem>>(emptyList()); private set
    private var trendingAt = 0L
    /** Bumped when tastes change, so Settings › Recommendations redraws. */
    var tasteVersion by mutableStateOf(0); private set
    /** Countries/languages hidden (Settings › Languages & countries). */
    var hiddenLanguages by mutableStateOf(store.hiddenLanguages); private set
    var artSaverMinutes by mutableStateOf(store.artSaverMinutes); private set
    /** Tuner's artwork screensaver is showing. */
    var saverOn by mutableStateOf(false); private set
    /** When CH+/CH− was last pressed (the player shows the channel strip). */
    var zappedAt by mutableStateOf(0L); private set

    var movies by mutableStateOf<List<VodItem>?>(null); private set
    var series by mutableStateOf<List<SeriesItem>?>(null); private set
    var vodStatus by mutableStateOf<String?>(null); private set
    var openSeries by mutableStateOf<SeriesItem?>(null); private set
    var episodes by mutableStateOf<List<Episode>?>(null); private set
    var resume by mutableStateOf<Map<String, ResumeEntry>>(emptyMap()); private set

    var recordings by mutableStateOf<List<Recording>>(emptyList()); private set
    var webhookUrl by mutableStateOf<String?>(null); private set
    var openLastChannel by mutableStateOf(false); private set
    /** "home", "guide" or "channel". */
    var startScreen by mutableStateOf("home"); private set
    var refreshOnStart by mutableStateOf(true); private set
    var seriesLinks by mutableStateOf<List<SeriesLink>>(emptyList()); private set

    // Other apps on the TV (Plex, BBC iPlayer, Netflix…)
    var tvApps by mutableStateOf<List<TvApp>?>(null); private set
    var hiddenApps by mutableStateOf<Set<String>>(emptySet()); private set
    private var appsJob: Job? = null

    var logText by mutableStateOf(""); private set

    // Sleep timer: when to stop (0 = off); endOfProgramme follows the programme if it overruns the guide.
    var sleepAt by mutableLongStateOf(0L); private set
    private var sleepJob: Job? = null

    // Look & sound
    var themeAccent by mutableStateOf("sky"); private set
    var themeBackground by mutableStateOf("ink"); private set
    var subtitleScale by mutableStateOf(1f); private set
    var subtitleBox by mutableStateOf(false); private set
    var nightMode by mutableStateOf(false); private set
    var clearSpeech by mutableStateOf(store.clearSpeech); private set
    /** Text size across Flicker (%). */
    var textScale by mutableStateOf(store.textScale); private set
    var frameRateMatch by mutableStateOf(false); private set

    // What's on
    var whatsOnSlot by mutableStateOf(0)
    var whatsOnGenre by mutableStateOf<String?>(null)
    var whatsOnFavouritesOnly by mutableStateOf(false)
    var whatsOnResults by mutableStateOf<List<HomeFeed.Pick>?>(null); private set
    private var whatsOnJob: Job? = null

    // Watch stats (seconds not yet saved, flushed every minute)
    var statsSummary by mutableStateOf<WatchStats.Summary?>(null); private set
    var statsProfile by mutableStateOf<Profile?>(null); private set

    // Per-channel picture size and lip sync; preferred languages
    var pictureModes by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var lipSyncMap by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var audioLanguage by mutableStateOf<String?>(null); private set
    var subtitleLanguage by mutableStateOf<String?>(null); private set

    // Recording tidy-up and network drive
    var keptRecordings by mutableStateOf<Set<String>>(emptySet()); private set
    var autoDeleteDays by mutableStateOf(0); private set
    var nasUrl by mutableStateOf<String?>(null); private set
    var nasKeepLocal by mutableStateOf(true); private set
    var nasStatus by mutableStateOf<String?>(null); private set

    // Plex, football scores and app updates live in their own classes (features/); the
    // properties below just forward to them so the screens didn't need to change.
    val plexFeature = PlexFeature(store, viewModelScope)
    val plexLinked: Boolean get() = plexFeature.linked
    val plexCode: String? get() = plexFeature.code
    val plexStatus: String? get() = plexFeature.status
    val plexItems: List<Plex.Item> get() = plexFeature.items
    val plexRecent: List<Plex.Entry>
        get() = if (activeProfile.kids) plexFeature.recent.filter { plexFeature.isKidsEntry(it) } else plexFeature.recent
    private var plexPlaying: Plex.Item? = null

    val football = FootballFeature(store, viewModelScope, { inForeground }, { showNotice(it) }, { e, d -> sendHa(e, d) })
    val footballKey: String? get() = football.key
    val footballTeams: Set<String> get() = football.teams
    val footballMatches: List<Football.Match> get() = football.matches
    val footballStatus: String? get() = football.status

    val updates = UpdateFeature(app, store, viewModelScope)
    val updateRepo: String? get() = updates.repo
    val updateStatus: String? get() = updates.status

    // ---------------- 5.3 ----------------

    /** True when Tuner started in safe mode after crashes (extras switched off). */
    val safeMode: Boolean get() = SafeMode.active
    val lastCrash: String? get() = SafeMode.lastCrash
    var safeModeBannerHidden by mutableStateOf(false)

    // Self-test (Settings › Help › Check everything)
    var selfTestResults by mutableStateOf<List<SelfTest.Result>>(emptyList()); private set
    var selfTestRunning by mutableStateOf(false); private set
    private var selfTestJob: Job? = null

    /** While ▲▼ are pressed quickly: the channel shown on the info bar, tuned when you stop. */
    var zapPreview by mutableStateOf<Channel?>(null); private set
    private var zapJob: Job? = null
    private var lastZapAt = 0L

    /** Bigger start-up buffer for channels that keep dropping out. */
    private val cushion = StreamCushion({ store.flakyStreams }, { store.flakyStreams = it })
    private var cleanPlaySeconds = 0

    /** When the guide in memory was read from the database (re-read every few hours). */
    private var epgLoadedAt = 0L
    /** Background extras (Plex, scores, apps…) start a few seconds after the channels. */
    private var extrasStarted = false

    // My list and new episodes
    var watchlist by mutableStateOf<List<WatchItem>>(emptyList()); private set
    var followedSeries by mutableStateOf<List<FollowedSeries>>(emptyList()); private set
    private val vodCache = VodCache(app.filesDir)
    /** Set while saved film/series lists are being refreshed in the background. */
    var vodUpdating by mutableStateOf(false); private set

    // Kids
    private var lastKidsWarning = ""
    private var bedtimeOverrideUntil = 0L

    // Home Assistant cinema lights
    var cinemaLights by mutableStateOf("off"); private set
    private var lightsDimmed = false

    /** How many streams the provider allows at once (for the recordings planner); null = unknown. */
    var accountStreams by mutableStateOf<Int?>(null); private set
    private var accountStreamsLoaded = false

    // ---------------- 5.4 ----------------

    // "Are you still watching?"
    var stillWatchingHours by mutableStateOf(4); private set
    var stillWatchingAsk by mutableStateOf(false); private set
    private var lastUserActivity = System.currentTimeMillis()
    private var stillWatchingDeadline = 0L

    // Per-channel volume level and picture quality
    var volumeLevels by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var qualityModes by mutableStateOf<Map<String, String>>(emptyMap()); private set

    // Followed teams' matches
    var sportFollowMode by mutableStateOf("remind"); private set

    // Film info (TMDB) and subtitles (OpenSubtitles)
    var tmdbKey by mutableStateOf<String?>(null); private set
    var openSubtitlesKey by mutableStateOf<String?>(null); private set
    /** The film/series info card: shown while [filmInfoOpen]; null info while loading or not found. */
    var filmInfoOpen by mutableStateOf(false); private set
    var filmInfo by mutableStateOf<Tmdb.Info?>(null); private set
    var filmInfoStatus by mutableStateOf<String?>(null); private set
    var subtitleHits by mutableStateOf<List<OpenSubtitles.Hit>?>(null); private set
    var subtitleStatus by mutableStateOf<String?>(null); private set

    // Skip intro (learned per series)
    var introEnds by mutableStateOf<Map<String, String>>(emptyMap()); private set

    // Keeping TVs in sync, and nightly backups, via the network drive
    var syncEnabled by mutableStateOf(false); private set
    var syncStatus by mutableStateOf<String?>(null); private set
    private var syncJob: Job? = null
    var nasBackupOn by mutableStateOf(true); private set

    // One entry for HD/SD copies of a channel
    var mergeVersions by mutableStateOf(true); private set
    private var preferredVersions: Map<String, String> = emptyMap()
    /** Shown channel id → all its versions (best first), when copies were combined. */
    private var versionsOf: Map<String, List<Channel>> = emptyMap()

    // Category editor
    var groupRenames by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var customGroups by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    /** Channel waiting to be added to one of your categories (shows the picker). */
    var categoryPickFor by mutableStateOf<Channel?>(null)
    private var originalGroups: Set<String> = emptySet()

    // TV's own channel list / Live tab
    var liveTabScope by mutableStateOf("off"); private set
    var liveTabStatus by mutableStateOf<String?>(null); private set
    private var liveSyncJob: Job? = null

    // Copy settings to / from another TV
    /** "send" (this TV shows a code) or "receive" (this TV looks for others). */
    var shareMode by mutableStateOf<String?>(null); private set
    var shareCode by mutableStateOf<String?>(null); private set
    var shareFound by mutableStateOf<List<TvShare.Found>>(emptyList()); private set
    var shareStatus by mutableStateOf<String?>(null); private set
    private var tvShare: TvShare? = null
    private val pendingStats = HashMap<Pair<String, String>, Long>()
    /** Live viewing per channel since the last save, for "your usual channel". */
    private val pendingHabits = HashMap<String, Long>()
    var offerUsual by mutableStateOf(store.offerUsual); private set
    var scrubPreviewsIptv by mutableStateOf(store.scrubPreviewsIptv); private set
    /** The channel you usually watch at this time, offered on Home. */
    var usualOffer by mutableStateOf<Channel?>(null); private set
    /** A better version of the channel you're on is working again (shown once, as a message on the picture). */
    var qualityBack by mutableStateOf<Channel?>(null); private set
    private var upgradeJob: Job? = null
    /** Channels already told about this session ("only display the message once"). */
    private val upgradeTold = HashSet<String>()
    /** The Christmas TV planner's programmes (null until loaded). */
    var festive by mutableStateOf<List<FestiveItem>?>(null); private set
    /** Missed it? Programmes from the last week you'd probably like, ready on catch-up. */
    var missed by mutableStateOf<List<HomeFeed.Pick>>(emptyList()); private set
    /** Programmes you switched off half-way (newest first). */
    var unfinished by mutableStateOf(loadUnfinished()); private set
    /** The live programme being watched: channel, programme start, seconds watched, last seen. */
    private var liveWatch: LiveWatch? = null
    /** Your teams' next fixtures with their channels (My teams). */
    var teamFixtures by mutableStateOf<List<TeamFixture>>(emptyList()); private set
    var epgOverrides by mutableStateOf<Map<String, String>>(emptyMap()); private set

    var mappingChannel by mutableStateOf<Channel?>(null); private set
    var epgCandidates by mutableStateOf<List<XmltvChannel>?>(null); private set

    var phoneUrl by mutableStateOf<String?>(null); private set
    var remoteUrl by mutableStateOf<String?>(null); private set
    private var phoneServer: PhoneSetupServer? = null
    private var phoneBase: String? = null
    private var setupOpen = false
    private var inForeground = true

    /** Key presses from the phone remote; MainActivity turns them into real key events. */
    val remoteKeys = MutableSharedFlow<Int>(extraBufferCapacity = 32)

    // Channel health, fast switching, timeshift, phone remote settings
    var deadChannels by mutableStateOf<Set<String>>(emptySet()); private set
    var hideDead by mutableStateOf(false); private set
    var healthNightly by mutableStateOf(false); private set
    var healthScope by mutableStateOf("favourites"); private set
    var healthCheckedAt by mutableLongStateOf(0L); private set
    var healthProgress by mutableStateOf<String?>(null); private set
    private var healthJob: Job? = null
    @Volatile private var healthCancel = false
    var fastSwitch by mutableStateOf(false); private set
    var timeshiftEnabled by mutableStateOf(true); private set
    var remoteEnabled by mutableStateOf(false); private set
    var audioPassthrough by mutableStateOf(false); private set
    var streamCompatibility by mutableStateOf(false); private set
    private var lastZapDirection = 1
    private var standbyJob: Job? = null

    private var pendingLink: Uri? = null
    private var startupHandled = false

    init {
        loadSettingsFromStore()
        playback.onEnded = { onPlaybackEnded() }
        playback.onRecovery = { showNotice(it) }
        playback.onAlternate = { url -> onBackupLink(url) }
        playback.cushionFor = { id -> cushion.cushionMs(id) }
        playback.onStreamTrouble = { id -> cushion.trouble(id) }
        playback.timeshiftDir = {
            if (!timeshiftEnabled) null
            else File(recordingDir).parentFile?.let { File(it, "Timeshift") } ?: File(app.filesDir, "Timeshift")
        }

        if (sources.isEmpty()) {
            editingSource = null
            resetTo(Screen.SourceEdit)
        } else {
            if (profiles.size > 1) resetTo(Screen.ProfilePicker)
            viewModelScope.launch {
                loadActive()
                // With "update on start-up" off, playlists only update overnight (or from Settings).
                val stale = activeSources().filter {
                    !db.hasChannels(it.id) ||
                        (refreshOnStart && System.currentTimeMillis() - store.lastRefresh(it.id) > 12 * 3600_000L)
                }
                if (stale.isNotEmpty()) refresh(stale)
            }
        }

        // Lip-sync follows the channel being watched (ignored in safe mode).
        viewModelScope.launch {
            snapshotFlow { playing?.id to lipSyncMap }.collect { (id, map) ->
                val ms = if (SafeMode.active) 0L else (id?.let { map[it] } ?: map["*"])?.toLongOrNull() ?: 0L
                com.titanejstudios.tuner.playback.LipSync.offsetUs = ms * 1000L
            }
        }
        // Volume level and picture quality follow the channel being watched.
        viewModelScope.launch {
            snapshotFlow { Triple(playing?.id, volumeLevels, qualityModes) }.collect { (id, vols, quals) ->
                playback.volumeDb = id?.let { vols[it]?.toIntOrNull() } ?: 0
                playback.quality = id?.let { quals[it] } ?: "auto"
            }
        }
        com.titanejstudios.tuner.apps.Updater.onStatus = { msg -> viewModelScope.launch { showNotice(msg) } }
        watchPlaybackForHomeAssistant()

        // The VPN first, so nothing streams before it's up.
        runCatching { vpn.autoStart() }
        // Start-up: channels first; everything else a few seconds later so the first screen is quick.
        viewModelScope.launch {
            delay(4_000)
            startExtras()
        }

        viewModelScope.launch {
            var ticks = 0
            while (true) {
                now = System.currentTimeMillis()
                checkReminders()
                offerStartOver()
                checkSaver()
                trackUsage()
                announceProgrammeChange()
                trackCleanPlay()
                checkStillWatching()
                if (ticks % 2 == 0 && mode is PlayMode.Vod && playback.isPlaying) saveResume(syncHome = false)
                if (ticks % 4 == 3) refreshHomeFeed()
                if (ticks % 4 == 1) keepGuideFresh()
                // Tuner can stay open for days: look for updates (at most once a day).
                if (ticks % 240 == 239) updates.autoCheck()
                // A minute without crashing: start-up crashes no longer count towards safe mode.
                if (ticks == 4) SafeMode.markStable()
                trackStats(flush = ticks % 4 == 0)
                recordings = store.recordings
                ticks++
                delay(15_000)
            }
        }
    }

    /** Things that don't need to be ready for the first screen. Most are skipped in safe mode. */
    private fun startExtras() {
        if (extrasStarted) return
        extrasStarted = true
        // Even in safe mode – a newer version may be the fix.
        updates.autoCheck()
        runCatching { GuideRefreshWorker.schedule(app) }
        runCatching { RecordingScheduler.rescheduleAll(app) }
        loadApps()
        reportCrashToHomeAssistant()
        if (SafeMode.active) {
            AppLog.info("Safe mode: Plex, football scores, Live tab, TV sync and update checks are off")
            return
        }
        football.start()
        plexFeature.refresh()
        checkNewEpisodes()
        startSync()
    }

    private fun loadSettingsFromStore() {
        profiles = store.profiles
        activeProfile = profiles.firstOrNull { it.id == store.activeProfileId } ?: profiles.first()
        favourites = store.favourites(activeProfile.id)
        favouriteSet = favourites.toSet()
        recents = store.recents(activeProfile.id)
        hiddenGroups = store.hiddenGroups
        hiddenChannels = store.hiddenChannels
        lockedGroups = store.lockedGroups
        groupOrder = store.groupOrder
        pin = store.pin
        reminders = store.reminders
        resume = store.resume
        sources = store.sources
        activeSourceId = store.activeSourceId
        recordings = store.recordings
        webhookUrl = store.webhookUrl
        openLastChannel = store.openLastChannel
        startScreen = store.startScreen
        refreshOnStart = store.refreshOnStart
        seriesLinks = store.seriesLinks
        hiddenApps = store.hiddenApps
        selectedGroupState = store.lastGroup(activeProfile.id) ?: ALL
        themeAccent = store.themeAccent
        themeBackground = store.themeBackground
        TunerColors.apply(themeAccent, themeBackground)
        subtitleScale = store.subtitleScale
        subtitleBox = store.subtitleBox
        SubtitleLook.scale = subtitleScale
        SubtitleLook.background = subtitleBox
        // Safe mode leaves the newer extras off without changing the saved settings.
        nightMode = store.nightMode && !SafeMode.active
        playback.nightModeOn = nightMode
        clearSpeech = store.clearSpeech && !SafeMode.active
        playback.speechOn = clearSpeech
        frameRateMatch = store.frameRateMatch && !SafeMode.active
        playback.setSeamlessFrameRate(frameRateMatch)
        liveTabScope = store.liveTabScope
        pictureModes = store.pictureModes
        groupRenames = store.groupRenames
        loadRecordingSettings()
        customGroups = store.customGroups
        lipSyncMap = store.lipSync
        audioLanguage = store.audioLanguage
        subtitleLanguage = store.subtitleLanguage
        playback.setLanguages(audioLanguage, subtitleLanguage)
        epgOverrides = store.epgOverrides
        deadChannels = store.deadChannels
        hideDead = store.hideDead
        healthNightly = store.healthNightly
        healthScope = store.healthScope
        healthCheckedAt = store.healthCheckedAt
        fastSwitch = store.fastSwitch && !SafeMode.active
        playback.fastSwitch = fastSwitch
        timeshiftEnabled = store.timeshiftEnabled
        remoteEnabled = store.remoteEnabled
        audioPassthrough = store.audioPassthrough
        streamCompatibility = store.streamCompatibility
        watchlist = Watchlist.decode(store.watchlistJson)
        followedSeries = Watchlist.decodeFollowed(store.followedSeriesJson)
        cinemaLights = store.cinemaLights
        stillWatchingHours = store.stillWatchingHours
        volumeLevels = store.volumeLevels
        qualityModes = store.qualityModes
        sportFollowMode = store.sportFollowMode
        tmdbKey = store.tmdbKey
        openSubtitlesKey = store.openSubtitlesKey
        introEnds = store.introEnds
        syncEnabled = store.syncEnabled
        nasBackupOn = store.nasBackup
        mergeVersions = store.mergeVersions
        preferredVersions = store.preferredVersions
        updateServer()
    }

    override fun onCleared() {
        link.stop()
        healthCancel = true
        tvShare?.stop()
        phoneServer?.stop()
        multi.release()
        playback.release()
    }

    fun showNotice(message: String) {
        notice = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(3_500)
            notice = null
        }
    }

    private fun sendHa(event: String, data: Map<String, Any?> = emptyMap()) {
        HomeAssistantWebhook.send(webhookUrl, event, data + ("profile" to activeProfile.name))
    }

    // ---------------- Loading ----------------

    fun activeSources(): List<Source> =
        if (activeSourceId == ALL_SOURCES) sources
        else sources.filter { it.id == activeSourceId }.ifEmpty { sources }

    val activeSourceName: String
        get() = if (activeSourceId == ALL_SOURCES || sources.size <= 1) {
            if (sources.size == 1) sources[0].name else "All playlists"
        } else sources.firstOrNull { it.id == activeSourceId }?.name ?: ""

    val hasVod: Boolean get() = !activeProfile.kids && activeSources().any { it is Source.Xtream }

    /**
     * Reads the saved playlists in two steps: channels first (quick – lists and Home appear straight
     * away), then the guide (can be hundreds of thousands of programmes).
     */
    private suspend fun loadActive() {
        val ids = activeSources().map { it.id }
        val list = withContext(Dispatchers.IO) {
            db.loadChannels(ids).also { altIndex = StreamAlternates.index(it) }
        }
        allChannels = list
        recomputeVisible()
        initialLoadDone = true
        handlePendingLink()
        handleStartup()
        publishFavouritesRow()
        loadGuide()
    }

    /** Reads the next 36 hours of guide (plus catch-up history) for the channels in memory. */
    private suspend fun loadGuide() {
        val ids = activeSources().map { it.id }
        val chs = allChannels
        val t = System.currentTimeMillis()
        val guide = withContext(Dispatchers.IO) {
            val catchupFrom = HashMap<String, Long>()
            // A copy without catch-up (e.g. the 1080p one) still gets the history if another copy
            // of the same channel has catch-up, so the guide can offer it.
            val familyDays = HashMap<String, Int>()
            for (c in chs) if (c.hasCatchup) {
                val k = c.group + "|" + ChannelMerge.key(c.name)
                familyDays[k] = maxOf(familyDays[k] ?: 0, c.catchupDays)
            }
            for (c in chs) {
                val days = if (c.hasCatchup) c.catchupDays else familyDays[c.group + "|" + ChannelMerge.key(c.name)] ?: 0
                if (days > 0) catchupFrom[c.id] = t - days.coerceAtMost(7) * 86_400_000L
            }
            shiftGuide(chs, db.loadEpg(ids, t - 3 * 3600_000L, t + 36 * 3600_000L, catchupFrom))
        }
        epg = guide
        epgLoadedAt = t
        applySeriesLinks()
        refreshHomeFeed()
        updateWidget()
        checkUsual()
        refreshTeamFixtures()
        applySportFollow()
    }

    /**
     * Tuner can stay open for days. Re-read the guide when the overnight update has saved a newer
     * one, or every 6 hours so "now and next" never runs off the end of what's in memory.
     */
    private fun keepGuideFresh() {
        if (!initialLoadDone || epgLoadedAt == 0L || refreshJob?.isActive == true) return
        val t = System.currentTimeMillis()
        val newer = activeSources().any { store.lastRefresh(it.id) > epgLoadedAt }
        if (newer) {
            // The overnight job may also have changed the channel list.
            viewModelScope.launch { loadActive() }
        } else if (t - epgLoadedAt > 6 * 3600_000L) {
            viewModelScope.launch { loadGuide() }
        }
    }

    fun refresh(targets: List<Source> = activeSources()) {
        if (targets.isEmpty() || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            error = null
            val problems = ArrayList<String>()
            for ((i, s) in targets.withIndex()) {
                val which = if (targets.size > 1) " – ${s.name} (${i + 1} of ${targets.size})" else ""
                try {
                    status = "Loading channels$which…"
                    val (list, playlistEpg) = withContext(Dispatchers.IO) { inBackgroundPriority { repo.refreshChannels(s) } }
                    // Show the channels straight away; the guide fills in when it's ready.
                    loadActive()
                    status = "Loading TV guide$which… (channels can be watched now)"
                    val guideError = withContext(Dispatchers.IO) { inBackgroundPriority { repo.refreshGuide(s, list, playlistEpg) } }
                    guideError?.let { problems += "${s.name}: TV guide failed ($it)" }
                } catch (e: Exception) {
                    problems += "${s.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            loadActive()
            status = null
            if (problems.isNotEmpty()) error = problems.joinToString("  ·  ")
            scheduleLiveSync()
        }
    }

    /** Run heavy work (guide parsing) at low CPU priority so the UI and video stay smooth. */
    private fun <T> inBackgroundPriority(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val old = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return block()
        } finally {
            android.os.Process.setThreadPriority(old)
        }
    }

    private fun recomputeVisible() {
        val hiddenCh = hiddenChannels
        val dead = if (hideDead) deadChannels else emptySet()
        originalGroups = allChannels.mapTo(HashSet()) { it.group }
        // Your renamed / merged categories.
        val renames = groupRenames
        val renamed = if (renames.isEmpty()) allChannels else allChannels.map { c -> renames[c.group]?.let { c.copy(group = it) } ?: c }
        // Languages & countries you've hidden (e.g. everything not in English).
        val hiddenLang = hiddenLanguages
        val source = if (hiddenLang.isEmpty()) renamed else renamed.filter { languageShown(Languages.tagOf(it)?.code, hiddenLang) }
        channels = source.filter { it.id !in hiddenCh && it.id !in dead }
        // Categories in the provider's order (playlist by playlist), like TiviMate.
        val sourceIndex = sources.withIndex().associate { (i, src) -> src.id to i }
        val firstOf = LinkedHashMap<String, Channel>()
        for (c in source) firstOf.putIfAbsent(c.group, c)
        val distinct = firstOf.values
            .sortedWith(compareBy({ sourceIndex[it.sourceId] ?: 0 }, { it.groupPos }))
            .map { it.group }
        val custom = customGroups.keys.filter { it != FAVOURITES && it != RECENT && it != ALL }
        val customSet = custom.toHashSet()
        val present = distinct.toHashSet().also { it.addAll(custom) }
        val ordered = groupOrder.filter { it in present }
        val orderedSet = ordered.toHashSet()
        // Your own categories come first (unless you've moved them), then the provider's.
        allGroups = ordered + custom.filter { it !in orderedSet } + distinct.filter { it !in orderedSet && it !in customSet }
        // Skip provider groups that clash with the built-in names (duplicate list keys crash Compose).
        val visibleGroups = allGroups.filter {
            it !in hiddenGroups && profileAllows(it) && it != FAVOURITES && it != RECENT && it != ALL
        }
        groupNames = listOf(FAVOURITES, RECENT, ALL) + visibleGroups
        if (selectedGroup !in groupNames) selectedGroup = ALL
        rebuildChannelCaches()
    }

    private fun rebuildChannelCaches() {
        if (mergeVersions) {
            // HD/SD copies of a channel show once (best quality, or the version you picked).
            val merged = ChannelMerge.merge(channels, preferredVersions)
            allowedAll = merged.channels.filter { isAllowed(it) }
            versionsOf = merged.versions
            byGroup = merged.channels.groupBy { it.group }
        } else {
            allowedAll = channels.filter { isAllowed(it) }
            versionsOf = emptyMap()
            byGroup = channels.groupBy { it.group }
        }
        // Every copy stays findable by id (favourites, recents, deep links, your own categories).
        byId = channels.associateBy { it.id }
        refreshHomeFeed()
    }

    /** Other versions of [channel] (HD/SD…), best first; empty when there's only one. */
    fun versionsFor(channel: Channel): List<Channel> {
        versionsOf[channel.id]?.let { return it }
        // Playing a version that isn't the one shown in the list: find its family.
        if (!mergeVersions) return emptyList()
        val k = ChannelMerge.key(channel.name)
        return versionsOf.values.firstOrNull { list -> list.any { it.id == channel.id } }
            ?: versionsOf.values.firstOrNull { list -> ChannelMerge.key(list[0].name) == k && list[0].group == channel.group }
            ?: emptyList()
    }

    fun versionLabel(channel: Channel): String = ChannelMerge.versionLabel(channel)

    /** Player menu › Version: switch to another copy, and remember it for this channel. */
    fun watchVersion(channel: Channel) {
        preferredVersions = preferredVersions + (ChannelMerge.key(channel.name) to channel.id)
        store.preferredVersions = preferredVersions
        startLive(channel)
        rebuildChannelCaches()
        showNotice("${channel.name} – Flicker will use this version from now on")
    }

    fun toggleMergeVersions() {
        mergeVersions = !mergeVersions
        store.mergeVersions = mergeVersions
        recomputeVisible()
        showNotice(if (mergeVersions) "HD and SD copies now show as one channel" else "Every copy of a channel is shown")
    }

    private fun refreshHomeFeed() {
        val chans = allowedAll
        val guide = epg
        val rec = recents
        val favs = favourites
        homeFeedJob?.cancel()
        homeFeedJob = viewModelScope.launch {
            val t = System.currentTimeMillis()
            val learned = taste
            val result = withContext(Dispatchers.Default) {
                // Once Tuner has learned a little about you, picks follow your taste (with reasons).
                val picks = if (learned.isEmpty) HomeFeed.topPicks(chans, guide, rec, favs, t)
                else Recommend.topPicks(chans, guide, learned, rec, t).ifEmpty { HomeFeed.topPicks(chans, guide, rec, favs, t) }
                Triple(HomeFeed.sport(chans, guide, t), picks, Recommend.yourShowsTonight(chans, guide, learned, t))
            }
            sportNow = result.first
            topPicks = result.second
            yourShows = result.third
            missed = if (learned.isEmpty) emptyList() else withContext(Dispatchers.Default) { missedPicks(chans, guide, learned, t) }
        }
    }

    /** Opens where you left off – Home, the TV Guide on your last category and channel, or the last channel. */
    private fun handleStartup() {
        if (startupHandled || allChannels.isEmpty() || pendingLink != null) return
        if (screen == Screen.ProfilePicker) return
        startupHandled = true
        if (mode != null) return
        // Safe mode always opens on Home, in case the last channel is what keeps crashing.
        if (SafeMode.active) return
        val id = store.lastChannel(activeProfile.id)
        val ch = id?.let { byId[it] }?.takeIf { isAllowed(it) }
        if (ch != null) focusChannelId = ch.id
        when (startScreen) {
            "channel" -> if (ch != null) {
                val group = selectedGroup
                val list = channelsFor(group).takeIf { l -> l.any { it.id == ch.id } } ?: channelsFor(ALL)
                play(ch, list)
            }
            "guide" -> if (screen == Screen.Home) navigate(Screen.Channels)
            else -> Unit
        }
    }

    // ---------------- Lists ----------------

    private fun profileAllows(group: String): Boolean =
        !activeProfile.kids || group in activeProfile.allowedGroups

    fun isGroupLocked(group: String): Boolean = pin != null && !unlocked && group in lockedGroups
    fun isAllowed(channel: Channel): Boolean = profileAllows(channel.group) && !isGroupLocked(channel.group)

    fun channelsFor(group: String): List<Channel> = when (group) {
        ALL -> allowedAll
        FAVOURITES -> favourites.mapNotNull { byId[it] }.filter { isAllowed(it) }
        RECENT -> recents.mapNotNull { byId[it] }.filter { isAllowed(it) }
        else -> when {
            isGroupLocked(group) || !profileAllows(group) -> emptyList()
            group in customGroups -> customGroups[group].orEmpty().mapNotNull { byId[it] }.filter { isAllowed(it) }
            else -> byGroup[group].orEmpty()
        }
    }

    // ---------------- Guide time fix ----------------

    /** Moves each playlist's guide by its chosen number of minutes. */
    private fun shiftGuide(chs: List<Channel>, guide: Map<String, List<Programme>>): Map<String, List<Programme>> {
        val shifts = store.epgShift.mapValues { (_, v) -> (v.toLongOrNull() ?: 0L) * 60_000L }.filterValues { it != 0L }
        if (shifts.isEmpty()) return guide
        val sourceOf = chs.associate { it.id to it.sourceId }
        return guide.mapValues { (id, list) ->
            val d = shifts[sourceOf[id]] ?: 0L
            if (d == 0L) list else list.map { it.copy(start = it.start + d, stop = it.stop + d) }
        }
    }

    fun guideShiftMinutes(sourceId: String): Int = store.epgShift[sourceId]?.toIntOrNull() ?: 0

    fun guideShiftLabel(sourceId: String): String {
        val m = guideShiftMinutes(sourceId)
        return when {
            m == 0 -> "No change"
            m % 60 == 0 -> (if (m > 0) "+" else "−") + "${kotlin.math.abs(m / 60)} hour" + if (kotlin.math.abs(m) > 60) "s" else ""
            else -> (if (m > 0) "+" else "−") + "${kotlin.math.abs(m)} min"
        }
    }

    /** Cycles 0 → +1h → +2h → −2h → −1h → −30 min → +30 min → 0. */
    fun cycleGuideShift(sourceId: String) {
        val steps = listOf(0, 60, 120, -120, -60, -30, 30)
        val i = steps.indexOf(guideShiftMinutes(sourceId)).coerceAtLeast(0)
        val next = steps[(i + 1) % steps.size]
        store.epgShift = if (next == 0) store.epgShift - sourceId else store.epgShift + (sourceId to next.toString())
        viewModelScope.launch { loadActive() }
        showNotice("Guide times for ${sources.firstOrNull { it.id == sourceId }?.name ?: "this playlist"}: ${guideShiftLabel(sourceId)}")
    }

    // ---------------- Category editor ----------------

    fun isCustomGroup(group: String) = group in customGroups

    private fun nameTaken(name: String) =
        name == FAVOURITES || name == RECENT || name == ALL || name in customGroups ||
            channels.any { it.group == name }

    /** Renames a category. Renaming to an existing category's name merges the two. */
    fun renameGroup(current: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == current) return
        if (name == FAVOURITES || name == RECENT || name == ALL) {
            showNotice("That name is used by Flicker")
            return
        }
        if (current in customGroups) {
            if (name in customGroups || channels.any { it.group == name }) {
                showNotice("There's already a category called $name")
                return
            }
            customGroups = customGroups.toMutableMap().also { m -> m[name] = m.remove(current).orEmpty() }
            store.customGroups = customGroups
        } else {
            val merging = channels.any { it.group == name }
            val r = groupRenames.toMutableMap()
            for (o in originalGroups) {
                if ((r[o] ?: o) == current) {
                    if (o == name) r.remove(o) else r[o] = name
                }
            }
            groupRenames = r
            store.groupRenames = r
            if (merging) showNotice("Merged $current into $name") else showNotice("Renamed to $name")
        }
        // Keep its place, hidden and locked settings.
        fun Set<String>.swap() = if (current in this) this - current + name else this
        hiddenGroups = hiddenGroups.swap().also { store.hiddenGroups = it }
        lockedGroups = lockedGroups.swap().also { store.lockedGroups = it }
        groupOrder = groupOrder.map { if (it == current) name else it }.distinct().also { store.groupOrder = it }
        if (selectedGroup == current) selectedGroup = name
        recomputeVisible()
    }

    /** Puts a category's original name back (undoes renames and merges into it). */
    fun resetGroupName(current: String) {
        groupRenames = groupRenames.filterValues { it != current }
        store.groupRenames = groupRenames
        recomputeVisible()
        showNotice("Original category names restored")
    }

    fun createCustomGroup(name: String, firstChannel: Channel?) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (nameTaken(n)) {
            showNotice("There's already a category called $n")
            return
        }
        customGroups = customGroups + (n to listOfNotNull(firstChannel?.id))
        store.customGroups = customGroups
        recomputeVisible()
        showNotice(if (firstChannel != null) "${firstChannel.name} added to $n" else "Category $n made")
    }

    fun toggleInCustomGroup(group: String, channel: Channel) {
        val ids = customGroups[group] ?: return
        val now = if (channel.id in ids) ids - channel.id else ids + channel.id
        customGroups = customGroups + (group to now)
        store.customGroups = customGroups
        recomputeVisible()
        showNotice(if (channel.id in now) "${channel.name} added to $group" else "${channel.name} removed from $group")
    }

    fun deleteCustomGroup(group: String) {
        customGroups = customGroups - group
        store.customGroups = customGroups
        if (selectedGroup == group) selectedGroup = ALL
        recomputeVisible()
        showNotice("Category $group deleted (the channels are still in the others)")
    }

    fun programmesFor(channelId: String): List<Programme> = epg[channelId].orEmpty()

    fun nowNext(channelId: String, t: Long): Pair<Programme?, Programme?> {
        val list = epg[channelId] ?: return null to null
        val i = list.indexOfFirst { it.stop > t }
        if (i < 0) return null to null
        val current = list[i].takeIf { it.start <= t }
        val next = if (current != null) list.getOrNull(i + 1) else list[i]
        return current to next
    }

    /** How far back the guide can scroll – the longest catch-up window, at least 2 hours. */
    val guideHistoryMs: Long
        get() = maxOf(2 * 3600_000L, (allChannels.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(7) * 86_400_000L)

    // ---------------- Playback ----------------

    private fun liveMetadata(channel: Channel): MediaMetadata {
        val current = nowNext(channel.id, System.currentTimeMillis()).first
        val b = MediaMetadata.Builder().setTitle(current?.title ?: channel.name).setArtist(channel.name)
        channel.logo?.let { b.setArtworkUri(Uri.parse(it)) }
        return b.build()
    }

    fun play(channel: Channel, list: List<Channel>) {
        if (!isAllowed(channel)) {
            requestUnlock { play(channel, list) }
            return
        }
        if (timeLeftSeconds() <= 0 || isBedtimeNow()) {
            timesUp = true
            return
        }
        zapList = list.ifEmpty { listOf(channel) }
        startLive(channel)
        navigate(Screen.Player)
    }

    /** Switch channel from the in-player channel list (stays in the player). */
    fun playFromOverlay(channel: Channel, list: List<Channel>) {
        zapList = list.ifEmpty { listOf(channel) }
        startLive(channel)
    }

    private fun startLive(channel: Channel, force: Boolean = false) {
        if (vpnBlocks()) return
        if (zapPreview != null) {
            zapJob?.cancel()
            zapPreview = null
        }
        cleanPlaySeconds = 0
        usualOffer = null
        val current = mode
        if (!force && current is PlayMode.Live && current.channel.id == channel.id && playback.isActive) return
        upgradeJob?.cancel()
        qualityBack = null
        if (current is PlayMode.Live && current.channel.id != channel.id) previousChannel = current.channel
        if (current is PlayMode.Vod) saveResume(syncHome = true)
        mode = PlayMode.Live(channel)
        focusChannelId = channel.id
        playback.play(
            channel.url,
            live = true,
            metadata = liveMetadata(channel),
            alternates = StreamAlternates.forChannel(channel, altIndex).filter { it != channel.url },
            key = channel.id,
        )
        addRecent(channel.id)
        store.setLastChannel(activeProfile.id, channel.id)
        scheduleStandby(channel)
        val p = nowNext(channel.id, System.currentTimeMillis()).first
        lastAnnouncedProgramme = p?.start ?: 0L
        sendHa(
            "playing",
            mapOf(
                "kind" to "live", "channel" to channel.name, "group" to channel.group, "logo" to channel.logo,
                "title" to (p?.title ?: channel.name), "category" to p?.category,
                "start" to p?.start?.div(1000), "stop" to p?.stop?.div(1000),
            ),
        )
    }

    /**
     * ▲▼ in the player. One press changes channel straight away. Pressing quickly shows each
     * channel's name on the info bar and only tunes the one you stop on – so flicking past five
     * channels doesn't open five streams.
     */
    fun zap(delta: Int) {
        if (zapList.isEmpty()) return
        lastZapDirection = if (delta < 0) -1 else 1
        zappedAt = System.currentTimeMillis()
        val fromId = zapPreview?.id ?: playing?.id
        val idx = zapIndexOf(fromId)
        val target = zapList[(idx + delta).mod(zapList.size)]
        val t = System.currentTimeMillis()
        val rapid = t - lastZapAt < ZAP_RAPID_MS
        lastZapAt = t
        zapJob?.cancel()
        if (!rapid) {
            zapPreview = null
            startLive(target)
            return
        }
        zapPreview = target
        zapJob = viewModelScope.launch {
            delay(ZAP_SETTLE_MS)
            val ch = zapPreview ?: return@launch
            zapPreview = null
            if (screen == Screen.Player && mode is PlayMode.Live) startLive(ch)
        }
    }

    /** Typing a number on the remote: the provider's channel number if the playlist has them. */
    fun zapToNumber(number: Int) {
        val all = channelsFor(ALL)
        if (all.any { it.number > 0 }) {
            val ch = zapList.firstOrNull { it.number == number } ?: all.firstOrNull { it.number == number }
            if (ch == null) {
                showNotice("No channel $number")
                return
            }
            if (zapList.none { it.id == ch.id }) zapList = all
            startLive(ch)
            return
        }
        val ch = zapList.getOrNull(number - 1)
        if (ch == null) showNotice("No channel $number") else startLive(ch)
    }

    /** [each] channels either side of [id] in the channel list (for the channel strip). */
    fun zapNeighbours(id: String?, each: Int): List<Channel> {
        if (zapList.isEmpty()) return emptyList()
        val i = zapIndexOf(id).coerceAtLeast(0)
        val n = minOf(each, (zapList.size - 1) / 2)
        return (-n..n).map { zapList[(i + it).mod(zapList.size)] }.distinctBy { it.id }
    }

    /** Position in the zap list, counting any version of a combined channel as that channel. */
    private fun zapIndexOf(id: String?): Int {
        if (id == null) return -1
        val i = zapList.indexOfFirst { it.id == id }
        if (i >= 0) return i
        return zapList.indexOfFirst { c -> versionsOf[c.id]?.any { it.id == id } == true }
    }

    fun playPrevious() {
        val prev = previousChannel ?: return
        if (!zapList.any { it.id == prev.id }) zapList = channelsFor(ALL)
        startLive(prev)
    }

    val zapChannels: List<Channel> get() = zapList

    /** The provider's channel number if it has one, otherwise the position in the list being shown. */
    fun displayNumber(channel: Channel, indexInList: Int): Int =
        if (channel.number > 0) channel.number else indexInList + 1

    fun channelNumber(channel: Channel): Int =
        if (channel.number > 0) channel.number else zapList.indexOfFirst { it.id == channel.id } + 1

    /**
     * The copy of [channel] that has catch-up. With HD/SD copies combined, the best-quality copy
     * shown in the list often has no catch-up while the 720p one does – use that one for catch-up.
     */
    fun catchupVersion(channel: Channel): Channel? =
        if (channel.hasCatchup) channel else versionsFor(channel).firstOrNull { it.hasCatchup }

    fun hasAnyCatchup(channel: Channel): Boolean = catchupVersion(channel) != null

    fun canCatchup(channel: Channel, p: Programme): Boolean =
        catchupVersion(channel)?.let { Catchup.isAvailable(it, p, now) } == true

    fun playCatchup(requested: Channel, p: Programme, list: List<Channel>, startAt: Long = 0L) {
        if (vpnBlocks()) return
        val channel = catchupVersion(requested) ?: requested
        if (!isAllowed(channel)) {
            requestUnlock { playCatchup(channel, p, list, startAt) }
            return
        }
        val url = Catchup.url(channel, p, System.currentTimeMillis())
        if (url == null) {
            showNotice("Catch-up isn't available for ${channel.name}")
            return
        }
        if (mode is PlayMode.Live) previousChannel = playing
        zapList = list.ifEmpty { listOf(channel) }
        mode = PlayMode.CatchupPlay(channel, p)
        focusChannelId = channel.id
        val meta = MediaMetadata.Builder().setTitle(p.title).setArtist(channel.name).build()
        playback.play(url, live = false, startPositionMs = startAt, metadata = meta)
        sendHa("playing", mapOf("kind" to "catchup", "channel" to channel.name, "title" to p.title, "category" to p.category))
        navigate(Screen.Player)
    }

    fun backToLive() {
        val m = mode as? PlayMode.CatchupPlay ?: return
        mode = null
        startLive(m.channel)
    }

    fun stopPlayback() {
        trackStats(flush = true)
        finishLiveWatch()
        upgradeJob?.cancel()
        qualityBack = null
        val wasPlaying = mode != null
        playback.stop()
        mode = null
        if (wasPlaying) sendHa("stopped")
    }

    fun onAppStopped() {
        inForeground = false
        finishLiveWatch()
        updateWidget()
        AppState.foreground = false
        trackStats(flush = true)
        updateServer()
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        playback.suspend()
        if (pin != null && unlocked) {
            unlocked = false
            recomputeVisible()
        }
    }

    fun onAppStarted() {
        inForeground = true
        checkUsual()
        if (link.linked) {
            link.start()
            link.syncSoon(2_500L)
        }
        if (extrasStarted) refreshPlex()
        AppState.foreground = true
        loadApps()
        updateServer()
        deadChannels = store.deadChannels
        if (mode is PlayMode.Live) playback.resumeAfterSuspend()
        recordings = store.recordings
    }

    private fun announceProgrammeChange() {
        val m = mode as? PlayMode.Live ?: return
        val p = nowNext(m.channel.id, System.currentTimeMillis()).first ?: return
        if (p.start == lastAnnouncedProgramme) return
        lastAnnouncedProgramme = p.start
        sendHa(
            "programme_changed",
            mapOf(
                "channel" to m.channel.name, "title" to p.title, "category" to p.category,
                "start" to p.start / 1000, "stop" to p.stop / 1000,
            ),
        )
    }

    // ---------------- Multi-view ----------------

    fun startMultiView(count: Int) {
        val current = playing
        val pool = zapList.ifEmpty { channelsFor(ALL) }
        val start = pool.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        val picks = (0 until count).mapNotNull { pool.getOrNull((start + it) % maxOf(1, pool.size)) }
        if (picks.isEmpty()) return
        stopPlayback()
        multi.start(count, picks)
        // Replace the player with multi-view so BACK returns to full screen.
        if (stack.last() == Screen.Player) stack.removeAt(stack.size - 1)
        stack.add(Screen.MultiView)
    }

    // ---------------- Favourites, recents, hiding, ordering ----------------

    fun toggleFavourite(channelId: String) {
        favourites = if (channelId in favouriteSet) favourites - channelId else favourites + channelId
        favouriteSet = favourites.toSet()
        store.setFavourites(activeProfile.id, favourites)
        updateWidget()
        publishFavouritesRow()
        markChanged(TvSync.FAVOURITES)
    }

    fun moveFavourite(channelId: String, delta: Int) {
        val i = favourites.indexOf(channelId)
        val j = i + delta
        if (i < 0 || j !in favourites.indices) return
        val list = favourites.toMutableList()
        list.add(j, list.removeAt(i))
        favourites = list
        store.setFavourites(activeProfile.id, list)
        publishFavouritesRow()
        markChanged(TvSync.FAVOURITES)
    }

    private fun addRecent(channelId: String) {
        recents = (listOf(channelId) + recents.filter { it != channelId }).take(30)
        store.setRecents(activeProfile.id, recents)
    }

    fun toggleHiddenChannel(channelId: String) {
        hiddenChannels = if (channelId in hiddenChannels) hiddenChannels - channelId else hiddenChannels + channelId
        store.hiddenChannels = hiddenChannels
        recomputeVisible()
    }

    fun toggleHiddenGroup(group: String) {
        hiddenGroups = if (group in hiddenGroups) hiddenGroups - group else hiddenGroups + group
        store.hiddenGroups = hiddenGroups
        recomputeVisible()
    }

    fun toggleLockedGroup(group: String) {
        if (pin == null) {
            showNotice("Set a parental PIN in Settings first")
            return
        }
        requestUnlock {
            lockedGroups = if (group in lockedGroups) lockedGroups - group else lockedGroups + group
            store.lockedGroups = lockedGroups
            recomputeVisible()
        }
    }

    fun moveGroup(group: String, delta: Int) {
        val list = allGroups.toMutableList()
        val i = list.indexOf(group)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        groupOrder = list
        store.groupOrder = list
        recomputeVisible()
    }

    fun resetGroupOrder() {
        groupOrder = emptyList()
        store.groupOrder = emptyList()
        recomputeVisible()
    }

    private fun publishFavouritesRow() {
        val favs = channelsFor(FAVOURITES)
        viewModelScope.launch(Dispatchers.IO) { HomeScreen.publishFavourites(app, store, favs) }
        if (liveTabScope == "favourites") scheduleLiveSync()
    }

    // ---------------- Parental PIN ----------------

    /** Asks for the PIN unless this session is already unlocked. */
    fun requestUnlock(then: () -> Unit) {
        if (pin == null || unlocked) then() else requestPin(then)
    }

    /** Always asks for the PIN (used for leaving a kids profile and extra time). */
    fun requestPin(then: () -> Unit) {
        val current = pin
        if (current == null) {
            then()
            return
        }
        pinPrompt = PinPrompt("Enter your PIN") { entered ->
            if (entered == current) {
                unlocked = true
                pinPrompt = null
                recomputeVisible()
                then()
            } else showNotice("Wrong PIN")
        }
    }

    fun cancelPin() {
        pinPrompt = null
    }

    fun changePin() {
        val askNew = {
            pinPrompt = PinPrompt("Choose a 4-digit PIN") { first ->
                pinPrompt = PinPrompt("Enter it again to confirm") { second ->
                    if (first == second) {
                        pin = first
                        store.pin = first
                        unlocked = true
                        pinPrompt = null
                        showNotice("PIN saved")
                    } else {
                        pinPrompt = null
                        showNotice("The PINs didn't match – try again")
                    }
                }
            }
        }
        if (pin == null) askNew() else requestPin { askNew() }
    }

    fun removePin() {
        if (pin == null) return
        requestPin {
            pin = null
            store.pin = null
            lockedGroups = emptySet()
            store.lockedGroups = emptySet()
            recomputeVisible()
            showNotice("PIN removed and all groups unlocked")
        }
    }

    // ---------------- Profiles & kids limits ----------------

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Seconds of TV left today for a kids profile with a limit (Long.MAX_VALUE otherwise). */
    fun timeLeftSeconds(): Long {
        val p = activeProfile
        if (!p.kids || p.dailyLimitMinutes <= 0) return Long.MAX_VALUE
        val day = today()
        val allowed = (p.dailyLimitMinutes + store.bonusMinutes(p.id, day)) * 60L
        return allowed - store.usageSeconds(p.id, day)
    }

    /** Kids profile with a bedtime, and it's between bedtime and 6am (unless a grown-up allowed more). */
    fun isBedtimeNow(): Boolean {
        val p = activeProfile
        if (!p.kids || p.bedtimeMinutes < 0) return false
        val t = System.currentTimeMillis()
        if (t < bedtimeOverrideUntil) return false
        return KidsRules.isBedtime(p.bedtimeMinutes, KidsRules.minutesOfDay(t))
    }

    private fun trackUsage() {
        val p = activeProfile
        if (!p.kids) return
        val watching = mode != null && playback.isPlaying
        if (!watching) return
        if (p.dailyLimitMinutes > 0) store.addUsage(p.id, today(), 15)
        val bedtime = isBedtimeNow()
        if (timeLeftSeconds() <= 0 || bedtime) {
            stopPlayback()
            resetTo(Screen.Home)
            timesUp = true
            sendHa(if (bedtime) "bedtime_reached" else "time_limit_reached")
            return
        }
        // A heads-up at 5 minutes and 1 minute, so it doesn't stop mid-scene without warning.
        val t = System.currentTimeMillis()
        val toBed = if (t < bedtimeOverrideUntil) null else KidsRules.minutesToBedtime(p.bedtimeMinutes, KidsRules.minutesOfDay(t))
        val warn = KidsRules.warningMinutes(timeLeftSeconds(), toBed) ?: return
        val key = "${p.id}|${today()}|$warn"
        if (key != lastKidsWarning) {
            lastKidsWarning = key
            showNotice(if (warn == 1) "⏰ 1 minute of TV left" else "⏰ $warn minutes of TV left")
        }
    }

    fun grantExtraTime(minutes: Int) {
        requestPin {
            if (isBedtimeNow()) bedtimeOverrideUntil = System.currentTimeMillis() + minutes * 60_000L
            else store.addBonus(activeProfile.id, today(), minutes)
            timesUp = false
            showNotice("$minutes more minutes added")
        }
    }

    fun dismissTimesUp() {
        timesUp = false
    }

    fun switchProfile(p: Profile) {
        val doSwitch = {
            trackStats(flush = true)
            activeProfile = p
            store.activeProfileId = p.id
            favourites = store.favourites(p.id)
            favouriteSet = favourites.toSet()
            taste = Taste.fromJson(store.taste(p.id))
            becauseFilms = emptyList()
            becauseFilmOf = null
            recents = store.recents(p.id)
            selectedGroupState = store.lastGroup(p.id) ?: ALL
            unlocked = false
            timesUp = false
            stopPlayback()
            recomputeVisible()
            publishFavouritesRow()
            movies = null
            series = null
            resetTo(Screen.Home)
            startupHandled = false
            handleStartup()
        }
        // Leaving a kids profile needs the PIN.
        if (activeProfile.kids && p.id != activeProfile.id && pin != null) requestPin { doSwitch() } else doSwitch()
    }

    fun startAddProfile() {
        editingProfile = null
        navigate(Screen.ProfileEdit)
    }

    fun startEditProfile(p: Profile) {
        editingProfile = p
        navigate(Screen.ProfileEdit)
    }

    fun saveProfile(p: Profile) {
        val list = profiles.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        profiles = list
        store.profiles = list
        if (p.id == activeProfile.id) {
            activeProfile = p
            recomputeVisible()
        }
        back()
    }

    fun deleteProfile(p: Profile) {
        if (p.id == Store.MAIN_PROFILE) return
        profiles = profiles.filter { it.id != p.id }
        store.profiles = profiles
        if (activeProfile.id == p.id) switchProfile(profiles.first())
    }

    fun newProfileId(): String = "p" + System.currentTimeMillis().toString(36)

    fun toggleOpenLastChannel() {
        openLastChannel = !openLastChannel
        store.openLastChannel = openLastChannel
    }

    fun cycleStartScreen() {
        startScreen = when (startScreen) {
            "home" -> "guide"
            "guide" -> "channel"
            else -> "home"
        }
        store.startScreen = startScreen
    }

    val startScreenLabel: String
        get() = when (startScreen) {
            "guide" -> "TV Guide (where you left it)"
            "channel" -> "Last channel watched"
            else -> "Home"
        }

    fun toggleRefreshOnStart() {
        refreshOnStart = !refreshOnStart
        store.refreshOnStart = refreshOnStart
        showNotice(if (refreshOnStart) "Playlists update on start-up when over 12 hours old" else "Playlists update overnight only")
    }

    /** Resume the last channel watched (the TV button on the remote). */
    fun playLastChannel() {
        val id = (mode as? PlayMode.Live)?.channel?.id ?: store.lastChannel(activeProfile.id)
        val ch = id?.let { byId[it] }
        if (ch == null) {
            showNotice("No channel watched yet")
            return
        }
        if (screen == Screen.Player) return
        play(ch, zapList.takeIf { l -> l.any { it.id == ch.id } } ?: channelsFor(ALL))
    }

    /**
     * Remote shortcuts, from anywhere: red = Sport, green = Recordings, yellow = Search,
     * blue = Favourites, Guide = TV guide, DVR = Recordings, TV = back to the last channel.
     */
    fun handleShortcut(code: Int): Boolean {
        if (pinPrompt != null || timesUp) return false
        if (screen == Screen.SourceEdit || screen == Screen.ProfilePicker || screen == Screen.ProfileEdit) return false
        when (code) {
            android.view.KeyEvent.KEYCODE_PROG_RED -> navigate(Screen.Sports)
            android.view.KeyEvent.KEYCODE_PROG_GREEN, android.view.KeyEvent.KEYCODE_DVR -> {
                if (activeProfile.kids) return false
                navigate(Screen.Recordings)
            }
            android.view.KeyEvent.KEYCODE_PROG_YELLOW -> navigate(Screen.Search)
            android.view.KeyEvent.KEYCODE_PROG_BLUE -> {
                selectedGroup = FAVOURITES
                navigate(Screen.Channels)
            }
            android.view.KeyEvent.KEYCODE_GUIDE -> openGuide()
            android.view.KeyEvent.KEYCODE_TV -> playLastChannel()
            else -> return false
        }
        return true
    }

    // ---------------- Other apps ----------------

    fun loadApps() {
        if (appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            tvApps = withContext(Dispatchers.IO) { runCatching { InstalledApps.load(app) }.getOrDefault(emptyList()) }
        }
    }

    /** Apps for the Home row / Apps screen (hidden ones only on the Apps screen when showAll). */
    fun visibleApps(showAll: Boolean = false): List<TvApp> =
        tvApps.orEmpty().filter { showAll || it.packageName !in hiddenApps }

    fun launchApp(app: TvApp) {
        if (activeProfile.kids && pin != null && !unlocked) {
            requestPin { openApp(app) }
            return
        }
        openApp(app)
    }

    private fun openApp(tvApp: TvApp) {
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        if (!InstalledApps.launch(app, tvApp.packageName)) showNotice("Couldn't open ${tvApp.label}")
    }

    // ---------------- BBC iPlayer, ITVX, Channel 4, My5 ----------------

    /** The broadcaster's app for this channel, if it's installed on this TV (package name). */
    fun catchupAppFor(channel: Channel): Pair<CatchupApps.Provider, String>? {
        val p = CatchupApps.forChannel(channel.name) ?: return null
        // Asked for every row of a list, so the answer is kept for a minute.
        val t = System.currentTimeMillis()
        val cached = catchupAppCache[p.name]
        val pkg = if (cached != null && t - cached.second < 60_000L) cached.first else {
            val pm = app.packageManager
            val found = p.packages.firstOrNull { pm.getLeanbackLaunchIntentForPackage(it) != null || pm.getLaunchIntentForPackage(it) != null }
                ?: tvApps.orEmpty().firstOrNull { p.label.containsMatchIn(it.label.trim()) }?.packageName
            catchupAppCache[p.name] = found to t
            found
        } ?: return null
        return p to pkg
    }

    private val catchupAppCache = HashMap<String, Pair<String?, Long>>()

    /** Opens the broadcaster's app on [title]: its own search when it has one, else just the app. */
    fun openInCatchupApp(channel: Channel, title: String?) {
        val (provider, pkg) = catchupAppFor(channel) ?: run {
            showNotice("The broadcaster's app isn't installed on this TV")
            return
        }
        if (activeProfile.kids && pin != null && !unlocked) {
            requestPin { openInCatchupApp(channel, title) }
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        val clean = title?.replace(Regex("^(live|new)\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE), "")?.trim()
        if (!clean.isNullOrBlank()) {
            val search = android.content.Intent(android.content.Intent.ACTION_SEARCH)
                .setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, clean)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(search) }.isSuccess) {
                showNotice("Searching ${provider.name} for \"$clean\"")
                return
            }
        }
        if (InstalledApps.launch(app, pkg)) {
            if (!clean.isNullOrBlank()) showNotice("In ${provider.name}, search for \"$clean\"")
        } else showNotice("Couldn't open ${provider.name}")
    }

    fun toggleHideApp(tvApp: TvApp) {
        hiddenApps = if (tvApp.packageName in hiddenApps) hiddenApps - tvApp.packageName else hiddenApps + tvApp.packageName
        store.hiddenApps = hiddenApps
        showNotice(if (tvApp.packageName in hiddenApps) "${tvApp.label} hidden from Home" else "${tvApp.label} shown on Home")
    }

    // ---------------- Problem log ----------------

    fun openLog() {
        navigate(Screen.Log)
        loadLog()
    }

    fun loadLog() {
        viewModelScope.launch {
            logText = withContext(Dispatchers.IO) { AppLog.read() }.ifEmpty { "No problems logged." }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppLog.clear() }
            logText = "No problems logged."
            showNotice("Problem log cleared")
        }
    }

    // ---------------- Series link ----------------

    fun seriesLinkFor(channel: Channel, p: Programme): SeriesLink? =
        seriesLinks.firstOrNull { SeriesLinks.matches(it, channel.id, p) }

    /** Record every episode of this programme on this channel (or stop doing so). */
    fun toggleSeriesLink(channel: Channel, p: Programme) {
        val existing = seriesLinkFor(channel, p)
        if (existing != null) {
            removeSeriesLink(existing)
            return
        }
        seriesLinks = seriesLinks + SeriesLink(channel.id, channel.name, p.title, System.currentTimeMillis())
        store.seriesLinks = seriesLinks
        applySeriesLinks(announce = true, title = p.title)
    }

    fun removeSeriesLink(link: SeriesLink) {
        seriesLinks = seriesLinks.filterNot { it.channelId == link.channelId && it.title == link.title }
        store.seriesLinks = seriesLinks
        showNotice("Series link removed – already-set recordings are kept")
    }

    private fun applySeriesLinks(announce: Boolean = false, title: String? = null) {
        if (seriesLinks.isEmpty()) return
        val chans = allChannels
        val guide = epg
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { SeriesLinks.apply(app, store, chans, guide) }.getOrDefault(0)
            }
            recordings = store.recordings
            if (announce) {
                showNotice(
                    if (added > 0) "Series linked – recording $added episode${if (added == 1) "" else "s"} of $title so far"
                    else "Series linked – new episodes of $title will be recorded"
                )
            }
        }
    }

    // ---------------- Reminders ----------------

    fun hasReminder(channel: Channel, p: Programme): Boolean =
        reminders.any { it.channelId == channel.id && it.start == p.start }

    fun toggleReminder(channel: Channel, p: Programme) {
        reminders = if (hasReminder(channel, p)) {
            showNotice("Reminder cancelled")
            reminders.filter { it.channelId == channel.id && it.start == p.start }.forEach { ReminderAlarms.cancel(app, it) }
            reminders.filterNot { it.channelId == channel.id && it.start == p.start }
        } else {
            showNotice("I'll remind you at ${hhmm(p.start)} – ${p.title}")
            val r = Reminder(channel.id, channel.name, p.title, p.start, p.stop)
            ReminderAlarms.schedule(app, r)
            reminders + r
        }
        store.reminders = reminders
    }

    private fun checkReminders() {
        if (dueReminder != null) return
        val t = System.currentTimeMillis()
        val live = reminders.filter { it.stop > t }
        // Followed teams' matches pop up 5 minutes early, so there's time to get settled.
        val teams = football.teams
        val due = live.firstOrNull { r ->
            val lead = if (teams.any { SportFollow.mentions(r.title, it) }) 5 * 60_000L else 60_000L
            t >= r.start - lead
        }
        val remaining = if (due != null) live - due else live
        if (remaining.size != reminders.size) {
            reminders = remaining
            store.reminders = remaining
        }
        if (due != null) {
            dueReminder = due
            sendHa("reminder", ReminderAlarms.payload(due) + ("app_open" to true))
        }
    }

    // ---------------- Recommendations ----------------

    /** 👍 / 👎 on a programme or film: more or fewer like it. */
    fun rate(title: String, genre: String?, like: Boolean) {
        if (like) taste.like(title, genre) else taste.dislike(title, genre)
        store.setTaste(activeProfile.id, taste.toJson())
        tasteVersion++
        showNotice(if (like) "👍 Got it – more like \"$title\"" else "👎 Got it – fewer like \"$title\"")
        refreshHomeFeed()
    }

    fun rateProgramme(channel: Channel, p: Programme, like: Boolean) = rate(p.title, Genres.of(p.category, p.title, channel.group), like)

    /** What Tuner has learned, for Settings › Recommendations. */
    fun tasteSummary(): Triple<List<Pair<String, Double>>, List<String>, Int> =
        Triple(taste.genreShares().take(8), taste.topShows(10), taste.liked.size + taste.disliked.size)

    fun resetTaste() {
        taste = Taste()
        store.setTaste(activeProfile.id, taste.toJson())
        tasteVersion++
        refreshHomeFeed()
        showNotice("Recommendations reset – Flicker will learn again from what you watch")
    }

    fun toggleLearnTaste() {
        learnTaste = !learnTaste
        store.learnTaste = learnTaste
    }

    /** Remembers what was just watched, for "Because you watched…". */
    private fun rememberWatched(kind: String, id: String, title: String, extra: String = "") {
        store.setLastWatchedFor(activeProfile.id, org.json.JSONObject().put("kind", kind).put("id", id).put("title", title).put("extra", extra).toString())
    }

    /** Fills "Because you watched…": Plex's own "more like this", else films from your provider in the same category. */
    fun loadBecause() {
        val o = runCatching { org.json.JSONObject(store.lastWatchedFor(activeProfile.id) ?: return) }.getOrNull() ?: return
        val title = o.optString("title")
        when (o.optString("kind")) {
            "plex" -> if (plexLinked) plexFeature.loadSimilar(o.optString("extra"), o.optString("id"), title)
            "film" -> {
                val list = movies ?: run {
                    if (hasVod) loadMovies()
                    return
                }
                val category = list.firstOrNull { it.id == o.optString("id") }?.category ?: o.optString("extra")
                becauseFilms = list.filter { it.category == category && it.id != o.optString("id") && it.id !in resume.keys }
                    .sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
                    .take(20)
                becauseFilmOf = title
            }
        }
    }

    // ---------------- VPN ----------------

    /** Kill switch: with the VPN on but not connected, your provider's streams don't play. */
    private fun vpnBlocks(): Boolean {
        if (!vpn.enabled || !vpn.killSwitch || vpn.connected) return false
        showNotice("🔒 The VPN isn't connected, so streams are paused (kill switch) – Settings › VPN")
        if (!vpn.connected) vpn.connect()
        return true
    }

    // ---------------- Trending ----------------

    /** This week's trending list from TMDB, matched against your Plex and your provider's films and series (every 6 hours). */
    fun loadTrending(force: Boolean = false) {
        val key = tmdbKey ?: return
        val t = System.currentTimeMillis()
        if (!force && t - trendingAt < 6 * 3_600_000L && trending.isNotEmpty()) return
        trendingAt = t
        val films = movies.orEmpty()
        val shows = series.orEmpty()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { com.titanejstudios.tuner.integrations.Tmdb.trending(key) }.getOrDefault(emptyList()) }
            if (list.isEmpty()) return@launch
            val plain = { s: String -> s.lowercase().replace(Regex("^(the|a|an) "), "").replace(Regex("[^\\p{L}\\p{N}]"), "") }
            val filmIndex = withContext(Dispatchers.Default) {
                films.groupBy { m -> FilmName.parse(m.name).let { plain(it.first) } }
            }
            val seriesIndex = withContext(Dispatchers.Default) {
                shows.groupBy { s -> FilmName.parse(s.name).let { plain(it.first) } }
            }
            val out = list.take(20).map { tr ->
                val p = plain(tr.title)
                val plex = if (!plexLinked) null
                else if (tr.isTv) withTimeoutOrNull(4_000) { plexFeature.showNamed(tr.title) }
                else withTimeoutOrNull(4_000) { plexFeature.copiesOf(tr.title, tr.year) }?.firstOrNull()
                val film = if (tr.isTv) null else filmIndex[p]?.let { c ->
                    c.firstOrNull { FilmName.parse(it.name).second == tr.year } ?: c.firstOrNull { FilmName.parse(it.name).second == null }
                }
                val show = if (tr.isTv) seriesIndex[p]?.firstOrNull() else null
                TrendingItem(tr, plex?.takeIf { !activeProfile.kids || plexFeature.isKidsEntry(it) }, film, show)
            }
            trending = out
        }
    }

    /** OK on a trending tile: play it where you have it, otherwise show its info. */
    fun openTrending(item: TrendingItem) {
        when {
            item.plex != null -> if (item.plex.isShow) openPlexShow(item.plex) else playPlexEntry(item.plex)
            item.film != null -> playMovie(item.film, fromStart = false)
            item.series != null -> openSeriesDetail(item.series)
            else -> openFilmInfo(item.trend.title + (item.trend.year?.let { " ($it)" } ?: ""), isSeries = item.trend.isTv)
        }
    }

    // ---------------- Languages & countries ----------------

    private fun languageShown(code: String?, hidden: Set<String>): Boolean = (code ?: Languages.NONE) !in hidden

    /** Films/series without the hidden languages ([text] = category and name, where the tags are). */
    private fun <T> langFilter(list: List<T>, text: (T) -> Pair<String, String>): List<T> {
        val hidden = hiddenLanguages
        if (hidden.isEmpty()) return list
        return list.filter { val (cat, name) = text(it); languageShown(Languages.tagOfItem(cat, name)?.code, hidden) }
    }

    /** Every country/language found in your channels (and films/series, if loaded), most first. */
    fun languageCounts(): List<Pair<Languages.Tag?, Int>> {
        val counts = HashMap<String, Pair<Languages.Tag?, Int>>()
        fun add(tag: Languages.Tag?) {
            val k = tag?.code ?: Languages.NONE
            counts[k] = tag to ((counts[k]?.second ?: 0) + 1)
        }
        allChannels.forEach { add(Languages.tagOf(it)) }
        return counts.values.sortedByDescending { it.second }
    }

    fun changeHiddenLanguages(hidden: Set<String>) {
        hiddenLanguages = hidden
        store.hiddenLanguages = hidden
        recomputeVisible()
        // Films and series are filtered as they load: load them again from the saved lists.
        movies = null
        series = null
    }

    /** Hides every country/language that isn't English (things with no country tag stay). */
    fun englishOnly() {
        // Every non-English tag, including ones only used by films and series.
        changeHiddenLanguages(Languages.nonEnglish)
        showNotice("Showing English channels only – change it in Settings › Languages & countries")
    }

    // ---------------- Home rows (5.5) ----------------

    fun saveHomeRows(order: List<String>, hidden: Set<String>) {
        homeRowOrder = order
        homeRowsHidden = hidden
        store.homeRowOrder = order
        store.homeRowsHidden = hidden
    }

    fun toggleHomeSmart() {
        homeSmart = !homeSmart
        store.homeSmart = homeSmart
    }

    /** "Newcastle 1–0 Arsenal" when a followed league's match in this programme is on (football-data.org). */
    fun liveScoreFor(p: Programme): String? {
        val m = football.matches.firstOrNull { (it.live || (it.finished && now - it.kickoff < 3 * 3_600_000L)) && Football.inTitle(p.title, it.home) && Football.inTitle(p.title, it.away) }
            ?: return null
        val tail = when {
            m.status == "PAUSED" -> "  HT"
            m.finished -> "  FT"
            else -> ""
        }
        return "${Football.shortName(m.home)} ${m.homeGoals ?: 0}–${m.awayGoals ?: 0} ${Football.shortName(m.away)}$tail"
    }

    fun allChannelsById(id: String): Channel? = allChannels.firstOrNull { it.id == id }

    /** Home's 🎤 button: opens Search and starts voice typing straight away. */
    fun startVoiceSearch() {
        voiceSearchPending = true
        navigate(Screen.Search)
    }

    /** The channel watched last (Home's ▶ button). */
    val lastChannel: Channel? get() = recents.firstNotNullOfOrNull { id -> allChannels.firstOrNull { it.id == id } }

    // ---------------- Start from the beginning (joined late) ----------------


    /** After a few seconds on a programme that started a while ago, mentions it can be watched from the start. */
    private fun offerStartOver() {
        val m = mode as? PlayMode.Live
        if (m == null || screen != Screen.Player || inPip) {
            liveSinceId = null
            return
        }
        val t = System.currentTimeMillis()
        if (liveSinceId != m.channel.id) {
            liveSinceId = m.channel.id
            liveSince = t
            return
        }
        if (t - liveSince < 10_000) return
        val cur = nowNext(m.channel.id, t).first ?: return
        if (t - cur.start < 5 * 60_000L || cur.stop - t < 10 * 60_000L) return
        if (!startOverOffered.add(m.channel.id + "@" + cur.start)) return
        if (!canCatchup(m.channel, cur)) return
        showNotice("Joined \"${cur.title}\" late? Press ⏪ (or OK › Watch from the start) to see it from the beginning")
    }

    /** ⏪ on live TV: start the current programme from the beginning, if it has catch-up. */
    fun startOverCurrent(): Boolean {
        val m = mode as? PlayMode.Live ?: return false
        val cur = nowNext(m.channel.id, System.currentTimeMillis()).first ?: return false
        if (!canCatchup(m.channel, cur)) return false
        playCatchup(m.channel, cur, zapChannels)
        return true
    }

    fun acceptReminder() {
        val r = dueReminder ?: return
        dueReminder = null
        val ch = allChannels.firstOrNull { it.id == r.channelId }
        if (ch == null) {
            showNotice("${r.channelName} isn't in the current playlist")
            return
        }
        if (screen != Screen.Player) play(ch, channelsFor(ALL)) else playFromOverlay(ch, channelsFor(ALL))
    }

    fun dismissReminder() {
        dueReminder = null
    }

    // ---------------- Search ----------------

    fun updateSearch(query: String) {
        searchQuery = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            val q = query.trim()
            if (q.length < 2) {
                searchResults = SearchResults(emptyList(), emptyList())
                searchPlex = emptyList()
                return@launch
            }
            // Plex is searched alongside (it's on the network, so its results can arrive a moment later).
            searchPlexJob?.cancel()
            searchPlex = emptyList()
            if (plexLinked && (!activeProfile.kids || kidsHavePlex)) {
                searchPlexJob = viewModelScope.launch {
                    val found = withContext(Dispatchers.IO) {
                        runCatching { Plex(store.plexClientId).search(plexFeature.servers, q) }.getOrDefault(emptyList())
                    }
                    searchPlex = if (activeProfile.kids) found.filter { plexFeature.isKidsEntry(it) } else found.take(40)
                }
            }
            val filmPool = if (hasVod) movies.orEmpty() else emptyList()
            val seriesPool = if (hasVod) series.orEmpty() else emptyList()
            val pool = channels.filter { isAllowed(it) }
            val ids = activeSources().map { it.id }
            searchResults = withContext(Dispatchers.IO) {
                val needle = q.lowercase()
                val matchedChannels = pool.filter { it.name.lowercase().contains(needle) }.take(50)
                val byId = pool.associateBy { it.id }
                val t = System.currentTimeMillis()
                val progs = db.searchProgrammes(ids, q, t - 7 * 86_400_000L, 600)
                    .mapNotNull { (id, p) -> byId[id]?.let { it to p } }
                    .filter { (c, p) -> p.stop > t || Catchup.isAvailable(c, p, t) }
                    .distinctBy { (c, p) -> c.id + "@" + p.start }
                    .take(200)
                val words = needle.split(Regex("\\s+")).filter { it.isNotBlank() }
                fun hit(name: String) = name.lowercase().let { n -> words.all { it in n } }
                val films = filmPool.filter { hit(it.name) }.take(40)
                val shows = seriesPool.filter { hit(it.name) }.take(40)
                SearchResults(matchedChannels, progs, films, shows)
            }
        }
    }

    // ---------------- Fix guide matches ----------------

    fun openEpgMapping(channel: Channel) {
        mappingChannel = channel
        epgCandidates = null
        navigate(Screen.EpgMap)
        viewModelScope.launch {
            epgCandidates = withContext(Dispatchers.IO) { db.xmltvChannels(channel.sourceId).distinctBy { it.id } }
        }
    }

    fun setEpgOverride(channel: Channel, xmltvId: String?) {
        val updated = epgOverrides.toMutableMap()
        if (xmltvId == null) updated.remove(channel.id) else updated[channel.id] = xmltvId
        epgOverrides = updated
        store.epgOverrides = updated
        back()
        showNotice(if (xmltvId == null) "Back to automatic matching – updating the guide…" else "Guide match saved – updating the guide…")
        sources.firstOrNull { it.id == channel.sourceId }?.let { refresh(listOf(it)) }
    }

    // ---------------- Films & series ----------------

    /**
     * Films: shows the saved list straight away, then downloads a fresh one in the background if
     * it's over 12 hours old (or you pressed Reload). Only downloads with a wait the very first time.
     */
    fun loadMovies(force: Boolean = false) {
        if (movies != null && !force) return
        val srcs = activeSources().filterIsInstance<Source.Xtream>()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { srcs.map { it to vodCache.loadMovies(it.id) } }
            val complete = cached.all { it.second != null }
            if (complete && movies == null) movies = langFilter(cached.flatMap { it.second!!.items }) { it.category to it.name }
            val stale = cached.any { it.second == null || System.currentTimeMillis() - it.second!!.savedAt > VodCache.MAX_AGE_MS }
            if (!force && complete && !stale) return@launch
            if (complete) vodUpdating = true else vodStatus = "Loading films…"
            try {
                movies = withContext(Dispatchers.IO) {
                    langFilter(srcs.flatMap { src -> XtreamClient.loadMovies(src).also { runCatching { vodCache.saveMovies(src.id, it) } } }) { it.category to it.name }
                }
            } catch (e: Exception) {
                if (!complete) movies = emptyList()
                showNotice("Couldn't load films: ${e.message}")
            }
            vodStatus = null
            vodUpdating = false
        }
    }

    /** Series: same saved-list approach as [loadMovies]. */
    fun loadSeries(force: Boolean = false) {
        if (series != null && !force) return
        val srcs = activeSources().filterIsInstance<Source.Xtream>()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { srcs.map { it to vodCache.loadSeries(it.id) } }
            val complete = cached.all { it.second != null }
            if (complete && series == null) series = langFilter(cached.flatMap { it.second!!.items }) { it.category to it.name }
            val stale = cached.any { it.second == null || System.currentTimeMillis() - it.second!!.savedAt > VodCache.MAX_AGE_MS }
            if (!force && complete && !stale) return@launch
            if (complete) vodUpdating = true else vodStatus = "Loading series…"
            try {
                series = withContext(Dispatchers.IO) {
                    langFilter(srcs.flatMap { src -> XtreamClient.loadSeries(src).also { runCatching { vodCache.saveSeries(src.id, it) } } }) { it.category to it.name }
                }
            } catch (e: Exception) {
                if (!complete) series = emptyList()
                showNotice("Couldn't load series: ${e.message}")
            }
            vodStatus = null
            vodUpdating = false
        }
    }

    // ---------------- My list & new episodes ----------------

    fun inWatchlist(id: String): Boolean = watchlist.any { it.id == id }

    fun toggleWatchlist(m: VodItem) {
        toggleWatchItem(WatchItem.of(m, System.currentTimeMillis()))
    }

    fun toggleWatchlist(s: SeriesItem) {
        toggleWatchItem(WatchItem.of(s, System.currentTimeMillis()))
    }

    private fun toggleWatchItem(item: WatchItem) {
        val adding = !inWatchlist(item.id)
        watchlist = Watchlist.toggle(watchlist, item)
        store.watchlistJson = Watchlist.encode(watchlist)
        markChanged(TvSync.WATCHLIST)
        showNotice(if (adding) "Added ${item.name} to My list" else "Removed ${item.name} from My list")
    }

    /** Plays a film or opens a series from My list / New episodes. */
    fun openWatchItem(item: WatchItem) {
        if (item.isSeries) {
            val s = item.toSeries() ?: return
            if (sources.none { it.id == s.sourceId }) {
                showNotice("That series' playlist has been removed")
                return
            }
            openSeriesDetail(s)
        } else {
            val m = item.toVod() ?: return
            playMovie(m, fromStart = (resume[m.id]?.positionMs ?: 0L) == 0L)
        }
    }

    /** Series you watch that have episodes you haven't seen in the list yet. */
    val newEpisodeSeries: List<FollowedSeries>
        get() = followedSeries.filter { it.newEpisodes > 0 }

    private fun saveFollowed(list: List<FollowedSeries>) {
        followedSeries = list
        store.followedSeriesJson = Watchlist.encodeFollowed(list)
        markChanged(TvSync.FOLLOWED)
    }

    /**
     * Once a day (a few seconds after start-up) counts the episodes of the series you've been
     * watching, one gentle request at a time. More than last time = "New episodes" on Home.
     */
    fun checkNewEpisodes(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - store.newEpisodesCheckedAt < 20 * 3600_000L) return
        val follow = followedSeries
        if (follow.isEmpty() || activeProfile.kids) return
        val xtream = sources.filterIsInstance<Source.Xtream>().associateBy { it.id }
        store.newEpisodesCheckedAt = now
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                follow.mapNotNull { f ->
                    val srcId = f.series.sourceId ?: return@mapNotNull null
                    val src = xtream[srcId] ?: return@mapNotNull null
                    val sid = f.series.seriesId ?: return@mapNotNull null
                    runCatching { f.series.id to XtreamClient.loadEpisodes(src, sid).size }.getOrNull()
                        .also { Thread.sleep(300) }
                }.toMap()
            }
            if (counts.isEmpty()) return@launch
            // Merge with whatever changed while we were checking.
            val updated = followedSeries.map { f -> counts[f.series.id]?.let { Watchlist.withCount(f, it) } ?: f }
            saveFollowed(updated)
            val fresh = updated.count { it.newEpisodes > 0 }
            if (fresh > 0) AppLog.info("New episodes found for $fresh series")
        }
    }

    fun openSeriesDetail(s: SeriesItem) {
        openSeries = s
        episodes = null
        navigate(Screen.SeriesDetail)
        val src = sources.firstOrNull { it.id == s.sourceId } as? Source.Xtream ?: return
        viewModelScope.launch {
            episodes = try {
                withContext(Dispatchers.IO) { XtreamClient.loadEpisodes(src, s.seriesId) }
            } catch (e: Exception) {
                showNotice("Couldn't load episodes: ${e.message}")
                emptyList()
            }
            // Opening a series you follow counts as having seen what's new.
            val count = episodes?.size ?: 0
            val f = followedSeries.firstOrNull { it.series.id == s.id }
            if (f != null && count > 0 && (f.newEpisodes > 0 || f.episodeCount != count)) {
                saveFollowed(followedSeries.map { if (it.series.id == s.id) it.copy(episodeCount = count, newEpisodes = 0) else it })
            }
        }
    }

    fun playMovie(m: VodItem, fromStart: Boolean, viaPlex: Boolean = true) {
        rememberWatched("film", m.id, m.name, m.category)
        // Also on Plex? Plex's copy is usually better (and its progress is shared with the Plex app).
        if (viaPlex && plexLinked && preferPlexFilms) {
            viewModelScope.launch {
                val (title, year) = FilmName.parse(m.name)
                showNotice("Starting ${m.name}…")
                // The lookup runs on its own, so a slow Plex server can't hold the film up for more than 5 seconds.
                val lookup = viewModelScope.async { plexFeature.copiesOf(title, year) }
                val copies = withTimeoutOrNull(5_000) { lookup.await() }.orEmpty()
                    .filter { !activeProfile.kids || plexFeature.isKidsEntry(it) }
                when {
                    copies.isEmpty() -> playMovie(m, fromStart, viaPlex = false)
                    copies.size == 1 -> {
                        showNotice("Playing from Plex" + (copies[0].library?.let { " ($it)" } ?: "") + " – hold OK on the film for the IPTV copy")
                        playPlexEntry(copies[0], fromStart, chosen = true) { playMovie(m, fromStart, viaPlex = false) }
                    }
                    else -> plexCopyChoice = PlexCopyChoice(m.name, copies, fromStart, m)
                }
            }
            return
        }
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(m.id, m.name, m.category, m.poster, m.url, null), fromStart)
    }

    fun playEpisode(e: Episode, all: List<Episode>, fromStart: Boolean) {
        val s = openSeries
        if (s != null) {
            saveFollowed(Watchlist.watched(followedSeries, WatchItem.of(s, System.currentTimeMillis()), all.size.takeIf { it > 0 }, System.currentTimeMillis()))
        }
        episodeQueue = all.dropWhile { it.id != e.id }.drop(1)
        startVod(
            PlayMode.Vod(
                contentId = e.id,
                title = s?.name ?: e.title,
                subtitle = "S${e.season} E${e.number}  ${e.title}",
                poster = e.image ?: s?.cover,
                url = e.url,
                seriesId = s?.id,
            ),
            fromStart,
        )
    }

    fun playResume(entry: ResumeEntry) {
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(entry.contentId, entry.title, entry.subtitle, entry.poster, entry.url, entry.seriesId), false)
    }

    private fun startVod(m: PlayMode.Vod, fromStart: Boolean, startAt: Long? = null) {
        // Downloads play from the phone itself, so the VPN kill switch doesn't apply.
        if (!m.url.startsWith("file:") && vpnBlocks()) {
            AppLog.info("Didn't play ${m.title}: the VPN kill switch is on and the VPN isn't connected")
            return
        }
        AppLog.info("Playing ${m.title}" + (m.subtitle?.let { " ($it)" } ?: ""))
        if (mode is PlayMode.Vod) saveResume(syncHome = false)
        mode = m
        val start = if (fromStart) 0L else startAt ?: resume[m.contentId]?.positionMs ?: 0L
        val meta = MediaMetadata.Builder().setTitle(m.title).setArtist(m.subtitle)
        m.poster?.let { meta.setArtworkUri(Uri.parse(it)) }
        playback.play(m.url, live = false, startPositionMs = start, metadata = meta.build())
        sendHa("playing", mapOf("kind" to if (m.seriesId != null) "episode" else "film", "title" to m.title, "subtitle" to m.subtitle, "poster" to m.poster))
        navigate(Screen.Player)
    }

    private fun saveResume(syncHome: Boolean) {
        val m = mode as? PlayMode.Vod ?: return
        if (m.contentId.startsWith("plex:")) {
            // Plex keeps its own place – tell it, rather than Tuner's list.
            val item = plexPlaying ?: return
            plexFeature.reportProgress(item, stopped = syncHome, positionMs = playback.positionMs, durationMs = playback.durationMs)
            return
        }
        val pos = playback.positionMs
        val dur = playback.durationMs
        if (dur <= 0) return
        val updated = resume.toMutableMap()
        if (pos > dur * 95 / 100) {
            updated.remove(m.contentId)
            com.titanejstudios.tuner.integrations.LinkSync.markDone(store, m.contentId)
        }
        else if (pos > 30_000) {
            updated[m.contentId] = ResumeEntry(
                m.contentId, m.title, m.subtitle, m.poster, m.url, pos, dur, System.currentTimeMillis(), m.seriesId,
            )
        }
        resume = updated
        store.resume = updated
        if (syncEnabled) scheduleSync(if (syncHome) 5_000L else 120_000L)
        if (syncHome) {
            val entries = updated.values.toList()
            viewModelScope.launch(Dispatchers.IO) { HomeScreen.syncContinueWatching(app, store, entries) }
            link.tellOthers()
        }
    }

    private fun onPlaybackEnded() {
        when (val m = mode) {
            is PlayMode.Vod -> {
                resume = resume - m.contentId
                store.resume = resume
                com.titanejstudios.tuner.integrations.LinkSync.markDone(store, m.contentId)
                link.tellOthers()
                if (episodeQueue.isNotEmpty()) {
                    showNotice("Playing next episode")
                    startNextEpisode(m)
                } else if (m.contentId.startsWith("plex:") && plexEpisodeQueue.isNotEmpty()) {
                    val next = plexEpisodeQueue.first()
                    val rest = plexEpisodeQueue.drop(1)
                    showNotice("Playing next episode")
                    playPlexEntry(next, fromStart = true)
                    plexEpisodeQueue = rest
                } else if (screen == Screen.Player) back()
            }
            is PlayMode.CatchupPlay -> showNotice("End of programme – press BACK, or choose Live from the menu")
            is PlayMode.RecordingPlay -> {
                if (screen == Screen.Player) back()
            }
            else -> Unit
        }
    }

    // ---------------- Recordings ----------------

    fun storageOptions(): List<StorageOption> {
        val dirs = app.getExternalFilesDirs("Recordings").filterNotNull() + File(app.filesDir, "Recordings")
        return dirs.mapIndexed { i, d ->
            d.mkdirs()
            val free = runCatching { StatFs(d.path).availableBytes }.getOrDefault(0L)
            val label = when {
                d.path.startsWith(app.filesDir.path) -> "Internal storage (app only)"
                i == 0 -> "Internal storage"
                else -> "External / USB storage ${i}"
            }
            StorageOption(d.path, label, free)
        }.distinctBy { it.path }
    }

    val recordingDir: String
        get() = RecordingPaths.dir(app, store)

    fun setRecordingDir(path: String) {
        store.recordingDir = path
        showNotice("New recordings will be saved there")
    }

    fun recordingFor(channel: Channel, p: Programme): Recording? =
        recordings.firstOrNull {
            it.channelId == channel.id && it.start <= p.start && it.stop >= p.stop &&
                (it.status == RecStatus.SCHEDULED || it.status == RecStatus.RECORDING)
        }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').take(60).ifEmpty { "Recording" }

    fun recordProgramme(channel: Channel, p: Programme) {
        if (recordingFor(channel, p) != null) {
            showNotice("Already set to record")
            return
        }
        val start = maxOf(p.start - 60_000L, System.currentTimeMillis())
        // Sport often overruns (extra time, rain delays) – keep recording for another half hour.
        val extra = if (Sports.classify(p) != null) SportFollow.SPORT_EXTRA_MS else 0L
        createRecording(channel, p.title, start, p.stop + 120_000L + extra, p.category)
    }

    fun recordNow(channel: Channel, minutes: Int) {
        val t = System.currentTimeMillis()
        val p = nowNext(channel.id, t).first
        createRecording(channel, p?.title ?: channel.name, t, t + minutes * 60_000L, p?.category)
    }

    private fun createRecording(channel: Channel, title: String, start: Long, stop: Long, category: String?) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(start))
        val id = "r" + System.currentTimeMillis().toString(36)
        val rec = Recording(
            id = id,
            channelId = channel.id,
            channelName = channel.name,
            title = title,
            start = start,
            stop = stop,
            url = channel.url,
            path = File(recordingDir, "${safeName(title)}_$stamp.ts").path,
            status = RecStatus.SCHEDULED,
            category = category,
        )
        store.updateRecordings { it + rec }
        recordings = store.recordings
        RecordingScheduler.schedule(app, rec)
        showNotice(
            if (start <= System.currentTimeMillis() + 90_000L) "Recording ${rec.title} now"
            else "Will record ${rec.title} at ${hhmm(start + 60_000L)}"
        )
    }

    fun stopRecording(rec: Recording) {
        when (rec.status) {
            RecStatus.SCHEDULED -> {
                RecordingScheduler.cancelAlarm(app, rec.id)
                store.updateRecordings { list -> list.map { if (it.id == rec.id) it.copy(status = RecStatus.CANCELLED) else it } }
            }
            RecStatus.RECORDING -> RecordingScheduler.stop(app, rec.id)
        }
        recordings = store.recordings
    }

    fun deleteRecording(rec: Recording) {
        if (rec.status == RecStatus.RECORDING) RecordingScheduler.stop(app, rec.id)
        if (rec.status == RecStatus.SCHEDULED) RecordingScheduler.cancelAlarm(app, rec.id)
        runCatching { File(rec.path).delete() }
        store.updateRecordings { list -> list.filter { it.id != rec.id } }
        recordings = store.recordings
    }

    fun refreshRecordings() {
        recordings = store.recordings
    }

    // ---------------- Recording tidy-up and network drive ----------------

    fun loadRecordingSettings() {
        keptRecordings = store.keptRecordings
        autoDeleteDays = store.recordingAutoDelete
        nasUrl = store.nasUrl
        nasKeepLocal = store.nasKeepLocal
    }

    fun toggleKeep(rec: Recording) {
        keptRecordings = if (rec.id in keptRecordings) keptRecordings - rec.id else keptRecordings + rec.id
        store.keptRecordings = keptRecordings
        showNotice(if (rec.id in keptRecordings) "\"${rec.title}\" will be kept" else "\"${rec.title}\" can be tidied away")
    }

    fun cycleAutoDelete() {
        val steps = listOf(0, 7, 14, 30, 60)
        autoDeleteDays = steps[(steps.indexOf(autoDeleteDays).coerceAtLeast(0) + 1) % steps.size]
        store.recordingAutoDelete = autoDeleteDays
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { com.titanejstudios.tuner.recording.RecordingTidy.cleanUp(store) }
            recordings = store.recordings
            if (n > 0) showNotice("Deleted $n old recording${if (n == 1) "" else "s"}")
        }
    }

    fun setNas(url: String?, user: String?, password: String?) {
        store.nasUrl = url?.takeIf { it.isNotBlank() }?.let { com.titanejstudios.tuner.recording.RecordingTidy.folderUrl(it) }
        if (user != null) store.nasUser = user.takeIf { it.isNotBlank() }
        if (password != null) store.nasPassword = password.takeIf { it.isNotEmpty() }
        nasUrl = store.nasUrl
        testNas()
    }

    fun testNas() {
        if (nasUrl == null) {
            nasStatus = null
            return
        }
        nasStatus = "Checking…"
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) { com.titanejstudios.tuner.recording.RecordingTidy.test(store) }
            nasStatus = if (problem == null) "✓ Connected – finished recordings will be copied there" else "Couldn't reach it: $problem"
        }
    }

    fun toggleNasKeepLocal() {
        nasKeepLocal = !nasKeepLocal
        store.nasKeepLocal = nasKeepLocal
    }

    val nasUser: String? get() = store.nasUser

    fun playRecording(rec: Recording) {
        if (rec.path.startsWith("smb://")) {
            showNotice("This recording was moved to your network drive – watch it from Plex or another app")
            return
        }
        val f = File(rec.path)
        if (!f.exists() || f.length() == 0L) {
            showNotice("Nothing recorded yet – try again in a few seconds")
            return
        }
        mode = PlayMode.RecordingPlay(rec)
        val stillRecording = { store.recordings.any { it.id == rec.id && it.status == RecStatus.RECORDING } }
        playback.play(
            Uri.fromFile(f).toString(),
            live = false,
            metadata = MediaMetadata.Builder().setTitle(rec.title).setArtist(rec.channelName).build(),
            chase = stillRecording,
        )
        navigate(Screen.Player)
    }

    // ---------------- Backup ----------------

    private fun backupFile(): File = File(recordingDir).parentFile?.let { File(it, "tuner-backup.json") }
        ?: File(app.filesDir, "tuner-backup.json")

    fun backupToStorage() {
        viewModelScope.launch {
            val f = backupFile()
            val ok = withContext(Dispatchers.IO) { runCatching { f.writeText(store.exportBackup()) }.isSuccess }
            showNotice(if (ok) "Backup saved to ${f.path}" else "Couldn't save the backup")
        }
    }

    fun restoreFromStorage() {
        viewModelScope.launch {
            val f = backupFile()
            val result = withContext(Dispatchers.IO) {
                runCatching { store.importBackup(f.readText()) }
            }
            if (result.isSuccess) afterRestore() else showNotice("No backup found at ${f.path}")
        }
    }

    private fun afterRestore() {
        stopPlayback()
        loadSettingsFromStore()
        updates.reload()
        link.reload()
        if (!SafeMode.active) {
            football.reload()
            plexFeature.reload()
        }
        startupHandled = true
        resetTo(Screen.Home)
        viewModelScope.launch {
            loadActive()
            refresh(activeSources())
        }
        RecordingScheduler.rescheduleAll(app)
        runCatching { ReminderAlarms.rescheduleAll(app) }
        showNotice("Settings restored")
    }

    // ---------------- Phone setup & phone remote (one small web server) ----------------

    fun startPhoneSetup() {
        setupOpen = true
        updateServer()
        val server = phoneServer
        phoneUrl = if (server != null && phoneBase != null) "$phoneBase/?t=${server.openSetup()}" else null
    }

    fun stopPhoneSetup() {
        setupOpen = false
        phoneServer?.closeSetup()
        phoneUrl = null
        updateServer()
    }

    /** Runs the server while the setup screen is open, or while the remote is on and Tuner is open. */
    private fun updateServer() {
        val wanted = setupOpen || shareMode == "send" || (remoteEnabled && inForeground)
        if (wanted && phoneServer == null) {
            val server = PhoneSetupServer(store, { newSourceId() }, this, app.cacheDir) { event ->
                when (event) {
                    is PhoneSetupServer.Event.SourceAdded -> addSourceFromPhone(event.source)
                    PhoneSetupServer.Event.SettingsChanged -> {
                        webhookUrl = store.webhookUrl
                        showNotice("Home Assistant webhook saved")
                    }
                    PhoneSetupServer.Event.Restored -> afterRestore()
                    is PhoneSetupServer.Event.VpnAdded -> {
                        val err = vpn.add(event.name, event.text)
                        showNotice(err ?: "🔒 VPN server added – Settings › VPN")
                    }
                }
            }
            phoneBase = runCatching { server.start() }.getOrNull()
            phoneServer = server
        } else if (!wanted && phoneServer != null) {
            phoneServer?.stop()
            phoneServer = null
            phoneBase = null
        }
        phoneServer?.remoteEnabled = remoteEnabled
        phoneServer?.shareCode = if (shareMode == "send") shareCode else null
        remoteUrl = if (remoteEnabled && phoneBase != null) "$phoneBase/remote?t=${store.remoteToken}" else null
    }

    fun toggleRemote() {
        remoteEnabled = !remoteEnabled
        store.remoteEnabled = remoteEnabled
        updateServer()
        showNotice(if (remoteEnabled) "Phone remote on – scan the code in Settings" else "Phone remote off")
    }

    // RemoteHandler (called on the main thread by the server)

    override fun channelsJson(): String {
        val arr = org.json.JSONArray()
        val t = System.currentTimeMillis()
        channelsFor(ALL).take(4000).forEachIndexed { i, c ->
            val (cur, _) = nowNext(c.id, t)
            arr.put(
                org.json.JSONObject().put("id", c.id).put("n", displayNumber(c, i)).put("name", c.name)
                    .put("group", c.group).put("logo", c.logo).put("now", cur?.title)
            )
        }
        return arr.toString()
    }

    override fun stateJson(): String {
        val o = org.json.JSONObject()
        when (val m = mode) {
            is PlayMode.Live -> {
                val p = nowNext(m.channel.id, System.currentTimeMillis()).first
                o.put("title", m.channel.name).put("detail", (p?.title ?: "Live") + (if (playback.isPaused) " · paused" else ""))
            }
            is PlayMode.CatchupPlay -> o.put("title", m.programme.title).put("detail", "Catch-up · ${m.channel.name}")
            is PlayMode.Vod -> o.put("title", m.title).put("detail", m.subtitle ?: "Film")
            is PlayMode.RecordingPlay -> o.put("title", m.recording.title).put("detail", "Recording · ${m.recording.channelName}")
            null -> o.put("title", "Flicker").put("detail", "Nothing playing · ${screen.name}")
        }
        return o.toString()
    }

    override fun key(code: Int) {
        // -1 = "Home" from the phone remote (Android doesn't let an app press the real Home button).
        if (code == -1) {
            resetTo(Screen.Home)
            return
        }
        remoteKeys.tryEmit(code)
    }

    override fun play(channelId: String) {
        val all = channelsFor(ALL)
        val ch = all.firstOrNull { it.id == channelId } ?: return
        if (screen == Screen.Player && mode is PlayMode.Live) playFromOverlay(ch, all) else {
            resetTo(Screen.Home)
            play(ch, all)
        }
    }

    override fun searchJson(query: String): String {
        val q = query.trim().lowercase()
        val arr = org.json.JSONArray()
        if (q.length < 2) return arr.toString()
        val chans = allowedAll
        val guide = epg
        val recs = recordings
        val t = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.UK)
        val found = ArrayList<Pair<Channel, Programme>>()
        for (c in chans) {
            for (p in guide[c.id].orEmpty()) {
                if (p.stop > t && p.title.lowercase().contains(q)) found += c to p
            }
            if (found.size > 400) break
        }
        found.sortBy { it.second.start }
        found.distinctBy { it.second.title.lowercase() + "|" + it.second.start }.take(60).forEach { (c, p) ->
            arr.put(
                org.json.JSONObject().put("id", c.id).put("ch", c.name).put("title", p.title).put("start", p.start)
                    .put("when", fmt.format(java.util.Date(p.start)))
                    .put("rec", recs.any { it.channelId == c.id && it.start < p.stop && it.stop > p.start && it.status != RecStatus.FAILED })
            )
        }
        return arr.toString()
    }

    override fun record(channelId: String, start: Long, series: Boolean): String {
        if (activeProfile.kids) return "Recording is off for kids profiles"
        val ch = byId[channelId] ?: return "Channel not found"
        val p = epg[channelId]?.firstOrNull { it.start == start } ?: return "Programme not found"
        return if (series) {
            if (seriesLinkFor(ch, p) == null) toggleSeriesLink(ch, p)
            "Series link set: every ${p.title} on ${ch.name}"
        } else {
            if (recordingFor(ch, p) == null) recordProgramme(ch, p)
            "Recording ${p.title} on ${ch.name}"
        }
    }

    override fun installApk(file: File) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { com.titanejstudios.tuner.apps.Updater.install(app, file) }.exceptionOrNull() }
            if (error != null) showNotice("Update failed: ${error.message}")
        }
    }

    // ---------------- Fast switching, timeshift, health settings ----------------

    private fun scheduleStandby(current: Channel) {
        standbyJob?.cancel()
        if (!fastSwitch) return
        standbyJob = viewModelScope.launch {
            delay(4_000) // let the current channel settle first
            if (zapList.size < 2) return@launch
            val idx = zapList.indexOfFirst { it.id == current.id }
            if (idx < 0) return@launch
            val next = zapList[(idx + lastZapDirection).mod(zapList.size)]
            playback.prepareStandby(next.url)
        }
    }

    fun toggleFastSwitch() {
        fastSwitch = !fastSwitch
        store.fastSwitch = fastSwitch
        playback.fastSwitch = fastSwitch
        playing?.let { scheduleStandby(it) }
    }

    // ---------------- Sleep timer ----------------

    /** [minutes] > 0 sets a timer, 0 turns it off, -1 = at the end of what's on now. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val t = System.currentTimeMillis()
        sleepAt = when {
            minutes == 0 -> 0L
            minutes < 0 -> playing?.let { nowNext(it.id, t).first?.stop } ?: (t + 60 * 60_000L)
            else -> t + minutes * 60_000L
        }
        if (sleepAt == 0L) {
            showNotice("Sleep timer off")
            return
        }
        showNotice("Flicker will stop at ${hhmm(sleepAt)}")
        sleepJob = viewModelScope.launch {
            val warnAt = sleepAt - 60_000L
            if (warnAt > System.currentTimeMillis()) {
                delay(warnAt - System.currentTimeMillis())
                showNotice("Sleep timer: stopping in 1 minute (set it again to keep watching)")
            }
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            sleepAt = 0L
            if (mode is PlayMode.Vod) saveResume(syncHome = true)
            stopPlayback()
            goHome()
            showNotice("Sleep timer – stopped")
            // Lets Home Assistant turn the TV off too.
            sendHa("sleep_timer")
        }
    }

    val sleepLabel: String
        get() = if (sleepAt == 0L) "Off" else "Stops at ${hhmm(sleepAt)}"

    // ---------------- Picture size, lip sync, languages ----------------

    /** For the video surface: 0 = fit, 4 = zoom (fill, crop edges), 3 = stretch. */
    val pictureMode: Int
        get() = (playing?.let { pictureModes[it.id] } ?: pictureModes["*"])?.toIntOrNull() ?: 0

    val pictureModeLabel: String
        get() = when (pictureMode) {
            4 -> "Zoom"
            3 -> "Stretch"
            else -> "Normal"
        }

    /** Cycles Normal → Zoom → Stretch for this channel (or for films/recordings when no channel). */
    fun cyclePictureMode() {
        val next = when (pictureMode) {
            0 -> 4
            4 -> 3
            else -> 0
        }
        val key = playing?.id ?: "*"
        pictureModes = if (next == 0) pictureModes - key else pictureModes + (key to next.toString())
        store.pictureModes = pictureModes
        showNotice("Picture: $pictureModeLabel" + if (playing != null) " (remembered for ${playing?.name})" else "")
    }

    val lipSyncMs: Long
        get() = (playing?.let { lipSyncMap[it.id] } ?: lipSyncMap["*"])?.toLongOrNull() ?: 0L

    fun lipSyncLabel(ms: Long = lipSyncMs): String = when {
        ms == 0L -> "In sync"
        ms > 0 -> "Sound ${ms} ms later"
        else -> "Sound ${-ms} ms earlier"
    }

    /** Sets the sound offset for the channel being watched (or every channel). */
    fun setLipSync(ms: Long, allChannels: Boolean = false) {
        val key = if (allChannels) "*" else playing?.id ?: "*"
        lipSyncMap = if (ms == 0L) lipSyncMap - key else lipSyncMap + (key to ms.toString())
        store.lipSync = lipSyncMap
        showNotice(lipSyncLabel(ms) + if (key == "*") " (all channels)" else "")
    }

    val languages = listOf(
        null to "Stream's default", "en" to "English", "fr" to "French", "de" to "German", "es" to "Spanish",
        "it" to "Italian", "pt" to "Portuguese", "pl" to "Polish", "nl" to "Dutch", "ar" to "Arabic", "tr" to "Turkish",
    )

    fun languageName(code: String?) = languages.firstOrNull { it.first == code }?.second ?: code ?: "Stream's default"

    fun cycleAudioLanguage() {
        val i = languages.indexOfFirst { it.first == audioLanguage }
        audioLanguage = languages[(i + 1).mod(languages.size)].first
        store.audioLanguage = audioLanguage
        playback.setLanguages(audioLanguage, subtitleLanguage)
    }

    fun cycleSubtitleLanguage() {
        val i = languages.indexOfFirst { it.first == subtitleLanguage }
        subtitleLanguage = languages[(i + 1).mod(languages.size)].first
        store.subtitleLanguage = subtitleLanguage
        playback.setLanguages(audioLanguage, subtitleLanguage)
    }

    // ---------------- Catch-up page ----------------

    /**
     * Channels with catch-up. Looks at every copy (the 720p copy often has catch-up when the 1080p
     * one doesn't), then combines copies as the TV Guide does.
     */
    val catchupChannels: List<Channel>
        get() {
            val withCatchup = channels.filter { it.hasCatchup && isAllowed(it) }
            return if (mergeVersions) ChannelMerge.merge(withCatchup, preferredVersions).channels else withCatchup
        }

    /** Programmes on [channel] that can be watched again, newest first, grouped by day label. */
    fun catchupProgrammes(channel: Channel): List<Programme> {
        val t = System.currentTimeMillis()
        return epg[channel.id].orEmpty().filter { Catchup.isAvailable(channel, it, t) }.sortedByDescending { it.start }
    }

    // ---------------- Look & sound ----------------

    fun setTheme(accent: String = themeAccent, background: String = themeBackground) {
        themeAccent = accent
        themeBackground = background
        store.themeAccent = accent
        store.themeBackground = background
        TunerColors.apply(accent, background)
    }

    fun cycleSubtitleSize() {
        val sizes = listOf(0.8f, 1f, 1.25f, 1.5f, 1.8f)
        val i = sizes.indexOfFirst { kotlin.math.abs(it - subtitleScale) < 0.01f }
        subtitleScale = sizes[(i + 1).mod(sizes.size)]
        store.subtitleScale = subtitleScale
        SubtitleLook.scale = subtitleScale
    }

    val subtitleSizeLabel: String
        get() = when {
            subtitleScale < 0.9f -> "Small"
            subtitleScale < 1.1f -> "Normal"
            subtitleScale < 1.4f -> "Large"
            subtitleScale < 1.6f -> "Extra large"
            else -> "Huge"
        }

    fun toggleSubtitleBox() {
        subtitleBox = !subtitleBox
        store.subtitleBox = subtitleBox
        SubtitleLook.background = subtitleBox
    }

    fun toggleClearSpeech() {
        clearSpeech = !clearSpeech
        store.clearSpeech = clearSpeech
        playback.speechOn = clearSpeech
        showNotice(
            when {
                !clearSpeech -> "Clearer speech off"
                !playback.nightModeSupported -> "Clearer speech isn't supported on this device"
                audioPassthrough -> "Clearer speech on – it needs Audio passthrough off to work"
                else -> "Clearer speech on – voices lifted over music and effects"
            }
        )
    }

    /** Normal → Large → Larger text. */
    fun cycleTextScale() {
        textScale = when (textScale) {
            100 -> 115
            115 -> 130
            else -> 100
        }
        store.textScale = textScale
    }

    val textScaleLabel: String
        get() = when (textScale) {
            115 -> "Large"
            130 -> "Larger"
            else -> "Normal"
        }

    fun toggleNightMode() {
        nightMode = !nightMode
        store.nightMode = nightMode
        playback.nightModeOn = nightMode
        showNotice(
            when {
                !nightMode -> "Night mode off"
                !playback.nightModeSupported -> "Night mode isn't supported on this TV"
                audioPassthrough -> "Night mode on – it needs Audio passthrough off to work"
                else -> "Night mode on – quieter loud bits, clearer speech"
            }
        )
    }

    fun toggleFrameRateMatch() {
        frameRateMatch = !frameRateMatch
        store.frameRateMatch = frameRateMatch
        playback.setSeamlessFrameRate(frameRateMatch)
        showNotice(if (frameRateMatch) "The TV will switch to the video's frame rate (the screen may blank for a second)" else "Frame-rate matching off")
    }

    // ---------------- What's on ----------------

    val whatsOnSlots: List<WhatsOn.Slot> get() = WhatsOn.slots(now)

    fun openWhatsOn(genre: String? = null) {
        whatsOnGenre = genre
        navigate(Screen.WhatsOn)
        updateWhatsOn()
    }

    fun updateWhatsOn() {
        val slots = WhatsOn.slots(System.currentTimeMillis())
        val slot = slots.getOrNull(whatsOnSlot) ?: slots.first()
        val chans = allowedAll
        val guide = epg
        val favs = favouriteSet
        val genre = whatsOnGenre
        val favOnly = whatsOnFavouritesOnly
        whatsOnJob?.cancel()
        whatsOnJob = viewModelScope.launch {
            whatsOnResults = withContext(Dispatchers.Default) { WhatsOn.list(chans, guide, slot.at, genre, favs, favOnly) }
        }
    }

    // ---------------- TV's Live tab ----------------

    fun cycleLiveTab() {
        liveTabScope = when (liveTabScope) {
            "off" -> "favourites"
            "favourites" -> "all"
            else -> "off"
        }
        store.liveTabScope = liveTabScope
        scheduleLiveSync(0)
    }

    val liveTabLabel: String
        get() = when (liveTabScope) {
            "favourites" -> "Favourites"
            "all" -> "All channels (up to 600)"
            else -> "Off"
        }

    /** Re-copies channels to the TV a little after things change (favourites, a guide update…). */
    fun scheduleLiveSync(delayMs: Long = 20_000L) {
        if (liveTabScope == "off" && delayMs > 0) return
        if (SafeMode.active && delayMs > 0) return
        liveSyncJob?.cancel()
        liveSyncJob = viewModelScope.launch {
            delay(delayMs)
            liveTabStatus = "Updating the TV's channel list…"
            val n = withContext(Dispatchers.IO) {
                inBackgroundPriority { runCatching { LiveChannelSync.sync(app) }.onFailure { AppLog.warn("Live tab update failed", it) }.getOrNull() }
            }
            liveTabStatus = when {
                n == null -> "Couldn't update the TV's channel list – see the problem log"
                liveTabScope == "off" -> null
                else -> "$n channels in the TV's channel list"
            }
        }
    }

    // ---------------- Plex (see features/PlexFeature) ----------------

    /** Starts linking: shows a code to type in at plex.tv/link, then waits for it. */
    fun linkPlex() {
        if (screen != Screen.Plex) navigate(Screen.Plex)
        plexFeature.link()
    }

    fun unlinkPlex() = plexFeature.unlink()

    /** Re-reads Continue watching. */
    fun refreshPlex(force: Boolean = false) {
        if (SafeMode.active && !force) return
        plexFeature.refresh(force)
    }

    fun playPlex(item: Plex.Item, fromStart: Boolean = false, fallback: (() -> Unit)? = null) {
        val url = item.playUrl
        if (url == null) {
            // Search results can leave out the file's address: ask Plex for it.
            viewModelScope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching { Plex(store.plexClientId).playUrlFor(item.server, item.ratingKey) }
                        .onFailure { AppLog.warn("Plex didn't give the file for ${item.title}", it) }
                        .getOrNull()
                }
                when {
                    found != null -> playPlex(
                        Plex.Item(item.ratingKey, item.title, item.subtitle, item.image, found, item.viewOffsetMs, item.durationMs, item.server),
                        fromStart,
                    )
                    fallback != null -> {
                        showNotice("Plex wouldn't play ${item.title} – playing your provider's copy")
                        fallback()
                    }
                    else -> showNotice("Plex wouldn't play ${item.title} – is the Plex server on?")
                }
            }
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = false)
        plexPlaying = item
        episodeQueue = emptyList()
        plexEpisodeQueue = emptyList()
        mode = PlayMode.Vod("plex:" + item.ratingKey, item.title, item.subtitle, item.image, url, null)
        val meta = MediaMetadata.Builder().setTitle(item.title).setArtist(item.subtitle)
        playback.play(url, live = false, startPositionMs = if (fromStart) 0L else item.viewOffsetMs, metadata = meta.build())
        sendHa("playing", mapOf("kind" to "plex", "title" to item.title, "subtitle" to item.subtitle))
        navigate(Screen.Player)
    }

    /** Films & TV › Plex: browse your Plex libraries. */
    fun openPlexLibrary() {
        if (!plexLinked) {
            linkPlex()
            return
        }
        if (activeProfile.kids && !kidsHavePlex) {
            showNotice("No Plex libraries are set for kids yet – a grown-up can hold OK on one in Plex to allow it")
            return
        }
        navigate(Screen.PlexLibrary)
        plexFeature.loadSections()
    }

    /** Plays a Plex film or episode. Episodes queue up the rest of the show after them. */
    fun playPlexEntry(
        entry: com.titanejstudios.tuner.integrations.Plex.Entry,
        fromStart: Boolean = false,
        chosen: Boolean = false,
        /** What to play instead if Plex won't give the file (the IPTV copy). */
        fallback: (() -> Unit)? = null,
    ) {
        // "Because you watched…": the film, or the show an episode belongs to.
        (if (entry.type == "episode") plexFeature.show else entry)?.let { rememberWatched("plex", it.ratingKey, it.title, it.server.machineId) }
        if (entry.type == "movie" && !chosen) {
            // In Films and Films 4K? Ask which copy to play.
            viewModelScope.launch {
                val lookup = viewModelScope.async { plexFeature.copiesOf(entry.title, entry.year) }
                val copies = withTimeoutOrNull(5_000) { lookup.await() }.orEmpty()
                    .filter { !activeProfile.kids || plexFeature.isKidsEntry(it) }
                if (copies.size > 1) plexCopyChoice = PlexCopyChoice(entry.title, copies, fromStart, null)
                else playPlexEntry(entry, fromStart, chosen = true, fallback = fallback)
            }
            return
        }
        val show = plexFeature.show
        val item = entry.toItem(if (entry.type == "episode") show?.title else null)
        playPlex(item, fromStart, fallback)
        if (entry.type == "episode") {
            plexEpisodeQueue = plexFeature.showEpisodes.orEmpty().dropWhile { it.ratingKey != entry.ratingKey }.drop(1)
        }
    }


    /** Plays the copy picked ([copy] null = the IPTV copy). */
    fun choosePlexCopy(copy: com.titanejstudios.tuner.integrations.Plex.Entry?) {
        val c = plexCopyChoice ?: return
        plexCopyChoice = null
        if (copy != null) playPlexEntry(copy, c.fromStart, chosen = true, fallback = c.iptv?.let { m -> { playMovie(m, c.fromStart, viaPlex = false) } })
        else c.iptv?.let { playMovie(it, c.fromStart, viaPlex = false) }
    }

    fun cancelPlexCopyChoice() {
        plexCopyChoice = null
    }


    fun togglePreferPlexFilms() {
        preferPlexFilms = !preferPlexFilms
        store.preferPlexFilms = preferPlexFilms
        showNotice(if (preferPlexFilms) "Films you also have on Plex will play from Plex" else "Films play from your IPTV provider")
    }

    val plexServerCount: Int get() = plexFeature.servers.size

    /** Kids' profiles only see the Plex libraries picked for them (hold OK on a library). */
    val kidsHavePlex: Boolean get() = plexLinked && plexFeature.kidsSections.isNotEmpty()

    fun plexSectionAllowed(s: Plex.Section): Boolean = !activeProfile.kids || s.id in plexFeature.kidsSections

    /** Marks a Plex film, show or episode watched/unwatched, then reloads what's on screen. */
    fun setPlexWatched(entry: Plex.Entry, watched: Boolean) {
        plexFeature.setWatched(entry, watched, { showNotice(it) }) {
            if (screen == Screen.PlexShow) plexFeature.show?.let { plexFeature.openShow(it) }
        }
    }

    fun openPlexShow(entry: com.titanejstudios.tuner.integrations.Plex.Entry) {
        plexFeature.openShow(entry)
        navigate(Screen.PlexShow)
    }

    /** The rest of a Plex show to play after the current episode. */
    private var plexEpisodeQueue: List<com.titanejstudios.tuner.integrations.Plex.Entry> = emptyList()

    fun openPlexApp() {
        val plexApp = tvApps.orEmpty().firstOrNull { it.packageName == "com.plexapp.android" }
        if (plexApp != null) launchApp(plexApp) else if (!InstalledApps.launch(app, "com.plexapp.android")) showNotice("The Plex app isn't installed")
    }

    // ---------------- Football scores (see features/FootballFeature) ----------------

    fun setFootballKey(key: String?) = football.changeKey(key)

    fun toggleFollowTeam(team: String) = football.toggleTeam(team)

    fun channelForMatch(m: Football.Match): Channel? = Football.channelFor(m, allowedAll, epg, System.currentTimeMillis())

    // ---------------- App updates (see features/UpdateFeature) ----------------

    val installedVersion: String get() = updates.installedVersion

    val updatePageUrl: String?
        get() = phoneBase?.let { "$it/update?t=${store.remoteToken}" }

    fun setUpdateRepo(repo: String?) = updates.changeRepo(repo)

    fun checkForUpdate() = updates.check()

    fun installAvailableUpdate() = updates.install()

    /** Turns the phone remote on (the upload page needs it) and opens the update screen. */
    fun openUpdates() {
        if (!remoteEnabled) toggleRemote()
        navigate(Screen.Update)
        if (updateRepo != null) checkForUpdate()
    }

    // ---------------- Copy settings between TVs ----------------

    fun startShareSend() {
        stopShare()
        shareMode = "send"
        shareCode = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
        shareStatus = null
        updateServer()
        val server = phoneServer
        if (server == null || phoneBase == null) {
            shareStatus = "This TV isn't on a network Flicker can use"
        } else {
            val share = TvShare(app).also { tvShare = it }
            val name = (android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL)
            share.advertise("Flicker on $name", server.port)
        }
        if (screen != Screen.ShareTv) navigate(Screen.ShareTv)
    }

    fun startShareReceive() {
        stopShare()
        shareMode = "receive"
        shareFound = emptyList()
        shareStatus = "Looking for other TVs with Flicker open on \"Copy to another TV\"…"
        val share = TvShare(app).also { tvShare = it }
        share.discover { found ->
            if (shareFound.none { it.host == found.host && it.port == found.port }) shareFound = shareFound + found
        }
        if (screen != Screen.ShareTv) navigate(Screen.ShareTv)
    }

    fun copyFrom(found: TvShare.Found, code: String) {
        shareStatus = "Copying from ${found.name}…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.importBackup(TvShare.fetch(found.host, found.port, code)) }
            }
            result.onSuccess {
                stopShare()
                afterRestore()
                showNotice("Copied everything from ${found.name}")
            }.onFailure {
                shareStatus = it.message ?: "Couldn't copy – try again"
            }
        }
    }

    fun stopShare() {
        tvShare?.stop()
        tvShare = null
        val wasSending = shareMode == "send"
        shareMode = null
        shareCode = null
        if (wasSending) updateServer()
    }

    // ---------------- Watch stats ----------------

    private fun trackStats(flush: Boolean) {
        val m = mode
        if (m != null && playback.isPlaying && inForeground) {
            val (name, genre) = when (m) {
                is PlayMode.Live -> m.channel.name to nowNext(m.channel.id, System.currentTimeMillis()).first
                    .let { p -> Genres.of(p?.category, p?.title ?: "", m.channel.group) }
                is PlayMode.CatchupPlay -> m.channel.name to Genres.of(m.programme.category, m.programme.title, m.channel.group)
                is PlayMode.Vod -> (if (m.seriesId != null) "Series" else "Films") to (if (m.seriesId != null) "Drama" else "Film")
                is PlayMode.RecordingPlay -> "Recordings" to Genres.of(m.recording.category, m.recording.title, null)
            }
            val key = name to (genre ?: "Other")
            pendingStats[key] = (pendingStats[key] ?: 0L) + 15L
            if (m is PlayMode.Live) pendingHabits[m.channel.id] = (pendingHabits[m.channel.id] ?: 0L) + 15L
            if (m is PlayMode.Live) {
                val now = System.currentTimeMillis()
                val p = nowNext(m.channel.id, now).first
                val w = liveWatch
                if (p != null && w != null && w.channelId == m.channel.id && w.start == p.start) {
                    w.seconds += 15
                    w.lastSeen = now
                } else {
                    finishLiveWatch()
                    if (p != null) liveWatch = LiveWatch(m.channel.id, p.start, 15, now)
                }
            } else finishLiveWatch()
            // Learning what you like: the programme or film, and its kind.
            val title = when (m) {
                is PlayMode.Live -> nowNext(m.channel.id, System.currentTimeMillis()).first?.title
                is PlayMode.CatchupPlay -> m.programme.title
                is PlayMode.Vod -> m.title
                is PlayMode.RecordingPlay -> m.recording.title
            }
            if (title != null && learnTaste) {
                val tk = title to genre
                pendingTaste[tk] = (pendingTaste[tk] ?: 0L) + 15L
            }
        }
        if (flush && pendingStats.isNotEmpty()) {
            val pid = activeProfile.id
            val day = WatchStats.dayKey(System.currentTimeMillis())
            var json = store.stats(pid)
            for ((k, secs) in pendingStats) json = WatchStats.add(json, day, k.first, k.second, secs)
            pendingStats.clear()
            store.setStats(pid, json)
        }
        if (flush && pendingHabits.isNotEmpty()) {
            val pid = activeProfile.id
            val t = System.currentTimeMillis()
            var json = store.habits(pid)
            for ((id, secs) in pendingHabits) json = com.titanejstudios.tuner.data.Habits.add(json, t, id, secs)
            pendingHabits.clear()
            store.setHabits(pid, json)
        }
        if (flush && pendingTaste.isNotEmpty()) {
            val t = System.currentTimeMillis()
            for ((k, secs) in pendingTaste) taste.record(k.first, k.second, secs, t)
            pendingTaste.clear()
            store.setTaste(activeProfile.id, taste.toJson())
        }
    }

    fun openStats(profile: Profile = activeProfile) {
        trackStats(flush = true)
        if (screen != Screen.Stats) navigate(Screen.Stats)
        statsProfile = profile
        statsSummary = null
        val pid = profile.id
        viewModelScope.launch {
            statsSummary = withContext(Dispatchers.Default) { WatchStats.summary(store.stats(pid), System.currentTimeMillis()) }
        }
    }

    fun toggleAudioPassthrough() {
        audioPassthrough = !audioPassthrough
        store.audioPassthrough = audioPassthrough
        showNotice("Saved – close and reopen Flicker for this to take effect")
    }

    fun toggleStreamCompatibility() {
        streamCompatibility = !streamCompatibility
        store.streamCompatibility = streamCompatibility
        showNotice("Saved – close and reopen Flicker for this to take effect")
    }

    fun toggleTimeshift() {
        timeshiftEnabled = !timeshiftEnabled
        store.timeshiftEnabled = timeshiftEnabled
    }

    fun toggleHideDead() {
        hideDead = !hideDead
        store.hideDead = hideDead
        recomputeVisible()
    }

    fun toggleHealthNightly() {
        healthNightly = !healthNightly
        store.healthNightly = healthNightly
    }

    fun toggleHealthScope() {
        healthScope = if (healthScope == "all") "favourites" else "all"
        store.healthScope = healthScope
    }

    val healthRunning: Boolean get() = healthJob?.isActive == true

    /** Checks channels now. Stops playback first, since many providers allow only one connection. */
    fun runHealthCheck() {
        if (healthJob?.isActive == true) return
        stopPlayback()
        val targets = if (healthScope == "all") allChannels.filter { it.group !in hiddenGroups && it.id !in hiddenChannels }
        else allChannels.filter { it.id in favouriteSet }
        if (targets.isEmpty()) {
            showNotice(if (healthScope == "all") "No channels to check" else "Add some favourites first, or check all channels")
            return
        }
        healthCancel = false
        healthJob = viewModelScope.launch {
            healthProgress = "Starting…"
            var done = 0
            val dead = withContext(Dispatchers.IO) {
                HealthCheck.run(targets, { healthCancel }) { d, total, deadCount ->
                    done = d
                    viewModelScope.launch { healthProgress = "Checked $d of $total · $deadCount offline" }
                }
            }
            val checked = targets.take(done).map { it.id }.toSet()
            deadChannels = (deadChannels - checked) + dead
            store.deadChannels = deadChannels
            healthCheckedAt = System.currentTimeMillis()
            store.healthCheckedAt = healthCheckedAt
            healthProgress = null
            recomputeVisible()
            showNotice("Checked $done channels – ${dead.size} offline")
        }
    }

    fun cancelHealthCheck() {
        healthCancel = true
    }

    fun clearDead(channelId: String? = null) {
        deadChannels = if (channelId == null) emptySet() else deadChannels - channelId
        store.deadChannels = deadChannels
        recomputeVisible()
    }

    private fun addSourceFromPhone(s: Source) {
        val first = sources.isEmpty()
        sources = sources + s
        store.sources = sources
        if (first) {
            activeSourceId = s.id
            store.activeSourceId = s.id
            stopPhoneSetup()
            resetTo(Screen.Home)
        }
        showNotice("${s.name} added from your phone – loading channels…")
        movies = null
        series = null
        refreshJob?.cancel()
        refresh(listOf(s))
    }

    // ---------------- Settings: playlists ----------------

    fun startAddSource() {
        editingSource = null
        navigate(Screen.SourceEdit)
    }

    fun startEditSource(s: Source) {
        editingSource = s
        navigate(Screen.SourceEdit)
    }

    fun newSourceId(): String = "s" + System.currentTimeMillis().toString(36)

    fun saveSource(s: Source) {
        val list = sources.toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list += s
        sources = list
        store.sources = list
        if (list.size == 1) {
            activeSourceId = s.id
            store.activeSourceId = s.id
        }
        // Saved film/series lists contain the login in their links – start afresh.
        vodCache.clear(s.id)
        movies = null
        series = null
        if (stack.size == 1) resetTo(Screen.Home) else back()
        refreshJob?.cancel()
        refresh(listOf(s))
    }

    fun deleteSource(s: Source) {
        sources = sources.filter { it.id != s.id }
        store.sources = sources
        store.deleteCaches(s.id)
        vodCache.clear(s.id)
        if (activeSourceId == s.id) {
            activeSourceId = ALL_SOURCES
            store.activeSourceId = ALL_SOURCES
        }
        movies = null
        series = null
        if (sources.isEmpty()) {
            stopPlayback()
            allChannels = emptyList()
            epg = emptyMap()
            recomputeVisible()
            editingSource = null
            resetTo(Screen.SourceEdit)
        } else viewModelScope.launch { loadActive() }
    }

    fun selectSource(id: String) {
        activeSourceId = id
        store.activeSourceId = id
        movies = null
        series = null
        viewModelScope.launch {
            loadActive()
            val missing = activeSources().filter { store.lastRefresh(it.id) == 0L || !db.hasChannels(it.id) }
            if (missing.isNotEmpty()) refresh(missing)
        }
    }

    // ---------------- Deep links (home screen row, Home Assistant) ----------------

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        onUserActivity()
        // Voice / TV search: "play Sky Sports on Tuner", or a search typed on the TV.
        val voiceActions = setOf(
            "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            Intent.ACTION_SEARCH,
            "com.google.android.gms.actions.SEARCH_ACTION",
        )
        if (intent.action in voiceActions) {
            val q = intent.getStringExtra(android.app.SearchManager.QUERY) ?: intent.getStringExtra("query") ?: return
            pendingLink = Uri.Builder().scheme("tuner").authority("voice").appendQueryParameter("q", q).build()
            handlePendingLink()
            return
        }
        val data = intent.data ?: return
        if (data.scheme != "tuner") return
        pendingLink = data
        handlePendingLink()
    }

    private fun handlePendingLink() {
        val uri = pendingLink ?: return
        val wantsChannel = uri.host == "play" || uri.host == "voice" || uri.getQueryParameter("channel") != null
        if (wantsChannel && allChannels.isEmpty()) return // wait until channels have loaded
        pendingLink = null
        startupHandled = true
        when (uri.host) {
            "play" -> {
                val all = channelsFor(ALL)
                val number = uri.getQueryParameter("number")?.toIntOrNull()
                val name = uri.getQueryParameter("name") ?: uri.getQueryParameter("channel")
                val ch = when {
                    number != null -> all.firstOrNull { it.number == number } ?: all.getOrNull(number - 1)
                    name != null -> findByName(all, name)
                    else -> null
                }
                if (ch != null) {
                    resetTo(Screen.Home)
                    play(ch, all)
                } else showNotice("Couldn't find channel \"${name ?: number}\"")
            }
            "stop" -> {
                stopPlayback()
                resetTo(Screen.Home)
            }
            "voice" -> {
                val q = uri.getQueryParameter("q").orEmpty()
                val all = channelsFor(ALL)
                val number = VoiceMatch.number(q)
                val ch = if (number != null) all.firstOrNull { it.number == number } ?: all.getOrNull(number - 1) else VoiceMatch.find(all, q)
                if (ch != null) {
                    resetTo(Screen.Home)
                    play(ch, all)
                } else {
                    // Not a channel – maybe a programme: show search results for it.
                    resetTo(Screen.Home)
                    navigate(Screen.Search)
                    updateSearch(VoiceMatch.clean(q).ifEmpty { q })
                }
            }
            "open" -> {
                uri.getQueryParameter("channel")?.let { id ->
                    val ch = allChannels.firstOrNull { it.id == id }
                    if (ch != null) {
                        resetTo(Screen.Home)
                        play(ch, channelsFor(FAVOURITES).ifEmpty { channelsFor(ALL) })
                    }
                }
                uri.getQueryParameter("vod")?.let { id ->
                    resume[id]?.let {
                        resetTo(Screen.Home)
                        playResume(it)
                    }
                }
            }
        }
    }

    private fun findByName(list: List<Channel>, name: String): Channel? {
        val n = XmltvParser.normalise(name)
        if (n.isEmpty()) return null
        return list.firstOrNull { XmltvParser.normalise(it.name) == n }
            ?: list.firstOrNull { XmltvParser.normalise(it.name).startsWith(n) }
            ?: list.firstOrNull { XmltvParser.normalise(it.name).contains(n) }
    }

    // ---------------- Self-test & safe mode ----------------

    fun openSelfTest() {
        navigate(Screen.SelfTest)
        if (selfTestResults.isEmpty() && !selfTestRunning) runSelfTest()
    }

    /** Runs every check one after another; results appear as they finish. */
    fun runSelfTest() {
        selfTestJob?.cancel()
        selfTestResults = emptyList()
        selfTestRunning = true
        val snap = SelfTest.Snapshot(
            sources = sources,
            channels = channels,
            epg = epg,
            favourites = favourites,
            recordingDir = recordingDir,
            webServerRunning = phoneServer != null,
            remoteUrl = remoteUrl,
            webhookUrl = webhookUrl,
            liveTabScope = liveTabScope,
            nightModeOn = nightMode,
            frameRateMatch = frameRateMatch,
            lipSyncSet = lipSyncMap.isNotEmpty(),
        )
        val test = SelfTest(app, store)
        selfTestJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                test.runAll(snap) { r ->
                    viewModelScope.launch { selfTestResults = selfTestResults + r }
                }
            }
            // Let the last result land before saying we're done.
            delay(50)
            selfTestRunning = false
            val bad = selfTestResults.count { it.state == SelfTest.State.Fail }
            AppLog.info("Self-test finished: $bad problem" + (if (bad == 1) "" else "s") + " – " +
                selfTestResults.filter { it.state == SelfTest.State.Fail }.joinToString { it.name })
        }
    }

    /** Turns safe mode off now (as far as possible) and for future starts. */
    fun leaveSafeMode() {
        SafeMode.leave()
        loadSettingsFromStore()
        extrasStarted = false
        startExtras()
        scheduleLiveSync()
        safeModeBannerHidden = true
        showNotice("Safe mode off. Audio passthrough and stream compatibility (if you use them) return next time Flicker starts.")
    }

    /** For "something's not right but it doesn't crash": start with the extras off next time. */
    fun startInSafeModeNextTime() {
        SafeMode.forceNextStart()
        showNotice("Next time Flicker starts it will be in safe mode")
    }

    fun clearLastCrash() {
        SafeMode.clearCrash()
        showNotice("Crash note cleared")
    }

    // ---------------- Smarter buffering ----------------

    /** Half an hour of trouble-free watching lets a channel earn back its quick start. */
    private fun trackCleanPlay() {
        val ch = (mode as? PlayMode.Live)?.channel ?: return
        if (!playback.isPlaying || playback.hadTrouble) return
        cleanPlaySeconds += 15
        if (cleanPlaySeconds >= 30 * 60) {
            cleanPlaySeconds = 0
            if (playback.cushionMs > 0) cushion.healthy(ch.id)
        }
    }

    // ---------------- Recordings planner ----------------

    /** Asks the provider (once) how many streams the account allows, for the planner's clash note. */
    fun loadAccountStreams() {
        if (accountStreamsLoaded) return
        accountStreamsLoaded = true
        val src = sources.filterIsInstance<Source.Xtream>().firstOrNull() ?: return
        viewModelScope.launch {
            accountStreams = withContext(Dispatchers.IO) { XtreamClient.maxConnections(src) }
        }
    }

    // ---------------- Home Assistant: paused / resumed and cinema lights ----------------

    fun cycleCinemaLights() {
        cinemaLights = CinemaLights.next(cinemaLights)
        store.cinemaLights = cinemaLights
        showNotice(
            if (cinemaLights == "off") "Cinema lights off"
            else "Cinema lights: ${CinemaLights.label(cinemaLights)} – Home Assistant gets a \"lights\" event"
        )
    }

    private fun playKind(m: PlayMode?): String? = when (m) {
        null -> null
        is PlayMode.Live -> "live"
        is PlayMode.CatchupPlay -> "catchup"
        is PlayMode.RecordingPlay -> "recording"
        is PlayMode.Vod -> when {
            m.contentId.startsWith("plex:") -> "plex"
            m.seriesId != null -> "episode"
            else -> "film"
        }
    }

    /**
     * Sends "paused" / "resumed", and "lights" (dim / restore) for the cinema-lights setting.
     * Waits a moment before acting so a quick pause or a channel change doesn't flash the lights.
     */
    private fun watchPlaybackForHomeAssistant() {
        viewModelScope.launch {
            var wasPaused: Boolean? = null
            snapshotFlow { Triple(playKind(mode), screen == Screen.Player && !inPip, playback.isPaused) }
                .distinctUntilChanged()
                .collectLatest { (kind, fullScreen, paused) ->
                    delay(1_500)
                    if (kind != null && webhookUrl != null && wasPaused != null && wasPaused != paused) {
                        sendHa(if (paused) "paused" else "resumed", mapOf("kind" to kind))
                    }
                    wasPaused = if (kind == null) null else paused
                    val dim = CinemaLights.shouldDim(cinemaLights, kind, fullScreen, paused)
                    if (dim != lightsDimmed) {
                        lightsDimmed = dim
                        sendHa("lights", mapOf("state" to (if (dim) "dim" else "restore"), "kind" to kind))
                    }
                }
        }
    }
    // ---------------- Crash alerts (5.4) ----------------

    /** A crash since last time? Tell Home Assistant (event "crash"), once. */
    private fun reportCrashToHomeAssistant() {
        val at = SafeMode.lastCrashAt
        val summary = SafeMode.lastCrash ?: return
        if (at <= store.crashReportedAt || webhookUrl == null) return
        store.crashReportedAt = at
        val log = AppLog.read().lines().filter { it.isNotBlank() }.take(12).joinToString("\n")
        sendHa(
            "crash",
            mapOf(
                "summary" to summary, "at" to at / 1000, "safe_mode" to SafeMode.active,
                "version" to installedVersion, "log" to log,
            ),
        )
    }

    // ---------------- "Are you still watching?" (5.4) ----------------

    /** Any button on the remote (or the phone remote, or Home Assistant) counts as someone watching. */
    fun onUserActivity() {
        lastUserActivity = System.currentTimeMillis()
        saverOn = false
    }

    fun toggleTintFromPicture() {
        tintFromPicture = !tintFromPicture
        store.tintFromPicture = tintFromPicture
    }

    fun cycleArtSaver() {
        val choices = listOf(5, 10, 20, 0)
        artSaverMinutes = choices[(choices.indexOf(artSaverMinutes) + 1).mod(choices.size)]
        store.artSaverMinutes = artSaverMinutes
    }

    /** One picture in the screensaver. */
    class SaverSlide(val title: String, val details: String, val image: String, val play: () -> Unit)

    /** Wide artwork for the screensaver: new on Plex, Continue on Plex, and pictures from the guide. */
    val saverSlides: List<SaverSlide>
        get() = buildList {
            plexRecent.forEach { e -> e.art?.let { add(SaverSlide(e.title, listOfNotNull(e.year, if (e.isShow) "TV" else "Film", e.library).joinToString(" · "), it) { if (e.isShow) openPlexShow(e) else playPlexEntry(e) }) } }
            plexItems.forEach { i -> i.image?.let { add(SaverSlide(i.title, listOfNotNull(i.subtitle, "Continue on Plex").joinToString(" · "), it) { playPlex(i) }) } }
            topPicks.forEach { p -> p.programme.image?.let { add(SaverSlide(p.programme.title, p.channel.name, it) { play(p.channel, channelsFor(ALL)) }) } }
        }.distinctBy { it.image }

    private var saverCurrent: SaverSlide? = null

    fun saverIndexChanged(slide: SaverSlide) {
        saverCurrent = slide
    }

    /** OK on the screensaver: watch what's showing. */
    fun playSaverSlide() {
        saverOn = false
        saverCurrent?.play?.invoke()
    }

    /** Idle for a while with nothing playing: show the artwork screensaver. */
    private fun checkSaver() {
        val mins = artSaverMinutes
        if (mins <= 0 || saverOn) return
        val idle = System.currentTimeMillis() - lastUserActivity
        val watching = mode != null && playback.isPlaying
        if (idle > mins * 60_000L && !watching && screen != Screen.Player && saverSlides.isNotEmpty()) saverOn = true
    }

    private fun checkStillWatching() {
        val hours = stillWatchingHours
        val t = System.currentTimeMillis()
        if (stillWatchingAsk) {
            if (t > stillWatchingDeadline) {
                stillWatchingAsk = false
                AppLog.info("Nobody answered \"Are you still watching?\" – stopped")
                sendHa("still_watching_timeout", mapOf("kind" to playKind(mode)))
                stopPlayback()
                goHome()
                showNotice("Stopped – nobody was watching")
            }
            return
        }
        if (hours <= 0 || mode == null || screen != Screen.Player || !playback.isPlaying) return
        if (t - lastUserActivity > hours * 3_600_000L) {
            stillWatchingAsk = true
            stillWatchingDeadline = t + 90_000L
        }
    }

    fun keepWatching() {
        stillWatchingAsk = false
        onUserActivity()
    }

    fun stopWatchingNow() {
        stillWatchingAsk = false
        stopPlayback()
        goHome()
    }

    fun cycleStillWatching() {
        val steps = listOf(0, 2, 3, 4, 6)
        stillWatchingHours = steps[(steps.indexOf(stillWatchingHours).coerceAtLeast(0) + 1) % steps.size]
        store.stillWatchingHours = stillWatchingHours
    }

    val stillWatchingLabel: String
        get() = if (stillWatchingHours <= 0) "Never" else "After $stillWatchingHours hours"

    // ---------------- Volume levelling & picture quality (5.4) ----------------

    val volumeDb: Int get() = playing?.let { volumeLevels[it.id]?.toIntOrNull() } ?: 0
    val volumeLabel: String get() = VolumeLevels.label(volumeDb)

    /** Steps the channel's volume level: Normal → +3 → +6 → +9 → −9 → −6 → −3 → Normal. */
    fun cycleVolumeLevel() {
        val ch = playing ?: return
        val next = VolumeLevels.next(volumeDb)
        volumeLevels = if (next == 0) volumeLevels - ch.id else volumeLevels + (ch.id to next.toString())
        store.volumeLevels = volumeLevels
        showNotice("${ch.name}: ${VolumeLevels.label(next)}" + (if (next > 0 && audioPassthrough) " (needs Audio passthrough off)" else ""))
    }

    val qualityMode: String get() = playing?.let { qualityModes[it.id] } ?: "auto"

    fun qualityLabel(q: String = qualityMode): String = when (q) {
        "best" -> "Best"
        "sd" -> "Data saver (SD)"
        else -> "Auto"
    }

    fun cycleQuality() {
        val ch = playing ?: return
        val next = when (qualityMode) {
            "auto" -> "best"
            "best" -> "sd"
            else -> "auto"
        }
        qualityModes = if (next == "auto") qualityModes - ch.id else qualityModes + (ch.id to next)
        store.qualityModes = qualityModes
        showNotice("${ch.name}: picture quality ${qualityLabel(next)} – only changes channels that offer more than one")
    }

    // ---------------- Followed teams in the guide (5.4) ----------------

    fun cycleSportFollow() {
        sportFollowMode = when (sportFollowMode) {
            "off" -> "remind"
            "remind" -> "record"
            else -> "off"
        }
        store.sportFollowMode = sportFollowMode
        showNotice(
            when (sportFollowMode) {
                "remind" -> "Followed teams: a reminder for each match"
                "record" -> "Followed teams: every match is recorded"
                else -> "Followed teams: nothing set automatically"
            }
        )
        applySportFollow()
    }

    val sportFollowLabel: String
        get() = when (sportFollowMode) {
            "remind" -> "Remind me"
            "record" -> "Record"
            else -> "Off"
        }

    /** Finds followed teams' matches in the guide and sets a reminder or recording (once per match). */
    private fun applySportFollow() {
        val teams = football.teams
        val modeNow = sportFollowMode
        if (teams.isEmpty() || modeNow == "off" || activeProfile.kids) return
        val chans = allowedAll
        val guide = epg
        val favs = favouriteSet
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { SportFollow.find(teams, chans, guide, favs, System.currentTimeMillis()) }
            if (found.isEmpty()) return@launch
            val done = store.sportAutoDone.toHashSet()
            val added = ArrayList<String>()
            for (m in found) {
                if (m.key in done) continue
                if (modeNow == "record") {
                    if (recordingFor(m.channel, m.programme) == null) recordProgramme(m.channel, m.programme)
                } else if (!hasReminder(m.channel, m.programme)) {
                    toggleReminder(m.channel, m.programme)
                }
                done += m.key
                added += m.key
            }
            if (added.isNotEmpty()) {
                store.sportAutoDone = store.sportAutoDone + added
                showNotice((if (modeNow == "record") "Recording " else "Reminders set for ") + "${added.size} match" + (if (added.size == 1) "" else "es") + " with your teams")
            }
        }
    }

    // ---------------- Film info (TMDB) & subtitles (OpenSubtitles) (5.4) ----------------

    fun saveTmdbKey(key: String?) {
        tmdbKey = key?.trim()?.takeIf { it.isNotEmpty() }
        store.tmdbKey = tmdbKey
        showNotice(if (tmdbKey != null) "TMDB key saved – hold OK on a film for its info" else "TMDB key removed")
    }

    fun saveOpenSubtitlesKey(key: String?) {
        openSubtitlesKey = key?.trim()?.takeIf { it.isNotEmpty() }
        store.openSubtitlesKey = openSubtitlesKey
        showNotice(if (openSubtitlesKey != null) "OpenSubtitles key saved – find subtitles from the player menu" else "OpenSubtitles key removed")
    }

    /** Opens the info card for a film or series (looked up on TMDB by name). */
    fun openFilmInfo(name: String, isSeries: Boolean) {
        val key = tmdbKey
        if (key == null) {
            showNotice("Add your free TMDB key in Settings › Films & series first")
            return
        }
        filmInfo = null
        filmInfoStatus = "Looking up $name…"
        filmInfoOpen = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, name, isSeries) } }
            r.onSuccess { info ->
                filmInfo = info
                filmInfoStatus = if (info == null) "Couldn't find \"${Tmdb.cleanTitle(name).first}\" on TMDB" else null
            }.onFailure { filmInfoStatus = it.message ?: "Couldn't reach TMDB" }
        }
    }

    fun closeFilmInfo() {
        filmInfoOpen = false
    }

    /** Opens the trailer in the YouTube app. */
    fun playTrailer(youtubeKey: String) {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeKey")),
        )
        for (i in intents) {
            try {
                app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        showNotice("Couldn't open YouTube on this TV")
    }

    /** Player menu › Find subtitles online: searches OpenSubtitles for the film/episode on screen. */
    fun findSubtitles() {
        val key = openSubtitlesKey
        val m = mode as? PlayMode.Vod
        if (key == null || m == null) {
            showNotice("Add your free OpenSubtitles key in Settings › Films & series first")
            return
        }
        val (title, year) = Tmdb.cleanTitle(m.title)
        val se = if (m.seriesId != null) OpenSubtitles.seasonEpisode(m.subtitle) else null
        val lang = subtitleLanguage ?: "en"
        subtitleStatus = "Searching for $lang subtitles…"
        subtitleHits = emptyList()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { OpenSubtitles.search(key, title, lang, if (se == null) year else null, se?.first, se?.second) }
            }
            r.onSuccess { hits ->
                subtitleHits = hits
                subtitleStatus = if (hits.isEmpty()) "No $lang subtitles found for $title" else null
            }.onFailure {
                subtitleHits = null
                subtitleStatus = null
                showNotice(it.message ?: "Couldn't reach OpenSubtitles")
            }
        }
    }

    fun useSubtitle(hit: OpenSubtitles.Hit) {
        val key = openSubtitlesKey ?: return
        subtitleHits = null
        subtitleStatus = null
        showNotice("Downloading subtitles…")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { OpenSubtitles.download(key, hit, File(app.cacheDir, "subs")) } }
            r.onSuccess { f ->
                playback.addSubtitleFile(f, hit.language)
                showNotice("Subtitles on")
            }.onFailure { showNotice(it.message ?: "Couldn't download the subtitles") }
        }
    }

    fun closeSubtitleHits() {
        subtitleHits = null
        subtitleStatus = null
    }

    // ---------------- Up next & skip intro (5.4) ----------------

    /** The episode that plays next (null for films, or the last episode). */
    val nextEpisode: Episode? get() = if (mode is PlayMode.Vod) episodeQueue.firstOrNull() else null

    private fun startNextEpisode(m: PlayMode.Vod) {
        val next = episodeQueue.firstOrNull() ?: return
        episodeQueue = episodeQueue.drop(1)
        startVod(
            PlayMode.Vod(next.id, m.title, "S${next.season} E${next.number}  ${next.title}", next.image ?: m.poster, next.url, m.seriesId),
            fromStart = true,
        )
    }

    /** "Play now" on the Up next card. */
    fun playNextEpisodeNow() {
        val m = mode as? PlayMode.Vod ?: return
        // This one's as good as finished – don't keep a resume point for it.
        resume = resume - m.contentId
        store.resume = resume
        startNextEpisode(m)
    }

    fun cancelAutoNext() {
        episodeQueue = emptyList()
        showNotice("Won't play the next episode")
    }

    /** Where this series' intro ends, if you've skipped it before. */
    fun introEndFor(): Long? {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return null
        return introEnds[sid]?.toLongOrNull()
    }

    /** "The intro ends here" from the player's menu. */
    fun setIntroEndHere(posMs: Long) {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        introEnds = introEnds + (sid to posMs.toString())
        store.introEnds = introEnds
        showNotice("Got it – every episode will offer to skip to here")
    }

    fun forgetIntro() {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        introEnds = introEnds - sid
        store.introEnds = introEnds
        showNotice("Intro forgotten – skip it once to teach Flicker again")
    }

    /** You skipped forward near the start of an episode and stayed there: remember it as the intro's end. */
    fun learnIntro(endMs: Long) {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        if (endMs !in 20_000L..300_000L) return
        val old = introEnds[sid]?.toLongOrNull()
        if (old != null && kotlin.math.abs(old - endMs) < 5_000L) return
        introEnds = introEnds + (sid to endMs.toString())
        store.introEnds = introEnds
        showNotice("Next episode: press ▶ to skip the intro")
    }

    // ---------------- Linked devices: send to TV, carry on where you left off ----------------

    /** What's playing here, ready to send to another device (null if it can't be sent). */
    private fun sendable(): JSONObject? {
        val pos = playback.positionMs
        return when (val m = mode) {
            is PlayMode.Live -> liveJson(m.channel)
            is PlayMode.CatchupPlay -> JSONObject().put("kind", "catchup").put("channel", m.channel.id)
                .put("name", m.channel.name).put("url", m.channel.url).put("number", m.channel.number)
                .put("start", m.programme.start).put("stop", m.programme.stop).put("title", m.programme.title)
                .put("category", m.programme.category).put("pos", pos)
            is PlayMode.Vod -> {
                // A download plays from this phone's storage: send the original instead.
                val url = if (m.url.startsWith("file:")) downloads.firstOrNull { it.contentId == m.contentId }?.sourceUrl ?: return null else m.url
                JSONObject().put("kind", "vod").put("id", m.contentId).put("title", m.title).put("sub", m.subtitle)
                    .put("poster", m.poster).put("url", url).put("series", m.seriesId).put("pos", pos)
            }
            is PlayMode.RecordingPlay, null -> null
        }
    }

    /** Sends what's playing to [peer], then stops it here. */
    fun sendTo(peer: com.titanejstudios.tuner.features.LinkFeature.Peer) {
        val what = sendable()
        if (what == null) {
            showNotice(if (mode is PlayMode.RecordingPlay) "Recordings stay on this device – they can't be sent" else "Nothing to send")
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        showNotice("Sending to ${peer.name}…")
        link.send(peer, what) { problem ->
            if (problem == null) {
                stopPlayback()
                if (screen == Screen.Player) back()
                showNotice("▶ Now playing on ${peer.name}")
            } else showNotice(problem)
        }
    }

    /** Another linked device sent something to play here. */
    private fun handleLinkPlay(from: String, j: JSONObject): Boolean {
        if (allChannels.isEmpty() && j.optString("kind") != "vod") return false
        val ok = when (j.optString("kind")) {
            "live" -> {
                val ch = linkedChannel(j) ?: return false
                resetTo(Screen.Home)
                play(ch, channelsFor(ALL))
                true
            }
            "catchup" -> {
                val ch = linkedChannel(j) ?: return false
                val start = j.optLong("start")
                val p = programmesFor(ch.id).firstOrNull { it.start == start } ?: Programme(
                    start = start, stop = j.optLong("stop"), title = j.optString("title"), desc = null,
                    category = j.optString("category").takeIf { it.isNotBlank() && it != "null" },
                )
                resetTo(Screen.Home)
                playCatchup(ch, p, channelsFor(ALL), startAt = j.optLong("pos"))
                true
            }
            "vod" -> {
                fun str(k: String) = j.optString(k).takeIf { it.isNotBlank() && it != "null" }
                val id = str("id") ?: return false
                val url = str("url") ?: return false
                episodeQueue = emptyList()
                resetTo(Screen.Home)
                startVod(PlayMode.Vod(id, j.optString("title"), str("sub"), str("poster"), url, str("series")), fromStart = false, startAt = j.optLong("pos"))
                true
            }
            else -> false
        }
        if (ok) {
            showNotice("▶ Sent from ${from.ifBlank { "another device" }}")
            if (!inForeground) {
                // Bring Flicker to the front (allowed because it may draw over other apps).
                runCatching {
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }
        return ok
    }

    /**
     * The channel another device means: the same id (playlists copied between them), else the
     * same stream address, else the same name (ignoring HD / FHD / 1080p labels).
     */
    private fun linkedChannel(j: JSONObject): Channel? {
        allChannelsById(j.optString("channel"))?.let { return it }
        val url = j.optString("url").takeIf { it.isNotBlank() && it != "null" }
        if (url != null) allChannels.firstOrNull { it.url == url }?.let { return it }
        val name = j.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return null
        allChannels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val key = com.titanejstudios.tuner.data.ChannelMerge.key(name)
        return allChannels.firstOrNull { com.titanejstudios.tuner.data.ChannelMerge.key(it.name) == key }
    }

    private fun applyLinkChanges(changed: Set<String>) {
        if (com.titanejstudios.tuner.integrations.LinkSync.INTROS in changed) introEnds = store.introEnds
        val lists = changed - com.titanejstudios.tuner.integrations.LinkSync.INTROS
        if (lists.isNotEmpty()) applySyncedChanges(lists)
        if (TvSync.RESUME in changed) {
            val entries = resume.values.toList()
            viewModelScope.launch(Dispatchers.IO) { HomeScreen.syncContinueWatching(app, store, entries) }
        }
    }

    // ---------------- Backup links: say which, and watch for the better one ----------------

    /** The player switched to a backup link: name it, and if it's a lower quality, keep checking the main one. */
    private fun onBackupLink(url: String) {
        val m = mode as? PlayMode.Live ?: return showNotice("Trying a backup link")
        val original = m.channel
        val alt = altIndex[StreamAlternates.nameKey(original.name)]?.firstOrNull { it.url == url }
        if (alt == null) {
            // The same stream in its other format: same quality, nothing to watch for.
            showNotice("${original.name} wasn't working – trying it another way")
            return
        }
        val label = ChannelMerge.versionLabel(alt).ifBlank { alt.name }
        showNotice("${original.name} isn't working – switched to $label")
        if (ChannelMerge.quality(alt.name) < ChannelMerge.quality(original.name)) watchForUpgrade(original)
    }

    /**
     * While you watch the lower-quality backup, checks every 2 minutes (for up to 3 hours) whether
     * the channel's own stream works again, and says so once.
     */
    private fun watchForUpgrade(original: Channel) {
        upgradeJob?.cancel()
        if (original.id in upgradeTold) return
        upgradeJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 3 * 3_600_000L) {
                delay(2 * 60_000L)
                val m = mode as? PlayMode.Live ?: return@launch
                if (m.channel.id != original.id || playback.currentUrl == original.url) return@launch
                if (!inForeground) continue
                if (withContext(Dispatchers.IO) { streamWorks(original.url) }) {
                    upgradeTold += original.id
                    qualityBack = original
                    AppLog.info("Main stream working again: ${original.name}")
                    delay(20_000)
                    if (qualityBack?.id == original.id) qualityBack = null
                    return@launch
                }
            }
        }
    }

    /** A quick look at a stream: does the server send video back? (Opens and closes a connection.) */
    private fun streamWorks(url: String): Boolean = runCatching {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", com.titanejstudios.tuner.data.Http.USER_AGENT)
        }
        try {
            c.responseCode in 200..299 && c.inputStream.use { it.read(ByteArray(2048)) > 0 }
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** "▲ BBC One FHD is working again" – switch back to it. */
    fun switchToBetterQuality() {
        val c = qualityBack ?: return
        qualityBack = null
        val m = mode as? PlayMode.Live ?: return
        if (m.channel.id == c.id) startLive(c, force = true)
    }

    fun dismissQualityBack() {
        qualityBack = null
    }

    // ---------------- Christmas TV planner ----------------

    /** From late November to New Year: Home offers the planner. */
    val christmasSeason: Boolean
        get() {
            val c = java.util.Calendar.getInstance()
            val m = c.get(java.util.Calendar.MONTH)
            val d = c.get(java.util.Calendar.DAY_OF_MONTH)
            return (m == java.util.Calendar.NOVEMBER && d >= 20) || m == java.util.Calendar.DECEMBER || (m == java.util.Calendar.JANUARY && d <= 2)
        }

    /**
     * Festive specials, Christmas films and the big sport (the darts, and your teams) from now to
     * 2 January, from the stored guide. Guides only reach about a week ahead, so it fills up as
     * Christmas gets nearer.
     */
    fun loadChristmas() {
        val ids = activeSources().map { it.id }
        val chans = allowedAll
        val favs = favouriteSet
        val teams = football.teams.toList()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val c = java.util.Calendar.getInstance()
            val year = if (c.get(java.util.Calendar.MONTH) == java.util.Calendar.JANUARY) c.get(java.util.Calendar.YEAR) - 1 else c.get(java.util.Calendar.YEAR)
            c.clear()
            c.set(year + 1, java.util.Calendar.JANUARY, 3, 0, 0, 0)
            val to = c.timeInMillis
            val from = now - 3 * 3_600_000L
            val byId = chans.associateBy { it.id }
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    val festiveWords = listOf("christmas", "xmas", "festive", "new year", "hogmanay", "santa", "snowman", "nativity", "carol", "boxing day")
                    val specials = db.findInWindow(ids, from, to, festiveWords, limit = 1500)
                    val films = db.findInWindow(
                        ids, from, to,
                        titleWords = festiveWords + listOf("elf", "grinch", "scrooge", "home alone", "polar express", "nutcracker", "reindeer", "jingle", "noel", "holiday"),
                        descWords = listOf("christmas", "santa", "festive", "xmas"),
                        categoryWords = listOf("film", "movie"),
                        limit = 1500,
                    )
                    val sport = db.findInWindow(ids, from, to, listOf("darts") + teams, limit = 1500)
                    val out = LinkedHashMap<String, FestiveItem>()
                    fun add(list: List<Pair<String, Programme>>, section: String, accept: (Programme) -> Boolean) {
                        // Favourite channels first, so the copy kept of a programme is on one you use.
                        for ((id, p) in list.sortedBy { if (it.first in favs) 0 else 1 }) {
                            val ch = byId[id] ?: continue
                            if (!accept(p)) continue
                            val key = p.title.lowercase().trim() + "|" + p.start
                            if (key !in out) out[key] = FestiveItem(ch, p, section)
                        }
                    }
                    val isFilm = { p: Programme -> (p.category ?: "").lowercase().let { "film" in it || "movie" in it } }
                    add(films, "films") { true }
                    add(specials, "specials") { !isFilm(it) && it.stop - it.start >= 20 * 60_000L && Genres.of(it.category, it.title, null) != "News" }
                    add(sport, "sport") { Sports.classify(it) != null && Sports.looksLive(it) }
                    out.values.sortedBy { it.programme.start }
                }.getOrDefault(emptyList())
            }
            festive = items
        }
    }

    // ---------------- Missed it? & finish what you started ----------------

    /** Ended programmes from the last week on catch-up channels that suit your taste, best first. */
    private fun missedPicks(chans: List<Channel>, guide: Map<String, List<Programme>>, learned: Taste, t: Long): List<HomeFeed.Pick> {
        val best = HashMap<String, Pair<Double, HomeFeed.Pick>>()
        for (c in chans) {
            if (!c.hasCatchup) continue
            val from = t - c.catchupDays.coerceIn(1, 7) * 86_400_000L
            for (p in guide[c.id].orEmpty()) {
                if (p.stop > t || p.start < from || p.stop - p.start < 20 * 60_000L) continue
                val genre = Genres.of(p.category, p.title, c.group)
                if (genre == "News") continue
                val (score, reason) = learned.score(p.title, genre, t)
                if (score < 5.0) continue
                val key = p.title.lowercase().trim()
                val old = best[key]
                // The best-suited, and of equals the most recent showing.
                if (old == null || score > old.first || (score == old.first && p.start > old.second.programme.start)) {
                    best[key] = score to HomeFeed.Pick(c, p, reason ?: "On catch-up")
                }
            }
        }
        return best.values.sortedByDescending { it.first }.take(20).map { it.second }
    }

    private fun loadUnfinished(): List<Unfinished> = runCatching {
        val arr = org.json.JSONArray(store.unfinishedJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Unfinished(
                o.getString("ch"),
                Programme(o.getLong("s"), o.getLong("e"), o.getString("t"), null, o.optString("c").takeIf { it.isNotBlank() && it != "null" }),
                o.getLong("pos"), o.getLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveUnfinished(list: List<Unfinished>) {
        // Keep the newest 10, and only while catch-up still has them (a week at most).
        val t = System.currentTimeMillis()
        val keep = list.filter { it.programme.start > t - 7 * 86_400_000L }.sortedByDescending { it.at }.take(10)
        unfinished = keep
        val arr = org.json.JSONArray()
        keep.forEach {
            arr.put(JSONObject().put("ch", it.channelId).put("s", it.programme.start).put("e", it.programme.stop)
                .put("t", it.programme.title).put("c", it.programme.category).put("pos", it.positionMs).put("at", it.at))
        }
        store.unfinishedJson = arr.toString()
    }

    /** The live programme you were watching changed or stopped: switched off half-way? */
    private fun finishLiveWatch() {
        val w = liveWatch ?: return
        liveWatch = null
        if (w.seconds < 10 * 60) return
        val p = programmesFor(w.channelId).firstOrNull { it.start == w.start } ?: return
        // Watched to (nearly) the end, or too short a programme to bother.
        if (w.lastSeen >= p.stop - 5 * 60_000L || p.stop - p.start < 25 * 60_000L) {
            if (unfinished.any { it.channelId == w.channelId && it.programme.start == w.start }) {
                saveUnfinished(unfinished.filterNot { it.channelId == w.channelId && it.programme.start == w.start })
            }
            return
        }
        val ch = allChannelsById(w.channelId) ?: return
        if (catchupVersion(ch) == null) return
        val pos = (w.lastSeen - p.start - 30_000L).coerceAtLeast(0L)
        saveUnfinished(listOf(Unfinished(w.channelId, p, pos, System.currentTimeMillis())) +
            unfinished.filterNot { it.channelId == w.channelId && it.programme.start == w.start })
    }

    /** Programmes you can still finish (catch-up has them). */
    val finishable: List<Unfinished>
        get() = unfinished.filter { u -> allChannelsById(u.channelId)?.let { canCatchup(it, u.programme) } == true }

    fun finishUnfinished(u: Unfinished) {
        val ch = allChannelsById(u.channelId) ?: return
        saveUnfinished(unfinished.filterNot { it === u })
        playCatchup(ch, u.programme, channelsFor(ALL), startAt = u.positionMs)
    }

    fun forgetUnfinished(u: Unfinished) {
        saveUnfinished(unfinished.filterNot { it === u })
    }

    fun playMissed(pick: HomeFeed.Pick) {
        playCatchup(pick.channel, pick.programme, channelsFor(ALL))
    }

    // ---------------- My teams: next fixtures and which channel ----------------

    /** The next fixture in the coming week (for Home). */
    val nextTeamFixture: TeamFixture?
        get() {
            val t = System.currentTimeMillis()
            return teamFixtures.firstOrNull { it.match.kickoff > t - 2 * 3_600_000L && it.match.kickoff < t + 7 * 86_400_000L }
        }

    /** Match day: one of your teams plays within the next 12 hours (or is playing now). */
    val matchDay: TeamFixture?
        get() {
            val t = now
            return teamFixtures.firstOrNull { it.match.live || it.match.kickoff in (t - 2 * 3_600_000L)..(t + 12 * 3_600_000L) }
                ?.takeIf { !it.match.finished }
        }

    /** Watch the match if it's about to start (or on); otherwise open My teams. */
    fun openMatchDay(f: TeamFixture) {
        if (f.channel != null && (f.match.live || f.match.kickoff - now <= 15 * 60_000L)) watchFixture(f) else navigate(Screen.Teams)
    }

    fun refreshTeamFixtures(force: Boolean = false) {
        if (football.key == null || football.teams.isEmpty()) {
            teamFixtures = emptyList()
            return
        }
        viewModelScope.launch {
            football.loadFixtures(force)
            matchFixtureChannels()
        }
    }

    fun loadTeamList() {
        viewModelScope.launch { football.loadTeamList() }
    }

    /** Looks in the guide around each kick-off for a channel showing it. */
    private suspend fun matchFixtureChannels() {
        val now = System.currentTimeMillis()
        val list = football.fixtures.take(12)
        if (list.isEmpty()) {
            teamFixtures = emptyList()
            return
        }
        val ids = activeSources().map { it.id }
        val chans = allowedAll
        val favs = favouriteSet
        teamFixtures = withContext(Dispatchers.IO) {
            list.map { m ->
                if (m.kickoff > now + 21 * 86_400_000L) return@map TeamFixture(m, null, null, false)
                val guide = runCatching {
                    shiftGuide(chans, db.loadEpg(ids, m.kickoff - 90 * 60_000L, m.kickoff + 30 * 60_000L, emptyMap()))
                }.getOrDefault(emptyMap())
                val b = Football.broadcastFor(m, chans, guide, favs)
                TeamFixture(m, b?.first, b?.second, guide.values.any { it.isNotEmpty() })
            }
        }
        // "Newcastle v Arsenal today at 17:30 on Sky Sports Main Event" – once per match.
        val today = teamFixtures.firstOrNull { it.channel != null && it.match.kickoff in now..(now + 14 * 3_600_000L) }
        if (today != null && today.match.id.toString() !in store.teamAnnounced) {
            store.teamAnnounced = (store.teamAnnounced + today.match.id.toString()).toList().takeLast(50).toSet()
            val ch = today.channel
            showNotice("⚽ ${today.match.home} v ${today.match.away} today at ${hhmm(today.match.kickoff)} on ${ch?.name}")
        }
    }

    fun watchFixture(f: TeamFixture) {
        val ch = f.channel ?: return
        play(ch, channelsFor(ALL))
    }

    // ---------------- Your usual channel ----------------

    /** Is there a channel you normally watch around now? Offered on Home when Flicker opens. */
    fun checkUsual() {
        usualOffer = null
        if (!offerUsual || mode != null || allChannels.isEmpty()) return
        val id = com.titanejstudios.tuner.data.Habits.usual(store.habits(activeProfile.id), System.currentTimeMillis()) ?: return
        val ch = allChannelsById(id) ?: return
        if (!isAllowed(ch)) return
        usualOffer = ch
    }

    fun playUsual() {
        val ch = usualOffer ?: return
        usualOffer = null
        play(ch, channelsFor(ALL))
    }

    fun toggleScrubPreviews() {
        scrubPreviewsIptv = !scrubPreviewsIptv
        store.scrubPreviewsIptv = scrubPreviewsIptv
    }

    fun toggleOfferUsual() {
        offerUsual = !offerUsual
        store.offerUsual = offerUsual
        if (offerUsual) checkUsual() else usualOffer = null
    }

    // ---------------- Home-screen widget (phones) ----------------

    /** Saves your first four favourites with the next few hours of their guide for the "On now" widget. */
    private fun updateWidget() {
        if (!isPhone || allChannels.isEmpty()) return
        val now = System.currentTimeMillis()
        val chs = favourites.mapNotNull { allChannelsById(it) }.take(4)
        val snap = com.titanejstudios.tuner.tv.NowWidget.snapshot(
            chs.map { c ->
                Triple(c.id, c.name, programmesFor(c.id).filter { it.stop > now && it.start < now + 12 * 3600_000L }.take(12).map { Triple(it.start, it.stop, it.title) })
            }
        )
        if (snap == store.widgetSnapshot) return
        store.widgetSnapshot = snap
        com.titanejstudios.tuner.tv.NowWidget.refresh(app)
    }

    // ---------------- Downloads (phones) ----------------

    fun downloadMovie(m: VodItem) {
        showNotice(downloader.start(m.id, m.name, null, m.poster, null, m.url))
    }

    fun downloadEpisode(e: Episode) {
        val s = openSeries
        showNotice(downloader.start(e.id, s?.name ?: e.title, "S${e.season} E${e.number}  ${e.title}", e.image ?: s?.cover, s?.id, e.url))
    }

    fun playDownload(d: com.titanejstudios.tuner.features.DownloadEntry) {
        if (!java.io.File(d.file).exists()) {
            showNotice("That download is missing – it may have been deleted")
            return
        }
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(d.contentId, d.title, d.subtitle, d.poster, Uri.fromFile(java.io.File(d.file)).toString(), d.seriesId), fromStart = false)
    }

    fun removeDownload(contentId: String) {
        downloader.remove(contentId)
        showNotice("Download deleted")
    }

    // ---- Pick on the phone, watch on the TV ----

    /** Something waiting for you to choose which TV it goes to (more than one found). */
    var tvChoice by mutableStateOf<JSONObject?>(null); private set

    // A channel is sent with its id, stream address and name: devices that added the playlist
    // separately give the same channel different ids, so the other device matches on the rest.
    fun liveJson(ch: Channel): JSONObject = JSONObject().put("kind", "live").put("channel", ch.id)
        .put("name", ch.name).put("url", ch.url).put("number", ch.number)

    fun filmJson(m: VodItem): JSONObject = JSONObject().put("kind", "vod").put("id", m.id).put("title", m.name)
        .put("sub", m.category).put("poster", m.poster).put("url", m.url).put("pos", resume[m.id]?.positionMs ?: 0L)

    fun episodeJson(e: Episode): JSONObject {
        val s = openSeries
        return JSONObject().put("kind", "vod").put("id", e.id).put("title", s?.name ?: e.title)
            .put("sub", "S${e.season} E${e.number}  ${e.title}").put("poster", e.image ?: s?.cover).put("url", e.url)
            .put("series", s?.id).put("pos", resume[e.id]?.positionMs ?: 0L)
    }

    fun catchupJson(ch: Channel, p: Programme): JSONObject = JSONObject().put("kind", "catchup").put("channel", ch.id)
        .put("name", ch.name).put("url", ch.url).put("number", ch.number)
        .put("start", p.start).put("stop", p.stop).put("title", p.title).put("category", p.category).put("pos", 0L)

    /** Plays [what] on a linked TV (asks which, if there are several). */
    fun watchOnTv(what: JSONObject) {
        if (!link.linked) {
            showNotice("Link your TV first: Settings › Linked devices")
            return
        }
        fun go() {
            val tvs = link.tvs
            when {
                tvs.isEmpty() -> showNotice("No TV found – is Flicker open on it?")
                tvs.size == 1 -> sendToTv(tvs[0], what)
                else -> tvChoice = what
            }
        }
        if (link.tvs.isEmpty()) {
            showNotice("Looking for your TV…")
            link.search { go() }
        } else go()
    }

    fun sendToTv(tv: com.titanejstudios.tuner.features.LinkFeature.Peer, what: JSONObject) {
        tvChoice = null
        link.send(tv, what) { problem -> showNotice(problem ?: "▶ Playing on ${tv.name}") }
    }

    fun cancelTvChoice() {
        tvChoice = null
    }

    fun linkShowCode() = link.showCode()
    fun linkFindCodes() = link.findCodes()

    // ---------------- Keeping TVs in sync & NAS backups (5.4) ----------------

    fun toggleSync() {
        if (!syncEnabled && nasUrl == null) {
            showNotice("Set up a network drive first (Settings › Recordings › Network drive)")
            return
        }
        syncEnabled = !syncEnabled
        store.syncEnabled = syncEnabled
        if (syncEnabled) {
            syncStatus = "Syncing…"
            startSync()
        } else {
            syncJob?.cancel()
            syncStatus = null
        }
    }

    /** Remember that a list changed here, and sync it soon. */
    private fun markChanged(section: String) {
        // Stamped even without the network drive: linked devices use it to see which list is newest.
        store.setSyncStamp(section, System.currentTimeMillis())
        if (!syncEnabled) return
        scheduleSync(15_000L)
    }

    private fun scheduleSync(delayMs: Long) {
        if (!syncEnabled || SafeMode.active) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(delayMs)
            syncNow()
            // Then every 10 minutes while Tuner is open.
            while (true) {
                delay(10 * 60_000L)
                if (inForeground) syncNow()
            }
        }
    }

    private fun startSync() = scheduleSync(2_000L)

    private suspend fun syncNow() {
        val r = withContext(Dispatchers.IO) { runCatching { TvSync(store).sync() } }
        r.onSuccess { out ->
            syncStatus = "Synced at ${hhmm(System.currentTimeMillis())}"
            if (out.changedHere.isNotEmpty()) applySyncedChanges(out.changedHere)
        }.onFailure {
            syncStatus = "Couldn't reach the network drive: ${it.message ?: it.javaClass.simpleName}"
            AppLog.warn("TV sync failed", it)
        }
    }

    private fun applySyncedChanges(changed: Set<String>) {
        if (TvSync.FAVOURITES in changed) {
            favourites = store.favourites(activeProfile.id)
            favouriteSet = favourites.toSet()
            publishFavouritesRow()
        }
        if (TvSync.WATCHLIST in changed) watchlist = Watchlist.decode(store.watchlistJson)
        if (TvSync.FOLLOWED in changed) followedSeries = Watchlist.decodeFollowed(store.followedSeriesJson)
        if (TvSync.RESUME in changed) resume = store.resume
        AppLog.info("Synced from another TV: " + changed.joinToString())
    }

    fun toggleNasBackup() {
        nasBackupOn = !nasBackupOn
        store.nasBackup = nasBackupOn
    }

    fun backupToNasNow() {
        if (nasUrl == null) {
            showNotice("Set up a network drive first (Settings › Recordings › Network drive)")
            return
        }
        showNotice("Backing up to the network drive…")
        viewModelScope.launch {
            val name = android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL
            val r = withContext(Dispatchers.IO) { runCatching { NasBackup.save(store, name) } }
            r.onSuccess { showNotice("Backed up as ${it ?: "?"} in \"Flicker backups\"") }
                .onFailure { showNotice("Backup failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    val nasBackupLabel: String
        get() = when {
            nasUrl == null -> "Needs a network drive"
            !nasBackupOn -> "Off"
            store.nasBackupAt == 0L -> "Nightly"
            else -> "Nightly · last ${dayLabel(store.nasBackupAt)}"
        }

    // ---------------- Home Assistant: "what's on?" (5.4) ----------------

    override fun whatsOnJson(kind: String, time: String): String {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        fun at(h: Int, dayOffset: Int = 0): Long {
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        val w = time.trim().lowercase(Locale.UK)
        val (whenAt, whenLabel) = when {
            w.isEmpty() || w == "now" -> now to "now"
            w == "tonight" -> (if (now < at(21)) at(21) else now) to "tonight"
            w == "tomorrow" -> at(20, 1) to "tomorrow night"
            Regex("^(\\d{1,2})\\s*pm$").matches(w) -> {
                val h = w.filter { it.isDigit() }.toInt() % 12 + 12
                (if (at(h) < now) at(h, 1) else at(h)) to "at ${h - 12}pm"
            }
            else -> now to "now"
        }
        val k = kind.trim().lowercase(Locale.UK)
        val genre = when {
            k.isEmpty() || k == "anything" -> null
            k.startsWith("sport") || k == "football" || k == "rugby" || k == "cricket" -> "Sport"
            k.startsWith("film") || k.startsWith("movie") -> "Film"
            k.startsWith("news") -> "News"
            k.startsWith("kid") || k.startsWith("child") || k.startsWith("cartoon") -> "Kids"
            k.startsWith("doc") || k.startsWith("factual") -> "Factual"
            k.startsWith("drama") -> "Drama"
            k.startsWith("music") -> "Music"
            else -> "Entertainment"
        }
        val chans = allowedAll
        val guide = epg
        var picks = WhatsOn.list(chans, guide, whenAt, genre, favouriteSet, favouritesOnly = false, max = 40)
        // "What football's on?" – narrow sport down to that sport when the guide says.
        if (k == "football" || k == "rugby" || k == "cricket") {
            picks = picks.filter { (Sports.classify(it.programme) ?: "").lowercase(Locale.UK).contains(k) }
        }
        val items = org.json.JSONArray()
        picks.take(20).forEach { pk ->
            items.put(
                org.json.JSONObject().put("channel", pk.channel.name).put("channel_id", pk.channel.id)
                    .put("number", channelNumberFor(pk.channel)).put("title", pk.programme.title)
                    .put("start", pk.programme.start / 1000).put("stop", pk.programme.stop / 1000)
                    .put("category", pk.programme.category)
            )
        }
        val top = picks.take(4)
        val what = if (k.isEmpty()) "" else "$k "
        val speech = if (top.isEmpty()) "I couldn't find any ${what}on $whenLabel."
        else "On $whenLabel: " + top.joinToString("; ") { "${it.programme.title} on ${it.channel.name}" } +
            (if (picks.size > top.size) ", and ${picks.size - top.size} more." else ".")
        return org.json.JSONObject().put("when", whenLabel).put("kind", k).put("items", items).put("speech", speech).toString()
    }

    private fun channelNumberFor(c: Channel): Int =
        if (c.number > 0) c.number else allowedAll.indexOfFirst { it.id == c.id } + 1

    // ---------------- Update pop-up ----------------

    /** The update to offer now – never over full-screen video. */
    val updateOffer: com.titanejstudios.tuner.apps.Updater.Release?
        get() = if (screen == Screen.Player || screen == Screen.MultiView || inPip) null else updates.offered

    fun installUpdateNow() {
        showNotice("Downloading the update…")
        updates.installOffered()
    }

    fun updateLater() = updates.later()

    // ---------------- Open on start-up ----------------

    var openOnBoot by mutableStateOf(store.openOnBoot); private set

    val canOpenOnBoot: Boolean
        get() = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(app)

    /** Settings › Open Tuner when the TV turns on. Asks for "Display over other apps" if needed. */
    fun toggleOpenOnBoot() {
        openOnBoot = !openOnBoot
        store.openOnBoot = openOnBoot
        if (!openOnBoot) {
            showNotice("Flicker won't open by itself any more")
            return
        }
        if (canOpenOnBoot) {
            showNotice("Flicker will open when the TV starts up")
            return
        }
        // Android only lets apps open themselves at start-up with this permission.
        val asked = runCatching {
            app.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        showNotice(
            if (asked) "Turn on \"Allow display over other apps\" for Flicker, then press BACK"
            else "This TV hides that setting – see the README (Open on start-up) for the one-line fix"
        )
    }
}

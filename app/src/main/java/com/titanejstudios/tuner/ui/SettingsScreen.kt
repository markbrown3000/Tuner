package com.titanejstudios.tuner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ALL_SOURCES
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Profile
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: TunerViewModel) {
    var menuFor by remember { mutableStateOf<Source?>(null) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    var profileMenu by remember { mutableStateOf<Profile?>(null) }
    /** Network drive set-up: 1 = address, 2 = user name, 3 = password. */
    var nasStep by remember { mutableStateOf(0) }
    /** Typing a key: "tmdb" or "subs". */
    var askKey by remember { mutableStateOf<String?>(null) }
    val restorer = rememberFocusRestorer()
    RestoreFocusWhenClosed(menuFor != null || confirmDelete != null || profileMenu != null, restorer)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = 64.dp, vertical = 32.dp)
    ) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionTitle("Playlists") }
            if (vm.sources.size > 1) {
                item {
                    SettingRow(
                        title = "Use all playlists together",
                        detail = if (vm.activeSourceId == ALL_SOURCES) "✓ In use" else null,
                        modifier = Modifier.focusRequester(first),
                        onClick = { vm.selectSource(ALL_SOURCES) },
                    )
                }
            }
            items(vm.sources, key = { it.id }) { s ->
                val inUse = vm.activeSourceId == s.id || (vm.activeSourceId == ALL_SOURCES && vm.sources.size == 1)
                SettingRow(
                    title = s.name,
                    subtitle = when (s) {
                        is Source.M3u -> "M3U playlist"
                        is Source.Xtream -> "Xtream Codes · ${s.server}"
                    },
                    detail = if (inUse) "✓ In use" else null,
                    modifier = (if (vm.sources.size == 1) Modifier.focusRequester(first) else Modifier).then(restorer.mod(s.id)),
                    onClick = {
                        restorer.key = s.id
                        menuFor = s
                    },
                )
            }
            item { SettingRow("Add a playlist", onClick = { vm.startAddSource() }) }
            item {
                SettingRow(
                    "Refresh channels and TV guide now",
                    subtitle = vm.status ?: "Also refreshes automatically every night at about 4am",
                    onClick = { vm.refresh() },
                )
            }
            item {
                SettingRow(
                    "Update when Tuner starts",
                    subtitle = if (vm.refreshOnStart) "If the saved copy is over 12 hours old (as well as overnight)"
                    else "Overnight only – Tuner opens straight onto the saved channels",
                    detail = if (vm.refreshOnStart) "On" else "Off",
                    onClick = { vm.toggleRefreshOnStart() },
                )
            }

            item {
                SettingRow(
                    "📱 Set up from your phone",
                    subtitle = "Add playlists, Home Assistant webhook, backup and restore – no typing on the TV",
                    onClick = { vm.navigate(Screen.PhoneSetup) },
                )
            }

            item { SectionTitle("Profiles") }
            items(vm.profiles, key = { "profile-" + it.id }) { p ->
                SettingRow(
                    (if (p.kids) "🧸 " else "👤 ") + p.name,
                    subtitle = if (p.kids) {
                        "Kids · ${p.allowedGroups.size} groups" + (if (p.dailyLimitMinutes > 0) " · ${p.dailyLimitMinutes} min a day" else " · no time limit")
                    } else null,
                    detail = if (p.id == vm.activeProfile.id) "✓ Watching" else null,
                    modifier = restorer.mod("profile-" + p.id),
                    onClick = {
                        restorer.key = "profile-" + p.id
                        profileMenu = p
                    },
                )
            }
            item { SettingRow("Add a profile", subtitle = "Each profile has its own favourites; kids profiles can have limits", onClick = { vm.startAddProfile() }) }
            item {
                SettingRow(
                    "When Tuner opens",
                    subtitle = "Home, the TV Guide on the category and channel you left, or straight into the last channel",
                    detail = vm.startScreenLabel,
                    onClick = { vm.cycleStartScreen() },
                )
            }

            item {
                SettingRow(
                    "VPN",
                    subtitle = "Tuner's own VPN (Surfshark and other WireGuard providers) – only Tuner's streams use it",
                    detail = when {
                        vm.vpn.connected -> "🔒 Connected"
                        vm.vpn.enabled -> "On – not connected"
                        else -> "Off"
                    },
                    onClick = { vm.navigate(Screen.Vpn) },
                )
            }

            item {
                SettingRow(
                    "Recommendations",
                    subtitle = "What Tuner has learned you like – and a reset if it gets it wrong",
                    detail = if (vm.learnTaste) "Learning" else "Off",
                    onClick = { vm.navigate(Screen.Recommendations) },
                )
            }

            item {
                SettingRow(
                    "Languages & countries",
                    subtitle = "Hide everything that isn't in English – or pick countries one by one",
                    detail = if (vm.hiddenLanguages.isEmpty()) "Everything" else "${vm.hiddenLanguages.size} hidden",
                    onClick = { vm.navigate(Screen.Languages) },
                )
            }

            item {
                SettingRow(
                    "Home rows",
                    subtitle = "Show, hide and reorder Home's rows – and whether they change with the time of day",
                    detail = if (vm.homeRowsHidden.isEmpty()) "All shown" else "${vm.homeRowsHidden.size} hidden",
                    onClick = { vm.navigate(Screen.HomeRows) },
                )
            }

            item {
                SettingRow(
                    "Open Tuner when the TV turns on",
                    subtitle = if (vm.openOnBoot && !vm.canOpenOnBoot) "Needs \"Display over other apps\" allowed – press to set it"
                    else "After the TV fully restarts (not when it wakes from standby)",
                    detail = if (vm.openOnBoot) "On" else "Off",
                    onClick = { vm.toggleOpenOnBoot() },
                )
            }

            item { SectionTitle("Playback") }
            item {
                SettingRow(
                    "Combine HD / SD copies of a channel",
                    subtitle = "Shows BBC One once and plays the best quality – change version from the player menu",
                    detail = if (vm.mergeVersions) "On" else "Off",
                    onClick = { vm.toggleMergeVersions() },
                )
            }
            item {
                SettingRow(
                    "Are you still watching?",
                    subtitle = "Asks after this long with no button pressed, then stops – saves your connection overnight",
                    detail = vm.stillWatchingLabel,
                    onClick = { vm.cycleStillWatching() },
                )
            }
            item {
                SettingRow(
                    "Pause live TV to storage (timeshift)",
                    subtitle = "Pausing keeps recording, so you can resume, rewind or skip – up to 90 minutes",
                    detail = if (vm.timeshiftEnabled) "On" else "Off",
                    onClick = { vm.toggleTimeshift() },
                )
            }
            item {
                SettingRow(
                    "Fast channel switching",
                    subtitle = "Keeps the next channel ready. Uses a 2nd connection – only if your provider allows 2+",
                    detail = if (vm.fastSwitch) "On" else "Off",
                    onClick = { vm.toggleFastSwitch() },
                )
            }

            item {
                SettingRow(
                    "Audio passthrough (Dolby to soundbar/amp)",
                    subtitle = "Leave off if the picture freezes while sound carries on · applies after reopening Tuner",
                    detail = if (vm.audioPassthrough) "On" else "Off",
                    onClick = { vm.toggleAudioPassthrough() },
                )
            }
            item {
                SettingRow(
                    "Stream compatibility mode",
                    subtitle = "Only for channels that never start · applies after reopening Tuner",
                    detail = if (vm.streamCompatibility) "On" else "Off",
                    onClick = { vm.toggleStreamCompatibility() },
                )
            }
            item {
                SettingRow(
                    "Match the TV's frame rate",
                    subtitle = "Switches the TV to 50 Hz for UK channels, 24 Hz for films… smoother motion. The screen may blank for a second on channel change",
                    detail = if (vm.frameRateMatch) "On" else "Off",
                    onClick = { vm.toggleFrameRateMatch() },
                )
            }

            item { SectionTitle("Sound & subtitles") }
            item {
                SettingRow(
                    "Night mode",
                    subtitle = "Quieter loud bits, clearer speech · needs Audio passthrough off",
                    detail = if (vm.nightMode) "On" else "Off",
                    onClick = { vm.toggleNightMode() },
                )
            }
            item { SettingRow("Subtitle size", detail = vm.subtitleSizeLabel, onClick = { vm.cycleSubtitleSize() }) }
            item {
                SettingRow(
                    "Subtitle background",
                    subtitle = "A dark box behind subtitles – easier to read over bright pictures",
                    detail = if (vm.subtitleBox) "On" else "Off",
                    onClick = { vm.toggleSubtitleBox() },
                )
            }
            item { SettingRow("Sleep timer", subtitle = "Also in the player's menu (hold OK)", detail = vm.sleepLabel, onClick = { vm.setSleepTimer(if (vm.sleepAt == 0L) 60 else 0) }) }

            item { SectionTitle("Look") }
            item {
                val accents = TunerColors.accents
                val i = accents.indexOfFirst { it.first == vm.themeAccent }
                SettingRow("Highlight colour", detail = accents.getOrNull(i)?.second ?: "Sky blue", onClick = { vm.setTheme(accent = accents[(i + 1).mod(accents.size)].first) })
            }
            item {
                val bgs = TunerColors.backgrounds
                val i = bgs.indexOfFirst { it.first == vm.themeBackground }
                SettingRow("Background", detail = bgs.getOrNull(i)?.second ?: "Evening", onClick = { vm.setTheme(background = bgs[(i + 1).mod(bgs.size)].first) })
            }
            item {
                SettingRow(
                    "Colours follow the picture",
                    subtitle = "Home, Films & TV and Plex take on the colours of what's highlighted",
                    detail = if (vm.tintFromPicture) "On" else "Off",
                    onClick = { vm.toggleTintFromPicture() },
                )
            }
            item {
                SettingRow(
                    "Artwork screensaver",
                    subtitle = "When nothing's playing: your Plex and programme pictures with the time. OK watches what's showing.",
                    detail = if (vm.artSaverMinutes == 0) "Off" else "After ${vm.artSaverMinutes} min",
                    onClick = { vm.cycleArtSaver() },
                )
            }

            item {
                SettingRow("Sound language", subtitle = "Picked automatically when a channel or film has several", detail = vm.languageName(vm.audioLanguage), onClick = { vm.cycleAudioLanguage() })
            }
            item {
                SettingRow(
                    "Subtitles language",
                    subtitle = "Subtitles in this language switch on by themselves when there are any",
                    detail = if (vm.subtitleLanguage == null) "Off" else vm.languageName(vm.subtitleLanguage),
                    onClick = { vm.cycleSubtitleLanguage() },
                )
            }

            item { SectionTitle("Films & series") }
            item {
                SettingRow(
                    "Film info (TMDB key)",
                    subtitle = "Free from themoviedb.org › Settings › API. Gives posters, plots, cast and trailers",
                    detail = if (vm.tmdbKey != null) "Set" else "Not set",
                    onClick = { askKey = "tmdb" },
                )
            }
            item {
                SettingRow(
                    "Subtitles online (OpenSubtitles key)",
                    subtitle = "Free from opensubtitles.com › API consumers. Then: player menu › Find subtitles online",
                    detail = if (vm.openSubtitlesKey != null) "Set" else "Not set",
                    onClick = { askKey = "subs" },
                )
            }

            item { SectionTitle("Plex, scores & updates") }
            item {
                SettingRow(
                    "Your teams' matches",
                    subtitle = "Follow teams on the Football screen – Tuner finds their matches in the guide, pops up 5 minutes before kick-off (sport recordings run 30 min over)",
                    detail = vm.sportFollowLabel,
                    onClick = { vm.cycleSportFollow() },
                )
            }
            item {
                SettingRow(
                    "Plex",
                    subtitle = if (vm.plexLinked) "Linked – your Continue watching is on Home" else "Link your Plex account to see Continue watching on Home",
                    detail = if (vm.plexLinked) "Linked" else "Link",
                    onClick = { if (vm.plexLinked) vm.navigate(Screen.Plex) else vm.linkPlex() },
                )
            }
            if (vm.plexLinked) {
                item {
                    SettingRow(
                        "Play films from Plex when you have them",
                        subtitle = "A film in Films & TV that's also on your Plex plays from Plex (usually a better copy). Hold OK on a film for the IPTV copy.",
                        detail = if (vm.preferPlexFilms) "On" else "Off",
                        onClick = { vm.togglePreferPlexFilms() },
                    )
                }
            }
            item {
                SettingRow(
                    "Football live scores & goal alerts",
                    subtitle = if (vm.footballKey == null) "Needs a free football-data.org account" else "${vm.footballTeams.size} team(s) followed",
                    detail = if (vm.footballKey == null) "Set up" else "On",
                    onClick = { vm.navigate(Screen.Football) },
                )
            }
            item {
                SettingRow(
                    "Update Tuner",
                    subtitle = "Install a new version from your computer's browser, or from GitHub · this is ${vm.installedVersion}",
                    onClick = { vm.openUpdates() },
                )
            }

            item { SectionTitle("Google TV") }
            item {
                SettingRow(
                    "Channels in the TV's Live tab",
                    subtitle = vm.liveTabStatus ?: "Adds your channels and guide to the TV's own channel list (Live tab / Live Channels app)",
                    detail = vm.liveTabLabel,
                    onClick = { vm.cycleLiveTab() },
                )
            }
            item {
                SettingRow(
                    "Voice",
                    subtitle = "Say \"Hey Google, play BBC One on Tuner\" – or use tuner://play links from Home Assistant",
                    onClick = { vm.showNotice("Try: \"Hey Google, play Sky Sports Main Event on Tuner\"") },
                )
            }
            item {
                SettingRow(
                    "Screensaver",
                    subtitle = "Clock + what's on now on your favourites. Choose \"Tuner – what's on\" in the TV's screensaver settings (see README)",
                    onClick = { vm.showNotice("Pick \"Tuner – what's on\" in the TV's screensaver / ambient settings") },
                )
            }

            item { SectionTitle("Phone remote") }
            item {
                SettingRow(
                    "Phone remote",
                    subtitle = "Control Tuner from your phone's browser – buttons, channel list, what's on",
                    detail = if (vm.remoteEnabled) "On" else "Off",
                    onClick = { vm.toggleRemote() },
                )
            }
            vm.remoteUrl?.let { url ->
                item {
                    val qr = remember(url) { PhoneSetupServer.qrCode(url, 320).asImageBitmap() }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(qr, contentDescription = "Phone remote QR code", modifier = Modifier.size(170.dp))
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text("Scan to open the remote, then add it to your home screen.", color = TunerColors.TextPrimary, fontSize = 16.sp)
                            Text(url, color = TunerColors.Accent, fontSize = 16.sp)
                            Text("Works while Tuner is open, on the same Wi-Fi.", color = TunerColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }

            item { SectionTitle("Channel health") }
            item {
                val progress = vm.healthProgress
                SettingRow(
                    if (progress != null) "Checking channels… (OK to stop)" else "Check channels now",
                    subtitle = progress ?: (
                        "${vm.deadChannels.size} offline" +
                            (if (vm.healthCheckedAt > 0) " · last checked ${dayLabel(vm.healthCheckedAt)} ${hhmm(vm.healthCheckedAt)}" else " · never checked") +
                            " · stops playback while checking"
                        ),
                    onClick = { if (progress != null) vm.cancelHealthCheck() else vm.runHealthCheck() },
                )
            }
            item {
                SettingRow(
                    "What to check",
                    subtitle = "Checking everything can take hours on big playlists",
                    detail = if (vm.healthScope == "all") "All visible channels" else "Favourites",
                    onClick = { vm.toggleHealthScope() },
                )
            }
            item {
                SettingRow(
                    "Check every night",
                    subtitle = "After the guide refresh (about 4am), for up to 7 minutes",
                    detail = if (vm.healthNightly) "On" else "Off",
                    onClick = { vm.toggleHealthNightly() },
                )
            }
            item {
                SettingRow(
                    "Hide offline channels",
                    subtitle = "Otherwise they're just marked ⚠ offline",
                    detail = if (vm.hideDead) "On" else "Off",
                    onClick = { vm.toggleHideDead() },
                )
            }
            if (vm.deadChannels.isNotEmpty()) {
                item { SettingRow("Clear the offline list", onClick = { vm.clearDead() }) }
            }

            item { SectionTitle("Channels") }
            item {
                SettingRow(
                    "Manage groups and channels",
                    subtitle = "Hide, reorder or lock groups · reorder favourites · unhide channels",
                    onClick = { vm.navigate(Screen.Manage) },
                )
            }

            item { SectionTitle("Parental controls") }
            item {
                SettingRow(
                    if (vm.pin == null) "Set a parental PIN" else "Change PIN",
                    subtitle = "Locked groups need the PIN once each time Tuner is opened",
                    onClick = { vm.changePin() },
                )
            }
            if (vm.pin != null) {
                item { SettingRow("Remove PIN", subtitle = "Unlocks every group", onClick = { vm.removePin() }) }
            }

            item { SectionTitle("Recordings") }
            item {
                SettingRow(
                    "Recordings",
                    subtitle = "Saved to ${vm.recordingDir}",
                    onClick = { vm.navigate(Screen.Recordings) },
                )
            }
            item {
                SettingRow(
                    "Delete old recordings",
                    subtitle = "Finished recordings older than this are deleted overnight (📌 kept ones stay)",
                    detail = if (vm.autoDeleteDays == 0) "Never" else "After ${vm.autoDeleteDays} days",
                    onClick = { vm.cycleAutoDelete() },
                )
            }
            item {
                SettingRow(
                    "Network drive (NAS / PC share)",
                    subtitle = vm.nasStatus ?: (vm.nasUrl?.let { "Copies finished recordings to $it" } ?: "Copy finished recordings to a shared folder – handy if Plex watches that folder"),
                    detail = if (vm.nasUrl == null) "Set up" else "Change",
                    onClick = { nasStep = 1 },
                )
            }
            if (vm.nasUrl != null) {
                item {
                    SettingRow(
                        "Keep a copy on the TV too",
                        subtitle = "Off saves space on the TV, but those recordings then play from Plex (or another app), not Tuner",
                        detail = if (vm.nasKeepLocal) "On" else "Off",
                        onClick = { vm.toggleNasKeepLocal() },
                    )
                }
            }
            item {
                SettingRow(
                    "Series links",
                    subtitle = if (vm.seriesLinks.isEmpty()) "None yet – hold OK on a programme in the guide and pick Series link"
                    else vm.seriesLinks.joinToString(" · ") { it.title },
                    detail = vm.seriesLinks.size.toString(),
                    onClick = { vm.navigate(Screen.Recordings) },
                )
            }

            item { SectionTitle("Apps") }
            item {
                SettingRow(
                    "Apps on this TV",
                    subtitle = "Plex, BBC iPlayer, Netflix and the rest – choose which appear on Home",
                    onClick = { vm.navigate(Screen.Apps) },
                )
            }

            item { SectionTitle("This TV") }
            item { SettingRow("Watch time", subtitle = "What each profile watched this week", onClick = { vm.openStats() }) }
            item { SettingRow("Copy to another TV", subtitle = "Send playlists, favourites and settings to a TV on the same Wi-Fi", onClick = { vm.startShareSend() }) }
            item { SettingRow("Copy from another TV", subtitle = "Get everything from another TV running Tuner", onClick = { vm.startShareReceive() }) }

            item { SectionTitle("Help") }
            item {
                SettingRow(
                    "Check everything",
                    subtitle = "Tries each part of Tuner on this TV and says what works and what doesn't",
                    onClick = { vm.openSelfTest() },
                )
            }
            item {
                SettingRow(
                    "Problem log",
                    subtitle = "Stream drops, backup links used, failed updates and crashes – newest first",
                    onClick = { vm.openLog() },
                )
            }
            item {
                if (vm.safeMode) {
                    SettingRow(
                        "Safe mode is ON",
                        subtitle = vm.lastCrash?.let { "After crashes: $it" } ?: "Newer extras are switched off",
                        detail = "Turn off",
                        onClick = { vm.leaveSafeMode() },
                    )
                } else {
                    SettingRow(
                        "Start in safe mode next time",
                        subtitle = "If something misbehaves: opens with night mode, lip sync, Live tab, Plex and other extras off",
                        onClick = { vm.startInSafeModeNextTime() },
                    )
                }
            }

            item { SectionTitle("Home Assistant") }
            item {
                SettingRow(
                    "Webhook",
                    subtitle = vm.webhookUrl ?: "Not set – add it with \"Set up from your phone\"",
                    detail = if (vm.webhookUrl != null) "On" else null,
                    onClick = { vm.navigate(Screen.PhoneSetup) },
                )
            }
            item {
                SettingRow(
                    "Cinema lights",
                    subtitle = "Sends \"lights\" dim / restore events when you watch and pause – see the README for the automation",
                    detail = com.titanejstudios.tuner.integrations.CinemaLights.label(vm.cinemaLights),
                    onClick = { vm.cycleCinemaLights() },
                )
            }

            item { SectionTitle("Backup") }
            item {
                SettingRow(
                    "Keep TVs in sync",
                    subtitle = vm.syncStatus ?: "Favourites, My list and where you got to, shared through your network drive",
                    detail = if (vm.syncEnabled) "On" else if (vm.nasUrl == null) "Needs a network drive" else "Off",
                    onClick = { vm.toggleSync() },
                )
            }
            item {
                SettingRow(
                    "Nightly backup to the network drive",
                    subtitle = "Keeps the last 7 in \"Tuner backups\" – press to back up now",
                    detail = vm.nasBackupLabel,
                    onClick = { vm.backupToNasNow() },
                )
            }
            if (vm.nasUrl != null) {
                item {
                    SettingRow(
                        if (vm.nasBackupOn) "Turn nightly backups off" else "Turn nightly backups on",
                        onClick = { vm.toggleNasBackup() },
                    )
                }
            }
            item {
                SettingRow(
                    "Save a backup",
                    subtitle = "Playlists, profiles, favourites, PIN, reminders and guide fixes – also downloadable from your phone",
                    onClick = { vm.backupToStorage() },
                )
            }
            item { SettingRow("Restore the saved backup", onClick = { vm.restoreFromStorage() }) }

            item { SectionTitle("Reminders") }
            if (vm.reminders.isEmpty()) {
                item { SettingRow("No reminders set", subtitle = "In the TV guide, press OK on an upcoming programme", onClick = {}) }
            } else {
                items(vm.reminders, key = { it.channelId + it.start }) { r ->
                    SettingRow(
                        r.title,
                        subtitle = "${dayLabel(r.start)} ${hhmm(r.start)} · ${r.channelName}",
                        detail = "OK to cancel",
                        onClick = {
                            vm.allChannels.firstOrNull { it.id == r.channelId }?.let { ch ->
                                vm.toggleReminder(ch, com.titanejstudios.tuner.data.Programme(r.start, r.stop, r.title, null))
                            }
                        },
                    )
                }
            }

            item { SectionTitle("About") }
            item {
                SettingRow(
                    "Tuner ${vm.installedVersion}",
                    subtitle = "Titan EJ Studios · ${vm.allChannels.size} channels loaded" + (if (vm.safeMode) " · safe mode" else ""),
                    onClick = {},
                )
            }
        }
    }

    when (askKey) {
        "tmdb" -> TextInputDialog(
            title = "TMDB key",
            initial = vm.tmdbKey.orEmpty(),
            hint = "Your API key (or the long read access token) – leave empty to turn off",
            label = "Key",
            onDismiss = { askKey = null },
        ) {
            askKey = null
            vm.saveTmdbKey(it)
        }
        "subs" -> TextInputDialog(
            title = "OpenSubtitles key",
            initial = vm.openSubtitlesKey.orEmpty(),
            hint = "Your API consumer key – leave empty to turn off",
            label = "Key",
            onDismiss = { askKey = null },
        ) {
            askKey = null
            vm.saveOpenSubtitlesKey(it)
        }
    }

    when (nasStep) {
        1 -> TextInputDialog(
            title = "Network drive address",
            initial = vm.nasUrl ?: "smb://",
            hint = "e.g. smb://192.168.1.20/Media/TV Recordings – leave empty to turn off",
            label = "Address",
            keyboard = KeyboardType.Uri,
            onDismiss = { if (nasStep == 1) nasStep = 0 },
        ) { url ->
            if (url.isBlank() || url == "smb://") {
                vm.setNas(null, null, null)
                nasStep = 0
            } else {
                vm.setNas(url, null, null)
                nasStep = 2
            }
        }
        2 -> TextInputDialog(
            title = "User name for the drive",
            initial = vm.nasUser.orEmpty(),
            hint = "Leave empty if the folder doesn't need a log-in",
            label = "User name",
            onDismiss = { if (nasStep == 2) nasStep = 0 },
        ) { user ->
            vm.setNas(vm.nasUrl, user, null)
            nasStep = if (user.isBlank()) 0 else 3
        }
        3 -> TextInputDialog(
            title = "Password for the drive",
            label = "Password",
            password = true,
            keyboard = KeyboardType.Password,
            onDismiss = { if (nasStep == 3) nasStep = 0 },
        ) { pass ->
            vm.setNas(vm.nasUrl, null, pass)
            nasStep = 0
        }
    }

    menuFor?.let { s ->
        MenuDialog(
            title = s.name,
            items = listOfNotNull(
                MenuItem("Use this playlist") { vm.selectSource(s.id) },
                MenuItem("Edit") { vm.startEditSource(s) },
                MenuItem("Refresh now") { vm.refresh(listOf(s)) },
                MenuItem("🕑 Guide times", vm.guideShiftLabel(s.id) + " · OK to change (if the guide is an hour out)") { vm.cycleGuideShift(s.id) },
                MenuItem("Delete") { confirmDelete = s },
            ),
            onDismiss = { menuFor = null },
        )
    }
    profileMenu?.let { p ->
        MenuDialog(
            title = p.name,
            items = buildList {
                if (p.id != vm.activeProfile.id) add(MenuItem("Switch to ${p.name}") { vm.switchProfile(p) })
                add(MenuItem("Edit") { vm.startEditProfile(p) })
                if (p.id != Store.MAIN_PROFILE) add(MenuItem("Delete") { vm.deleteProfile(p) })
            },
            onDismiss = { profileMenu = null },
        )
    }
    confirmDelete?.let { s ->
        MenuDialog(
            title = "Delete \"${s.name}\"?",
            subtitle = "Its channels and guide are removed from Tuner. Your favourites list is kept.",
            items = listOf(
                MenuItem("Cancel") {},
                MenuItem("Delete") { vm.deleteSource(s) },
            ),
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detail: String? = null,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        baseColor = TunerColors.Panel,
        modifier = modifier.fillMaxWidth().height(if (subtitle != null) 72.dp else 58.dp),
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            detail?.let {
                Text(it, color = if (focused) Color.White else TunerColors.Catchup, fontSize = 15.sp)
            }
        }
    }
}

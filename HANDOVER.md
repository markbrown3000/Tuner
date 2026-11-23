# Tuner – handover notes for Claude

This file is for a Claude picking this project up in a new chat. Read it before the code. The README is the user-facing guide. This file explains what the app is, how it's built, and the rules we've worked to.

---

## 1. What Tuner is

**Tuner** is an IPTV player for **Google TV / Android TV**, built as a personal alternative to **TiviMate**. It plays live TV from the user's own IPTV subscription (Xtream Codes login or M3U playlist), with a full TV guide. Over time it has grown to include recording, multi-view, Plex, sport and Home Assistant features.

- **Owner:** Mark, who publishes apps as **Titan EJ Studios**. Package: `com.titanejstudios.tuner`.
- **How he uses it:** he builds `app-release.apk` in Android Studio and sideloads it onto his Google TV. It isn't on the Play Store, and it's for his own household.
- **His provider:** Xtream Codes. He compares everything against TiviMate, so channel order, category order and smoothness are judged against it.
- **Look:** Sky-style (UK). Navy gradient, bright blue focus, a "Home" screen with rails, and a "TV Guide" with category tabs.
- **Current version:** 5.4. Since 5.4 the version code is worked out from the version name (5.4 → 50400, 5.4.1 → 50401) in `app/build.gradle.kts`; GitHub tag builds set the name from the tag (`TUNER_VERSION_NAME`).

### How we've been working
- Mark asks "how can we make it better?". I suggest a list of improvements, and he usually replies "do them all".
- He reports problems from real use on the TV, sometimes with photos: sluggish scrolling, a video freeze, Sport saying everything was on now, order not matching TiviMate, "0 channels" at start-up, and 4-way multi-view struggling.
- Delivery: zip the whole Android Studio project and send it. He builds it himself.
- Write for him in plain English. He isn't a professional Android developer, and step-by-step instructions help.

---

## 2. Build setup

| Thing | Value |
|---|---|
| Language / UI | Kotlin 2.0.21 (compose compiler plugin), Jetpack Compose (BOM 2024.12.01, material3) |
| Android Gradle Plugin | 8.7.3, Gradle wrapper 8.11.1, Java 17 |
| SDK | compileSdk/targetSdk 35, **minSdk 24** |
| Player | Media3 1.5.1 (exoplayer, hls, ui, session) |
| Other libraries | Coil 2.7.0, WorkManager 2.10.0, androidx.tvprovider 1.0.0, ZXing core 3.5.3 (QR codes), jcifs-ng 2.1.10 (SMB network drives), lifecycle 2.8.7, activity-compose 1.9.3, coroutines 1.9.0 |
| Release build | R8 minify + shrinkResources + `proguard-rules.pro`, **signed with the debug key**. Mark selects the *release* build variant. App updates only install if built on the same PC (same key). GitHub builds use that same key from the `DEBUG_KEYSTORE_BASE64` secret (signing config "ci"). |
| CI | `.github/workflows/build.yml`: `gradle testDebugUnitTest` + `assembleRelease` on every push; tag `v*` → GitHub release with the APK. Tests in `app/src/test` (JUnit 4 + real org.json). |

There is no network access in the sandbox I used, so I couldn't run Gradle. See section 8 for how I checked the code.

---

## 3. Project layout (`app/src/main/java/com/titanejstudios/tuner/`)

```
MainActivity.kt          Single activity. Hosts TunerApp() composable, a when(vm.screen) router, global overlays
                         (reminder pop-up, PIN, notices, category picker), BackHandler, PiP, remote shortcut keys
                         (dispatchKeyEvent), frame-rate matching effect, phone-remote key injection.
TunerApplication.kt      Starts AppLog (crash handler). Coil ImageLoaderFactory with a 150 MB disk cache.
TunerViewModel.kt        (~3,000 lines) Almost all app state and logic. Screen enum + back stack, playback modes,
                         channels/categories/guide caches, profiles, settings state, most features' functions.
                         Implements RemoteHandler (phone remote). Plex, football and updates forward to features/.

features/  (5.3) Self-contained parts the VM owns and forwards to: PlexFeature, FootballFeature, UpdateFeature,
           SelfTest (Check everything). New features should go here rather than into the VM.
data/      Models, Store (SharedPreferences settings), Database (SQLite), Repository (refresh),
           M3uParser, XtreamClient, XmltvParser, Catchup, Http, HealthCheck, Sports (sport finder),
           HomeFeed (Top picks / Sport rows), SeriesLinks (+ RecordingPaths), StreamAlternates (backup links),
           VoiceMatch, WatchStats, WhatsOn (+ Genres), Football (football-data.org), AppLog (problem log),
           SafeMode, KidsRules (bedtime), Watchlist (My list + followed series), VodCache, RecordingPlanner
playback/  PlaybackController (the shared ExoPlayer + everything around it), PlayerFactory (player config,
           LipSync audio sink), Timeshift, MultiView, NightMode, StreamCushion (bigger start buffer for flaky channels)
recording/ Recorder (TS + HLS stream recorder), RecordingService (foreground service + scheduler + receivers),
           RecordingTidy (auto-delete, SMB upload)
integrations/ PhoneSetupServer (local web server: phone setup, phone remote, backups, TV-to-TV copy, APK upload),
           HomeAssistantWebhook, ReminderAlarms (+ AppState), TvShare (NSD discovery), Plex, CinemaLights
tv/        GuideRefreshWorker (nightly), HomeScreen (Google TV home rows / Watch Next), LiveInput
           (TvInputService for the TV's Live tab + LiveChannelSync + LiveSetupActivity), TunerDream (screensaver)
apps/      InstalledApps (Plex/iPlayer/Netflix… launcher), Updater (+ InstallResultReceiver)
ui/        One file per screen + Components.kt (shared widgets, theme, focus helpers)
```

`res/`: banner.png (TV launcher banner), launcher icons, themes.xml, `xml/network_security_config.xml` (cleartext HTTP allowed, which IPTV needs), `xml/tv_input.xml`.

---

## 4. Everything Tuner does

### Playlists and data
- **Playlist types:** Xtream Codes login (live channels, films, series, catch-up) and M3U URL. Several playlists can be used separately or "all together".
- **Ordering like TiviMate:**
  - Xtream channels are sorted by the provider's `num`, and categories follow the order of `get_live_categories`.
  - M3U uses `tvg-chno` for channel numbers and keeps groups in order of first appearance.
  - Shown channel number = provider number if present, otherwise position in the list.
- **TV guide:** XMLTV is streamed into SQLite. Matching is by tvg-id or by a normalised name, with manual fixes per channel (the "Fix TV guide match" screen).
  - Programme pictures come from `<icon src>`.
  - Channels with no logo borrow the guide's.
  - Each playlist can have a guide time shift (±30 minutes, 1 hour or 2 hours).
- **Refreshing:** two steps. Channels load first and are usable straight away, then the guide loads.
  - There's a nightly WorkManager job at about 4am.
  - "Update when Tuner starts" (if the saved copy is over 12 hours old) can be turned off.
- **Storage:** SQLite (`Database`, schema **v3**) for channels, programmes and XMLTV channels. SharedPreferences (`Store`) for everything else. `Store.exportBackup()/importBackup()` cover all prefs except device-only keys (`notBackedUp`).

### Screens (Screen enum)
Home, Channels (the Sky-style "TV Guide" list with category tabs), Guide (grid EPG), Player, Search, Movies / Series / SeriesDetail (VOD), Settings, SourceEdit, Manage (groups, favourites order, hidden, offline), ProfilePicker / ProfileEdit, PhoneSetup, EpgMap, Recordings, MultiView, Sports, Apps, Log, WhatsOn, Stats, ShareTv, Catchup, Categories (→ Manage), Plex, Football, Update.

### Home screen
- A hero area at the top. When something is playing it shows the live preview.
- Rows:
  - Continue watching (recent channels)
  - Continue on Plex
  - Top picks for you
  - Favourites
  - Sport on now
  - Your apps
  - Recordings
  - Resume films and series
  - All channels
- **Kids profiles** get a separate big-tile home with a "minutes of TV left today" count.

### Watching live TV
- **Channel switching:** up/down, number entry, previous channel, a channel list overlay, a mini guide, and hold ▼ for a favourites strip.
- **Info bar:** Sky-style, with NOW/NEXT and the channel number.
- **Pause and rewind:** live pause (timeshift recorded to disk, up to 90 minutes), seeking, and catch-up playback where the provider supports it.
- **Fast switching:** an optional muted standby player has the next channel ready.
- **Smart stream recovery:**
  - Reconnects with backoff.
  - Falls back to HLS on parse errors.
  - Stall detection after 12 seconds of buffering.
  - Backup links: Xtream .ts ↔ .m3u8, and same-named channels (FHD/HD).
- **Player menu:**
  - Audio and subtitle tracks
  - Subtitle size and background
  - Picture size per channel (Fit / Zoom / Stretch)
  - Lip sync per channel (±400 ms; implemented by offsetting the audio sink clock)
  - Night mode sound (DynamicsProcessing compressor)
  - Sleep timer
  - Record, series link
  - Multi-view
  - Stream info
- **Also:** frame-rate matching (optional, switches the display mode), preferred audio and subtitle languages, picture-in-picture, and a MediaSession.

### Multi-view
2 or 4 pictures at once (`MultiViewController`).
- Only the highlighted slot decodes audio. The others have the audio track type disabled, not just muted.
- Text tracks are off.
- Four-up caps streams at 720p.
- Slots start staggered and use small buffers.

### Guide, discovery and sport
- **Grid guide:** colour-coded by genre, with remind, record and series link options.
- **Search:** finds channels and programmes.
- **What's On:** now, 6, 8, 9 or 10pm, or tomorrow 8pm. Filter by kind (Sport, Film, News, Kids, Factual, Entertainment, Drama, Music) and favourites-only.
- **Catch-up page.**
- **Sport finder:**
  - Classifies the guide; "v"/"vs" titles only count as live within 4 hours.
  - `Sports.normalise` turns "Next: A v B – Sun 29 Nov 16:30" placeholders into real timed events.
- **Live football scores and goal alerts:** uses football-data.org with the user's own free key, and lets him follow teams.
- **Reminders:** a pop-up in the app. A Home Assistant event also fires via alarm when the app is closed.

### Recording
- **How it works:** a foreground service (`specialUse`) records TS or HLS to a file. Exact alarms wake it, and BootReceiver re-arms them after a restart.
- **Ways to set one:** record a programme, record now, series link, or from the phone remote's Record tab.
- **Series link:** matches the programme title on the same channel (ignoring "New:" and episode numbers) and is re-checked whenever the guide updates.
- **Playing back:** recordings play while still recording ("chase play").
- **Where they go:** a storage location chooser (internal or USB).
- **Tidying up:** auto-delete after N days, with 📌 Keep to exempt a recording.
- **Network drive:** copies finished recordings to an SMB share (jcifs-ng), optionally removing the TV copy.

### Organising
- **Favourites** (per profile), recents, hide channels and groups, and PIN-lock groups.
- **Profiles:** kids profiles have allowed groups and a daily time limit.
- **Manage screen:**
  - Reorder groups and favourites.
  - Rename or merge categories (rename to an existing name = merge).
  - Make your own categories (add channels from the channel menu).
  - Hidden and offline lists.
- **Channel health check:** nightly or manual, with an option to hide dead channels.

### Connected features
- **Phone setup / remote:** a local HTTP server on port 8765.
  - **Setup** uses a one-off token in a QR code: add playlists, set the webhook, back up and restore.
  - **Remote** uses a permanent token: a key pad, the channel list, a Record tab, `/log.txt` and `/update` (APK upload).
- **TV-to-TV copy:** NSD service `_tunertv._tcp.` plus a 4-digit code (5 wrong tries kills it) to fetch `/share.json`, which is a backup.
- **Home Assistant:** the user sets a webhook URL.
  - Events: playing, stopped, programme_changed, reminder (with a `tuner://open?channel=` link), recording_started/finished/failed, goal, sleep_timer.
  - Deep links: `tuner://play?name=…`, `tuner://play?number=…`, `tuner://open?channel=…`, `tuner://stop`.
- **Google TV integration:**
  - Home screen preview channel and Watch Next.
  - **Live tab:** a TvInputService plus a sync of channels and 24 hours of guide into TvContract (favourites or all, max 600).
  - **Voice:** MEDIA_PLAY_FROM_SEARCH / SEARCH intents → `VoiceMatch`.
  - **Screensaver:** DreamService. Google TV needs an adb command to select it.
- **Apps page and row:** launches other apps. Plex, BBC iPlayer, Netflix, YouTube, Disney+, Prime Video, ITVX, Channel 4, My5 and NOW come first. Hold OK to hide one from Home. Needs `<queries>` in the manifest.
- **Plex:**
  - Links via the PIN at plex.tv/link.
  - Reads On Deck from each server, choosing the first address that answers (home network first).
  - Plays directly in Tuner and reports progress with `/:/timeline`.
- **Updates:** upload an APK from a PC browser, or check a GitHub repo's latest release. Installs through PackageInstaller (`REQUEST_INSTALL_PACKAGES`).
- **Remote shortcut keys:** Red = Sport, Green/DVR = Recordings, Yellow = Search, Blue = Favourites, Guide = grid, TV = last channel.

### Added in 5.3
- **Check everything** (Screen.SelfTest, `features/SelfTest`): ~19 independent checks run on IO, each returns Pass/Warn/Fail/Info with plain-English detail.
- **Safe mode** (`data/SafeMode`, own prefs file `tuner_safe`, written with commit()): the crash handler counts crashes within 60 s of process start; 2 → next start is safe. Turns off (in memory only) night mode, frame-rate, lip sync, passthrough, TS compat, fast switch, Live-tab sync, Plex, football, new-episode check, and start-on-last-channel. Banner on Home. `markStable()` after ~60 s.
- **Start-up**: `loadActive()` = channels, then `loadGuide()`. `startExtras()` 4 s later. `keepGuideFresh()` re-reads the guide every 6 h or after the overnight job. Nightly `Database.tidy()` deletes programmes older than 8 days; VACUUM weekly.
- **Zapping**: `zap()` tunes at once on a single press; rapid presses (<700 ms) set `zapPreview` and tune 450 ms after the last. Full-screen PlayerView keeps the last frame.
- **StreamCushion**: per-channel stall count (`flaky_streams`, not backed up) → 3/6/10 s "priming" (prepare paused, poll buffered duration, then play). Decays after 30 min clean play.
- **VOD**: lists cached as JSON in `filesDir/vod`, background refresh after 12 h. My list (`watchlist` pref), followed series (`followed_series`) with a daily episode count check → "New episodes" row.
- **Kids**: `Profile.bedtimeMinutes` (-1 = none) stored as "bedtime" in the profile JSON; warnings at 5 and 1 minutes.
- **Mini guide**: hold OK → Remind / Record / Series link menu. Info bar marks NEXT with ⏰ / ●.
- **Recordings**: grouped by day with overlap warnings (`RecordingPlanner`), plus the account's max connections from Xtream `user_info`.
- **Home Assistant**: `paused`, `resumed`, `lights` (dim/restore, debounced 1.5 s, setting `cinema_lights`), `bedtime_reached`, `test`.

### Added in 5.4
- **CI + tests + crash alerts**: see the table above. `crash` HA event sent on the next start (`Store.crashReportedAt`).
- **Still watching?**: `onUserActivity()` from `MainActivity.dispatchKeyEvent` and deep links; checked in the 15 s tick; 90 s to answer.
- **Volume level / quality per channel**: `volume_levels` / `quality_modes` maps; `PlaybackController.volumeDb` (player volume for minus, `LoudnessBoost` = LoudnessEnhancer for plus) and `.quality` (track-selection size/bitrate limits).
- **Sport follow**: `SportFollow.find` after each guide load, using `football.teams`; `sport_auto_done` stops repeats. `recordProgramme` adds 30 min to sport.
- **TMDB** (`integrations/Tmdb`, `cleanTitle` is tested) → `FilmInfoDialog`. **OpenSubtitles** (`integrations/OpenSubtitles`) → `PlaybackController.addSubtitleFile` (SubtitleConfiguration on the MediaItem, reloaded at the same position).
- **Up next / skip intro**: in PlayerScreen; intro end learned per series (`intro_ends`).
- **TV sync** (`integrations/TvSync`, file `tuner-sync.json` in the NAS folder; per-section timestamps `sync_stamp_*`; resume merged per entry). **NAS backup** (`integrations/NasBackup`, nightly in GuideRefreshWorker, keeps 7 per TV).
- **What's on API**: `GET /api/whatson?t=<remote token>&kind=&when=` → `RemoteHandler.whatsOnJson` (JSON with `speech`).

### Other
- **Settings:**
  - Look: 6 highlight colours and 4 backgrounds (`TunerColors.apply`).
  - When Tuner opens: Home, TV Guide where you left it, or the last channel.
  - Audio passthrough (off by default, because it caused a freeze).
  - Stream compatibility mode.
- **Watch time stats:** per profile, by day, channel and kind of programme.
- **Problem log:** `AppLog`, with a crash handler, readable in Settings and at `/log.txt`.

---

## 5. Key technical decisions (and why)

- **One shared ExoPlayer** in `PlaybackController`, reused across screens (Home preview, TV Guide preview, full screen). `VideoSurface` (a PlayerView in AndroidView) just attaches to it.
- **Audio is decoded to PCM by default.** Passthrough on Mark's TV made the picture freeze while sound continued. `TunerRenderersFactory` uses `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` unless passthrough is turned on. Decoder fallback is on.
- **Frame-rate strategy is OFF by default** on the player for the same reason. Matching is an opt-in setting.
- **TS extractor flags** (non-IDR keyframes, detect access units) only in "stream compatibility mode", because they froze some decoders.
- **Speed fixes:**
  - Release build with R8.
  - Coil logos at 160×100.
  - A custom `LocalBringIntoViewSpec` (`TvScrolling`, 110 ms linear tween).
  - Custom `tvClickable` (fires on OK key-up, long-press on repeat 1, no pointerInput).
  - Category switching debounced by 260 ms.
  - Channel caches (`allowedAll`, `byGroup`, `byId`).
  - Home rows computed on `Dispatchers.Default`.
  - Refresh runs at background thread priority.
- **The VM owns state as `mutableStateOf`** with `private set`. The UI calls VM functions.
- **Focus helpers in Components.kt:** `FocusSurface`, `PillButton`, `ModalLayer`/`MenuDialog` (trap focus), `FocusRestorer` + `RestoreFocusWhenClosed`, `TextInputDialog`, `TopNav`, `ChannelTile`, `ChannelLogo`.

---

## 6. Rules learned the hard way (please keep following them)

1. **Declare every ViewModel property before the `init {}` block.** `init` calls `loadSettingsFromStore()`, which assigns many of them. A property declared after `init` crashes (null delegate) or gets reset. After editing, check with a quick script that every name assigned in `loadSettingsFromStore()` is declared above `init`.
2. **Lazy list keys must be unique**, or Compose crashes. Provider group names can clash with the built-ins (`FAVOURITES`, `RECENT`, `ALL`), so they are filtered. Duplicate search results and XMLTV ids are deduplicated.
3. **Avoid a bare `if (...) x` directly before an `else ->` branch** in a `when`, because it parses as a dangling else. Use braces.
4. **Wrap `+ if (...) a else b` in parentheses** when concatenating.
5. **`MenuDialog` calls `onDismiss()` before the item's `onClick`.** If an item needs to open a follow-up dialog, keep that state outside the menu, or re-set it in the onClick (see `CategoryPicker`).
6. **Never resize or swap a playing video surface on focus changes.** It causes hitches, so show a border overlay instead. Previews stay put while scrolling.
7. **Keep work off the main thread.** Anything that scans every channel's guide runs on `Dispatchers.Default`/`IO` with snapshots of the maps.
8. **When changing the DB schema, add an `onUpgrade` step instead of forcing a re-download.** Mark disliked the full re-download after a data-format change.
9. **Store migrations:** `migrateToV4` reset `last_refresh` (one-off). Don't force re-downloads again without a good reason.
10. **New Settings that should not copy between TVs** go in `Store.notBackedUp`.

---

## 7. Things that are untested on a real TV

I couldn't run the app. Everything after about v5.0 has only been checked by compiling against stubs and by unit tests of the pure logic. The most likely places for runtime problems:

- **Live tab** (TvInputService / TvContract sync): Google TV may not show third-party inputs in its Live tab.
- **Screensaver** (needs an adb command), **voice** (Google decides whether to route to Tuner), **TV-to-TV copy** (NSD).
- **Night mode** (DynamicsProcessing constructor arguments), **frame-rate matching** (display modes), **lip sync** (ForwardingAudioSink).
- **SMB upload** (jcifs-ng on Android and R8 rules), **Plex** (direct play, server address choice), **APK self-update** (PackageInstaller flow).
- **New screens:** What's On, Catch-up, Stats, Plex, Football, Update, ShareTv, the Categories picker, and the multi-view changes.

**Added in 5.4, also untested:** everything in "Added in 5.4". The tests and the GitHub workflow have never been run either – the first GitHub build is the first real compile of 5.3 + 5.4, so expect to fix a few compile errors from its log.

**Added in 5.3, also untested on a real TV:** everything in "Added in 5.3" above. Highest risk: the cushion/priming logic in PlaybackController (paused-while-buffering states), zap coalescing with fast switching on, the self-test's TvInputManager/DisplayManager/DynamicsProcessing probes, and the two-phase start-up.

**Suggested next step:** a test-and-polish round. Mark installs 5.2, tries each feature (multi-view first, because he reported it struggling) and sends photos of errors. Fix those before adding more.

---

## 8. How I checked code without Gradle

- **Syntax:** ran the Kotlin compiler (kotlin-compiler-embeddable 2.0.21) over every `.kt` file and looked only for parse errors.
- **Type checks:** compiled `data/`, `recording/Recorder.kt`, the server and Plex against hand-written Java stubs of the Android APIs used (Context, SharedPreferences, SQLite, org.json, Uri…). Compiled `TunerViewModel.kt` against Kotlin stubs of Compose, Media3 and the UI/playback classes.
- **Logic tests:** small JVM `main()` tests for:
  - parsers and ordering
  - sports normalisation
  - series-link matching
  - backup links
  - Top picks
  - voice matching
  - What's On and genres
  - frame-rate picking
  - version compare
  - SMB paths
  - goal detection
- **Reference checks:** a regex script confirms every `vm.something` used in the UI exists in the ViewModel and isn't a `private set` being assigned.
- **Compose UI** can't be compiled this way, so it was reviewed by hand.
- **5.3 session:** no Kotlin compiler could be downloaded at all, so 5.3 was checked only with a bracket/string-aware balance checker over every .kt file, the `vm.x` reference check, and the rule-1 check (everything assigned in `loadSettingsFromStore()` declared above `init`). Expect the first Gradle build to possibly flag small type errors – fix those first.

If you have Gradle and network access, run `./gradlew assembleRelease` instead.

---

## 9. Version history (short)

- **1.x–4.x:**
  - Core player, M3U/Xtream, EPG grid, favourites and recents.
  - Overlays, catch-up, reminders, search, VOD, multiple playlists, PIN, home-screen rows.
  - Phone setup, profiles, backup, multi-view, recording, Home Assistant.
  - Timeshift, mini guide, sport finder, health check, fast switching, phone remote.
  - After Mark's feedback: speed fixes, freeze fix, Sky look, Sport "on now" fix, TiviMate order, start-up loading fix.
- **5.0:**
  - Apps row (Plex/iPlayer/Netflix…), Top picks, programme pictures, series link.
  - Smart stream recovery, reopen where you left off, refresh-on-start setting.
  - Remote shortcut keys, favourites strip, kids home, problem log, speed work.
- **5.1:**
  - What's On, sleep timer, night mode, subtitle style, frame-rate matching.
  - Better logos, watch-time stats, themes, Live tab, voice, screensaver.
  - Home Assistant alerts when closed, copy to another TV.
- **5.4:**
  - GitHub build + release workflow, unit tests, crash alerts to HA.
  - Still watching?, volume level and picture quality per channel, followed teams' matches + sport overrun padding.
  - TMDB film info + trailers, OpenSubtitles, Up next card, learned Skip intro.
  - TV sync and nightly backups via the network drive, "what's on?" endpoint for HA voice.
- **5.3:**
  - Check everything (self-test), safe mode, Plex/football/updates moved to features/.
  - Two-step start-up, quick-zap coalescing + last frame kept, guide kept fresh + nightly tidy, start-up cushion for flaky channels.
  - My list, new episodes, cached film/series lists, record from the mini guide, kids bedtime + warnings, recordings planner, cinema lights.
- **5.2:**
  - Smoother multi-view, picture size, lip sync, languages, catch-up page.
  - Category rename, merge and custom categories, guide time shift.
  - Recording tidy-up and NAS copy, Plex row, football scores, record from phone, self-update.

# Tuner 5.4 – IPTV player for Google TV

A TiviMate-style live TV app for Google TV / Android TV. Built with Kotlin, Jetpack Compose and Media3 ExoPlayer.


## What's new in 5.4
**Catching mistakes before they reach the TV**
- **Automatic builds on GitHub** – see "Automatic builds on GitHub" below. Every push runs the tests and builds the APK; pushing a tag like `v5.4.1` publishes a release that Settings › Update Tuner › Check now installs on the TV.
- **Tests** for channel order, guide matching, series links, sport, bedtime, clashes, buffering, film-name cleaning, My list and TV sync (`app/src/test`). Run them in Android Studio with right-click › Run Tests, or `./gradlew testDebugUnitTest`.
- **Crash alerts**: after a crash, the next start sends a `crash` event to Home Assistant with what went wrong and the last lines of the problem log. There's a phone-notification automation below.

**Better live TV**
- **Are you still watching?** (Settings › Playback): after 2, 3, 4 or 6 hours with no button pressed, Tuner asks; with no answer in about 90 seconds it stops. Default 4 hours.
- **Volume level per channel** (player menu, hold OK): Quieter −9/−6/−3 dB or Louder +3/+6/+9 dB, remembered for each channel. Louder needs Audio passthrough off.
- **Picture quality per channel** (player menu): Auto, Best or Data saver (SD). This only makes a difference on channels that offer more than one quality (most HLS/.m3u8 links; plain .ts links have just one). Auto already steps down when your connection struggles.
- **Your teams' matches**: follow teams on the Football screen, and Tuner finds their matches in the guide and sets a reminder, or records them (Settings › Plex, scores & updates › Your teams' matches). Any sport recording now carries on 30 minutes past the guide's end time for extra time and overruns.

**Films and series**
- **Film info from TMDB** (your key is built in via `keys.properties`): hold OK on a film or series poster (or the ℹ Info button on a series, or the player menu) for the proper poster, plot, cast, rating, running time and a ▶ Trailer button. Needs a free key from themoviedb.org (Settings › Films & series).
- **Subtitles from OpenSubtitles**: player menu › Find subtitles online, then pick one. Uses your subtitle language from Settings (English if none). Needs a free key from opensubtitles.com; the free plan allows a few downloads a day.
- **Up next**: in the last 20 seconds of an episode, a card shows the next one. OK plays it now, BACK cancels.
- **Skip intro**: the first time you skip an intro with ▶ near the start of an episode, Tuner learns where it ends for that series. Next time, a "Skip intro – press ▶" button appears.

**Around the house**
- **Keep TVs in sync** (Settings › Backup): favourites, My list, followed series and where you got to in films and episodes are shared through a small `tuner-sync.json` file on your network drive. Each list goes to whichever TV changed it last; resume points are merged one by one. The first TV to switch it on shares its lists, and the others then take those.
- **Ask Home Assistant "what's on?"**: Tuner's phone-remote server now answers `/api/whatson`. There's a ready-made voice automation below.
- **Nightly backup to the network drive**: each night Tuner saves its settings to `Tuner backups` on your network drive, keeping the last 7 for each TV. Press the setting to back up straight away.
- **Check everything** now also tests the TMDB and OpenSubtitles keys and the sync drive.

**Added since 5.4.0**
- **Plex libraries**: Films & TV › **Plex ›** (or **Plex** in the top menu if you have no Xtream films) shows all your Plex film and TV libraries. Shows open to seasons and episodes, and episodes carry on to the next one automatically.
- **Open Tuner when the TV turns on** (Settings, near the top). Android only allows this with "Display over other apps" allowed for Tuner. If your TV hides that setting, run this once from a computer: `adb shell appops set com.titanejstudios.tuner SYSTEM_ALERT_WINDOW allow`. It works after a full restart; Google TV doesn't tell apps when it simply wakes from standby.
- **Trending this week** (Home, replaces "New on Plex" – switch that back on in Settings › Home rows): TMDB's weekly trending films and TV, marked with where you have them (your Plex, Films & TV, Series). OK plays it; ones you don't have open their info and trailer.
- **Recommendations that learn**: Tuner learns (per profile, on the TV) from what you actually watch – genres, your regular shows, what you watch at different times of the week – plus 👍/👎 (hold OK on a programme). Home gets "Your shows tonight", "Because you watched…" (Plex's own "more like this", or films from the same category), and Top picks ranked by your taste with a reason on each, plus a couple of "Something different". Settings › Recommendations shows what it's learned, can pause learning or start again.
- **Languages & countries** (Settings): "English only" hides channels, categories, films and series tagged with another country or language ("AR |", "[FR]", "Polish"…); untagged ones stay. Or show/hide countries one by one.
- **Evening look** (new default; Settings › Look › Background/Highlight colour to change): deep ink-blue, sky-blue highlights, frosted buttons. Colours follow the highlighted picture on Home, Films & TV and Plex (Settings › Look). New info bar while watching (logo, number, LIVE/score, HD/5.1/Subtitles/Catch-up badges, progress with times) and a channel strip when you press CH+/CH−. Profile pictures (colour + icon) in Who's watching and the top bar. Start-up screen, pulsing placeholders while Home loads, and an artwork screensaver after a few idle minutes (Settings › Look).
- **New Home**: the highlighted item's picture fills the top behind the menu (posters are shown whole, never cropped), quick buttons (▶ last channel, Guide, Sport, Search, 🎤), one "Carry on watching" row for Plex, films and channels, bigger tiles with time left and what's next, live football scores on sport tiles. Settings › Home rows: show/hide/reorder rows, and "change with the time of day" (sport first when your team plays, films and Plex in the evening).
- **BBC iPlayer, ITVX, Channel 4, My5**: these only play in their own apps, so Tuner opens them for you. Hold OK on a finished programme on their channels › "Watch on iPlayer" (etc.) opens the app searching for it; Search marks such programmes "▶ On iPlayer"; hold OK on a channel › open its app; the apps come first in Your apps.
- **Plex everywhere**: "New on Plex" row on Home; Search (and the 🎤 button) finds live TV, catch-up, films, series and Plex together; Plex libraries have Genre and Sort; hold OK to mark watched/unwatched (episodes too). A film in several Plex libraries asks which copy to play (4K first). Films & TV films you also have on Plex play from Plex (Settings › Plex, scores & updates; hold OK on a film for the IPTV copy).
- **Kids and Plex**: a grown-up holds OK on a Plex library › Allow for kids' profiles (🧸). Kids only see those libraries.
- **Joined late?** On live TV, ⏪ starts the programme from the beginning when it has catch-up. Followed teams' matches pop up 5 minutes before kick-off.
- Check everything now times each Plex server and warns about slow relay connections.
- HD/SD copies of a channel shown once, catch-up found on any copy, scrolling programme descriptions, update pop-up.

## What was new in 5.3
**Making it solid**
- **Check everything** (Settings › Help): tries each part of Tuner on this TV and shows ✓ / ! / ✗ with the reason. It covers your playlist login and expiry date, the guide, a test stream, recording storage and timers, night mode, frame-rate matching, the Live tab, screensaver, phone remote, Home Assistant, network drive, Plex, football scores and updates. Take a photo of anything red and send it over.
- **Safe mode.** If Tuner crashes twice in a row just after starting, the next start opens on Home with the newer extras switched off (night mode, lip sync, frame-rate matching, audio passthrough, stream compatibility, fast switching, Live tab, Plex, football and update checks). A banner says what crashed. Your settings aren't changed – press **Turn off** when you're ready. You can also choose "Start in safe mode next time" in Settings › Help.
- **Tidier code inside.** Plex, football scores and app updates now live in their own files instead of the one giant view model. You won't see a difference.

**Smoother**
- **Quicker start-up.** Channels appear first and the guide fills in a moment later. Plex, scores and the apps list wait a few seconds so they don't slow the first screen.
- **Faster channel flicking.** One press of ▲▼ changes channel at once. Pressing quickly shows each channel's name and only tunes the one you stop on. While the next channel loads, the last picture stays on screen instead of going black.
- **Guide stays fresh.** If Tuner is left on for days, it now re-reads the guide every 6 hours, and straight after the overnight update. The overnight job also clears programmes older than a week and compacts the database once a week.
- **Smarter buffering.** Channels that have recently frozen or dropped out get a bigger cushion before they start (3, 6 or 10 seconds). They're a touch slower to start but freeze far less, and they earn back a quick start after half an hour of clean viewing. Stream info shows when a channel has a cushion.

**Nicer to use**
- **My list**: hold OK on any film or series poster (or press ＋ My list on a series) to save it. It shows on Home and as "★ My list" in Films and Series.
- **New episodes**: series you watch are checked once a day, and ones with new episodes appear in a "New episodes" row on Home.
- **Films and Series open instantly.** The lists are saved on the TV and refreshed in the background when they're over 12 hours old. Reload still forces a fresh download.
- **Record from the mini guide**: while watching, press OK for the mini guide, pick a programme and **hold OK** to choose Remind me, Record or Record every episode. The info bar now shows ⏰ or ● next to NEXT when it's set.
- **Kids bedtime**: kids profiles can have a bedtime (6pm–9pm). There's no TV from then until 6am. A "5 minutes left" and a "1 minute left" warning now appear before bedtime or the daily limit, and a grown-up can allow 30 more minutes with the PIN.
- **Recordings planner**: Scheduled recordings are grouped by day (Today, Tomorrow, Wed 25 Nov…). Recordings that overlap are flagged ⚠, and Tuner tells you how many streams your account allows at once.

**Home Assistant**
- **Cinema lights** (Settings › Home Assistant): choose Films, Films and series, or Everything. Tuner then sends a `lights` event with `state: dim` when you start watching full screen, and `state: restore` when you pause, stop or leave. It waits 1.5 seconds first, so a quick pause doesn't flash the lights. There's a ready-made automation below.
- New events: `paused`, `resumed`, `lights`, `bedtime_reached`, and `test` (sent by Check everything).

## What was new in 5.2
- **Smoother 4-way multi-view.** Only the highlighted picture decodes sound (the others have audio switched off, not just muted), subtitles are off, four-up caps multi-quality streams at 720p, the streams start one after another with small buffers, and the highlight no longer resizes the video.
- **Picture size per channel** (player menu): Normal, Zoom (fills the screen and crops the edges) or Stretch. Tuner remembers it for each channel.
- **Lip-sync fix** (player menu › Sound timing): move the sound up to 400 ms earlier or later, for one channel or for all of them.
- **Preferred languages** (Settings): the sound language, and a subtitles language that switches on by itself.
- **Catch-up page** (What's On › ⏪ Catch-up): pick a channel, then anything from the last few days.
- **Edit categories** (Settings › Manage › Groups):
  - Rename a category. Giving it the same name as another category merges the two.
  - Make your own categories, and add channels to them from the TV Guide (hold OK on a channel › Add to my category).
- **Guide time fix**: open Settings and pick a playlist › 🕑 Guide times, to shift that playlist's guide by ±30 minutes, 1 hour or 2 hours.
- **Recording tidy-up**: delete finished recordings after 7, 14, 30 or 60 days. Pick 📌 Keep on any recording you want to save from the tidy-up.
- **Network drive**: copy finished recordings to a NAS or PC share (`smb://…`). If Plex watches that folder, recordings show up in Plex. You can also choose not to keep a copy on the TV.
- **Plex**:
  - Link your account with a code at plex.tv/link.
  - Your Plex "Continue watching" then appears on Home.
  - It plays in Tuner and keeps Plex up to date with where you got to.
- **Football live scores & goal alerts**: uses a free football-data.org key. It's also on the Sport screen (⚽ Live scores). Follow teams to get "GOAL" pop-ups and a `goal` event for Home Assistant.
- **Record from your phone**: the phone remote has a new **Record** tab. Search the guide, then tap ● to record or 🔁 for a series link.
- **Update Tuner from your computer**: Settings › Update Tuner shows an address to open in your PC's browser. Upload the new `app-release.apk` there and confirm on the TV. Alternatively, Tuner can check a GitHub repo's releases.

## What was new in 5.1
- **What's On** (top bar): what's on now, at 6/8/9/10pm or tomorrow night, filtered by Sport, Film, News, Kids, Factual, Entertainment, Drama or Music, with an option for favourites only.
- **Sleep timer** (player menu, or Settings): 15 minutes to 2 hours, or at the end of the programme. It also sends a `sleep_timer` event to Home Assistant, so you can turn the TV off too.
- **Night mode sound**: quieter loud bits and clearer speech. Audio passthrough needs to be off for it to work.
- **Subtitles**: five sizes, with an optional dark box behind them.
- **Match the TV's frame rate** (off by default): switches to 50 Hz for UK channels, 24 Hz for films and so on.
- **Better logos**: channels with no logo borrow the guide's, and a broken logo shows the channel's initials.
- **Watch time** (Settings › This TV): this week by day, top channels and kinds of programme, for each profile.
- **Look**: six highlight colours and four backgrounds.
- **TV's Live tab**: Tuner can add your favourites (or all channels, up to 600) and the next day's guide to the TV's own channel list. See "Google TV extras" below.
- **Voice**: "Hey Google, play BBC One on Tuner".
- **Screensaver**: a clock plus what's on now on your favourites.
- **Home Assistant phone alerts**: reminders are sent even when Tuner is closed, and the event includes a `link` to open the channel. There are also `recording_finished` and `recording_failed` events. An example automation is below.
- **Copy to another TV**: Settings › This TV. One TV shows a 4-digit code and the other finds it on the Wi-Fi.

## What was new in 5.0
- **Your other apps**: an **Apps** page in the top bar, plus a **Your apps** row on Home – Plex, BBC iPlayer, Netflix, YouTube, Disney+, Prime Video, ITVX, Channel 4, My5 and NOW first, then everything else A–Z. OK opens the app; hold OK to hide it from Home.
- **Top picks for you** row on Home: programmes on now or starting soon on the kinds of channels and programmes you watch most.
- **Programme pictures** in tiles, the Home hero and the TV Guide panel, wherever your guide includes them. They appear after the next guide update; nothing needs re-downloading.
- **Series link**: hold OK on a programme and pick *Series link* to record every episode on that channel. New episodes are picked up whenever the guide updates, including overnight. Manage them on the Recordings page.
- **Smart stream recovery**: a stream stuck buffering for 12 seconds is reconnected; if it keeps failing, Tuner tries backup links (the other Xtream format, and same-named channels such as the FHD/HD versions).
- **Opens where you left off**: Settings › *When Tuner opens* → Home, the TV Guide on your last category and channel, or straight into the last channel.
- **Update when Tuner starts** can be turned off, so playlists only update overnight.
- **Remote shortcuts** that work from any screen (see below), and **hold ▼** while watching for a favourites strip.
- **Kids Home**: kids profiles get big channel tiles and a "minutes of TV left today" count.
- **Problem log** (Settings › Help): stream drops, backup links used, failed updates and crashes. With the phone remote on it's also at `http://<tv>:8765/log.txt?t=<remote token>`.
- **Speed**: Home's sport and top-picks rows are worked out in the background, logos and pictures are cached on disk, and video starts a little sooner.

## What was new in 4.1
- **Sky-style look**: navy and blue theme, a **Home** screen with a menu bar along the top, a big "now" area with the live picture, and rows of tiles (Continue watching, Favourites, Sport on now, Recordings, Carry on watching, All channels).
- **TV Guide** (list view): categories as tabs across the top, channel list on the left, live picture and programme details of the highlighted channel on the right. *Grid guide* is one press away.
- **Faster category browsing**: channels are pre-sorted by category, and the list only switches when you pause on a category (or press ▼).
- **Player info bar**: channel number in a blue badge, with NOW and NEXT labels.
- **Playback fixes**: audio is decoded to PCM by default (fixes video freezing while sound continues), safer stream handling, no refresh-rate switching, and decoder fallback. *Stream info* now shows frames shown/dropped and the decoders in use.
- **Speed**: release builds are optimised with R8 (install `app-release.apk`), logos are decoded small, list scrolling is quicker, and the guide refresh runs at low priority.

## Added in 4.0
- **Pause live TV for up to 90 minutes (timeshift)**: pausing now hands the stream to a recorder that saves it to storage. Resume where you left off, ◀ ▶ skip back and forward, and *Jump to live* from the menu. It uses the same single provider connection, and you can turn it off in Settings › Playback.
- **Mini guide while watching**: OK shows the info bar, and OK again opens the mini guide. ▲▼ browse other channels and ◀▶ move through their programmes. OK watches, sets a reminder, or plays catch-up.
- **Sport**: a new button on the main screen lists today's and tomorrow's sport from your guide, sorted into football, rugby, cricket, F1, darts and snooker, and more. Duplicate HD/SD feeds are merged. OK watches or sets a reminder; hold OK to pick another channel or record.
- **Channel health check**: Settings › Channel health. It checks favourites (or every visible channel) one at a time, marks dead ones ⚠ offline, can hide them, and can run every night after the guide refresh. Manage › Offline lists them. It stops playback while checking, because of provider connection limits.
- **Fast channel switching** (off by default): keeps the next channel running muted with no video, so ▲▼ switches almost instantly. It needs a provider that allows 2 connections.
- **Phone remote**: Settings › Phone remote, then scan the QR code. Your phone gets a D-pad, OK, Back, Menu, CH+/−, number keys and play/pause, plus a searchable channel list showing what's on (tap to watch) and what's playing now. Add it to your phone's home screen. It works while Tuner is open and the phone is on the same Wi-Fi.
- **Films & Series** now share one button on the main screen, with a switch between them.

## Added in 3.0
- **Set up from your phone**: Settings › 📱 *Set up from your phone* (also on the first screen) shows a QR code. Scan it to add playlists or Xtream logins, set the Home Assistant webhook, or back up and restore from your phone's browser. It only works while that screen is open, and the link has a one-off code.
- **Profiles**: each profile has its own favourites, recents and last channel, and you're asked "Who's watching?" on launch when there's more than one. **Kids profiles** only see the groups you allow, have no Films, Series, Recordings or Settings, and can have a **daily time limit**. Leaving a kids profile needs the PIN, and a grown-up can add 30 minutes with the PIN.
- **Start on the last channel**: Settings › Profiles.
- **Fix TV guide matches**: hold OK on a channel › *Fix TV guide match*, then search the guide's channel list and pick the right one.
- **Backup and restore**: Settings › Backup, or download and upload from your phone.
- **Multi-view**: in the player, hold OK › *Multi-view: 2 / 4 channels*. Sound follows the highlighted picture, OK changes its channel, and BACK goes full screen on the one you're listening to.
- **Recording**: in the guide, hold OK on a programme › *Record*, or in the player use *Record this programme* / *Record for an hour*. Recordings run in the background with Tuner closed, survive a reboot, and save as .ts files to internal storage or a USB drive (Recordings › Storage). You can watch a recording from the start while it's still recording.
- **Genre colours**: guide cells get a colour strip by category (sport, film, news, kids…), with a legend in the guide header.
- **"Hey Google, pause"**: Tuner now has a media session, so voice and remote media keys control it.
- **Home Assistant events**: see below.
- **Database**: channels and guides are now stored in SQLite and written straight from the download. Big playlists and guides use much less memory, and search is instant. After updating, the first launch re-downloads everything once.

## Added in 2.0
**Watching**
- **Channel list over the video**: press ◀ while watching. Groups are on the left and channels on the right, and the video keeps playing behind.
- **Previous channel**: press ▶ (or the LAST button) to flip back to the last channel.
- **Number entry**: type 1-0-1 on the remote to jump to channel 101.
- **Pause live TV**: from the menu, or Play/Pause on remotes that have it. It keeps buffering for as long as memory allows (see limits). ◀ ▶ rewind/forward on streams that support it (most HLS and all catch-up).
- **Picture-in-picture**: press Home while watching, or pick it from the menu, if your TV supports it.
- **Live preview**: back out of the player and the channel keeps playing in a small window on the Channels and Guide screens.

**Guide**
- **Catch-up**: past programmes on catch-up channels show ↺ in green. Press OK to watch. Works with Xtream `tv_archive` and the M3U `catchup` modes (default, append, shift, flussonic, xc).
- **Watch from the start**: available on programmes that are on now.
- **Reminders**: press OK on an upcoming programme. About a minute before it starts, a pop-up appears and switches channel after 15 seconds unless you dismiss it.
- **Search**: search channels and programme titles (on now, upcoming, and catch-up).
- **Group picker inside the guide**.

**Library**
- **Films and Series** (Xtream providers): poster grids by category, seasons and episodes, resume points, "Continue watching", and next-episode autoplay.
- **Multiple playlists**: add as many as you like. You can use one at a time or all of them together.
- **Manage groups and channels**: hide, reorder or lock groups; reorder favourites; hide and unhide channels.
- **Parental PIN**: locked groups need the PIN once each time Tuner is opened.

**Polish**
- **Auto-reconnect**: dropped streams retry quietly (1 s, 2 s, 4 s … up to 30 s).
- **Stream info**: shows resolution, frame rate, codec, bitrate, audio format and buffer.
- **Track selection**: audio track and subtitles, from the menu.
- **Background guide refresh**: playlists and guides update every night at about 4am.
- **Home screen**: a "Tuner – Favourites" row, plus films and episodes in Continue watching (see limits).
- **Home Assistant control**: see below.

## Remote controls
| Where | Button | Action |
|---|---|---|
| Lists | OK / hold OK | Watch / options menu (favourite, hide, catch-up, reorder) |
| Guide | OK | On now → watch · finished → catch-up · upcoming → reminder |
| Guide | Hold OK | Menu: watch live, watch from start, remind me, favourite |
| Guide | ◀ ▶ at the edge | Move back/forward 30 min |
| Live TV | ▲ ▼ / CH+ CH− | Change channel |
| Live TV | ◀ | Channel list overlay |
| Live TV | ▶ | Previous channel |
| Live TV | 0–9 | Jump to channel number |
| Live TV | OK | Show info · OK again opens the mini guide (or resumes if paused) |
| Mini guide | ▲▼ / ◀▶ / OK | Other channels / their programmes / watch, remind or catch-up |
| Timeshift | ◀ ▶ | Skip back 10 s / forward 30 s (forward past live = back to live) |
| Any player | Hold OK / MENU | Options: pause, audio, subtitles, stream info, PiP, catch-up… |
| Films / catch-up | OK | Play/pause |
| Films / catch-up | ◀ ▶ | Back 10 s / forward 30 s |
| Live TV | Hold ▼ | Favourites strip |
| Anywhere | Red | Sport |
| Anywhere | Green / DVR | Recordings |
| Anywhere | Yellow | Search |
| Anywhere | Blue | TV Guide on Favourites |
| Anywhere | Guide | Grid guide |
| Anywhere | TV | Back to the last channel |
| Apps | OK / hold OK | Open the app / hide or show it on Home |

Colour, Guide and TV buttons only work if your remote has them (the standard Chromecast remote doesn't; many TV remotes do).

## Home Assistant
Tuner opens links of the form `tuner://play?name=BBC One` or `tuner://play?number=101`.

**With the Android TV Remote integration** (no ADB needed):
```yaml
script:
  tuner_play_channel:
    alias: Play channel in Tuner
    fields:
      channel:
        example: BBC One
    sequence:
      - action: media_player.play_media
        target:
          entity_id: media_player.google_tv   # your Android TV Remote entity
        data:
          media_content_type: url
          media_content_id: "tuner://play?name={{ channel | urlencode }}"
```

**With the ADB (androidtv) integration**:
```yaml
      - action: androidtv.adb_command
        target:
          entity_id: media_player.google_tv_adb
        data:
          command: >-
            am start -a android.intent.action.VIEW -d "tuner://play?name={{ channel | urlencode }}"
```
Then say "run Play channel in Tuner with BBC One", or use it in an automation, for example to switch to the football at kick-off. Names are matched loosely, so "bbc one", "BBC One HD" and "BBC 1" all work as long as the name is close.

### Tuner → Home Assistant (what's playing)
Set the webhook URL from your phone, e.g. `http://homeassistant.local:8123/api/webhook/tuner`. Tuner then sends:
`playing`, `programme_changed`, `stopped`, `paused`, `resumed`, `lights`, `reminder`, `recording_started`, `recording_finished`, `time_limit_reached`, `bedtime_reached`, `goal`, `test`.
Each event includes `profile`, and most include `channel`, `title`, `category` or `kind`. 5.4 adds `crash` (with `summary`, `version`, `log`), `still_watching_timeout` and `bedtime_reached`.

```yaml
template:
  - triggers:
      - trigger: webhook
        webhook_id: tuner
        local_only: true
    sensor:
      - name: Tuner now playing
        # Only "playing", "programme_changed" and "stopped" change it; other events keep what was there.
        state: >-
          {% set e = trigger.json.event %}
          {% if e in ['playing', 'programme_changed'] %}{{ trigger.json.title }}
          {% elif e == 'stopped' %}Off
          {% else %}{{ this.state }}{% endif %}
        attributes:
          event: "{{ trigger.json.event }}"
          channel: "{{ trigger.json.channel if trigger.json.event in ['playing', 'programme_changed'] else this.attributes.get('channel') }}"
          category: "{{ trigger.json.category if trigger.json.event in ['playing', 'programme_changed'] else this.attributes.get('category') }}"
          profile: "{{ trigger.json.profile }}"
          logo: "{{ trigger.json.logo if trigger.json.event == 'playing' else this.attributes.get('logo') }}"
    binary_sensor:
      # On while Tuner wants the room dimmed (Settings › Home Assistant › Cinema lights).
      - name: Tuner cinema lights
        state: >-
          {% if trigger.json.event == 'lights' %}{{ trigger.json.state == 'dim' }}
          {% else %}{{ this.state == 'on' }}{% endif %}

automation:
  - alias: Dim the lights when a film starts on Tuner
    triggers:
      - trigger: state
        entity_id: sensor.tuner_now_playing
    conditions:
      - condition: template
        value_template: >-
          {{ 'film' in (state_attr('sensor.tuner_now_playing', 'category') or '') | lower }}
    actions:
      - action: light.turn_on
        target:
          entity_id: light.living_room
        data:
          brightness_pct: 20
```

**Cinema lights** – dims when you watch, puts the lights back exactly as they were when you pause or stop. Turn it on in Settings › Home Assistant › Cinema lights, add the `binary_sensor` above, then:
```yaml
automation:
  - alias: Tuner cinema lights
    mode: restart
    triggers:
      - trigger: state
        entity_id: binary_sensor.tuner_cinema_lights
        to: ["on", "off"]
    actions:
      - choose:
          - conditions: "{{ trigger.to_state.state == 'on' }}"
            sequence:
              - action: scene.create          # remember how the lights were
                data:
                  scene_id: before_tuner
                  snapshot_entities:
                    - light.living_room
              - action: light.turn_on
                target:
                  entity_id: light.living_room
                data:
                  brightness_pct: 10
                  transition: 3
          - conditions: "{{ trigger.to_state.state == 'off' }}"
            sequence:
              - action: scene.turn_on         # put them back
                target:
                  entity_id: scene.before_tuner
                data:
                  transition: 2
```
Add more lights to both lists (`snapshot_entities` and the `light.turn_on` target) as you like.
`tuner://stop` stops playback (same way as `tuner://play`).

## Google TV extras

**Live tab.** Settings › Google TV › *Channels in the TV's Live tab* → Favourites. Tuner copies them in about 20 seconds later, then again overnight and whenever favourites change. On some Google TV devices the Live tab only appears after restarting the TV, and some only show channels from apps Google has partnered with. If they don't appear in the Live tab, they still appear in the "Live Channels" app (free on the Play Store).

**Screensaver.** Google TV hides other apps' screensavers in its settings. To turn it on once from a computer, use adb:
```
adb shell settings put secure screensaver_components com.titanejstudios.tuner/.tv.TunerDream
```
To go back to Google's ambient mode:
```
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
```

**Voice.** Google decides which app handles "play … on Tuner". If it opens YouTube or does nothing, use a Home Assistant script with `tuner://play?name=BBC%20One` instead, which always works.

## Home Assistant: buzz your phone

```yaml
automation:
  - alias: "Tuner – programme starting"
    trigger:
      - platform: webhook
        webhook_id: tuner
        local_only: true
    condition:
      - "{{ trigger.json.event in ['reminder', 'recording_failed'] }}"
    action:
      - service: notify.mobile_app_your_phone
        data:
          title: >-
            {{ 'Starting soon' if trigger.json.event == 'reminder' else 'Recording failed' }}
          message: "{{ trigger.json.title }} on {{ trigger.json.channel }}"
```
Change `notify.mobile_app_your_phone` to your own phone's notify service. (If you already use the `tuner` webhook for the template sensor, add this as a second automation on the same webhook id. Home Assistant allows one trigger per webhook id, so merge them into one automation with a `choose:` if it complains.)

### Crash alerts on your phone
```yaml
automation:
  - alias: Tuner crashed
    triggers:
      - trigger: state
        entity_id: sensor.tuner_now_playing
        attribute: event
        to: crash
    actions:
      - action: notify.mobile_app_your_phone
        data:
          title: Tuner crashed
          message: "{{ state_attr('sensor.tuner_now_playing', 'summary') }}"
```
For this, add `summary: "{{ trigger.json.summary }}"` to the sensor's attributes above. (The `crash` event is sent the next time Tuner starts, because a crashing app can't reliably send anything.)

### "What's on?" by voice
Turn on the phone remote (Settings › Phone remote) and note its address and token, e.g. `http://192.168.1.50:8765/remote?t=abcd2345`. Tuner answers while it's open on the TV.
```yaml
rest_command:
  tuner_whats_on:
    url: "http://192.168.1.50:8765/api/whatson?t=abcd2345&kind={{ kind }}&when={{ when }}"
    method: GET

automation:
  - alias: Tuner – what's on
    triggers:
      - trigger: conversation
        command:
          - "what's on [tonight]"
          - "what {kind} is on [tonight]"
          - "what {kind}'s on [tonight]"
    actions:
      - action: rest_command.tuner_whats_on
        data:
          kind: "{{ trigger.slots.kind | default('') }}"
          when: "{{ 'tonight' if 'tonight' in trigger.sentence else 'now' }}"
        response_variable: result
      - set_conversation_response: "{{ result.content.speech }}"
```
- `kind`: sport, football, film, news, kids, documentary, drama, music, or leave it empty for anything.
- `when`: now, tonight (9pm), 6pm–10pm (e.g. `8pm`), or tomorrow (8pm).

The answer lists up to four programmes, favourites first. The JSON also has `items` with each channel's id and number. To switch over, use the "Play channel in Tuner" script above (for example "put BBC One on").

## Limits worth knowing
- **Recording** saves the stream as it arrives. Plain MPEG-TS and HLS with TS segments work. Encrypted HLS streams and providers that only allow one connection at a time won't record while you're watching another channel. To start on time with Tuner closed, the TV must be on or in standby (not unplugged). A Chromecast with Google TV's internal storage is small, so a USB drive (via a USB-C hub) is best.
- **Multi-view** needs one hardware decoder per picture. Most Google TV sticks manage 2 HD pictures; 4 often fails, and the tile says so.
- **Kids time limits** count time actually spent watching in Tuner. They can't stop other apps.
- **Timeshift** needs free storage (it won't start with less than 500 MB free) and stops after 90 minutes. Rewinding restarts playback from the nearest point, so expect a second of black. Channel changes discard the buffer.
- **Fast channel switching** and **health checks** open extra connections. If your provider allows only one, leave fast switching off and run checks when nobody is watching (the nightly option).
- **Sport finder** relies on your guide's titles and categories. A match listed as just "Liverpool v Arsenal" is assumed to be football.
- **Pausing live TV** buffers into memory (about 160 MB), which is a few minutes of HD or around 10 minutes of SD. After that the stream reconnects at live. Rewinding a paused live channel only works on HLS streams with a DVR window. Plain MPEG-TS can be paused but not rewound, so use catch-up for that.
- **Reminders** only pop up while Tuner is open. Android TV doesn't let apps show alerts over other apps.
- **Home screen row**: Android TV shows it. Google TV only shows rows and Continue watching from apps it has approved, so on a Chromecast with Google TV these may not appear. Nothing breaks either way.
- **Picture-in-picture** depends on the TV. If it's not supported, the menu option is hidden.
- **Programme pictures** and **Top picks** depend on the guide. Many IPTV guides don't include pictures, in which case you'll see logos as before.
- **Series link** matches by programme title on one channel, ignoring "New:" and episode numbers. It only records what's in the guide (usually the next day or two), so it tops up each time the guide updates.
- **Backup links** only exist if your playlist has another copy of the channel (or it's an Xtream login). Each one may use a connection on your account.
- **Opening other apps**: Back or Home on the remote returns you to Tuner. Google TV may not list some apps that don't have a TV launcher entry.
- **Multi-view**: four pictures need four video decoders at once. Most Google TV boxes have four, but some cheaper sticks have only two or three. The fourth picture then shows "Your TV can't decode this many streams at once".
- **Plex** plays files directly. Something Plex would normally convert (some 4K HDR or unusual audio) may not play in Tuner; hold OK › *Open the Plex app* instead.
- **Football scores** on the free football-data.org plan only cover their free leagues, and scores can lag a little behind the TV.
- **App updates** must be signed with the same key. Build on the same computer as before, otherwise Android refuses the update.
- **Night mode** and **frame-rate matching** depend on the TV's hardware; if the TV doesn't support them, Tuner says so in a notice or the problem log.
- **Copy to another TV** sends playlist logins over your home network, protected by the 4-digit code. The code stops working after 5 wrong tries or when you leave the screen.
- **Films and Series** need an Xtream Codes login. M3U playlists usually put films into ordinary groups instead.
- **New episodes** only knows about series you've watched in Tuner (the last 25). It checks once a day, one series at a time, a few seconds after Tuner starts.
- **Kids bedtime and limits** only apply inside Tuner, and the "30 more minutes" at bedtime lasts until Tuner is closed.
- **Check everything** uses your provider connection for a few seconds for the test stream, and sends a `test` event to Home Assistant. If someone else is watching on a one-connection account, the stream test may fail.
- **Keep TVs in sync** needs every TV to use the same network drive folder. It syncs a few seconds after a change and every 10 minutes while Tuner is open. Each TV keeps its own recordings, profiles and settings.
- **Film info** matches by name. Odd provider names can occasionally pick the wrong film; the info card shows the title it found.
- **Skip intro** is learned per series, so it can be off for an episode with a different-length intro. Skip it manually once and Tuner relearns it.
- **Safe mode** turns off audio passthrough and stream compatibility for the players it builds, so leaving safe mode brings those two back only after Tuner is closed and opened again.

## Automatic builds on GitHub
GitHub builds Tuner for you, runs the tests, and can publish each version so the TV updates itself.

1. **Make a GitHub repository** (it can be private) and upload this whole `Tuner` folder to it. GitHub Desktop is the easiest way: File › Add local repository › pick the Tuner folder › Publish. From then on, every time you press *Push*, GitHub builds it. You'll see a ✓ or ✗ on the repo's **Actions** tab.
2. **Give GitHub your signing key**, so its APKs install over the ones you built yourself. On your PC the key is `C:\Users\<you>\.android\debug.keystore`. In PowerShell:
   ```
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
   ```
   Then on GitHub: repo › Settings › Secrets and variables › Actions › New repository secret. Use the name `DEBUG_KEYSTORE_BASE64` and paste the copied text as the value.
   - Also add a secret called `TMDB_KEY` containing your TMDB key, so GitHub builds have film info built in too. (On your PC the key lives in `keys.properties`, which is deliberately never uploaded.)
3. **Get the APK**: open the build on the Actions tab › Artifacts › `tuner-apk`.
4. **Let the TV update itself**: in GitHub Desktop, Repository › Create tag, enter `v5.4.1` (always a higher number than last time), then push. GitHub then publishes a release with the APK. On the TV: Settings › Update Tuner › set the repo (`yourname/Tuner`) › Check now › Install.
   - The TV can only see releases of a **public** repository. If yours is private, download the APK from the release and use the "From your computer" upload instead.

If a build goes ✗, open it and send me a photo of the red error lines.

Version numbers: the version is set in `app/build.gradle.kts` (`"5.4"`), or comes from the tag for GitHub releases. The internal version code is worked out from it (5.4.1 → 50401), so a newer version always installs over an older one.

## Build the APK
1. Open this folder in **Android Studio** (File → Open), and let Gradle sync.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)** gives you `app/build/outputs/apk/debug/app-debug.apk`.
3. Install it over the old version. Everything from 2.0 carries over; the first launch re-downloads playlists and guides into the new database.

## Install on the Google TV
- **Send Files to TV / Downloader**: enable developer options (Settings → System → About → click *Android TV OS build* 7×), allow *Unknown sources* for that app, then send and open the APK.
- **ADB**: `adb connect <tv-ip>:5555` then `adb install -r app-debug.apk`.

## Project layout
```
app/src/main/java/com/titanejstudios/tuner/
  MainActivity.kt            entry point, deep links, PiP, pop-ups
  TunerViewModel.kt          state: navigation, playlists, playback, reminders, PIN, search, VOD
  features/                  self-contained parts: Plex, football scores, app updates, Check everything
app/src/test/                tests of the pure logic (run on GitHub with every build)
.github/workflows/build.yml  the GitHub build
  data/                      M3U, Xtream, XMLTV, catch-up URLs, SQLite database, settings, repository
  recording/                 recorder (TS + HLS), background service, alarms, reboot handling
  integrations/              phone setup web server + QR, Home Assistant webhook
  playback/                  shared ExoPlayer (reconnect, pause buffer, tracks, stats, media session), multi-view
  tv/HomeScreen.kt           Favourites row + Continue watching
  tv/GuideRefreshWorker.kt   nightly refresh
  ui/                        Channels, Guide, Player, Search, Films/Series, Settings, Manage, dialogs
```

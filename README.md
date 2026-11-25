# Flicker 5.4 – IPTV player for Google TV, Firestick and Android phones

*Flicker was called Tuner. The app ID (`com.titanejstudios.tuner`), the GitHub repo and backups keep the old name so updates install over the top and nothing is lost.*

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
- **Who's watching? after the screensaver**: with more than one profile, the TV asks which profile is watching whenever Flicker's artwork screensaver, the TV's own screensaver or standby ends.
- **YouTube** (Home › ▶ YouTube, or Settings): search YouTube and play it inside Flicker in YouTube's own player (remote: OK play/pause, ◀ ▶ 10 s, ▼ options – pause, ±10 s, start again, subtitles on/off, speed, ⏭ next, more from this channel; BACK closes; phone: tap, double-tap a side, options along the bottom). At the end of a video the next one in the list plays. Subtitles are off unless you turn them on; ✕ Clear search empties the search. Each profile signs in to its own YouTube (a code on google.com/device, tick the YouTube box) for "New on your YouTube" on Home – the latest from the channels you subscribe to. Film trailers play here too; a video whose owner doesn't allow other apps opens in the YouTube app instead. Google and YouTube always bypass Flicker's VPN (YouTube asks VPN users to "confirm you're not a bot"). YouTube Shorts are left out of search and subscriptions. **More YouTube**: share a video from the YouTube app on your phone to Flicker and it plays on the TV (or 📺 Watch on TV from a video's menu); your **liked videos** and **playlists** (signed in); **Watch later** in Flicker (hold OK on any video, ▶ Play all); **channel pages** (Newest / Most popular, ▶ Play all); **chapters** from the description (CH+/CH− or the ⏮/⏭ Chapter buttons, marks on the bar); **sponsor skipping** with SponsorBlock (sponsor reads, self-promotion and subscribe reminders – on/off in the player options); tiles show the length, LIVE / PREMIERE and a red bar for part-watched; hold OK on a recommendation for **Not interested** or **Fewer from this channel**; 🎤 voice search, recent searches and filters (Long videos, Live now, This week); and each profile's YouTube sign-in, history (saved every half minute while playing), Watch later and recommendations **sync** between your devices and houses – sign in once and every TV and phone has it; signing out on one signs that profile out everywhere. Flicker remembers what each profile watches on YouTube: part-watched videos carry on where you stopped (in Carry on watching), and **Recommended on YouTube** on Home picks new videos from the channels that profile watches most plus similar finds (updated every few hours). Kids' profiles choose (Settings › Profiles): **Only channels I pick** (a grown-up searches for channels and ticks them; the child sees just those channels' newest videos, with a button per channel and no searching), **Search** (strict SafeSearch) or **Off**. If the YouTube Kids app is installed, kids also get a **YouTube Kids** button that opens it with its own kids' profiles. Kids' profiles get YouTube too (a ▶ YouTube button on their Home, strict SafeSearch, their own sign-in if they have one, and it counts towards their daily TV time and bedtime) – turn it off per profile in Settings › Profiles. Needs the one-off setup below.
- **Each profile's own Continue watching**: profiles other than "Everyone" start empty – Plex's Continue watching and your films and episodes only show what that profile has started in Flicker (on any of your linked or synced devices, matched by profile name), with Plex's own progress. "Everyone" still shows everything. Hold OK on a Plex tile › Remove from my Continue watching.
- **Sync between houses** (Settings › ☁ Sync between houses): sign each device in to the same Google account (type a code on google.com/device). Where you got to in films and episodes, My list, followed series, your teams, learned intros and profiles (with kids' settings) sync through one small file in a "Flicker" folder in that account's own Google Drive (a few KB; Flicker can only see files it made), every 3 minutes, whenever you stop something or change a profile, and when the profile picker opens. Name each house; favourites stay per house unless you turn on sharing. Needs the Google sign-in set up once – see "Google Drive sync" below.
- **Buttons on live TV**: press OK while watching a channel for the info bar with buttons – ⏸ Pause / ▶ Play, ⏪ ⏩ 30s and ● Live (when paused or behind), ⏮ From the start (catch-up), ● Record, ☰ Guide, 📺 Channels, ★ Favourite, Subtitles, Audio and ⋮ More. ◀ ▶ to move, OK to press, ▲ ▼ still change channel, BACK or 8 seconds hides them.
- **Faster backups** (live TV): if the provider says a channel isn't there, Flicker switches to a backup at once; a frozen picture gets one reconnect (after 8 seconds) before switching; a server that doesn't answer within 7 seconds counts as down. Backups go best quality first (FHD → HD → SD), and a message says which one it switched to. On a lower-quality backup it checks the main stream every 2 minutes, and says once when it's working again – OK (or tap) switches back. Films and catch-up keep the more patient reconnecting.
- **TV remote on your phone** (phones, once linked): Home › 📺 TV remote or Settings. Buttons (▲▼◀▶ OK, Back, Home, Guide, ⋮, CH ±, ⏯ ⏪ ⏩) and the TV's channels with what's on – tap one to put it on the TV. Shows what the TV is playing.
- **Watch on TV** (phones, once linked): hold a channel, film or episode (or a catch-up programme in the guide) › 📺 Watch on TV. Asks which TV if there's more than one.
- **Missed it?**: a Home row of programmes from the last week that suit what you watch, ready on catch-up (once Flicker has learned your taste).
- **Finish what you started**: switch off half-way through a live programme (after 10+ minutes) and it appears first in Carry on watching – OK carries on from where you stopped on catch-up; hold to forget it.
- **Christmas TV planner** (Home › 🎄 Christmas TV, from late November): festive specials, Christmas films and the big sport (the darts and your teams) up to New Year, by day. Pick one to remind or record; ✓ Picked shows your choices. It fills up as the guide reaches further.
- **Match day**: when your team plays within 12 hours, Home leads with it – kick-off countdown and channel (a card on phones, a highlighted button on TVs); from 15 minutes before kick-off it plays the match straight away.
- **Form and table**: My teams shows each team's league place, points, goal difference and last five results (W D L); open a fixture for the head-to-head of their last 10 meetings.
- **Text size** (Settings): Normal, Large or Larger text everywhere, per device. **🗣 Clearer speech** (Settings, or ⋮ in the player): lifts voices over music and effects and turns down rumble (needs Audio passthrough off).
- **My teams** (Sport › ★ My teams, or Settings): add your team from the list (the English, Scottish, Welsh and Irish leagues, and the big European ones). Their fixtures come from ESPN's public data (no key needed), cups included, and Flicker checks the TV guide around each kick-off for which of your channels shows it. Choose one to watch it, set a reminder or record it. Home shows the next game ("⚽ Newcastle v Arsenal · Sat 17:30 · Sky Sports Main Event"), and on match day Flicker says which channel it's on. Teams added by name only, or any time ESPN can't be reached, have their games found in the TV guide instead.
- **Linked devices** (Settings › 🔗 Linked devices): show a 4-digit code on one device and enter it on the other (same Wi-Fi) – or copy settings to another TV, which links it too. Then:
  - **Send to TV / phone**: ⋮ › *Send to another TV or phone* (📺 on the phone's player) hands over the channel, catch-up, film or episode at the same spot, and stops it here. Flicker must be open on the other device (a TV can have it in the background).
  - **Carry on where you left off**: films and episodes, favourites, My list, followed series and learned intros stay in step. When you stop watching on one device, the others pick it up straight away; finished films don't come back.
- **Downloads** (phones): hold a film or episode › ⬇ Download. Settings › ⬇ Downloads (or the Home button) shows progress, plays them offline and deletes them. Wi-Fi only by default. Streams split into pieces (HLS) can't be saved.
- **"On now" widget** (phones): add Flicker's widget to the home screen for your first four favourites with what's on now; tap one to watch.
- **Your usual channel**: Flicker learns which channel you watch at which time (weekdays and weekends apart). Open it at that time and Home offers "★ Your usual: BBC One". Settings › Offer my usual channel.
- **Skip intro**: phones can tap the button; ⋮ › *The intro ends here* teaches it by hand, and *Forget this series' intro* resets it.
- **Scrub previews**: hold ◀ / ▶ on films, episodes, catch-up and recordings to scrub (faster the longer you hold) with a picture of where you'll land; on a phone, drag the bar. Pictures are always on for Plex, downloads and recordings; for IPTV it's Settings › Preview pictures when skipping (off by default, as it needs a second connection).
- **Phones – better**: Films & Series like Netflix (Films | Series | Plex tabs, 🔍 search, rows: Carry on watching, My list, Recently added, Top rated and one per category; "See all ›" gives a grid you can sort by Recently added, Rating, Year or A–Z); clean titles with the year and ★ rating underneath, title cards when there's no poster; adult titles hidden (⋯ to show them; always hidden for kids); pull down to refresh on Home, the guide and Films & TV; a **mini player** above the menu bar keeps playing when you leave a video (⏸, ✕, 📺 carry on on the TV); menu bar: Home, TV Guide, Films & TV, ▶ YouTube, More (TV remote, Search, What's On, Catch-up, Sport, Plex, Recordings, Downloads, Settings); 📺 TV remote on Home.
- **Phones (stage 1)**: the first time Tuner opens on something that isn't a TV it asks "phone or TV?" (change it in Settings › This device is a). Phone mode: tap = OK, press and hold = hold OK; a menu bar along the bottom (Home, TV Guide, Films & TV, Search, More); the player turns sideways and full screen, tap for controls (back, channels, menu, ⏮ ⏯ ⏭ or ⏪ ⏩ and a scrub bar), swipe up/down in the middle to change channel, on the left for brightness and the right for volume, double-tap a side to jump 10 seconds. Stage 2 (phone layouts): Home (featured picture, quick buttons, smaller tiles), TV Guide (category chips and a channel list with now/next), Films & Series and Plex (chips and poster grids), Plex shows and series (small header), Catch-up, Settings (rows wrap), Recommendations, VPN and Home rows stack, and dialogs fit the screen. Stage 3: the grid guide on phones shows an hour with channel logos, category chips and Earlier / Now / Later buttons; Sport, What's On, Recordings and Search have phone spacing. Stage 4: every remaining screen (watch time, copy to another device, check everything, problem log, updates, channel tidy-up, playlist editing, guide matching, languages, profiles) has slim phone margins; watch time stacks; the PIN and film-info boxes fit; "Set up from your phone" becomes "Set up from a computer" on a phone; the phone remote and TV screensaver rows are hidden on a phone.
- **Flicker for Windows**: every build makes a Windows installer (Flicker-x.y.z.msi). Get it from the run's Artifacts (tuner-windows), or from the Releases page for tagged versions. VLC's engine is inside the installer, so there's nothing else to install.
  - **Sign in once**: Settings › Sync with your TV › Sign in with Google, using the same account as Flicker on the TV (Settings › Sync between houses). The TV's playlists, profiles, favourites, My list and Continue watching come across, and what you watch on the PC goes back. It syncs every 3 minutes while it's open. (The TV adds its playlists to the sync file from this version on, so update the TV first.)
  - **Home**: Continue watching, your channels, My list, Continue on Plex and what's on now.
  - **Live TV**: categories, search, favourites (☆), now/next and catch-up. **TV Guide**: the next day and a half, with your favourites or every channel. Click to watch, set a reminder (it pops up while Flicker's open) or watch on catch-up.
  - **Films and Series**: your provider's catalogue with categories and search, posters, film details from TMDB, My list, and Carry on / From the start. Series open on the season you're part way through, and the next episode plays by itself.
  - **Plex**: link with a code at plex.tv/link, then Continue watching and your libraries. **YouTube**: your profile's subscriptions and search, using the YouTube sign-in from your TV. Videos open in your browser, because YouTube doesn't allow playing inside other apps on a PC.
  - **Mini player** (▣): the video in a small window that stays on top of everything, so you can watch while doing other things.
  - **Multi-view**: two or four channels at once. Click a picture to hear it, or ⤢ to watch it full size.
  - **Recordings**: in the TV Guide, click a programme › ● Record. Recordings go in Videos\Flicker and run while Flicker's open (it can be minimised).
  - **Phone remote and Watch on…**: the PC joins your linked devices through the sync, so the phone's remote and "Watch on…" find it, and 📺 Watch on… sends what's playing on the PC to the TV. Windows asks once to let Flicker use the network: allow it on private networks.
  - **Updates**: checks GitHub once a day and offers to install a new version (Settings › Updates › Check now).
  - **Picture & sound** (⚙ on Live TV and the player): sound track, subtitles, picture shape (fit, fill, 16:9, 4:3) and, for films, speed. Old interlaced channels are smoothed out (Settings › This PC).
  - **Pause live TV**: Space (or ⏸ Pause) on Live TV. Flicker records the channel from that moment, Space again carries on from where you paused, and ● Back to live jumps to now.
  - **Runs in the tray**: closing the window leaves Flicker running, so recordings and reminders carry on. Right-click the tray icon › Quit to close it. It can also start with Windows (Settings › This PC).
  - **Up next and Skip intro**: a countdown to the next episode in the last 30 seconds (Play now or Cancel), and a Skip intro button that uses the intro times your TV has learnt.
  - **Right-click menus** on channels (watch, favourite, remind, record), films (play, from the start, My list, download) and Continue watching (remove).
  - **Volume slider, media keys and even sound**: 🔊 slider up to 150%, keyboard media and volume keys work, and Settings › This PC can even out loud and quiet channels.
  - **Other screen**: 🖥 moves the picture full screen to a second monitor while you keep using the guide.
  - **Hover preview**: rest the pointer on a channel (with nothing playing) for a silent preview.
  - **Films**: 🎲 Surprise me, and a film's page shows the rest of its series.
  - **Profiles with pictures**: colours and emoji like the TV, and you can edit profiles from the PC (it goes back to the TV).
  - **Windows notifications**, **text size** and **colour themes** in Settings › This PC.
  - **Problem log**: Settings › Problem log shows recent errors, with 📋 Copy to send them on.
  - **Kids' limits**: the TV's PIN, daily TV time and bedtime apply on the PC too. A grown-up's PIN gives 30 more minutes, and switching from a kids' profile to a grown-up one needs the PIN.
  - **What's On**: now and coming up by kind, sport (with LIVE badges), and My teams, with your teams' fixtures from ESPN and the channel showing each one.
  - **Search** (top of the menu): channels, the guide (on now, coming up, catch-up), films, series, Plex and YouTube in one go.
  - **Downloads**: ⤓ on a film or episode saves it to Videos\Flicker\Downloads to watch without the internet. Downloaded ones play from the PC automatically. They're listed on the Recordings page.
  - **Remembers the window**: same size and place (and maximised) next time.
  - **Channel numbers**: shown in the channel list. Type a number (on Live TV, Home or the guide) to go straight to that channel.
  - **Keys**: F11 or double-click for full screen, Esc to leave full screen or close a film, Space to pause, ← → to skip back 10s / forward 30s, Page Up/Down to change channel, Ctrl+M to mute.
  - Settings are kept in %USERPROFILE%\.tuner. The code is in desktop/, and it shares the TV app's playlist, guide, Plex, YouTube, film-info and sync code.
- **Built-in VPN** (Settings › VPN): WireGuard, e.g. Surfshark (surfshark.com › VPN › Manual setup › Desktop or mobile › WireGuard › create a key pair › Locations › download a server). Send the .conf file to the TV from your phone (Settings › Set up from your phone › VPN). Only Tuner's traffic uses it, home-network addresses (Plex) stay direct; optional kill switch; reconnects when Tuner opens; 🔒 in the top bar when connected. The VPN key is never put in backups.
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
- **Catch-up page** (Catch-up along the top): pick a channel, then anything from the last few days.
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
- **Record from your phone**: the phone remote has a new **Record** tab. Search the guide, then tap ● to record or 🔁 for a series link.
- **Update Tuner from your computer**: Settings › Update Tuner shows an address to open in your PC's browser. Upload the new `app-release.apk` there and confirm on the TV. Alternatively, Tuner can check a GitHub repo's releases.

## What was new in 5.1
- **Joined a film part way?** Watching a film on live TV that started more than a few minutes ago, Flicker checks your Plex and your provider's films; if it's there, "Watch … from the beginning?" shows for half a minute – OK for yes, then pick where (each Plex copy, or your provider's films), and it plays from the start.
- **What's On** (top bar): a slim strip about the highlighted show (with its channel live beside it), one row of controls (Now ◀ time ▶, 9pm, Day ▾, Kind ▾, ☆ favourites, 🧹 tidy, 🕒 Timeline, 📺 preview), then picture tiles: **Tonight's best** (your team, premieres, new episodes, live sport, shows you like, well-rated films), **Starting soon**, and a row for each kind – or a grid when you pick one kind. OK on a tile watches it (on now) or sets a reminder (later); ▶ / Record on the remote records it; hold OK for more. Badges: LIVE (before it starts too), NEW, PREMIERE, FINAL, JUST STARTED, YOUR TEAM. TV shows without a guide picture get one from TMDB. 🕒 Timeline shows the next three hours channel by channel. 🧹 Tidy hides shopping, adult and radio channels, and other countries' and languages' channels (Indian, Arabic, Polish… – your favourites always stay), and folds +1 channels into "Also on ITV1 +1 at 22:00".
- **Profiles sync** between linked devices and houses (Google Drive sync): a new device takes the existing profiles – names, pictures, kids settings, YouTube choices and each one's favourites – and adding, editing or deleting a profile on one device does the same on the others (matched by name).
- **Kids' Settings**: a kids' profile gets its own small Settings page (change profile, subtitle and text size, clearer speech); everything else needs the parental PIN (🔒 Grown-up settings).
- **Profiles**: any profile can be deleted, Everyone included, as long as one grown-up profile is left.
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

## Google Drive sync (one-off setup)

Flicker signs in to Google with the "TV" sign-in (a code you type on your phone) and only asks to see files it makes itself in Drive (`drive.file` – a non-sensitive permission, so Google doesn't need to review the app; TV sign-in doesn't allow the hidden app-data folder).

1. Go to https://console.cloud.google.com and create a project (e.g. "Flicker").
2. **APIs & Services › Library**: search for **Google Drive API** and press **Enable**.
3. **APIs & Services › OAuth consent screen** (Google Auth Platform): choose **External**, app name **Flicker**, your email for support and developer contact. Under **Data access / Scopes**, add `.../auth/drive.file`. Then **Publish app** (to "In production") – in "Testing" Google signs devices out every 7 days.
4. **Credentials › Create credentials › OAuth client ID**, application type **TVs and Limited Input devices**, name it "Flicker TV". Copy the **Client ID** and **Client secret**.
5. On GitHub: **Settings › Secrets and variables › Actions › New repository secret** – add `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`. (For builds on your PC, put `google_client_id=…` and `google_client_secret=…` lines in `keys.properties`.)
6. Push and make a release. Then on each device: Settings › ☁ Sync between houses › Sign in with Google.

Sign-in is kept on each device and never put in backups or copied between TVs.

## YouTube (one-off setup)

In the same Google Cloud project as Google Drive sync:

1. **APIs & Services › Library**: search for **YouTube Data API v3** and press **Enable**.
2. **APIs & Services › Credentials › Create credentials › API key**. Edit the key: under **API restrictions** pick **Restrict key › YouTube Data API v3**, and **Save**. Copy the key into a GitHub secret called `YOUTUBE_KEY` (or `youtube_key=…` in `keys.properties`). This is for searching.
3. **Google Auth Platform › Data access › Add or remove scopes**: add `.../auth/youtube.readonly` and **Save**. It's a "sensitive" permission, so Google shows "Google hasn't verified this app" when you sign in – press **Advanced › Go to Flicker** (fine for your own family; up to 100 people can sign in like this).

Searches use about 100 of the project's 10,000 free daily units each (roughly 100 searches a day); playing videos uses none, and the subscriptions row uses about one unit per channel every few hours.

## IMDb ratings (optional)

Films and series show their real rating – **IMDb 7.3** – instead of the provider's own numbers (which are often missing or a flat 10.0). Flicker finds each title on TMDB (your TMDB key), then asks OMDb for its IMDb rating; without an OMDb key it shows TMDB's score (★ 7.1) instead. Ratings are remembered for a month.

1. Go to **omdbapi.com › API Key**, choose **FREE (1,000 daily limit)**, enter your email, and click the link in the email to turn the key on.
2. Add it as a GitHub secret called `OMDB_KEY` (or `omdb_key=…` in `keys.properties`), and make a new release.

## More (5.4)

- **🛠 Fix it** – Settings, the player's Options, or hold OK on an error: checks the internet, the VPN and your provider, restarts the stream, clears stuck pictures and reloads an old guide.
- **Which copy to play** – when a film is in more than one place, pick "☆ Always use…" and Flicker stops asking. Change it in Settings › Which copy to play.
- **Age ratings for kids** – each kids' profile can have a UK age limit (U, PG, 12, 15). Films and series rated above it need the grown-ups' PIN. Ratings come from TMDB, so a TMDB key is needed. Titles with no rating are allowed.
- **New episodes** – series you watch are checked every 6 hours. Any with new episodes get a 🆕 badge and appear in the New episodes row on Home.
- **Plex Home users** – Edit profile › Plex user lets each Flicker profile watch as its own Plex Home user, with its own Continue watching and watched list. A protected user needs their Plex PIN once.
- **👫 Watch together** – in the player, Send to… › Watch together starts the same thing on another linked device in the house. Pausing or skipping on either one does the same on the other. This only works between devices on the same network.
- **Faster start-up** – "now and next" appears straight away and the rest of the guide follows. More posters are kept between starts.

## Even more (5.4)

- **📊 Kids' week** – Watch time for a kids' profile now lists what they watched (YouTube included) and anything stopped or blocked (age rating, daily limit, bedtime). On a phone it adds up every linked TV. Open it from More › Kids' week, or Settings › Watch time.
- **🎁 Extra TV time from your phone** – More › Extra TV time sends +15, +30 or +60 minutes to a kids' profile on every linked TV. It asks for the PIN if one is set.
- **Missed while you were away** – "Missed it?" now puts your shows first when they aired while nothing was playing in Flicker.
- **📱 About this** – TV remote › About this on the phone shows the cast, rating and plot of what's on the TV. Tap an actor to see what else they're in, and ▶ On TV plays any of those films you have.
- **Film series** – a film's info shows the rest of its series in order, and the films you have can be played from there. When one finishes, Flicker offers the next one you have.
- **🎲 Surprise me** – in Films, this picks a well-rated film you haven't watched. Choose Play, Another one, or What's it about.
- **Trailers on film info** – the trailer plays silently behind a film's details after a couple of seconds. You can turn this off in Settings › Films & series.
- **Search** – YouTube results are included now, and programmes are grouped into On now, Coming up and Earlier.

## Trakt (optional, one-off setup)

1. Sign in at trakt.tv, go to Settings › Your API Apps (trakt.tv/oauth/applications) and choose **New application**.
2. Name it Flicker and use `urn:ietf:wg:oauth:2.0:oob` as the redirect URI. Save it.
3. In GitHub, go to the repo › Settings › Secrets and variables › Actions. Add **TRAKT_CLIENT_ID** and **TRAKT_CLIENT_SECRET** from the app page.
4. After the next build, go to Settings › Trakt on the TV and enter the code at trakt.tv/activate.

Films and episodes you finish on grown-up profiles are logged, and films Trakt says you've seen count as watched in Flicker. Letterboxd has no public API, so it can't be added.
- **🎤 Say a channel** – say a channel's name or number into any 🎤 Say it button ("BBC One", "Sky Sports Main Event", "101") and Flicker goes straight to it. Anything else is searched for.

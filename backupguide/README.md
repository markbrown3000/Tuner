# Backup TV guide (personal use only)

"Use extra guide sources" in Flicker: after your provider's guide is saved, channels with no
listings (and gaps) are filled from free guides – EPGTalk (UK including Sky, and US),
Freeview-EPG (UK free-to-air) and epg.pw (Hong Kong, including Now Sports) – plus any guide
addresses you add yourself. Off by default.

Matching: guide ID first, then a cleaned-up name ("BBC One HD" = "BBC 1"). The source that worked
for each channel is remembered; wrong matches can be fixed in Settings › Channels & guide ›
Backup guide matches.

## Removing it before a public release

1. Delete this `backupguide` folder.
2. `settings.gradle.kts`: remove `include(":backupguide")`.
3. `app/build.gradle.kts`: remove `implementation(project(":backupguide"))`.
4. Delete `app/src/main/java/com/titanejstudios/tuner/ui/BackupGuideSettings.kt`.
5. Remove the lines marked `Backup guide (remove with the module)` – search the app for
   `BackupGuide` (Repository.kt, SettingsScreen.kt, CloudSyncFeature.kt, TunerViewModel.kt).

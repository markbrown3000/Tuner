package com.titanejstudios.tuner

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ui.TunerColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.titanejstudios.tuner.ui.AppsScreen
import com.titanejstudios.tuner.ui.CatchupScreen
import com.titanejstudios.tuner.ui.MenuDialog
import com.titanejstudios.tuner.ui.MenuItem
import com.titanejstudios.tuner.ui.CategoriesScreen
import com.titanejstudios.tuner.ui.CategoryPicker
import com.titanejstudios.tuner.ui.ChannelsScreen
import com.titanejstudios.tuner.ui.FootballScreen
import com.titanejstudios.tuner.ui.PlexScreen
import com.titanejstudios.tuner.ui.UpdateScreen
import com.titanejstudios.tuner.ui.FrameRateMatcher
import com.titanejstudios.tuner.ui.LogScreen
import com.titanejstudios.tuner.ui.ShareTvScreen
import com.titanejstudios.tuner.ui.StatsScreen
import com.titanejstudios.tuner.ui.WhatsOnScreen
import com.titanejstudios.tuner.ui.EpgMapScreen
import com.titanejstudios.tuner.ui.HomeScreen
import com.titanejstudios.tuner.ui.MultiViewScreen
import com.titanejstudios.tuner.ui.PhoneSetupScreen
import com.titanejstudios.tuner.ui.ProfileEditScreen
import com.titanejstudios.tuner.ui.ProfilePickerScreen
import com.titanejstudios.tuner.ui.RecordingsScreen
import com.titanejstudios.tuner.ui.TimesUpDialog
import com.titanejstudios.tuner.ui.GuideScreen
import com.titanejstudios.tuner.ui.ManageScreen
import com.titanejstudios.tuner.ui.MoviesScreen
import com.titanejstudios.tuner.ui.NoticeToast
import com.titanejstudios.tuner.ui.PinDialog
import com.titanejstudios.tuner.ui.PlayerScreen
import com.titanejstudios.tuner.ui.ReminderPopup
import com.titanejstudios.tuner.ui.SearchScreen
import com.titanejstudios.tuner.ui.SelfTestScreen
import com.titanejstudios.tuner.ui.FilmInfoDialog
import com.titanejstudios.tuner.ui.StillWatchingDialog
import com.titanejstudios.tuner.ui.UpdateDialog
import com.titanejstudios.tuner.ui.SportsScreen
import com.titanejstudios.tuner.ui.SeriesDetailScreen
import com.titanejstudios.tuner.ui.SeriesScreen
import com.titanejstudios.tuner.ui.SettingsScreen
import com.titanejstudios.tuner.ui.SourceEditScreen
import com.titanejstudios.tuner.ui.TunerTheme
import com.titanejstudios.tuner.ui.enterPip

class MainActivity : ComponentActivity() {
    private val vm: TunerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.handleIntent(intent)
        addOnNewIntentListener { vm.handleIntent(it) }
        addOnPictureInPictureModeChangedListener { info -> vm.inPip = info.isInPictureInPictureMode }
        // Buttons pressed on the phone remote arrive here as real key presses.
        lifecycleScope.launch {
            vm.remoteKeys.collect { code ->
                if (code == KeyEvent.KEYCODE_BACK) {
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    val t = SystemClock.uptimeMillis()
                    dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                    dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
                }
            }
        }
        setContent {
            TunerTheme {
                // Settings › Text size: everything's text scaled up (on top of the device's own setting).
                val density = androidx.compose.ui.platform.LocalDensity.current
                val scaled = androidx.compose.ui.unit.Density(density.density, density.fontScale * vm.textScale / 100f)
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides scaled) {
                    TunerApp(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppStarted()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppStopped()
    }

    /** Colour / Guide / DVR / TV buttons work from any screen (see TunerViewModel.handleShortcut). */
    private val shortcutKeysDown = HashSet<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            // The screensaver: OK watches what's showing; any other button just closes it.
            if (vm.saverOn) {
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) vm.playSaverSlide()
                vm.onUserActivity()
                shortcutKeysDown += code
                return true
            }
            // Someone's there – resets "Are you still watching?".
            vm.onUserActivity()
            if (event.repeatCount == 0 && vm.handleShortcut(code)) {
                shortcutKeysDown += code
                return true
            }
            if (code in shortcutKeysDown) return true
        } else if (event.action == KeyEvent.ACTION_UP && shortcutKeysDown.remove(code)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Pressing Home while watching shrinks the video into picture-in-picture (where supported). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.screen == Screen.Player && vm.mode != null && vm.playback.isPlaying) enterPip(this)
    }
}

@Composable
private fun TunerApp(vm: TunerViewModel) {
    Box(Modifier.fillMaxSize()) {
        BackHandler(enabled = vm.canGoBack) { vm.back() }

        // Match the TV's refresh rate to the video while watching full screen (if turned on).
        val activity = LocalContext.current as? Activity
        val fps = vm.playback.videoFrameRate
        val fullScreen = vm.screen == Screen.Player && vm.mode != null
        LaunchedEffect(fps, fullScreen, vm.frameRateMatch) {
            val a = activity ?: return@LaunchedEffect
            if (vm.frameRateMatch && fullScreen && fps > 0f) FrameRateMatcher.apply(a, fps) else FrameRateMatcher.reset(a)
        }

        // Android's one-time "allow Tuner to set up a VPN" screen.
        val vpnAsk = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { r -> vm.vpn.permissionResult(r.resultCode == Activity.RESULT_OK) }
        val vpnIntent = vm.vpn.permissionIntent
        LaunchedEffect(vpnIntent) { if (vpnIntent != null) runCatching { vpnAsk.launch(vpnIntent) } }

        // Phones: upright for browsing, sideways and full screen for watching.
        val phone = vm.isPhone
        val watching = vm.screen == Screen.Player || vm.screen == Screen.MultiView || vm.screen == Screen.YouTubePlayer
        LaunchedEffect(phone, watching) {
            val a = activity ?: return@LaunchedEffect
            if (!phone) return@LaunchedEffect
            a.requestedOrientation = if (watching) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val bars = androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (watching) {
                bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }

        Column(Modifier.fillMaxSize().then(if (phone && !watching) Modifier.background(TunerColors.BackgroundBottom).safeDrawingPadding() else Modifier)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Channels -> ChannelsScreen(vm)
                Screen.Guide -> GuideScreen(vm)
                Screen.Player -> PlayerScreen(vm)
                Screen.Search -> SearchScreen(vm)
                Screen.Movies -> MoviesScreen(vm)
                Screen.Series -> SeriesScreen(vm)
                Screen.SeriesDetail -> SeriesDetailScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.SourceEdit -> SourceEditScreen(vm)
                Screen.Manage -> ManageScreen(vm)
                Screen.ProfilePicker -> ProfilePickerScreen(vm)
                Screen.ProfileEdit -> ProfileEditScreen(vm)
                Screen.PhoneSetup -> PhoneSetupScreen(vm)
                Screen.EpgMap -> EpgMapScreen(vm)
                Screen.Recordings -> RecordingsScreen(vm)
                Screen.MultiView -> MultiViewScreen(vm)
                Screen.Sports -> SportsScreen(vm)
                Screen.Apps -> AppsScreen(vm)
                Screen.Log -> LogScreen(vm)
                Screen.WhatsOn -> WhatsOnScreen(vm)
                Screen.Stats -> StatsScreen(vm)
                Screen.ShareTv -> ShareTvScreen(vm)
                Screen.Catchup -> CatchupScreen(vm)
                Screen.Categories -> CategoriesScreen(vm)
                Screen.Plex -> PlexScreen(vm)
                Screen.Football -> FootballScreen(vm)
                Screen.Update -> UpdateScreen(vm)
                Screen.SelfTest -> SelfTestScreen(vm)
                Screen.PlexLibrary -> com.titanejstudios.tuner.ui.PlexLibraryScreen(vm)
                Screen.PlexShow -> com.titanejstudios.tuner.ui.PlexShowScreen(vm)
                Screen.HomeRows -> com.titanejstudios.tuner.ui.HomeRowsScreen(vm)
                Screen.Languages -> com.titanejstudios.tuner.ui.LanguagesScreen(vm)
                Screen.Recommendations -> com.titanejstudios.tuner.ui.RecommendationsScreen(vm)
                Screen.Vpn -> com.titanejstudios.tuner.ui.VpnScreen(vm)
                Screen.Link -> com.titanejstudios.tuner.ui.LinkScreen(vm)
                Screen.Downloads -> com.titanejstudios.tuner.ui.DownloadsScreen(vm)
                Screen.Teams -> com.titanejstudios.tuner.ui.TeamsScreen(vm)
                Screen.Remote -> com.titanejstudios.tuner.ui.RemoteScreen(vm)
                Screen.Christmas -> com.titanejstudios.tuner.ui.ChristmasScreen(vm)
                Screen.Cloud -> com.titanejstudios.tuner.ui.CloudScreen(vm)
                Screen.YouTube -> com.titanejstudios.tuner.ui.YouTubeScreen(vm)
                Screen.YouTubePlayer -> com.titanejstudios.tuner.ui.YouTubePlayerScreen(vm)
            }
        }
        if (phone && !watching && vm.screen in PhoneTabs) PhoneBottomBar(vm)
        }

        // First time on something that isn't a TV: "Is this a TV, or a phone?"
        if (vm.askDevice) DeviceChoice(vm)

        if (!vm.inPip) {
            vm.dueReminder?.let { r ->
                ReminderPopup(r, onWatch = { vm.acceptReminder() }, onDismiss = { vm.dismissReminder() })
            }
            vm.categoryPickFor?.let { CategoryPicker(vm, it) }
            if (vm.timesUp) TimesUpDialog(vm)
            if (vm.filmInfoOpen) FilmInfoDialog(vm)
            if (vm.stillWatchingAsk) StillWatchingDialog(vm)
            vm.updateOffer?.let { UpdateDialog(vm, it) }
            vm.plexCopyChoice?.let { c ->
                MenuDialog(
                    title = "Which copy of ${c.title}?",
                    subtitle = "It's in more than one place",
                    items = buildList {
                        c.copies.forEach { e ->
                            val where = listOfNotNull(e.library, e.year).joinToString(" · ").ifEmpty { "Plex" }
                            add(MenuItem("Plex – $where", if (vm.plexServerCount > 1) e.server.name else null) { vm.choosePlexCopy(c, e) })
                        }
                        if (c.iptv != null) add(MenuItem("Your IPTV provider") { vm.choosePlexCopy(c, null) })
                    },
                    onDismiss = { vm.cancelPlexCopyChoice() },
                )
            }
            if (vm.cloudPrompt && vm.screen == Screen.Home && vm.initialLoadDone) {
                MenuDialog(
                    title = "Sync between houses?",
                    subtitle = "Sign in with Google once and this device keeps films, episodes, My list and your teams in step " +
                        "with your other TVs and phones – at home and at other houses. It takes a minute: you type a code on your phone.",
                    items = listOf(
                        MenuItem("☁ Set it up now") { vm.acceptCloudPrompt() },
                        MenuItem("Not now", "it's in Settings when you want it") {
                            vm.showNotice("You can set it up any time: Settings › Sync between houses")
                        },
                    ),
                    onDismiss = { vm.dismissCloudPrompt() },
                )
            }
            vm.tvChoice?.let { what ->
                MenuDialog(
                    title = "Watch on which TV?",
                    items = vm.link.tvs.map { tv -> MenuItem("📺  " + tv.name) { vm.sendToTv(tv, what) } },
                    onDismiss = { vm.cancelTvChoice() },
                )
            }
            vm.pinPrompt?.let { p -> PinDialog(p, onCancel = { vm.cancelPin() }) }
            vm.notice?.let { NoticeToast(it, Modifier.align(Alignment.BottomCenter)) }
            if (vm.screen == Screen.Home && !vm.initialLoadDone && vm.channels.isEmpty() && vm.sources.isNotEmpty()) {
                com.titanejstudios.tuner.ui.StartUpScreen(vm.status)
            }
            if (vm.saverOn) com.titanejstudios.tuner.ui.ArtScreensaver(vm)
        }
    }
}


/** Screens that show the phone's bottom menu bar. */
private val PhoneTabs = setOf(
    Screen.Home, Screen.Channels, Screen.Guide, Screen.Movies, Screen.Series, Screen.PlexLibrary, Screen.Search,
    Screen.Settings, Screen.Sports, Screen.WhatsOn, Screen.Recordings, Screen.Catchup, Screen.Apps,
)

/** Phones: Home, TV Guide, Films & TV, Search and More along the bottom. */
@Composable
private fun PhoneBottomBar(vm: TunerViewModel) {
    val films = when {
        vm.hasVod -> Screen.Movies
        vm.plexLinked -> Screen.PlexLibrary
        else -> Screen.Catchup
    }
    val items = listOf(
        Triple("🏠", "Home", Screen.Home),
        Triple("📺", "TV Guide", Screen.Channels),
        Triple("🎬", if (films == Screen.Catchup) "Catch-up" else "Films & TV", films),
        Triple("🔍", "Search", Screen.Search),
        Triple("☰", "More", Screen.Settings),
    )
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().height(64.dp).background(TunerColors.Panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (icon, label, target) ->
            val on = vm.screen == target || (target == Screen.Movies && vm.screen == Screen.Series) || (target == Screen.Channels && vm.screen == Screen.Guide)
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f).fillMaxSize().clickable {
                    when (target) {
                        Screen.Home -> vm.goHome()
                        Screen.PlexLibrary -> vm.openPlexLibrary()
                        else -> if (vm.screen != target) vm.navigate(target)
                    }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text(icon, fontSize = 20.sp)
                Text(label, color = if (on) TunerColors.Accent else TunerColors.TextSecondary, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** Asked once, the first time Tuner opens on something that isn't a TV. */
@Composable
private fun DeviceChoice(vm: TunerViewModel) {
    Box(Modifier.fillMaxSize().background(TunerColors.Background).safeDrawingPadding().padding(28.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        ) {
            Text("Welcome to Flicker", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("What are you watching on?", color = TunerColors.TextSecondary, fontSize = 17.sp)
            listOf(
                Triple("📱", "A phone or tablet", "phone"),
                Triple("📺", "A TV (with a remote)", "tv"),
            ).forEach { (icon, label, mode) ->
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().height(78.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(TunerColors.Panel).clickable { vm.chooseDevice(mode) }.padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(icon, fontSize = 30.sp)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                    Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("You can change this later in Settings", color = TunerColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

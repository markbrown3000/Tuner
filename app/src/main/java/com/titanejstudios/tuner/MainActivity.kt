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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.titanejstudios.tuner.ui.AppsScreen
import com.titanejstudios.tuner.ui.CatchupScreen
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
                TunerApp(vm)
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
        }

        if (!vm.inPip) {
            vm.dueReminder?.let { r ->
                ReminderPopup(r, onWatch = { vm.acceptReminder() }, onDismiss = { vm.dismissReminder() })
            }
            vm.categoryPickFor?.let { CategoryPicker(vm, it) }
            if (vm.timesUp) TimesUpDialog(vm)
            if (vm.filmInfoOpen) FilmInfoDialog(vm)
            if (vm.stillWatchingAsk) StillWatchingDialog(vm)
            vm.updateOffer?.let { UpdateDialog(vm, it) }
            vm.pinPrompt?.let { p -> PinDialog(p, onCancel = { vm.cancelPin() }) }
            vm.notice?.let { NoticeToast(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.subtitles.LiveSubtitles
import com.titanejstudios.tuner.subtitles.LiveSubtitles.Status
import kotlinx.coroutines.delay

/** The live subtitle words over the picture; they fade out after a few quiet seconds. */
@Composable
fun BoxScope.LiveCaptionOverlay() {
    if (!LiveSubtitles.on) return
    val text = LiveSubtitles.caption
    val at = LiveSubtitles.captionAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        now = System.currentTimeMillis()
        delay(5_000)
        now = System.currentTimeMillis()
    }
    if (text.isBlank() || now - at > 5_000) return
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 48.dp, start = 48.dp, end = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = Color.White,
            fontSize = (26 * SubtitleLook.scale).sp,
            lineHeight = (32 * SubtitleLook.scale).sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Settings › Watching › Sound & subtitles. */
@Composable
fun LiveSubtitlesSetting(vm: TunerViewModel) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val about = "Subtitles made from the sound as you watch, for channels with none · English · about 1–2 s behind"
    when (val s = LiveSubtitles.status) {
        is Status.NotAvailable -> SettingRow(
            "💬 Live subtitles",
            subtitle = "Not available on this device – ${s.reason}",
            detail = "Not available",
            onClick = { vm.showNotice("Live subtitles aren't available on this device – ${s.reason}") },
        )
        Status.NotDownloaded -> SettingRow(
            "💬 Live subtitles",
            subtitle = about,
            detail = "Download (about ${LiveSubtitles.DOWNLOAD_MB} MB)",
            onClick = {
                LiveSubtitles.download(ctx) { vm.showNotice(it) }
                vm.showNotice("Downloading live subtitles (about ${LiveSubtitles.DOWNLOAD_MB} MB) – carry on watching meanwhile")
            },
        )
        is Status.Downloading -> SettingRow(
            "💬 Live subtitles",
            subtitle = "Downloading the ${s.step}…",
            detail = "${s.percent}%",
            onClick = { menu = true },
        )
        is Status.Failed -> SettingRow(
            "💬 Live subtitles",
            subtitle = "Didn't work: ${s.message}",
            detail = "Try again",
            onClick = {
                LiveSubtitles.remove(ctx)
                LiveSubtitles.download(ctx) { vm.showNotice(it) }
            },
        )
        Status.Ready -> SettingRow(
            "💬 Live subtitles",
            subtitle = about + " · also in the player's Subtitles menu",
            detail = if (LiveSubtitles.on) "On" else "Off",
            onClick = { menu = true },
        )
    }
    if (menu) {
        val downloading = LiveSubtitles.status is Status.Downloading
        MenuDialog(
            title = "💬 Live subtitles",
            subtitle = if (downloading) null else "Using ${LiveSubtitles.sizeMb(ctx)} MB on this device",
            items = buildList {
                if (!downloading) add(MenuItem(if (LiveSubtitles.on) "Turn off" else "Turn on") { LiveSubtitles.setOn(ctx, !LiveSubtitles.on) })
                add(MenuItem(if (downloading) "Stop downloading" else "Remove from this device", if (downloading) null else "you can download it again any time") {
                    LiveSubtitles.remove(ctx)
                    vm.showNotice(if (downloading) "Download stopped" else "Live subtitles removed")
                })
            },
            onDismiss = { menu = false },
        )
    }
}

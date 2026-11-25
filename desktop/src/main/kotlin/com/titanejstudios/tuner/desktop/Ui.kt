package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The "Evening" colours from the TV app.
val Ink = Color(0xFF07123A)
val InkBottom = Color(0xFF030920)
val Panel = Color(0xFF10204F)
val PanelLight = Color(0xFF1A2A5E)
val Sky = Color(0xFF4D9BFF)
val Soft = Color(0xFFAAB6DA)
val Gold = Color(0xFFFFB648)
val LiveRed = Color(0xFFFF5252)

private val hhmmFormat = SimpleDateFormat("HH:mm", Locale.UK)
fun time(t: Long): String = synchronized(hhmmFormat) { hhmmFormat.format(Date(t)) }

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.UK, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.UK, "%d:%02d", s / 60, s % 60)
}

/** Opens a web page in the PC's browser. */
fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

@Composable
fun Pill(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.White else Soft, fontSize = 14.sp, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (selected) Sky.copy(alpha = 0.55f) else Panel)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 14.dp, bottom = 8.dp))
}

/** A poster (2:3) with its title underneath, and an optional progress bar. */
@Composable
fun PosterTile(title: String, image: String?, caption: String? = null, progress: Float? = null, width: Dp = 150.dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(PanelLight), contentAlignment = Alignment.Center) {
            if (image.isNullOrBlank()) Text(title.take(2).uppercase(), color = Soft, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            else NetImage(image, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Sky))
            }
        }
        Text(title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
        caption?.let { Text(it, color = Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

/** A wide 16:9 tile (channels, Plex, YouTube). */
@Composable
fun WideTile(title: String, image: String?, caption: String? = null, progress: Float? = null, width: Dp = 240.dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(PanelLight), contentAlignment = Alignment.Center) {
            if (image.isNullOrBlank()) Text(title.take(24), color = Soft, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            else NetImage(image, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Sky))
            }
        }
        Text(title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
        caption?.let { Text(it, color = Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

/** The video (VLC). Only one of these is on screen at a time (in the mini player when that's open). */
@Composable
fun VideoBox(app: AppState, modifier: Modifier, inMini: Boolean = false) {
    val player = app.player
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val video = player.component
        if (app.mini && !inMini) Text(
            "▣ Playing in the mini player\nClick to bring it back here", color = Soft, fontSize = 15.sp,
            modifier = Modifier.clickable { app.toggleMini() }.padding(24.dp),
        )
        else if (video != null) SwingPanel(factory = { video }, modifier = Modifier.fillMaxSize())
        else Text(
            "Flicker couldn't start its video player.\nInstall VLC (64-bit) from videolan.org, then open Flicker again.",
            color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(24.dp),
        )
    }
}

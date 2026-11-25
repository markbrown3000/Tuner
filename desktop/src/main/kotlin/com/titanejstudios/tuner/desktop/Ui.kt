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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** A colour theme (the TV app's themes). */
class Theme(val name: String, val ink: Long, val inkBottom: Long, val panel: Long, val panelLight: Long, val sky: Long, val soft: Long)

val Themes = listOf(
    Theme("Evening", 0xFF07123A, 0xFF030920, 0xFF10204F, 0xFF1A2A5E, 0xFF4D9BFF, 0xFFAAB6DA),
    Theme("Midnight", 0xFF0B0B0F, 0xFF000000, 0xFF17171E, 0xFF24242E, 0xFF7C9CFF, 0xFFB4B4C4),
    Theme("Ocean", 0xFF042A36, 0xFF01141B, 0xFF0A3B4A, 0xFF135467, 0xFF2FD4C7, 0xFFA6D4DC),
    Theme("Plum", 0xFF22103A, 0xFF10061E, 0xFF331A55, 0xFF472670, 0xFFC08BFF, 0xFFCDB8E6),
    Theme("Forest", 0xFF0E2416, 0xFF06120A, 0xFF173823, 0xFF214D31, 0xFF5FD38A, 0xFFB5D6C0),
    Theme("Sunset", 0xFF2A1220, 0xFF140810, 0xFF3D1B2E, 0xFF55263F, 0xFFFF8A5B, 0xFFE2C1C9),
    Theme("Slate", 0xFF1B2128, 0xFF0D1014, 0xFF262E37, 0xFF333D49, 0xFF64B5F6, 0xFFB9C3CE),
)

/** The theme in use (Settings › This PC); every screen redraws when it changes. */
var currentTheme by androidx.compose.runtime.mutableStateOf(Themes[0])

/** Text and everything else a bit bigger, for watching from further away (1 = normal). */
var uiScale by androidx.compose.runtime.mutableStateOf(1f)

val Ink: Color get() = Color(currentTheme.ink)
val InkBottom: Color get() = Color(currentTheme.inkBottom)
val Panel: Color get() = Color(currentTheme.panel)
val PanelLight: Color get() = Color(currentTheme.panelLight)
val Sky: Color get() = Color(currentTheme.sky)
val Soft: Color get() = Color(currentTheme.soft)
val Gold = Color(0xFFFFB648)
val LiveRed = Color(0xFFFF5252)

/** The profile colours and pictures from the TV. */
val AvatarColours = listOf(
    Color(0xFF1F7BFF), Color(0xFFE0567A), Color(0xFFFFB648), Color(0xFF2FBF71),
    Color(0xFF8A4FFF), Color(0xFF14B8C4), Color(0xFFFF7A1A), Color(0xFF64748B),
)
val AvatarIcons = listOf("", "🦊", "🐼", "🦁", "🐸", "🐶", "🐱", "🚀", "⚽", "🎸", "🌈", "🎮", "🧸")

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
        val video = if (player.ready) player.component else null
        if (app.videoElsewhere && !inMini) Text(
            if (app.secondScreen != null) "🖥 Playing on your other screen\nClick to bring it back here" else "▣ Playing in the mini player\nClick to bring it back here",
            color = Soft, fontSize = 15.sp,
            modifier = Modifier.clickable { if (app.secondScreen != null) app.toggleSecondScreen() else app.toggleMini() }.padding(24.dp),
        )
        else if (video != null) SwingPanel(factory = { video }, modifier = Modifier.fillMaxSize())
        else if (!player.ready) Text("Starting the video player…", color = Soft, fontSize = 15.sp)
        else Text(
            "Flicker couldn't start its video player.\nInstall VLC (64-bit) from videolan.org, then open Flicker again.",
            color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(24.dp),
        )
    }
}


/** Right-click menu around [content] (nothing extra when [items] is empty). */
@Composable
fun WithMenu(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    if (items.isEmpty()) content()
    else androidx.compose.foundation.ContextMenuArea(items = { items.map { (label, action) -> androidx.compose.foundation.ContextMenuItem(label, action) } }) { content() }
}

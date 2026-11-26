package com.titanejstudios.tuner.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.Tmdb
import kotlinx.coroutines.delay

/** "Are you still watching?" – after a few hours with no button pressed. Stops by itself after 90 s. */
@Composable
fun StillWatchingDialog(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    ModalLayer(onDismiss = { vm.keepWatching() }) {
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 620.dp).clip(RoundedCornerShape(16.dp)).background(TunerColors.Panel).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📺", fontSize = 44.sp)
            Text("Are you still watching?", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Flicker will stop in a minute or so if nobody answers – it saves your connection overnight.",
                color = TunerColors.TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Keep watching", { vm.keepWatching() }, Modifier.focusRequester(first))
                PillButton("Stop", { vm.stopWatchingNow() })
            }
        }
    }
}

/** Film / series details from TMDB: picture, plot, cast, rating and a trailer button. */
@Composable
fun FilmInfoDialog(vm: TunerViewModel) {
    val info = vm.filmInfo
    val first = remember { FocusRequester() }
    LaunchedEffect(info != null) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    ModalLayer(onDismiss = { vm.closeFilmInfo() }) {
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 900.dp).clip(RoundedCornerShape(16.dp)).background(TunerColors.Panel)
        ) {
            if (info == null) {
                Column(Modifier.fillMaxWidth().padding(32.dp)) {
                    Text(vm.filmInfoStatus ?: "Looking it up…", color = Color.White, fontSize = 20.sp)
                    Spacer(Modifier.height(18.dp))
                    PillButton("Close", { vm.closeFilmInfo() }, Modifier.focusRequester(first))
                }
                return@Column
            }
            Box(Modifier.fillMaxWidth().aspectRatio(2.4f)) {
                val pic = info.backdrop ?: info.poster
                if (pic != null) AsyncImage(model = pic, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // The trailer quietly takes over the picture after a moment (muted).
                val trailerKey = info.trailerKey
                if (trailerKey != null && vm.trailerAutoplay) {
                    var showTrailer by remember(trailerKey) { mutableStateOf(false) }
                    LaunchedEffect(trailerKey) {
                        delay(2_500)
                        showTrailer = true
                    }
                    if (showTrailer) com.titanejstudios.tuner.ui.MutedTrailer(trailerKey, Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, TunerColors.Panel))))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp, vertical = 12.dp)) {
                    Text(
                        info.title + (info.year?.let { "  ($it)" } ?: ""),
                        color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            Tmdb.ratingText(info.rating),
                            info.runtimeMinutes?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                            info.genres.take(3).joinToString(", ").ifEmpty { null },
                        ).joinToString("   ·   "),
                        color = TunerColors.Gold, fontSize = 16.sp,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp)) {
                info.overview?.let { Text(it, color = Color(0xFFD5DCF2), fontSize = 16.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                if (info.cast.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("With " + info.cast.joinToString(", "), color = TunerColors.TextSecondary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // Part of a series: the films in order, yours to play.
                vm.filmCollection?.let { (name, parts) ->
                    Spacer(Modifier.height(12.dp))
                    Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(parts.size) { i ->
                            val p = parts[i]
                            val here = p.part.title.equals(info.title, ignoreCase = true)
                            FocusSurface(
                                onClick = { vm.playSeriesPart(p) },
                                baseColor = if (here) TunerColors.Accent.copy(alpha = 0.35f) else TunerColors.PanelLight,
                                modifier = Modifier.width(92.dp),
                            ) { _ ->
                                Column {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                                        AsyncImage(model = p.part.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        if (p.film == null) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                                    }
                                    Text("${i + 1}. " + p.part.title, color = if (p.film != null) Color.White else TunerColors.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val trailer = info.trailerKey
                    if (trailer != null) PillButton("▶ Trailer", { vm.playTrailer(trailer, info.title) }, Modifier.focusRequester(first))
                    PillButton("Close", { vm.closeFilmInfo() }, if (trailer == null) Modifier.focusRequester(first) else Modifier)
                    // 👍 / 👎: more or fewer like it in recommendations and Surprise me.
                    val thumb = remember(vm.tasteVersion, info.title) { vm.thumbOf(info.title) }
                    PillButton("👍", { vm.thumb(info.title, info.genres.firstOrNull(), true) }, selected = thumb == 1)
                    PillButton("👎", { vm.thumb(info.title, info.genres.firstOrNull(), false) }, selected = thumb == -1)
                    PillButton("📍 Where to watch", { vm.whereToWatch(info.title) })
                }
                Spacer(Modifier.height(4.dp))
                Text("Film info from TMDB", color = TunerColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

/** "A new version of Tuner is available" – from the daily check of your GitHub releases. */
@Composable
fun UpdateDialog(vm: TunerViewModel, release: com.titanejstudios.tuner.apps.Updater.Release) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    ModalLayer(onDismiss = { vm.updateLater() }) {
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 680.dp).clip(RoundedCornerShape(16.dp)).background(TunerColors.Panel).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⬆", fontSize = 40.sp, color = Color.White)
            Text("Flicker ${release.version.trimStart('v', 'V')} is available", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("You have ${vm.installedVersion}", color = TunerColors.TextSecondary, fontSize = 16.sp)
            release.notes?.let {
                Spacer(Modifier.height(10.dp))
                Text(it.take(400), color = Color(0xFFD5DCF2), fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // "Later" is picked first, so a stray OK press doesn't install (and close Flicker).
                PillButton("Later", { vm.updateLater() }, Modifier.focusRequester(first))
                PillButton("Install now", { vm.installUpdateNow() })
            }
            Spacer(Modifier.height(8.dp))
            Text("The TV will ask you to confirm. Your settings and recordings are kept.", color = TunerColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

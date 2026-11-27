package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.saverIndexChanged
import com.titanejstudios.tuner.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tuner's screensaver: your artwork full screen, slowly drifting, with the time. Any button closes it; OK watches. */
@Composable
fun ArtScreensaver(vm: TunerViewModel) {
    val slides = remember { vm.saverSlides.shuffled() }
    if (slides.isEmpty()) return
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(slides) {
        while (true) {
            vm.saverIndexChanged(slides[index])
            delay(12_000)
            index = (index + 1) % slides.size
        }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = index, animationSpec = tween(1_500), label = "saver") { i ->
            val s = slides[i]
            // A slow zoom so nothing sits still long enough to mark the screen.
            var grow by remember { mutableStateOf(false) }
            LaunchedEffect(i) { grow = true }
            val zoom by animateFloatAsState(if (grow) 1.08f else 1f, tween(12_000, easing = LinearEasing), label = "zoom")
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = s.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().scale(zoom))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 48.dp)) {
                    Text("ON TUNER", color = Color(0xFFFFCF8A), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(s.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Text(s.details + "  ·  press OK to watch", color = Color(0xD9FFFFFF), fontSize = 16.sp)
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 56.dp), horizontalAlignment = Alignment.End) {
            Text(SimpleDateFormat("HH:mm", Locale.UK).format(Date(now)), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)), color = Color(0xD9FFFFFF), fontSize = 18.sp)
        }
    }
}

/** Shown while Tuner loads your channels for the very first time. */
@Composable
fun StartUpScreen(status: String?) {
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF16307A), TunerColors.BackgroundBottom), radius = 1100f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.logo_icon), contentDescription = "Flicker", modifier = Modifier.size(120.dp))
            Text("Flicker", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(200.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x26FFFFFF))) {
                val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "load")
                val x by pulse.animateFloat(
                    0f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(tween(1_200, easing = LinearEasing)),
                    label = "x",
                )
                Box(Modifier.fillMaxWidth(0.35f).height(4.dp).padding(start = (130 * x).dp).clip(RoundedCornerShape(2.dp)).background(TunerColors.Accent))
            }
            Text(status ?: "Loading your channels…", color = Color(0xFF8FA3D4), fontSize = 15.sp)
        }
    }
}

package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.delay

/** Settings › Recommendations: what Tuner has learned this profile likes. */
@Composable
fun RecommendationsScreen(vm: TunerViewModel) {
    val (genres, shows, ratings) = remember(vm.tasteVersion, vm.activeProfile.id) { vm.tasteSummary() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Row(Modifier.fillMaxSize().skyBackground().padding(horizontal = 56.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(520.dp)) {
            Text("Recommendations", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Learned from what ${vm.activeProfile.name} actually watches (a quick flick past doesn't count), and from 👍 / 👎 – hold OK on a programme. Kept on this TV, separately for each profile.",
                color = TunerColors.TextSecondary, fontSize = 15.sp,
            )
            Spacer(Modifier.height(18.dp))
            Text("What you watch", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (genres.isEmpty()) {
                Text("Nothing yet – watch a few things and come back.", color = TunerColors.TextSecondary, fontSize = 15.sp)
            }
            genres.forEach { (g, share) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(150.dp))
                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF))) {
                        Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(TunerColors.Accent))
                    }
                    Text("${(share * 100).toInt()}%", color = TunerColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(56.dp).padding(start = 10.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(48.dp))
            Text("Your shows", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (shows.isEmpty()) "None yet" else shows.joinToString("  ·  "), color = TunerColors.TextSecondary, fontSize = 15.sp)
            if (ratings > 0) Text("$ratings 👍/👎 given", color = TunerColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            SettingRow(
                "Learn from what I watch",
                modifier = Modifier.focusRequester(first),
                subtitle = "Off: recommendations stay as they are now",
                detail = if (vm.learnTaste) "On" else "Off",
                onClick = { vm.toggleLearnTaste() },
            )
            SettingRow(
                "Start again",
                subtitle = "Forget everything learned for ${vm.activeProfile.name}",
                onClick = { vm.resetTaste() },
            )
        }
    }
}

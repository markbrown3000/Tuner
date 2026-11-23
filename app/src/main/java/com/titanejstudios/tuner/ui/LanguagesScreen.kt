package com.titanejstudios.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Languages
import kotlinx.coroutines.delay

/** Settings › Languages & countries: hide channels, films and series by country or language. */
@Composable
fun LanguagesScreen(vm: TunerViewModel) {
    val counts = remember(vm.allChannels) { vm.languageCounts() }
    val hidden = vm.hiddenLanguages
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = 56.dp, vertical = 32.dp)) {
        Text("Languages & countries", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Found from the tags in your provider's names (\"UK |\", \"[AR]\", \"French\"…). Hidden ones disappear from the TV Guide, Home, Search, Films and Series.",
            color = TunerColors.TextSecondary, fontSize = 15.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("🇬🇧 English only", { vm.englishOnly() }, Modifier.focusRequester(first), compact = true)
            PillButton("Show everything", { vm.changeHiddenLanguages(emptySet()) }, compact = true)
            Text(
                if (hidden.isEmpty()) "Everything shown" else "${hidden.size} hidden",
                color = TunerColors.TextSecondary, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(counts.size, key = { counts[it].first?.code ?: Languages.NONE }) { i ->
                val (tag, n) = counts[i]
                val code = tag?.code ?: Languages.NONE
                val shown = code !in hidden
                FocusSurface(
                    onClick = { vm.changeHiddenLanguages(if (shown) hidden + code else hidden - code) },
                    baseColor = if (shown) TunerColors.Panel else Color(0x66000000),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tag == null) "❔" else Languages.flag(tag.code), fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tag?.name ?: "No country in the name",
                                color = if (shown || focused) Color.White else TunerColors.TextSecondary,
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("$n channels", color = TunerColors.TextSecondary, fontSize = 12.sp)
                        }
                        Text(
                            if (shown) "Shown" else "Hidden",
                            color = if (shown) Color(0xFF7EF0A8) else TunerColors.TextSecondary,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

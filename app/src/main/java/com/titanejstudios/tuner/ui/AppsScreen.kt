package com.titanejstudios.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.delay

/** Every app on the TV – Plex, BBC iPlayer, Netflix, YouTube… – one press away. */
@Composable
fun AppsScreen(vm: TunerViewModel) {
    LaunchedEffect(Unit) { vm.loadApps() }
    val apps = vm.tvApps
    val all = remember(apps) { apps.orEmpty() }
    val first = remember { FocusRequester() }
    LaunchedEffect(all.isNotEmpty()) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = 44.dp, vertical = 22.dp)) {
        TopNav(vm, Screen.Apps)
        Spacer(Modifier.height(8.dp))
        Text("Apps", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "OK opens the app – press Home or Back on the remote to come back to Tuner. " +
                "Hold OK to show or hide an app in the \"Your apps\" row on Home.",
            color = TunerColors.TextSecondary,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(14.dp))
        when {
            apps == null -> CenterMessage("⟳  Finding your apps…")
            all.isEmpty() -> CenterMessage("No other apps found")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(all.size, key = { all[it].packageName }) { i ->
                    val a = all[i]
                    Column {
                        AppTile(
                            vm,
                            a,
                            (if (i == 0) Modifier.focusRequester(first) else Modifier).size(width = 208.dp, height = 117.dp),
                            dimmed = a.packageName in vm.hiddenApps,
                        )
                        Text(
                            a.label + if (a.packageName in vm.hiddenApps) "  (hidden on Home)" else "",
                            color = TunerColors.TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Settings › Problem log: what went wrong recently (stream drops, crashes, failed updates). */
@Composable
fun LogScreen(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val lines = remember(vm.logText) { vm.logText.lines() }
    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Problem log", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            PillButton("Refresh", { vm.loadLog() }, Modifier.focusRequester(first), compact = true)
            Spacer(Modifier.width(10.dp))
            PillButton("Clear", { vm.clearLog() }, compact = true)
        }
        Text(
            "Newest first. With the phone remote on, you can also read this on your phone at …/log.txt.",
            fontSize = 15.sp,
            color = TunerColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(lines.size, key = { it }) { i ->
                // Each line is focusable so the list can be scrolled with the remote.
                FocusSurface(onClick = {}, modifier = Modifier.fillMaxWidth()) { focused ->
                    val line = lines[i]
                    Text(
                        line,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        color = when {
                            focused -> Color.White
                            "CRASH" in line -> TunerColors.Error
                            "PROBLEM" in line -> Color(0xFFFFC857)
                            else -> TunerColors.TextSecondary
                        },
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

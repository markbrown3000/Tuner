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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.features.SelfTest
import kotlinx.coroutines.delay

/** Settings › Help › Check everything: what works on this TV and what doesn't. */
@Composable
fun SelfTestScreen(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val results = vm.selfTestResults
    val fails = results.count { it.state == SelfTest.State.Fail }
    val warns = results.count { it.state == SelfTest.State.Warn }

    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Check everything", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(18.dp))
            Text(
                when {
                    vm.selfTestRunning -> "Checking… ${results.size} done"
                    results.isEmpty() -> ""
                    fails == 0 && warns == 0 -> "✓ Everything looks fine"
                    else -> listOfNotNull(
                        if (fails > 0) "$fails not working" else null,
                        if (warns > 0) "$warns to look at" else null,
                    ).joinToString(" · ")
                },
                fontSize = 18.sp,
                color = if (fails > 0) TunerColors.Error else TunerColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            PillButton(if (vm.selfTestRunning) "Checking…" else "Check again", { if (!vm.selfTestRunning) vm.runSelfTest() }, Modifier.focusRequester(first), compact = true)
            Spacer(Modifier.width(10.dp))
            PillButton("Problem log", { vm.openLog() }, compact = true)
        }
        Text(
            "Tries each part of Tuner on this TV. Take a photo of anything red and send it over. " +
                "The stream test uses one connection to your provider for a few seconds.",
            fontSize = 15.sp,
            color = TunerColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results.size, key = { results[it].name }) { i ->
                val r = results[i]
                val (icon, colour) = when (r.state) {
                    SelfTest.State.Pass -> "✓" to TunerColors.Catchup
                    SelfTest.State.Warn -> "!" to Color(0xFFFFC857)
                    SelfTest.State.Fail -> "✗" to TunerColors.Error
                    SelfTest.State.Info -> "i" to TunerColors.TextSecondary
                }
                // Focusable so the list scrolls with the remote.
                FocusSurface(onClick = {}, baseColor = TunerColors.Panel, modifier = Modifier.fillMaxWidth()) { focused ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Text(icon, color = if (focused) Color.White else colour, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(r.detail, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (vm.selfTestRunning) {
                item(key = "running") {
                    Text("⟳  Working through the checks…", color = TunerColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
                }
            }
            if (!vm.selfTestRunning && vm.safeMode) {
                item(key = "safe") {
                    Spacer(Modifier.height(8.dp))
                    PillButton("Turn safe mode off", { vm.leaveSafeMode() })
                }
            }
        }
    }
}

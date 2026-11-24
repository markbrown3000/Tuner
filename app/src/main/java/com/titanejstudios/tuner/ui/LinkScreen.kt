package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.TvShare
import kotlinx.coroutines.delay

/**
 * Settings › Linked devices: link the TVs and phones in the house so you can send what's playing
 * from one to another, and carry on a film or episode where you left off.
 */
@Composable
fun LinkScreen(vm: TunerViewModel) {
    val link = vm.link
    var renaming by remember { mutableStateOf(false) }
    var codeFor by remember { mutableStateOf<TvShare.Found?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
        if (link.linked) link.search()
    }
    DisposableEffect(Unit) { onDispose { link.stopPairing() } }
    val narrow = narrowScreen()

    LazyColumn(
        Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp)).widthIn(max = 1000.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Linked devices", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "Link your TVs and phones on the same Wi-Fi. Then you can send what's playing to another one " +
                    "(⋮ › Send to…), and films and episodes carry on where you left off on whichever you pick up next. " +
                    "Favourites and My list stay in step too.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        item {
            SettingRow("This device", subtitle = "What the others call it", detail = link.name, onClick = { renaming = true }, modifier = Modifier.focusRequester(first))
        }
        link.status?.let { st -> item { Text(st, color = if (st.startsWith("✓")) TunerColors.Accent else TunerColors.Gold, fontSize = 15.sp) } }

        if (link.linked) {
            item { SectionTitle("On the Wi-Fi now") }
            if (link.peers.isEmpty()) {
                item {
                    Text(
                        if (link.searching) "Looking…" else "None found. Flicker needs to be open on the others (a TV can be in the background).",
                        color = TunerColors.TextSecondary, fontSize = 15.sp,
                    )
                }
            }
            items(link.peers.size, key = { link.peers[it].id }) { i ->
                val p = link.peers[i]
                SettingRow(
                    (if (p.phone) "📱  " else "📺  ") + p.name,
                    subtitle = if (vm.mode != null) "Send what's playing here to it" else "Linked",
                    detail = if (vm.mode != null) "Send ▶" else null,
                    onClick = { if (vm.mode != null) vm.sendTo(p) },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (link.searching) "Looking…" else "↻ Look again", { link.search() }, compact = true)
                    PillButton("Sync now", { link.syncSoon(0L) }, compact = true)
                }
                if (link.lastSync > 0) Text("Last synced at ${hhmmText(link.lastSync)}", color = TunerColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item { SectionTitle(if (link.linked) "Link another device" else "Link a device") }
        item {
            Text(
                "Show a code on one device, and enter it on the other (both on the same Wi-Fi). Copying settings to another TV links it too.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Show a code here", { vm.linkShowCode() }, compact = true, selected = link.codeShown != null)
                PillButton("Enter a code", { vm.linkFindCodes() }, compact = true)
            }
        }
        link.codeShown?.let { code ->
            item {
                Text("On the other device: Settings › Linked devices › Enter a code, pick \"${link.name}\" and type:", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    code.forEach { d ->
                        Box(
                            Modifier.size(width = if (narrow) 60.dp else 80.dp, height = if (narrow) 76.dp else 96.dp)
                                .clip(RoundedCornerShape(12.dp)).background(TunerColors.Panel),
                            contentAlignment = Alignment.Center,
                        ) { Text(d.toString(), color = Color.White, fontSize = if (narrow) 40.sp else 52.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        if (link.codeDevices.isNotEmpty()) {
            items(link.codeDevices.size, key = { "code:" + link.codeDevices[it].host + link.codeDevices[it].port }) { i ->
                val f = link.codeDevices[i]
                SettingRow("🔗  " + f.name, subtitle = "Showing a code – pick it and type the code", onClick = { codeFor = f })
            }
        }
        if (link.linked) {
            item { Spacer(Modifier.height(10.dp)) }
            item { SettingRow("Unlink this device", subtitle = "It stops seeing the others until you link it again", onClick = { link.unlink() }) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (renaming) {
        TextInputDialog(
            title = "Name this device",
            initial = vm.link.name,
            hint = "e.g. Living room TV, Mark's phone",
            onDismiss = { renaming = false },
            onDone = {
                vm.link.rename(it)
                renaming = false
            },
        )
    }
    codeFor?.let { f ->
        TextInputDialog(
            title = "Code shown on ${f.name}",
            label = "4-digit code",
            keyboard = KeyboardType.Number,
            onDismiss = { codeFor = null },
            onDone = { code ->
                codeFor = null
                vm.link.enterCode(f, code.trim()) { }
            },
        )
    }
}

private fun hhmmText(t: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(t))

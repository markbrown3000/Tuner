package com.titanejstudios.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.delay

/** Settings › VPN: Tuner's own WireGuard VPN (only Tuner's streams go through it). */
@Composable
fun VpnScreen(vm: TunerViewModel) {
    val v = vm.vpn
    val first = remember { FocusRequester() }
    var removeAsk by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Row(Modifier.fillMaxSize().skyBackground().padding(horizontal = 56.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("VPN", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                if (v.connected) "🔒 " + (v.status ?: "Connected") else (v.status ?: if (v.enabled) "On – not connected" else "Off"),
                color = if (v.connected) Color(0xFF7EF0A8) else TunerColors.TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Only Tuner's streams go through it – Netflix, YouTube and the rest of the TV are untouched, and Plex on your home network stays direct.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            SettingRow(
                if (v.enabled) "Turn the VPN off" else "Turn the VPN on",
                modifier = Modifier.focusRequester(first),
                subtitle = if (v.enabled) "Reconnects by itself whenever Tuner opens" else "Android asks once to allow it",
                detail = if (v.enabled) "On" else "Off",
                onClick = { if (v.enabled) v.turnOff() else v.turnOn() },
            )
            if (v.enabled && !v.connected) SettingRow("Connect now", onClick = { v.connect() })
            SettingRow(
                "Kill switch",
                subtitle = "If the VPN drops, your provider's channels and films stop instead of playing without it",
                detail = if (v.killSwitch) "On" else "Off",
                onClick = { v.toggleKillSwitch() },
            )
            SettingRow(
                "Add a server from your phone",
                subtitle = "Send the WireGuard file from your provider's website (e.g. Surfshark › Manual setup › WireGuard)",
                onClick = { vm.navigate(Screen.PhoneSetup) },
            )
        }
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(48.dp))
            Text("Servers", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("OK to use one · hold OK to remove it", color = TunerColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (v.servers.isEmpty()) {
                Text("None yet – add one from your phone.", color = TunerColors.TextSecondary, fontSize = 15.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(v.servers.size, key = { v.servers[it].name }) { i ->
                    val s = v.servers[i]
                    val chosen = s.name == v.active
                    FocusSurface(
                        onClick = { v.choose(s.name) },
                        onLongClick = { removeAsk = s.name },
                        selected = chosen,
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.height(56.dp),
                    ) { _ ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                s.endpoint?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 12.sp) }
                            }
                            if (chosen) Text(if (v.connected) "🔒 In use" else "Chosen", color = Color(0xFF7EF0A8), fontSize = 14.sp)
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }
        }
    }
    removeAsk?.let { name ->
        MenuDialog(
            title = name,
            items = listOf(MenuItem("Remove this server") { v.remove(name) }),
            onDismiss = { removeAsk = null },
        )
    }
}

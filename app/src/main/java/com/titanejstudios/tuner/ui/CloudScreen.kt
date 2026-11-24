package com.titanejstudios.tuner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import kotlinx.coroutines.delay

/** Settings › Sync between houses: sign in with Google, name this house, see the other devices. */
@Composable
fun CloudScreen(vm: TunerViewModel) {
    val cloud = vm.cloud
    var renaming by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(cloud.signedIn) {
        delay(80)
        runCatching { first.requestFocus() }
        if (cloud.signedIn) cloud.syncNow()
    }
    val narrow = narrowScreen()
    val fmt = remember { java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.UK) }

    LazyColumn(
        Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp)).widthIn(max = 1000.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("☁ Sync between houses", fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "Sign in every device to the same Google account – at home and at other houses. Where you got to in films and episodes, " +
                    "My list, followed series, your teams and learned intros stay in step. It's one small file (a few KB) in a " +
                    "\"Flicker\" folder in that account's own Google Drive – Flicker can only see files it made itself.",
                color = TunerColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        cloud.status?.let { st -> item { Text(st, color = if (st.startsWith("✓")) TunerColors.Accent else TunerColors.Gold, fontSize = 15.sp) } }

        if (!cloud.available) {
            item {
                Text(
                    "This copy of Flicker was built without Google sign-in. Add the GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET " +
                        "secrets on GitHub (see README › Google Drive sync) and update.",
                    color = Color.White, fontSize = 16.sp,
                )
            }
            return@LazyColumn
        }

        val c = cloud.code
        when {
            c != null -> item {
                // Signing in: type the code on a phone or computer.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val qr = remember(c.url) { PhoneSetupServer.qrCode(c.url, 300).asImageBitmap() }
                    Image(qr, contentDescription = "QR code for ${c.url}", modifier = Modifier.size(if (narrow) 120.dp else 170.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text("On your phone, go to", color = TunerColors.TextSecondary, fontSize = 15.sp)
                        Text(c.url.removePrefix("https://").removePrefix("www."), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("and enter this code:", color = TunerColors.TextSecondary, fontSize = 15.sp)
                        Text(
                            c.userCode, color = Color.White, fontSize = if (narrow) 30.sp else 40.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TunerColors.Panel).padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Pick the Google account to share. This screen carries on by itself.", color = TunerColors.TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        // Google lets people untick the Drive permission – then sync can't work. Make it hard to miss.
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFFFD66B), fontWeight = FontWeight.ExtraBold)) { append("⚠ IMPORTANT: ") }
                                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("on Google's page, tick the Google Drive box (or press Select all) – without it, sync can't work.")
                                }
                            },
                            color = Color.White, fontSize = if (narrow) 15.sp else 17.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFD66B)).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                PillButton("Cancel", { cloud.cancelSignIn() }, Modifier.focusRequester(first), compact = true)
            }
            !cloud.signedIn -> item {
                PillButton("Sign in with Google", { cloud.signIn() }, Modifier.focusRequester(first))
            }
            else -> {
                item {
                    SettingRow("Google account", subtitle = "Every device signed in to this account syncs", detail = cloud.email ?: "Signed in", onClick = { cloud.syncNow() }, modifier = Modifier.focusRequester(first))
                }
                item { SettingRow("This house", subtitle = "Devices in the same house share favourites", detail = cloud.house, onClick = { renaming = true }) }
                item {
                    SettingRow(
                        "Share favourites with other houses",
                        subtitle = "Off: each house keeps its own channel favourites (everything else still syncs)",
                        detail = if (cloud.shareFavourites) "On" else "Off",
                        onClick = { cloud.toggleShareFavourites() },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(if (cloud.syncing) "Syncing…" else "↻ Sync now", { cloud.syncNow() }, compact = true)
                        if (cloud.lastSync > 0) Text("Last synced ${fmt.format(java.util.Date(cloud.lastSync))}", color = TunerColors.TextSecondary, fontSize = 13.sp)
                    }
                }
                item { SectionTitle("Other devices") }
                if (cloud.devices.isEmpty()) {
                    item { Text("None yet – sign in on your other TVs and phones with the same Google account.", color = TunerColors.TextSecondary, fontSize = 15.sp) }
                }
                val list = cloud.devices
                items(list.size, key = { "dev" + list[it].id }) { i ->
                    val d = list[i]
                    Text(
                        (if (d.phone) "📱  " else "📺  ") + d.name + "  ·  " + d.house + "  ·  last seen " + fmt.format(java.util.Date(d.seenAt)),
                        color = Color.White, fontSize = 15.sp,
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item { SettingRow("Sign out", subtitle = "This device stops syncing (the others carry on)", onClick = { cloud.signOut() }) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (renaming) {
        TextInputDialog(
            title = "Which house is this?",
            initial = cloud.house,
            hint = "e.g. Home, Mum's, Caravan",
            label = "House",
            onDismiss = { renaming = false },
        ) {
            cloud.changeHouse(it)
            renaming = false
        }
    }
}

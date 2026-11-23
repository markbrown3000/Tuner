package com.titanejstudios.tuner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import kotlinx.coroutines.delay

/** Shows a QR code; the phone opens a page to add playlists, set the HA webhook, back up and restore. */
@Composable
fun PhoneSetupScreen(vm: TunerViewModel) {
    DisposableEffect(Unit) {
        vm.startPhoneSetup()
        onDispose { vm.stopPhoneSetup() }
    }
    val url = vm.phoneUrl
    val qr = remember(url) { url?.let { PhoneSetupServer.qrCode(it).asImageBitmap() } }
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { done.requestFocus() }
    }

    val narrow = narrowScreen()
    val qrBox: @Composable () -> Unit = {
        Box(
            Modifier.size(if (narrow) 220.dp else 380.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(if (narrow) 10.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
            } else {
                Text("No network connection", color = Color.Black, fontSize = 20.sp)
            }
        }
    }
    val words: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 14.dp)) {
            Text(
                if (vm.isPhone) "Set up from a computer" else "Set up from your phone",
                fontSize = if (narrow) 26.sp else 36.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary,
            )
            Text(
                if (vm.isPhone) {
                    "Handy for pasting long playlist links or logins.\n" +
                        "1.  Make sure the computer is on the same Wi-Fi as this phone\n" +
                        "2.  Type the address below into its web browser\n" +
                        "3.  Paste in your playlist or login, add a VPN, or restore a backup"
                } else {
                    "1.  Make sure your phone is on the same Wi-Fi as the TV\n" +
                        "2.  Scan the code with your phone's camera\n" +
                        "3.  Paste in your playlist or login, Home Assistant webhook, or restore a backup"
                },
                fontSize = if (narrow) 16.sp else 19.sp,
                color = TunerColors.TextSecondary,
                lineHeight = if (narrow) 24.sp else 30.sp,
            )
            if (url != null) {
                Text(if (vm.isPhone) "Address:" else "Or type this address:", fontSize = 15.sp, color = TunerColors.TextSecondary)
                Text(url, fontSize = if (narrow) 19.sp else 22.sp, color = TunerColors.Accent, fontWeight = FontWeight.SemiBold)
            }
            Text("Keep this screen open while you use the other device.", fontSize = 15.sp, color = TunerColors.Gold)
            Spacer(Modifier.height(6.dp))
            PillButton("Done", { vm.back() }, Modifier.focusRequester(done))
        }
    }
    if (narrow) {
        Column(
            Modifier.fillMaxSize().skyBackground().verticalScroll(rememberScrollState()).padding(screenPadding(64.dp, 64.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            words()
            Spacer(Modifier.height(20.dp))
            // A QR code is still useful from a phone: another phone or tablet can scan it.
            qrBox()
            Spacer(Modifier.height(20.dp))
        }
    } else {
        Row(
            Modifier.fillMaxSize().skyBackground().padding(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            qrBox()
            Spacer(Modifier.width(56.dp))
            words()
        }
    }
}

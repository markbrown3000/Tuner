package com.titanejstudios.tuner.ui

import androidx.compose.foundation.Image
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

    Row(
        Modifier.fillMaxSize().skyBackground().padding(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(380.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
            } else {
                Text("No network connection", color = Color.Black, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.width(56.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set up from your phone", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Text(
                "1.  Make sure your phone is on the same Wi-Fi as the TV\n" +
                    "2.  Scan the code with your phone's camera\n" +
                    "3.  Paste in your playlist or login, Home Assistant webhook, or restore a backup",
                fontSize = 19.sp,
                color = TunerColors.TextSecondary,
                lineHeight = 30.sp,
            )
            if (url != null) {
                Text("Or type this address:", fontSize = 15.sp, color = TunerColors.TextSecondary)
                Text(url, fontSize = 22.sp, color = TunerColors.Accent, fontWeight = FontWeight.SemiBold)
            }
            Text("Keep this screen open while you use your phone.", fontSize = 15.sp, color = TunerColors.Gold)
            Spacer(Modifier.height(6.dp))
            PillButton("Done", { vm.back() }, Modifier.focusRequester(done))
        }
    }
}

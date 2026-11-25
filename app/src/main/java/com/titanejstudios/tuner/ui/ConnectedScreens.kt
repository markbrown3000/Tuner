package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.delay

// ---------------- Plex ----------------

@Composable
fun PlexScreen(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.refreshPlex(force = vm.plexLinked)
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Text("Plex", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Text("Shows your Plex \"Continue watching\" on Flicker's Home screen, and plays it here.", color = TunerColors.TextSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(22.dp))
        val code = vm.plexCode
        if (code != null) {
            Text("Go to  plex.tv/link  and enter:", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                code.forEach { c ->
                    Box(Modifier.size(width = 80.dp, height = 100.dp).clip(RoundedCornerShape(12.dp)).background(TunerColors.Panel), contentAlignment = Alignment.Center) {
                        Text(c.toString(), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        vm.plexStatus?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 17.sp) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.plexLinked) {
                PillButton("Refresh", { vm.refreshPlex(force = true) }, Modifier.focusRequester(first))
                PillButton("Open the Plex app", { vm.openPlexApp() })
                PillButton("Unlink", { vm.unlinkPlex() })
            } else {
                PillButton(if (code == null) "Link my Plex account" else "Get a new code", { vm.linkPlex() }, Modifier.focusRequester(first))
            }
        }
        if (vm.plexLinked && vm.plexItems.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Continue watching", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            PlexRail(vm, vm.plexItems, Modifier)
        }
    }
}

/** Plex "Continue watching" tiles (Home row and Plex page). OK plays here; hold OK for options. */
@Composable
fun PlexRail(vm: TunerViewModel, items: List<Plex.Item>, firstModifier: Modifier, onFocus: (Plex.Item) -> Unit = {}) {
    var menuFor by remember { mutableStateOf<Plex.Item?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        items(items.size, key = { "plex" + items[it].server.machineId + items[it].ratingKey }) { i ->
            val item = items[i]
            FocusSurface(
                onClick = { vm.playPlex(item) },
                onLongClick = { menuFor = item },
                onFocused = { onFocus(item) },
                baseColor = TunerColors.Panel,
                shape = RoundedCornerShape(12.dp),
                zoom = true,
                modifier = (if (i == 0) firstModifier else Modifier).size(width = 232.dp, height = 132.dp),
            ) { focused ->
                item.image?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (focused) Color(0x88000000) else Color(0xDD050816)))))
                Text(
                    "PLEX",
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0xFFE5A00D), RoundedCornerShape(bottomEnd = 8.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                    color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.subtitle?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (item.durationMs > 0) ProgressBar(item.viewOffsetMs.toFloat() / item.durationMs, Modifier.padding(top = 4.dp).fillMaxWidth())
                }
            }
        }
    }
    menuFor?.let { item ->
        MenuDialog(
            title = item.title,
            subtitle = item.subtitle,
            items = listOf(
                MenuItem("Resume") { vm.playPlex(item) },
                MenuItem("Play from the start") { vm.playPlex(item, fromStart = true) },
                MenuItem("Open the Plex app") { vm.openPlexApp() },
            ),
            onDismiss = { menuFor = null },
        )
    }
}

// ---------------- App updates ----------------

@Composable
fun UpdateScreen(vm: TunerViewModel) {
    var askRepo by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Text("Update Flicker", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Text("This TV has Flicker ${vm.installedVersion}.", color = TunerColors.TextSecondary, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        Text("From your computer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Build app-release.apk in Android Studio as usual, then on the same Wi-Fi open this address in your computer's browser and upload it:",
            color = TunerColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.widthIn(max = 950.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(vm.updatePageUrl ?: "Turning on the phone remote…", color = TunerColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(26.dp))
        Text("From GitHub", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            vm.updateRepo?.let { "Checks the latest release of github.com/$it for an .apk" }
                ?: "If you put each new APK on a GitHub release, Flicker can check for it and install it.",
            color = TunerColors.TextSecondary, fontSize = 16.sp,
        )
        vm.updateStatus?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color.White, fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.updateRepo != null) {
                PillButton("Check now", { vm.checkForUpdate() }, Modifier.focusRequester(first))
                PillButton("Install", { vm.installAvailableUpdate() })
            }
            PillButton(if (vm.updateRepo == null) "Set GitHub repo" else "Change repo", { askRepo = true }, if (vm.updateRepo == null) Modifier.focusRequester(first) else Modifier)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Android will ask you to confirm. The first time it asks to allow Flicker to install apps – say yes. " +
                "Updates must be built on the same computer (same signing key), or Android refuses them.",
            color = TunerColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.widthIn(max = 950.dp),
        )
    }
    if (askRepo) {
        TextInputDialog(
            title = "GitHub repository",
            initial = vm.updateRepo.orEmpty(),
            hint = "owner/name, e.g. titanejstudios/tuner",
            label = "Repository",
            onDismiss = { askRepo = false },
        ) { vm.setUpdateRepo(it) }
    }
}

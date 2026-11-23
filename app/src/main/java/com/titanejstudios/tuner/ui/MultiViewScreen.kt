package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.delay

/** 2 or 4 channels at once. Sound follows the highlighted one; OK picks a channel; BACK goes full screen. */
@Composable
fun MultiViewScreen(vm: TunerViewModel) {
    val slots = vm.multi.slots
    if (slots.isEmpty()) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var pickFor by remember { mutableStateOf<Int?>(null) }
    val firstFocus = remember { FocusRequester() }
    val restorer = rememberFocusRestorer()
    RestoreFocusWhenClosed(pickFor != null, restorer)
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    val pool = remember(vm.channels, vm.unlocked) { vm.zapChannels.ifEmpty { vm.channelsFor(ALL) } }

    @Composable
    fun Tile(index: Int, modifier: Modifier) {
        val slot = slots[index]
        val audio = vm.multi.audioSlot == index
        FocusSurface(
            onClick = {
                restorer.key = "slot$index"
                pickFor = index
            },
            onFocused = { vm.multi.selectAudio(index) },
            baseColor = Color.Black,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier
                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                .then(restorer.mod("slot$index")),
        ) { focused ->
            // Same size whether highlighted or not – resizing a video surface makes the TV re-lay it out.
            VideoSurface(slot.player, Modifier.fillMaxSize(), showSubtitles = false)
            if (focused) Box(Modifier.fillMaxSize().border(4.dp, TunerColors.Accent, RoundedCornerShape(8.dp)))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    (if (audio) "🔊 " else "") + (slot.channel?.name ?: "Empty"),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = if (audio) FontWeight.Bold else FontWeight.Normal,
                )
            }
            slot.error?.let {
                Box(Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(12.dp)) {
                    Text(it, color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (slots.size <= 2) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in slots.indices) Tile(i, Modifier.weight(1f).fillMaxSize())
            }
        } else {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0 until 2) {
                        val i = row * 2 + col
                        if (i < slots.size) Tile(i, Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
        }
        Text(
            "Sound follows the highlighted picture  ·  OK to change channel  ·  BACK for full screen",
            color = TunerColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    pickFor?.let { i ->
        MenuDialog(
            title = "Channel for picture ${i + 1}",
            items = pool.map { ch -> MenuItem(ch.name, ch.group) { vm.multi.setChannel(i, ch) } },
            onDismiss = { pickFor = null },
        )
    }
}

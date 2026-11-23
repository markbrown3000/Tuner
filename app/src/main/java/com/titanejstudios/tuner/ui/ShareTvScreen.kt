package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.integrations.TvShare
import kotlinx.coroutines.delay

/** Copy every setting (playlists, favourites, profiles…) from one TV to another on the same Wi-Fi. */
@Composable
fun ShareTvScreen(vm: TunerViewModel) {
    var picked by remember { mutableStateOf<TvShare.Found?>(null) }
    var code by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(vm.shareMode, picked) {
        delay(80)
        runCatching { first.requestFocus() }
    }

    val narrow = narrowScreen()
    // On a phone the words talk about devices rather than TVs, since either end could be a phone.
    val other = if (vm.isPhone) "device" else "TV"
    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Text(
            if (vm.shareMode == "send") "Copy to another $other" else "Copy from another $other",
            fontSize = if (narrow) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary,
        )
        Text(
            "Copies playlists, favourites, profiles, PIN, reminders and settings. Both ${other}s need to be on the same Wi-Fi.",
            color = TunerColors.TextSecondary, fontSize = 15.sp,
        )
        Spacer(Modifier.height(24.dp))

        when (vm.shareMode) {
            "send" -> {
                Text("On the other $other, open Tuner › Settings › Copy from another $other, pick this one and enter:", color = Color.White, fontSize = if (narrow) 17.sp else 20.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    (vm.shareCode ?: "----").forEach { d ->
                        Box(
                            Modifier.size(width = if (narrow) 64.dp else 90.dp, height = if (narrow) 80.dp else 110.dp).clip(RoundedCornerShape(14.dp)).background(TunerColors.Panel),
                            contentAlignment = Alignment.Center,
                        ) { Text(d.toString(), color = Color.White, fontSize = if (narrow) 42.sp else 60.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                vm.shareStatus?.let { Text(it, color = TunerColors.Error, fontSize = 16.sp) }
                Text("Keep this screen open until the other $other says it's done.", color = TunerColors.TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                PillButton("Done", { vm.back() }, Modifier.focusRequester(first))
            }
            else -> {
                val target = picked
                if (target == null) {
                    vm.shareStatus?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 16.sp) }
                    Spacer(Modifier.height(12.dp))
                    if (vm.shareFound.isEmpty()) {
                        Text(
                            "On the other $other, open Tuner › Settings › Copy to another $other. It will appear here.",
                            color = Color.White, fontSize = 18.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        PillButton("Cancel", { vm.back() }, Modifier.focusRequester(first))
                    } else {
                        LazyColumn(Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(vm.shareFound.size, key = { vm.shareFound[it].host + ":" + vm.shareFound[it].port }) { i ->
                                val f = vm.shareFound[i]
                                FocusSurface(
                                    onClick = {
                                        picked = f
                                        code = ""
                                    },
                                    baseColor = TunerColors.Panel,
                                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(64.dp),
                                ) { _ ->
                                    Row(Modifier.padding(horizontal = 18.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                                        Text((if (vm.isPhone) "📱  " else "📺  ") + f.name, color = Color.White, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    fun addDigit(d: Char) {
                        if (code.length < 4) code += d
                        if (code.length == 4) vm.copyFrom(target, code)
                    }
                    Column(
                        Modifier.onPreviewKeyEvent { e ->
                            val k = e.nativeKeyEvent.keyCode
                            if (k in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                                if (e.type == KeyEventType.KeyDown) addDigit('0' + (k - android.view.KeyEvent.KEYCODE_0))
                                true
                            } else false
                        }
                    ) {
                        Text("Enter the code shown on ${target.name}", color = Color.White, fontSize = 20.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (i in 0 until 4) {
                                Box(
                                    Modifier.size(width = 70.dp, height = 86.dp).clip(RoundedCornerShape(12.dp)).background(TunerColors.Panel),
                                    contentAlignment = Alignment.Center,
                                ) { Text(code.getOrNull(i)?.toString() ?: "", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        vm.shareStatus?.let { Text(it, color = TunerColors.TextSecondary, fontSize = 16.sp) }
                        Spacer(Modifier.height(14.dp))
                        // On-screen number pad for remotes without number buttons.
                        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
                        keys.chunked(3).forEachIndexed { r, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                                row.forEachIndexed { c, k ->
                                    PillButton(
                                        k,
                                        {
                                            when (k) {
                                                "⌫" -> code = code.dropLast(1)
                                                "✕" -> picked = null
                                                else -> addDigit(k[0])
                                            }
                                        },
                                        (if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier).width(if (narrow) 76.dp else 90.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

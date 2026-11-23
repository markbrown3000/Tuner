package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.XmltvParser

/** Pick the right guide channel for a playlist channel whose guide didn't match automatically. */
@Composable
fun EpgMapScreen(vm: TunerViewModel) {
    val channel = vm.mappingChannel ?: return
    val candidates = vm.epgCandidates
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf(channel.name) }
    val current = vm.epgOverrides[channel.id]

    val shown = remember(candidates, query) {
        val all = candidates.orEmpty()
        val q = XmltvParser.normalise(query)
        if (q.isEmpty()) all.take(300) else {
            all.map { c ->
                val n = XmltvParser.normalise(c.name)
                val id = XmltvParser.normalise(c.id)
                val score = when {
                    n == q || id == q -> 0
                    n.startsWith(q) || id.startsWith(q) -> 1
                    n.contains(q) || id.contains(q) -> 2
                    q.contains(n) && n.length >= 3 -> 3
                    else -> 9
                }
                score to c
            }.filter { it.first < 9 }.sortedBy { it.first }.map { it.second }.take(300)
        }
    }

    Column(Modifier.fillMaxSize().skyBackground().padding(screenPadding(64.dp, 32.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 76.dp, height = 50.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Fix TV guide for ${channel.name}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
                Text(
                    if (current != null) "Currently matched by hand to \"$current\"" else "Currently matched automatically" +
                        (channel.epgId?.let { " (tvg-id \"$it\")" } ?: ""),
                    fontSize = 15.sp,
                    color = TunerColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search the guide's channels") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        if (candidates == null) {
            CenterMessage("Loading guide channels…")
            return@Column
        }
        if (candidates.isEmpty()) {
            CenterMessage("This playlist's guide has no channel list yet.\nRefresh the playlist in Settings, then try again.")
            return@Column
        }
        LazyColumn(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (current != null) {
                item {
                    SettingRow("Go back to automatic matching", onClick = { vm.setEpgOverride(channel, null) })
                }
            }
            items(shown, key = { it.id }) { c ->
                SettingRow(
                    c.name,
                    subtitle = "id: ${c.id}",
                    detail = if (c.id == current) "✓ Current" else null,
                    onClick = { vm.setEpgOverride(channel, c.id) },
                )
            }
        }
    }
}

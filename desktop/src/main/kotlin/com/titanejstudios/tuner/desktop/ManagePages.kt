package com.titanejstudios.tuner.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.data.Channel

/**
 * Settings › Manage groups and channels: hide, rename, reorder and lock groups; hide channels;
 * give a channel with no guide another channel's guide. Changes show everywhere straight away.
 */
@Composable
fun ManageChannels(app: AppState, onClose: () -> Unit) {
    val v = app.manageVersion
    val groups = remember(app.channels, v) { app.allGroups }
    var selected by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var guideFor by remember { mutableStateOf<Channel?>(null) }
    val hiddenG = remember(v) { app.config.hiddenGroups }
    val hiddenC = remember(v) { app.config.hiddenChannels }
    val locked = remember(v) { app.config.lockedGroups }
    val offline = remember(v) { app.config.offline }
    val epgMap = remember(v) { app.config.epgMap }
    val counts = remember(app.channels) { app.channels.groupingBy { it.group }.eachCount() }

    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.97f)).padding(24.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Groups and channels", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                Pill("Show everything again") { app.resetChannelChanges() }
                Pill("Done", selected = true, onClick = onClose)
            }
            Text(
                "👁 hide or show · ▲▼ move · ✎ rename · 🔒 lock (needs the PIN to watch). Click a group to see its channels.",
                color = Soft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groups, key = { it }) { g ->
                        val hidden = g in hiddenG
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (g == selected) Sky.copy(alpha = 0.35f) else Panel)
                                .clickable { selected = g }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (renaming == g) {
                                OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.weight(1f))
                                Pill("Save", selected = true) { app.renameGroup(g, newName); renaming = null }
                                Pill("Cancel") { renaming = null }
                            } else {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.groupLabel(g), color = if (hidden) Soft else Color.White, fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${counts[g] ?: 0} channels" + (if (app.groupLabel(g) != g) " · was \"$g\"" else "") + (if (hidden) " · hidden" else ""),
                                        color = Soft, fontSize = 11.sp, maxLines = 1,
                                    )
                                }
                                Pill(if (hidden) "🚫" else "👁") { app.toggleGroupHidden(g) }
                                Pill("▲") { app.moveGroup(g, -1) }
                                Pill("▼") { app.moveGroup(g, 1) }
                                Pill("✎") { renaming = g; newName = app.groupLabel(g) }
                                Pill(if (g in locked) "🔒" else "🔓", selected = g in locked) { app.toggleGroupLocked(g) }
                            }
                        }
                    }
                }
                val g = selected
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (g == null) {
                        Text("Pick a group to hide single channels or fix a missing guide.", color = Soft, fontSize = 14.sp)
                    } else {
                        val list = remember(g, app.channels) { app.channels.filter { it.group == g } }
                        Text(app.groupLabel(g), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            items(list, key = { it.id }) { c ->
                                val hidden = c.id in hiddenC
                                val hasGuide = app.guide[c.id]?.isNotEmpty() == true
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, color = if (hidden) Soft else Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(
                                                if (hidden) "hidden" else null,
                                                if (c.id in offline) "⚠ didn't answer the last check" else null,
                                                epgMap[c.id]?.let { from -> "guide from " + (app.channels.firstOrNull { it.id == from }?.name ?: "another channel") }
                                                    ?: if (!hasGuide) "no guide" else null,
                                            ).joinToString(" · "),
                                            color = if (c.id in offline) Gold else Soft, fontSize = 11.sp, maxLines = 1,
                                        )
                                    }
                                    Pill("▶") { onClose(); app.playChannel(c) }
                                    Pill(if (epgMap.containsKey(c.id)) "Guide ✓" else "Guide…") { guideFor = c }
                                    Pill(if (hidden) "🚫" else "👁") { app.toggleChannelHidden(c.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        guideFor?.let { c -> GuidePicker(app, c) { guideFor = null } }
    }
}

/** "Use the guide from…": pick another channel (one with a guide) by searching for it. */
@Composable
private fun GuidePicker(app: AppState, c: Channel, onClose: () -> Unit) {
    var q by remember { mutableStateOf(c.name.replace(Regex("(?i)\\b(f?hd|sd|uhd|4k|hevc|h265|\\+1)\\b|[|:(\\[].*"), "").trim()) }
    val matches = remember(q, app.guide) {
        val t = q.trim().lowercase()
        if (t.length < 2) emptyList()
        else app.channels.filter { it.id != c.id && app.guide[it.id]?.isNotEmpty() == true && it.name.lowercase().contains(t) }.take(30)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(16.dp)).background(Panel).clickable(enabled = false) {}.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Guide for ${c.name}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Pick a channel that shows the same programmes – its guide will be used for this one.", color = Soft, fontSize = 13.sp)
            OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Channel name") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(matches, key = { it.id }) { m ->
                    val nowOn = app.guide[m.id]?.firstOrNull { it.start <= app.now && it.stop > app.now }?.title
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelLight)
                            .clickable { app.useGuideFrom(c, m); onClose() }.padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(m.name, color = Color.White, fontSize = 14.sp)
                        Text(listOfNotNull(m.group, nowOn?.let { "now: $it" }).joinToString(" · "), color = Soft, fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (matches.isEmpty()) item { Text("Type part of a channel's name.", color = Soft, fontSize = 13.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (app.config.epgMap.containsKey(c.id)) Pill("Use its own guide") { app.useGuideFrom(c, null); onClose() }
                Pill("Cancel", onClick = onClose)
            }
        }
    }
}

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(vm: TunerViewModel) {
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val restorer = rememberFocusRestorer()
    var menuFor by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    RestoreFocusWhenClosed(menuFor != null, restorer)
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fieldFocus.requestFocus() }
    }
    val results = vm.searchResults
    val now = vm.now
    val all = remember(vm.channels, vm.unlocked) { vm.channelsFor(ALL) }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Text("Search", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.updateSearch(it) },
            label = { Text("Channel or programme name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
        Spacer(Modifier.height(16.dp))

        if (vm.searchQuery.trim().length >= 2 && results.channels.isEmpty() && results.programmes.isEmpty()) {
            Text("Nothing found", color = TunerColors.TextSecondary, fontSize = 18.sp)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (results.channels.isNotEmpty()) {
                item { SectionTitle("Channels") }
                items(results.channels, key = { "c" + it.id }) { ch ->
                    ChannelRow(
                        vm = vm,
                        channel = ch,
                        number = vm.displayNumber(ch, all.indexOfFirst { it.id == ch.id }),
                        modifier = Modifier,
                        onClick = { vm.play(ch, all) },
                    )
                }
            }
            if (results.programmes.isNotEmpty()) {
                item { SectionTitle("Programmes") }
                items(results.programmes, key = { (c, p) -> "p" + c.id + "@" + p.start }) { (ch, p) ->
                    val key = ch.id + "@" + p.start
                    val live = p.start <= now && p.stop > now
                    val catchup = p.stop <= now && vm.canCatchup(ch, p)
                    val reminder = p.start > now && vm.hasReminder(ch, p)
                    val tag = when {
                        live -> "On now"
                        catchup -> "↺ Catch-up"
                        reminder -> "⏰ Reminder set"
                        p.start > now -> "Upcoming"
                        else -> "Earlier"
                    }
                    FocusSurface(
                        onClick = {
                            when {
                                live -> vm.play(ch, all)
                                catchup -> vm.playCatchup(ch, p, all)
                                p.start > now -> vm.toggleReminder(ch, p)
                                else -> vm.showNotice("This has finished and has no catch-up")
                            }
                        },
                        onLongClick = {
                            restorer.key = key
                            menuFor = ch to p
                        },
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(70.dp).then(restorer.mod(key)),
                    ) { focused ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(150.dp)) {
                                Text(dayLabel(p.start), color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 14.sp)
                                Text("${hhmm(p.start)} – ${hhmm(p.stop)}", color = Color.White, fontSize = 16.sp)
                            }
                            ChannelLogo(ch.logo, ch.name, Modifier.size(width = 60.dp, height = 40.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(ch.name, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 14.sp, maxLines = 1)
                            }
                            Text(
                                tag,
                                color = when {
                                    focused -> Color.White
                                    catchup -> TunerColors.Catchup
                                    reminder -> TunerColors.Gold
                                    live -> TunerColors.Accent
                                    else -> TunerColors.TextSecondary
                                },
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { (ch, p) ->
        val t = System.currentTimeMillis()
        val items = buildList {
            add(MenuItem("Watch ${ch.name} live") { vm.play(ch, all) })
            if (p.start <= t && vm.canCatchup(ch, p)) add(MenuItem("Watch this programme (catch-up)") { vm.playCatchup(ch, p, all) })
            if (p.start > t) add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel reminder" else "Remind me") { vm.toggleReminder(ch, p) })
            if (p.stop > t && !vm.activeProfile.kids) {
                val rec = vm.recordingFor(ch, p)
                if (rec == null) add(MenuItem("● Record") { vm.recordProgramme(ch, p) })
                add(MenuItem(if (vm.seriesLinkFor(ch, p) != null) "Cancel series link" else "🔁 Series link (record every episode)") { vm.toggleSeriesLink(ch, p) })
            }
        }
        MenuDialog(p.title, items, onDismiss = { menuFor = null }, subtitle = "${dayLabel(p.start)} ${hhmm(p.start)} · ${ch.name}")
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = TunerColors.TextSecondary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

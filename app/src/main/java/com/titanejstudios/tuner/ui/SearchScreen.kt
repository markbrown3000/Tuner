package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.recordProgramme
import com.titanejstudios.tuner.recordingFor
import com.titanejstudios.tuner.updateSearch
import com.titanejstudios.tuner.voiceHeard
import com.titanejstudios.tuner.whereToWatch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
            .padding(horizontal = if (narrowScreen()) 12.dp else 48.dp, vertical = if (narrowScreen()) 10.dp else 28.dp)
    ) {
        // The remote's microphone: say what you're looking for.
        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.voiceHeard(it) }
        }
        fun startVoice() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, or what you'd like to watch")
            try {
                voice.launch(intent)
            } catch (e: ActivityNotFoundException) {
                vm.showNotice("This TV has no voice typing – press the microphone button on the remote instead")
            }
        }
        // Home's 🎤 button asked for voice straight away.
        LaunchedEffect(Unit) {
            if (vm.voiceSearchPending) {
                vm.voiceSearchPending = false
                startVoice()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Search", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            if (vm.searchQuery.trim().length >= 2) {
                PillButton("📍 Where can I watch it?", { vm.whereToWatch(vm.searchQuery) }, compact = true)
                Spacer(Modifier.width(10.dp))
            }
            PillButton("🎤 Say it", { startVoice() }, compact = true)
        }
        Text(
            "Try saying: \"record EastEnders\", \"next episode\", \"what's on BBC One later\", \"where can I watch Dune\", \"something funny\"",
            color = TunerColors.TextSecondary, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.updateSearch(it) },
            label = { Text("Channel, programme, film, series, Plex or YouTube") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
        Spacer(Modifier.height(16.dp))

        val plexFound = vm.searchPlex
        if (vm.searchQuery.trim().length >= 2 && results.channels.isEmpty() && results.programmes.isEmpty() &&
            results.films.isEmpty() && results.seriesFound.isEmpty() && plexFound.isEmpty()
        ) {
            Text(if (vm.searchYouTube.isEmpty()) "Nothing found" else "Only on YouTube", color = TunerColors.TextSecondary, fontSize = 18.sp)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (plexFound.isNotEmpty()) {
                item { SectionTitle("On Plex") }
                item(key = "plexrow") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                        items(plexFound.size, key = { "px" + plexFound[it].server.machineId + plexFound[it].ratingKey }) { i ->
                            val e = plexFound[i]
                            PosterCard(
                                title = e.title,
                                image = e.image,
                                caption = listOfNotNull(e.year, e.library).joinToString("  ·  ").ifEmpty { null },
                                progress = null,
                                onClick = { if (e.isShow) vm.openPlexShow(e) else vm.playPlexEntry(e) },
                                modifier = Modifier.width(118.dp),
                            )
                        }
                    }
                }
            }
            if (results.films.isNotEmpty()) {
                item { SectionTitle(results.actor?.let { "Films with $it" } ?: "Films") }
                item(key = "filmrow") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                        items(results.films.size, key = { "f" + results.films[it].id }) { i ->
                            val m = results.films[i]
                            PosterCard(
                                title = m.name, image = m.poster, caption = m.category, progress = null,
                                onClick = { vm.playMovie(m, fromStart = false) },
                                modifier = Modifier.width(118.dp),
                            )
                        }
                    }
                }
            }
            if (results.seriesFound.isNotEmpty()) {
                item { SectionTitle("Series") }
                item(key = "seriesrow") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                        items(results.seriesFound.size, key = { "s" + results.seriesFound[it].id }) { i ->
                            val s = results.seriesFound[i]
                            PosterCard(
                                title = s.name, image = s.cover, caption = s.category, progress = null,
                                onClick = { vm.openSeriesDetail(s) },
                                modifier = Modifier.width(118.dp),
                            )
                        }
                    }
                }
            }
            val yt = vm.searchYouTube
            if (yt.isNotEmpty()) {
                item { SectionTitle("YouTube") }
                item(key = "ytrow") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                        items(yt.size, key = { "yt$it" }) { i -> Box(Modifier.width(240.dp)) { VideoCard(vm, yt[i], narrowScreen(), queue = yt) } }
                    }
                }
            }
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
            // Programmes grouped by when: on now, coming up (the week ahead), then earlier.
            val progGroups = listOf(
                "On now" to results.programmes.filter { (_, p) -> p.start <= now && p.stop > now },
                "Coming up" to results.programmes.filter { (_, p) -> p.start > now }.sortedBy { it.second.start },
                "Earlier – catch-up" to results.programmes.filter { (_, p) -> p.stop <= now }.sortedByDescending { it.second.start },
            ).filter { it.second.isNotEmpty() }
            for ((groupTitle, group) in progGroups) {
                item(key = "pt$groupTitle") { SectionTitle(groupTitle) }
                items(group, key = { (c, p) -> "p" + c.id + "@" + p.start }) { (ch, p) ->
                    val key = ch.id + "@" + p.start
                    val live = p.start <= now && p.stop > now
                    val catchup = p.stop <= now && vm.canCatchup(ch, p)
                    val reminder = p.start > now && vm.hasReminder(ch, p)
                    // Finished with no catch-up from your provider: the broadcaster's own app may have it.
                    val onApp = if (!live && !catchup && p.stop <= now) vm.catchupAppFor(ch)?.first else null
                    val tag = when {
                        live -> "On now"
                        catchup -> "↺ Catch-up"
                        reminder -> "⏰ Reminder set"
                        p.start > now -> "Upcoming"
                        onApp != null -> "▶ On ${onApp.name}"
                        else -> "Earlier"
                    }
                    FocusSurface(
                        memoryKey = "sr|$key",
                        onClick = {
                            when {
                                live -> vm.play(ch, all)
                                catchup -> vm.playCatchup(ch, p, all)
                                p.start > now -> vm.toggleReminder(ch, p)
                                onApp != null -> vm.openInCatchupApp(ch, p.title)
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
                            Column(Modifier.width(if (narrowScreen()) 78.dp else 150.dp)) {
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
            add(MenuItem("👍 More like this") { vm.rateProgramme(ch, p, like = true) })
            add(MenuItem("👎 Not for me") { vm.rateProgramme(ch, p, like = false) })
            if (p.start <= t) vm.catchupAppFor(ch)?.let { (provider, _) -> add(MenuItem("▶ Watch on ${provider.name}") { vm.openInCatchupApp(ch, p.title) }) }
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

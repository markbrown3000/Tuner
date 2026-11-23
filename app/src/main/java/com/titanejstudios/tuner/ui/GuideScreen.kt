package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import kotlinx.coroutines.delay

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 2 * 3600_000L
private val CHANNEL_COL = 230.dp

/** A slice of the timeline in one row: a programme, or a gap with no guide data. */
private data class Segment(val start: Long, val end: Long, val programme: Programme?)

private fun segments(list: List<Programme>, windowStart: Long, windowEnd: Long): List<Segment> {
    val out = ArrayList<Segment>()
    var cursor = windowStart
    for (p in list) {
        if (p.stop <= cursor) continue
        if (p.start >= windowEnd) break
        if (p.start > cursor) {
            out += Segment(cursor, p.start, null)
            cursor = p.start
        }
        val end = minOf(p.stop, windowEnd)
        out += Segment(cursor, end, p)
        cursor = end
        if (cursor >= windowEnd) break
    }
    if (cursor < windowEnd) out += Segment(cursor, windowEnd, null)
    return out
}

private fun floorToHalfHour(t: Long) = t - (t % HALF_HOUR)

/** Which row should grab focus after the time window shifts (so focus never gets lost). */
private data class PendingFocus(val channelId: String, val toLast: Boolean)

@Composable
fun GuideScreen(vm: TunerViewModel) {
    val group = vm.selectedGroup
    val list = remember(group, vm.channels, vm.favourites, vm.recents, vm.unlocked, vm.lockedGroups) {
        vm.channelsFor(group)
    }

    val startOfNow = remember { floorToHalfHour(System.currentTimeMillis()) }
    val minStart = remember { floorToHalfHour(startOfNow - vm.guideHistoryMs) }
    val maxStart = startOfNow + 22 * 3600_000L
    var windowStart by remember { mutableLongStateOf(startOfNow) }
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    var pending by remember { mutableStateOf<PendingFocus?>(null) }
    var showGroups by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val restorer = rememberFocusRestorer()
    RestoreFocusWhenClosed(menuFor != null || showGroups, restorer)

    val initialRow = remember(list) {
        list.indexOfFirst { it.id == vm.focusChannelId }.coerceAtLeast(0)
    }
    val initialFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialRow)
    LaunchedEffect(list) {
        delay(60)
        runCatching { listState.scrollToItem(initialRow) }
        runCatching { initialFocus.requestFocus() }
    }

    fun shift(channelId: String, toLast: Boolean, delta: Long) {
        val target = (windowStart + delta).coerceIn(minStart, maxStart)
        if (target != windowStart) {
            pending = PendingFocus(channelId, toLast)
            windowStart = target
        }
    }

    fun activate(ch: Channel, p: Programme?) {
        val t = System.currentTimeMillis()
        if (p == null || (p.start <= t && p.stop > t)) {
            vm.play(ch, list)
        } else if (p.stop <= t) {
            if (vm.canCatchup(ch, p)) {
                vm.playCatchup(ch, p, list)
            } else {
                vm.showNotice("Catch-up isn't available for this programme")
            }
        } else {
            vm.toggleReminder(ch, p)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        GuideHeader(vm, focused, group, groupButton = restorer.mod("groups"), onGroups = {
            restorer.key = "groups"
            showGroups = true
        })
        Spacer(Modifier.height(10.dp))

        if (list.isEmpty()) {
            CenterMessage(
                if (vm.isGroupLocked(group)) "🔒 \"$group\" is locked – pick it on the Channels screen to unlock"
                else "No channels in \"$group\""
            )
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val timelineWidth = maxWidth - CHANNEL_COL
            val dpPerMs: Dp = timelineWidth / WINDOW.toFloat()
            Column {
                TimeRuler(windowStart, dpPerMs)
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(list, key = { _, c -> c.id }) { index, ch ->
                            GuideRow(
                                vm = vm,
                                channel = ch,
                                number = vm.displayNumber(ch, index),
                                windowStart = windowStart,
                                dpPerMs = dpPerMs,
                                initialFocus = if (index == initialRow) initialFocus else null,
                                pending = pending,
                                restorer = restorer,
                                onPendingHandled = { pending = null },
                                onShift = { toLast, delta -> shift(ch.id, toLast, delta) },
                                onFocus = { p -> focused = ch to p },
                                onActivate = { p -> activate(ch, p) },
                                onMenu = { key, p ->
                                    restorer.key = key
                                    menuFor = ch to p
                                },
                            )
                        }
                    }
                    // "Now" line
                    val now = vm.now
                    if (now in windowStart..(windowStart + WINDOW)) {
                        Box(
                            Modifier
                                .offset(x = CHANNEL_COL + dpPerMs * (now - windowStart).toFloat())
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(TunerColors.NowLine)
                        )
                    }
                }
            }
        }
    }

    if (showGroups) {
        MenuDialog(
            title = "Show group",
            items = vm.groupNames.map { g ->
                MenuItem(g, if (g == group) "✓" else null) { vm.selectedGroup = g }
            },
            onDismiss = { showGroups = false },
        )
    }

    menuFor?.let { (ch, p) ->
        val t = System.currentTimeMillis()
        val items = buildList {
            add(MenuItem("Watch live") { vm.play(ch, list) })
            if (p != null && p.start <= t && vm.canCatchup(ch, p)) {
                add(MenuItem(if (p.stop > t) "Watch from the start" else "Watch (catch-up)") { vm.playCatchup(ch, p, list) })
            }
            if (p != null && p.start > t) {
                add(MenuItem(if (vm.hasReminder(ch, p)) "Cancel reminder" else "Remind me") { vm.toggleReminder(ch, p) })
            }
            if (p != null && p.stop > t && !vm.activeProfile.kids) {
                val rec = vm.recordingFor(ch, p)
                if (rec == null) add(MenuItem("● Record") { vm.recordProgramme(ch, p) })
                else add(MenuItem("Cancel recording") { vm.stopRecording(rec) })
                add(MenuItem(if (vm.seriesLinkFor(ch, p) != null) "Cancel series link" else "🔁 Series link (record every episode)") { vm.toggleSeriesLink(ch, p) })
            }
            add(MenuItem(if (ch.id in vm.favouriteSet) "Remove from favourites" else "Add to favourites") { vm.toggleFavourite(ch.id) })
        }
        MenuDialog(
            title = p?.title ?: ch.name,
            subtitle = if (p != null) "${dayLabel(p.start)}  ${hhmm(p.start)} – ${hhmm(p.stop)}  ·  ${ch.name}" else null,
            items = items,
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun GuideHeader(
    vm: TunerViewModel,
    focused: Pair<Channel, Programme?>?,
    group: String,
    groupButton: Modifier,
    onGroups: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(160.dp)) {
        Column(Modifier.weight(1f)) {
            val channel = focused?.first
            val p = focused?.second
            Text(
                p?.title ?: channel?.name ?: "TV Guide",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TunerColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val t = vm.now
            val tag = if (p == null || channel == null) "" else when {
                p.start <= t && p.stop > t -> "  ·  On now"
                p.stop <= t && vm.canCatchup(channel, p) -> "  ·  ↺ Catch-up – OK to watch"
                p.start > t && vm.hasReminder(channel, p) -> "  ·  ⏰ Reminder set"
                p.start > t -> "  ·  OK to set a reminder"
                else -> ""
            }
            Text(
                when {
                    p != null && channel != null ->
                        "${dayLabel(p.start)}  ${hhmm(p.start)} – ${hhmm(p.stop)}  ·  ${channel.name}$tag"
                    channel != null -> "No guide information for ${channel.name}"
                    else -> ""
                },
                fontSize = 17.sp,
                color = TunerColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                p?.desc ?: "",
                fontSize = 16.sp,
                color = TunerColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Group: $group  ▾", onGroups, groupButton)
                Spacer(Modifier.width(16.dp))
                Text("OK watch / remind  ·  hold OK: record & more", fontSize = 14.sp, color = TunerColors.TextSecondary)
                Spacer(Modifier.width(14.dp))
                genreLegend.forEach { (name, color) ->
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Spacer(Modifier.width(4.dp))
                    Text(name, fontSize = 12.sp, color = TunerColors.TextSecondary)
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(vm.now), fontSize = 16.sp, color = TunerColors.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text(hhmm(vm.now), fontSize = 28.sp, color = TunerColors.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            MiniPreview(vm, Modifier.size(width = 210.dp, height = 118.dp))
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, dpPerMs: Dp) {
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text(dayLabel(windowStart), color = TunerColors.TextSecondary, fontSize = 15.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            for (k in 0 until (WINDOW / HALF_HOUR).toInt()) {
                Text(
                    hhmm(windowStart + k * HALF_HOUR),
                    modifier = Modifier
                        .offset(x = dpPerMs * (k * HALF_HOUR).toFloat() + 8.dp)
                        .align(Alignment.CenterStart),
                    color = TunerColors.TextPrimary,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    vm: TunerViewModel,
    channel: Channel,
    number: Int,
    windowStart: Long,
    dpPerMs: Dp,
    initialFocus: FocusRequester?,
    pending: PendingFocus?,
    restorer: FocusRestorer,
    onPendingHandled: () -> Unit,
    onShift: (toLast: Boolean, delta: Long) -> Unit,
    onFocus: (Programme?) -> Unit,
    onActivate: (Programme?) -> Unit,
    onMenu: (key: String, programme: Programme?) -> Unit,
) {
    val programmes = vm.programmesFor(channel.id)
    val segs = remember(programmes, windowStart) { segments(programmes, windowStart, windowStart + WINDOW) }
    val firstReq = remember { FocusRequester() }
    val lastReq = remember { FocusRequester() }
    val now = vm.now

    LaunchedEffect(windowStart) {
        val p = pending
        if (p != null && p.channelId == channel.id) {
            delay(16)
            runCatching { (if (p.toLast) lastReq else firstReq).requestFocus() }
            onPendingHandled()
        }
    }

    Row(Modifier.fillMaxWidth().height(62.dp)) {
        // Channel cell
        Row(
            Modifier
                .width(CHANNEL_COL)
                .fillMaxHeight()
                .padding(end = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TunerColors.Panel)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number", color = TunerColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(36.dp))
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 54.dp, height = 36.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name,
                color = TunerColors.TextPrimary,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Programme cells
        segs.forEachIndexed { idx, seg ->
            val isFirst = idx == 0
            val isLast = idx == segs.lastIndex
            val prog = seg.programme
            val live = prog != null && now >= prog.start && now < prog.stop
            val past = prog != null && prog.stop <= now
            val catchup = prog != null && past && vm.canCatchup(channel, prog)
            val reminder = prog != null && prog.start > now && vm.hasReminder(channel, prog)
            val recording = prog != null && prog.stop > now && vm.recordingFor(channel, prog) != null
            val genre = genreColor(prog?.category)
            val cellKey = "${channel.id}@${seg.start}"
            var mod: Modifier = Modifier
                .width(dpPerMs * (seg.end - seg.start).toFloat())
                .fillMaxHeight()
                .padding(end = 3.dp)
            if (isFirst) mod = mod.focusRequester(firstReq)
            if (isLast) mod = mod.focusRequester(lastReq)
            if (isFirst && initialFocus != null) mod = mod.focusRequester(initialFocus)
            mod = mod.then(restorer.mod(cellKey))
            mod = mod.onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    isFirst && e.key == Key.DirectionLeft -> { onShift(false, -HALF_HOUR); true }
                    isLast && e.key == Key.DirectionRight -> { onShift(true, HALF_HOUR); true }
                    else -> false
                }
            }
            FocusSurface(
                onClick = { onActivate(prog) },
                onLongClick = { onMenu(cellKey, prog) },
                onFocused = { onFocus(prog) },
                baseColor = when {
                    live -> TunerColors.PanelLight
                    past -> Color(0xFF14181E)
                    else -> TunerColors.Panel
                },
                shape = RoundedCornerShape(6.dp),
                modifier = mod,
            ) { isFocused ->
                if (genre != null) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(genre))
                }
                Column(Modifier.padding(horizontal = 10.dp)) {
                    val prefix = when {
                        recording -> "● "
                        reminder -> "⏰ "
                        catchup -> "↺ "
                        else -> ""
                    }
                    Text(
                        prefix + (prog?.title ?: "No information"),
                        color = when {
                            isFocused -> Color.White
                            prog == null -> TunerColors.TextSecondary
                            past && !catchup -> TunerColors.TextSecondary
                            catchup -> TunerColors.Catchup
                            else -> Color.White
                        },
                        fontSize = 15.sp,
                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (prog != null) {
                        Text(
                            "${hhmm(prog.start)} – ${hhmm(prog.stop)}",
                            color = if (isFocused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

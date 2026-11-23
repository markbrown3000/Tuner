package com.titanejstudios.tuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.FAVOURITES
import com.titanejstudios.tuner.RECENT
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(vm: TunerViewModel) {
    // Category tabs: the highlight follows the remote instantly, but the channel list only
    // switches once you pause on a category – so flicking through categories stays smooth.
    var tab by remember { mutableStateOf(vm.selectedGroup) }
    LaunchedEffect(tab) {
        if (tab != vm.selectedGroup) {
            delay(260)
            vm.selectedGroup = tab
        }
    }
    val group = vm.selectedGroup
    val list = remember(group, vm.channels, vm.favourites, vm.recents, vm.unlocked, vm.lockedGroups) {
        vm.channelsFor(group)
    }
    val locked = vm.isGroupLocked(group)

    val tabFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val restorer = rememberFocusRestorer()
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var focused by remember { mutableStateOf<Channel?>(null) }
    RestoreFocusWhenClosed(menuFor != null, restorer)

    // Getting around long lists: BACK or ◀ jumps up to the categories, CH+/CH− move a page,
    // and typing a number goes straight to that channel.
    var listHasFocus by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var jumpIndex by remember { mutableIntStateOf(-1) }
    val jumpFocus = remember { FocusRequester() }
    var digits by remember { mutableStateOf("") }

    // When coming back from the player, land on the channel that was playing.
    val returnChannelId = remember { vm.focusChannelId }
    val returnIndex = remember { list.indexOfFirst { it.id == returnChannelId } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, returnIndex - 2))
    val tabState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, vm.groupNames.indexOf(group) - 1))
    var shownGroup by remember { mutableStateOf(group) }
    LaunchedEffect(group) {
        if (group != shownGroup) {
            shownGroup = group
            runCatching { listState.scrollToItem(0) }
        }
    }

    fun jumpTo(index: Int) {
        if (list.isEmpty()) return
        val i = index.coerceIn(0, list.lastIndex)
        jumpIndex = i
        scope.launch {
            runCatching { listState.scrollToItem(maxOf(0, i - 2)) }
            delay(40)
            runCatching { jumpFocus.requestFocus() }
        }
    }

    fun backToCategories() {
        scope.launch {
            runCatching { tabState.scrollToItem(maxOf(0, vm.groupNames.indexOf(tab) - 1)) }
            delay(40)
            runCatching { tabFocus.requestFocus() }
            runCatching { listState.scrollToItem(0) }
        }
    }

    BackHandler(enabled = listHasFocus && menuFor == null) { backToCategories() }

    // Typed channel number: jump once you stop typing.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val n = digits.toIntOrNull()
        digits = ""
        if (n != null) {
            val i = list.indices.firstOrNull { vm.displayNumber(list[it], it) == n }
            if (i != null) jumpTo(i) else vm.showNotice("No channel $n in this category")
        }
    }

    var didInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(list.isNotEmpty()) {
        if (didInitialFocus) return@LaunchedEffect
        delay(60)
        val ok = runCatching {
            when {
                returnIndex >= 0 -> channelFocus.requestFocus()
                list.isNotEmpty() -> firstChannelFocus.requestFocus()
                else -> tabFocus.requestFocus()
            }
        }.isSuccess
        if (ok && list.isNotEmpty()) didInitialFocus = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = 44.dp, vertical = 22.dp)
    ) {
        TopNav(vm, Screen.Channels)
        Spacer(Modifier.height(8.dp))

        // Category tabs
        LazyRow(
            state = tabState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(vm.groupNames, key = { it }) { g ->
                val isTab = g == tab
                FocusSurface(
                    onClick = {
                        tab = g
                        vm.selectedGroup = g
                        scope.launch {
                            delay(80)
                            runCatching { listState.scrollToItem(0) }
                            delay(50)
                            runCatching { firstChannelFocus.requestFocus() }
                        }
                    },
                    onFocused = { tab = g },
                    selected = isTab,
                    baseColor = TunerColors.Panel,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .then(if (isTab) Modifier.focusRequester(tabFocus) else Modifier)
                        .onPreviewKeyEvent { e ->
                            // ▼ straight after picking a category: switch now and go into the list.
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && vm.selectedGroup != tab) {
                                vm.selectedGroup = tab
                                scope.launch {
                                    delay(60)
                                    runCatching { listState.scrollToItem(0) }
                                    runCatching { firstChannelFocus.requestFocus() }
                                }
                                true
                            } else false
                        },
                ) { f ->
                    Text(
                        (if (g in vm.lockedGroups && vm.pin != null) "🔒 " else "") + g,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = if (f || isTab) Color.White else TunerColors.TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (isTab) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.weight(1f)) {
            // Channel list
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { listHasFocus = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val code = e.nativeKeyEvent.keyCode
                        when (code) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                backToCategories()
                                true
                            }
                            android.view.KeyEvent.KEYCODE_CHANNEL_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                                jumpTo(focusedIndex - 8)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                                jumpTo(focusedIndex + 8)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_MOVE_HOME, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                jumpTo(0)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_MOVE_END, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                jumpTo(list.lastIndex)
                                true
                            }
                            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                                if (e.nativeKeyEvent.repeatCount == 0 && digits.length < 5) {
                                    digits += (code - android.view.KeyEvent.KEYCODE_0).toString()
                                }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                val status = vm.status
                when {
                    tab != group -> CenterMessage("") // about to switch – keeps flicking through tabs smooth
                    locked -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🔒  This category is locked", color = TunerColors.TextSecondary, fontSize = 22.sp)
                        Spacer(Modifier.height(16.dp))
                        PillButton("Enter PIN", { vm.requestUnlock { } })
                    }
                    list.isEmpty() && status != null -> CenterMessage(status)
                    list.isEmpty() && group == FAVOURITES ->
                        CenterMessage("No favourites yet.\nHold OK on any channel to add it here.")
                    list.isEmpty() && group == RECENT -> CenterMessage("Channels you watch will appear here.")
                    list.isEmpty() -> CenterMessage(vm.error ?: "No channels")
                    else -> LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(list, key = { _, c -> c.id }) { index, ch ->
                            ChannelRow(
                                vm = vm,
                                channel = ch,
                                number = vm.displayNumber(ch, index),
                                modifier = Modifier
                                    .then(if (ch.id == returnChannelId) Modifier.focusRequester(channelFocus) else Modifier)
                                    .then(if (index == 0) Modifier.focusRequester(firstChannelFocus) else Modifier)
                                    .then(if (index == jumpIndex) Modifier.focusRequester(jumpFocus) else Modifier)
                                    .then(restorer.mod(ch.id)),
                                onClick = { vm.play(ch, list) },
                                onLongClick = {
                                    restorer.key = ch.id
                                    menuFor = ch
                                },
                                onFocused = {
                                    focused = ch
                                    focusedIndex = index
                                },
                                compact = true,
                            )
                        }
                    }
                }
                if (digits.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000))
                            .padding(horizontal = 22.dp, vertical = 8.dp)
                    ) {
                        Text(digits, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // Live picture + details of the highlighted channel
            Column(Modifier.width(380.dp).fillMaxHeight()) {
                Box(
                    Modifier.fillMaxWidth().height(214.dp).clip(RoundedCornerShape(14.dp)).background(TunerColors.Panel),
                    contentAlignment = Alignment.Center,
                ) {
                    val f = focused
                    // Live picture of what's playing; otherwise the programme's picture, or the logo.
                    val picture = if (vm.mode == null) f?.let { vm.nowNext(it.id, vm.now).first?.image } else null
                    // (The live picture stays put while scrolling – swapping it in and out would be slow.)
                    if (vm.mode != null) {
                        MiniPreview(vm, Modifier.fillMaxSize())
                    } else if (picture != null) {
                        AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else if (f != null) {
                        ChannelLogo(f.logo, f.name, Modifier.size(width = 200.dp, height = 120.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                val ch = focused ?: vm.playing
                if (ch != null) {
                    val (cur, next) = vm.nowNext(ch.id, vm.now)
                    Text(
                        cur?.title ?: ch.name,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ch.name + (cur?.let { "  ·  ${hhmm(it.start)} – ${hhmm(it.stop)}" } ?: ""),
                        color = TunerColors.Accent,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (cur != null) {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProgressBar(progressOf(cur, vm.now), Modifier.width(160.dp))
                            Spacer(Modifier.width(10.dp))
                            GenreChip(cur.category)
                        }
                        cur.desc?.let {
                            Text(
                                it,
                                color = Color(0xFFD5DCF2),
                                fontSize = 15.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    if (next != null) {
                        Text(
                            "Next  ${hhmm(next.start)}  ${next.title}",
                            color = TunerColors.TextSecondary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row {
                    PillButton("Grid guide", { vm.navigate(Screen.Guide) }, compact = true)
                    Spacer(Modifier.width(8.dp))
                    PillButton("Sports", { vm.navigate(Screen.Sports) }, compact = true)
                }
            }
        }

        // Status line
        Spacer(Modifier.height(8.dp))
        val status = vm.status
        val error = vm.error
        Text(
            when {
                status != null -> "⟳  $status"
                error != null -> error
                else -> "OK watch  ·  Hold OK options  ·  BACK or ◀ categories  ·  CH+/− page  ·  0–9 jump to a number  ·  ${list.size} channels"
            },
            color = if (status == null && error != null) TunerColors.Error else TunerColors.TextSecondary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    menuFor?.let { ch ->
        ChannelMenu(vm, ch, group, list, onDismiss = { menuFor = null })
    }
}

/** Hold-OK menu for a channel. */
@Composable
fun ChannelMenu(
    vm: TunerViewModel,
    ch: Channel,
    group: String,
    list: List<Channel>,
    onDismiss: () -> Unit,
) {
    val isFav = ch.id in vm.favouriteSet
    val items = buildList {
        add(MenuItem("Watch") { vm.play(ch, list) })
        add(MenuItem(if (isFav) "Remove from favourites" else "Add to favourites") { vm.toggleFavourite(ch.id) })
        if (group == FAVOURITES && isFav) {
            add(MenuItem("Move up in favourites") { vm.moveFavourite(ch.id, -1) })
            add(MenuItem("Move down in favourites") { vm.moveFavourite(ch.id, 1) })
        }
        if (ch.hasCatchup) {
            add(MenuItem("Catch-up", "${ch.catchupDays} days") {
                vm.selectedGroup = if (group == ALL || group == FAVOURITES || group == RECENT) group else ch.group
                vm.navigate(Screen.Guide)
            })
        }
        if (!vm.activeProfile.kids) {
            add(MenuItem("● Record now for an hour") { vm.recordNow(ch, 60) })
            add(MenuItem("Fix TV guide match") { vm.openEpgMapping(ch) })
        }
        if (!vm.activeProfile.kids) add(MenuItem("➕ Add to my category…") { vm.categoryPickFor = ch })
        if (vm.isCustomGroup(group)) add(MenuItem("Remove from $group") { vm.toggleInCustomGroup(group, ch) })
        add(MenuItem("Hide this channel") {
            vm.toggleHiddenChannel(ch.id)
            vm.showNotice("${ch.name} hidden – unhide it in Settings › Manage channels")
        })
    }
    MenuDialog(ch.name, items, onDismiss, subtitle = ch.group)
}

@Composable
fun ChannelRow(
    vm: TunerViewModel,
    channel: Channel,
    number: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    onFocused: (() -> Unit)? = null,
    highlight: Boolean = false,
) {
    val (current, next) = vm.nowNext(channel.id, vm.now)
    val isFavourite = channel.id in vm.favouriteSet
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick ?: { vm.toggleFavourite(channel.id) },
        onFocused = onFocused,
        selected = highlight,
        baseColor = TunerColors.Panel,
        modifier = modifier.fillMaxWidth().height(if (compact) 66.dp else 76.dp),
    ) { focused ->
        val secondary = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                number.toString(),
                modifier = Modifier.width(if (compact) 40.dp else 52.dp),
                color = secondary,
                fontSize = if (compact) 15.sp else 18.sp,
            )
            ChannelLogo(
                channel.logo, channel.name,
                if (compact) Modifier.size(width = 60.dp, height = 40.dp) else Modifier.size(width = 76.dp, height = 50.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        modifier = Modifier.weight(1f, fill = false),
                        color = Color.White,
                        fontSize = if (compact) 17.sp else 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isFavourite) Text("  ★", color = TunerColors.Gold, fontSize = 16.sp)
                    if (channel.hasCatchup) Text("  ↺", color = if (focused) Color.White else TunerColors.Catchup, fontSize = 16.sp)
                    val versions = vm.versionsFor(channel).size
                    if (versions > 1) Text("  ⧉ $versions", color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 14.sp)
                    if (channel.id in vm.deadChannels) Text("  ⚠ offline", color = if (focused) Color.White else TunerColors.Error, fontSize = 14.sp)
                }
                if (current != null) {
                    Text(
                        "${hhmm(current.start)}  ${current.title}",
                        color = secondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProgressBar(
                        progressOf(current, vm.now),
                        Modifier.padding(top = 4.dp).width(if (compact) 160.dp else 240.dp),
                        color = if (focused) Color.White else TunerColors.Accent,
                    )
                } else {
                    Text("No guide information", color = secondary, fontSize = 14.sp)
                }
            }
            if (next != null && !compact) {
                Spacer(Modifier.width(16.dp))
                Text(
                    "Next  ${hhmm(next.start)}  ${next.title}",
                    modifier = Modifier.widthIn(max = 300.dp),
                    color = secondary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

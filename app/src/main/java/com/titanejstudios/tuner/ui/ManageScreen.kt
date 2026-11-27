package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.clearDead
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Groups: hide / lock / reorder. Favourites: reorder / remove. Hidden channels: unhide.
 * Reordering: pick "Move" in the menu, then ▲ ▼ to move and OK to drop.
 */
@Composable
fun ManageScreen(vm: TunerViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var moving by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var mergeFrom by remember { mutableStateOf<String?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    val restorer = rememberFocusRestorer()
    RestoreFocusWhenClosed(menuFor != null || renaming != null || mergeFrom != null, restorer)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { tabFocus.requestFocus() }
    }

    val favourites = remember(vm.favourites, vm.allChannels) {
        val byId = vm.allChannels.associateBy { it.id }
        vm.favourites.mapNotNull { byId[it] }
    }
    val hidden = remember(vm.hiddenChannels, vm.allChannels) { vm.allChannels.filter { it.id in vm.hiddenChannels } }
    val offline = remember(vm.deadChannels, vm.allChannels) { vm.allChannels.filter { it.id in vm.deadChannels } }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(screenPadding(64.dp, 32.dp))
    ) {
        Text("Manage groups and channels", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Groups", { tab = 0; moving = null }, Modifier.focusRequester(tabFocus), selected = tab == 0)
            PillButton("Favourites order", { tab = 1; moving = null }, selected = tab == 1)
            PillButton("Hidden channels (${hidden.size})", { tab = 2; moving = null }, selected = tab == 2)
            PillButton("Offline (${offline.size})", { tab = 3; moving = null }, selected = tab == 3)
            if (tab == 0) PillButton("＋ New category", { newCategory = true })
            if (tab == 0) PillButton("Reset group order", { vm.resetGroupOrder() })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                moving != null -> "Moving – press ▲ ▼ to move, OK to drop it here"
                tab == 2 -> "OK on a channel to show it again"
                tab == 3 -> "Channels that didn't respond in the last check · OK to clear one"
                else -> "OK on an item for options"
            },
            color = if (moving != null) TunerColors.Gold else TunerColors.TextSecondary,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(12.dp))

        when (tab) {
            0 -> ReorderList(
                keys = vm.allGroups,
                label = { it },
                badges = { g ->
                    listOfNotNull(
                        if (g in vm.hiddenGroups) "Hidden" else null,
                        if (g in vm.lockedGroups && vm.pin != null) "🔒 Locked" else null,
                        if (vm.isCustomGroup(g)) "Yours · ${vm.customGroups[g]?.size ?: 0} channels" else null,
                        if (g in vm.groupRenames.values) "Renamed" else null,
                    ).joinToString("  ·  ")
                },
                moving = moving,
                listState = listState,
                scope = scope,
                restorer = restorer,
                onMove = { key, delta -> vm.moveGroup(key, delta) },
                onDrop = { moving = null },
                onClick = { key ->
                    restorer.key = key
                    menuFor = key
                },
            )
            1 -> if (favourites.isEmpty()) CenterMessage("No favourites yet") else ReorderList(
                keys = favourites.map { it.id },
                label = { id -> favourites.firstOrNull { it.id == id }?.name ?: id },
                badges = { id -> favourites.firstOrNull { it.id == id }?.group ?: "" },
                moving = moving,
                listState = listState,
                scope = scope,
                restorer = restorer,
                onMove = { key, delta -> vm.moveFavourite(key, delta) },
                onDrop = { moving = null },
                onClick = { key ->
                    restorer.key = key
                    menuFor = key
                },
            )
            3 -> if (offline.isEmpty()) CenterMessage("No offline channels – run a check in Settings › Channel health") else LazyColumn(
                Modifier.widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(offline, key = { _, c -> c.id }) { _, ch ->
                    SettingRow(ch.name, subtitle = ch.group, detail = "OK to clear", onClick = { vm.clearDead(ch.id) })
                }
            }
            else -> if (hidden.isEmpty()) CenterMessage("No hidden channels") else LazyColumn(
                Modifier.widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(hidden, key = { _, c -> c.id }) { _, ch ->
                    SettingRow(ch.name, subtitle = ch.group, detail = "OK to unhide", onClick = { vm.toggleHiddenChannel(ch.id) })
                }
            }
        }
    }

    renaming?.let { key ->
        TextInputDialog(
            title = "Rename $key",
            initial = key,
            hint = "Tip: give it the same name as another category to merge them",
            onDismiss = { renaming = null },
        ) { vm.renameGroup(key, it) }
    }
    mergeFrom?.let { key ->
        MenuDialog(
            title = "Merge $key into…",
            items = vm.allGroups.filter { it != key }.map { target -> MenuItem(target) { vm.renameGroup(key, target) } },
            onDismiss = { mergeFrom = null },
        )
    }
    if (newCategory) {
        TextInputDialog(title = "New category", hint = "Then add channels from the TV Guide (hold OK on a channel)", onDismiss = { newCategory = false }) {
            vm.createCustomGroup(it, null)
        }
    }

    menuFor?.let { key ->
        if (tab == 0) {
            MenuDialog(
                title = key,
                items = buildList {
                    add(MenuItem("Move") { moving = key })
                    add(MenuItem("Move to top") { repeat(vm.allGroups.indexOf(key)) { vm.moveGroup(key, -1) } })
                    add(MenuItem("✏️ Rename…") { renaming = key })
                    if (!vm.isCustomGroup(key)) {
                        add(MenuItem("Merge into another category…") { mergeFrom = key })
                        if (key in vm.groupRenames.values) add(MenuItem("Restore original name(s)") { vm.resetGroupName(key) })
                    } else {
                        add(MenuItem("Delete this category") { vm.deleteCustomGroup(key) })
                    }
                    add(MenuItem(if (key in vm.hiddenGroups) "Show this group" else "Hide this group") { vm.toggleHiddenGroup(key) })
                    add(MenuItem(if (key in vm.lockedGroups) "Unlock (needs PIN)" else "Lock with PIN") { vm.toggleLockedGroup(key) })
                },
                onDismiss = { menuFor = null },
            )
        } else {
            val name = favourites.firstOrNull { it.id == key }?.name ?: key
            MenuDialog(
                title = name,
                items = listOf(
                    MenuItem("Move") { moving = key },
                    MenuItem("Move to top") { repeat(vm.favourites.indexOf(key)) { vm.moveFavourite(key, -1) } },
                    MenuItem("Remove from favourites") { vm.toggleFavourite(key) },
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

@Composable
private fun ReorderList(
    keys: List<String>,
    label: (String) -> String,
    badges: (String) -> String,
    moving: String?,
    listState: LazyListState,
    scope: CoroutineScope,
    restorer: FocusRestorer,
    onMove: (key: String, delta: Int) -> Unit,
    onDrop: () -> Unit,
    onClick: (String) -> Unit,
) {
    val movingFocus = remember { FocusRequester() }
    LaunchedEffect(moving) {
        if (moving != null) {
            delay(40)
            runCatching { movingFocus.requestFocus() }
        }
    }
    LazyColumn(
        Modifier.widthIn(max = 900.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(keys, key = { _, k -> k }) { index, k ->
            val isMoving = k == moving
            val badge = badges(k)
            FocusSurface(
                onClick = { if (isMoving) onDrop() else if (moving == null) onClick(k) },
                selected = isMoving,
                baseColor = TunerColors.Panel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .then(if (isMoving) Modifier.focusRequester(movingFocus) else Modifier)
                    .then(restorer.mod(k))
                    .onPreviewKeyEvent { e ->
                        if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (e.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            Key.DirectionLeft, Key.DirectionRight -> return@onPreviewKeyEvent true
                            else -> return@onPreviewKeyEvent false
                        }
                        onMove(k, delta)
                        val target = (index + delta).coerceIn(0, keys.lastIndex)
                        scope.launch {
                            runCatching { listState.animateScrollToItem((target - 3).coerceAtLeast(0)) }
                            runCatching { movingFocus.requestFocus() }
                        }
                        true
                    },
            ) { focused ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isMoving) "↕" else "${index + 1}",
                        color = if (focused) Color.White else TunerColors.TextSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(label(k), color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (badge.isNotEmpty()) {
                        Text(badge, color = if (focused) Color.White else TunerColors.Gold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

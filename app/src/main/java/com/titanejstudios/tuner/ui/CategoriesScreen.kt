package com.titanejstudios.tuner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel

/** Categories are edited on the Manage screen's Groups tab. */
@Composable
fun CategoriesScreen(vm: TunerViewModel) {
    ManageScreen(vm)
}

/** "Add to my category…": your categories (tick = already in it) plus "New category…". */
@Composable
fun CategoryPicker(vm: TunerViewModel, channel: Channel) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = "New category",
            hint = "${channel.name} will be added to it",
            onDismiss = { vm.categoryPickFor = null },
        ) { vm.createCustomGroup(it, channel) }
        return
    }
    val items = buildList {
        vm.customGroups.forEach { (name, ids) ->
            add(MenuItem((if (channel.id in ids) "✓ " else "") + name, "${ids.size} channels") { vm.toggleInCustomGroup(name, channel) })
        }
        // The menu closes itself before running an item, so put the picker back for the name box.
        add(MenuItem("＋ New category…") {
            vm.categoryPickFor = channel
            creating = true
        })
    }
    MenuDialog(
        title = "Add ${channel.name} to…",
        items = items,
        onDismiss = { vm.categoryPickFor = null },
        subtitle = "Your own categories appear in the TV Guide tabs",
    )
}

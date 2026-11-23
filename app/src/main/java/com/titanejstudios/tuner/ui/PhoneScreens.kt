package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel

/** True on a phone held upright (or any narrow screen in phone mode): use the phone layouts. */
@Composable
fun compactLayout(vm: TunerViewModel): Boolean = vm.isPhone && LocalConfiguration.current.screenWidthDp < 700

/** A narrow screen (a phone held upright) – for screens that don't have the view model to hand. */
@Composable
fun narrowScreen(): Boolean = LocalConfiguration.current.screenWidthDp < 600

/** Screen edges: the TV's roomy margins, or slim ones on a narrow phone screen. */
@Composable
fun screenPadding(horizontal: Dp, vertical: Dp): PaddingValues =
    if (narrowScreen()) PaddingValues(horizontal = 14.dp, vertical = 12.dp) else PaddingValues(horizontal = horizontal, vertical = vertical)

/** A row of category chips that scrolls sideways – the phone version of the TV's category column. */
@Composable
fun CategoryChips(categories: List<String>, selected: String?, label: (String) -> String = { it }, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        items(categories.size, key = { categories[it] }) { i ->
            val c = categories[i]
            val on = c == selected
            Text(
                label(c),
                color = if (on) Color.White else TunerColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (on) TunerColors.Accent else Color(0x24FFFFFF))
                    .tvClickable({ onPick(c) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Phone TV Guide: categories along the top, then channels with what's on now and next. */
@Composable
fun PhoneGuideScreen(vm: TunerViewModel) {
    val group = vm.selectedGroup
    val list = remember(group, vm.channels, vm.favourites, vm.recents, vm.unlocked, vm.lockedGroups) { vm.channelsFor(group) }
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    val now = vm.now
    Column(Modifier.fillMaxSize().skyBackground()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TV Guide", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(
                "Grid", color = TunerColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x24FFFFFF)).tvClickable({ vm.openGuide() }).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Catch-up", color = TunerColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x24FFFFFF)).tvClickable({ vm.navigate(com.titanejstudios.tuner.Screen.Catchup) }).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        CategoryChips(vm.groupNames, group) { vm.selectedGroup = it }
        Spacer(Modifier.height(8.dp))
        if (vm.isGroupLocked(group)) {
            CenterMessage("🔒 This category is locked – Settings › Parental controls")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(list.size, key = { list[it].id }) { i ->
                val c = list[i]
                val (cur, next) = vm.nowNext(c.id, now)
                Row(
                    Modifier.fillMaxWidth().height(72.dp)
                        .background(if (c.id == vm.playing?.id) TunerColors.AccentSoft else Color.Transparent)
                        .tvClickable({ vm.play(c, list) }, { menuFor = c })
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(vm.displayNumber(c, i).toString(), color = TunerColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                    Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
                        ChannelLogo(c.logo, c.name, Modifier.fillMaxSize().padding(3.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur?.title ?: c.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (cur != null) "${c.name} · until ${hhmm(cur.stop)}" else c.name,
                            color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (cur != null) ProgressBar(progressOf(cur, now), Modifier.padding(top = 4.dp).fillMaxWidth())
                        next?.let { Text("Next ${hhmm(it.start)} ${it.title}", color = TunerColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (c.id in vm.favouriteSet) Text(" ★", color = TunerColors.Gold, fontSize = 16.sp)
                }
            }
        }
    }
    menuFor?.let { ch -> ChannelMenu(vm, ch, group, list, onDismiss = { menuFor = null }) }
}

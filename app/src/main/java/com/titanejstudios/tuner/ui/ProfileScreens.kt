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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.Profile
import com.titanejstudios.tuner.data.Store
import kotlinx.coroutines.delay

/** "Who's watching?" */
@Composable
fun ProfilePickerScreen(vm: TunerViewModel) {
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { current.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().skyBackground().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Who's watching?", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(36.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(vm.profiles.size, key = { vm.profiles[it].id }) { i ->
                val p = vm.profiles[i]
                FocusSurface(
                    onClick = { vm.switchProfile(p) },
                    baseColor = Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .size(width = 200.dp, height = 230.dp)
                        .then(if (p.id == vm.activeProfile.id) Modifier.focusRequester(current) else Modifier),
                ) { focused ->
                    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        ProfileAvatar(p, 130.dp, i)
                        Spacer(Modifier.height(14.dp))
                        Text(p.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (p.kids) {
                            Text(
                                listOfNotNull(
                                    "Kids",
                                    if (p.dailyLimitMinutes > 0) "${p.dailyLimitMinutes} min a day" else null,
                                    if (p.bedtimeMinutes >= 0) "bed ${KidsRules.label(p.bedtimeMinutes)}" else null,
                                ).joinToString(" · "),
                                color = if (focused) Color.White else TunerColors.TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("Add or edit profiles in Settings", color = TunerColors.TextSecondary, fontSize = 15.sp)
    }
}

private val limitChoices = listOf(0, 30, 60, 90, 120, 180)

/** Create or edit a profile, including kids settings. */
@Composable
fun ProfileEditScreen(vm: TunerViewModel) {
    val existing = vm.editingProfile
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kids by remember { mutableStateOf(existing?.kids ?: false) }
    var groups by remember { mutableStateOf(existing?.allowedGroups ?: emptySet()) }
    var limit by remember { mutableIntStateOf(existing?.dailyLimitMinutes ?: 0) }
    var bedtime by remember { mutableIntStateOf(existing?.bedtimeMinutes ?: -1) }
    var colour by remember { mutableIntStateOf(existing?.colour ?: -1) }
    var icon by remember { mutableStateOf(existing?.icon ?: "") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().skyBackground().padding(horizontal = 64.dp, vertical = 32.dp)
    ) {
        Text(if (existing == null) "New profile" else "Edit profile", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Grown-up", { kids = false }, Modifier.focusRequester(first), selected = !kids)
            PillButton("🧸 Kids", { kids = true }, selected = kids)
        }
        Spacer(Modifier.height(12.dp))
        // Profile picture: a colour and an icon (or the first letter of the name).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileAvatar(Profile("x", name.ifEmpty { "?" }, kids, colour = colour, icon = icon), 52.dp)
            Spacer(Modifier.width(6.dp))
            AvatarColours.indices.forEach { c ->
                FocusSurface(onClick = { colour = c }, shape = CircleShape, selected = colour == c, modifier = Modifier.size(38.dp)) { _ ->
                    Box(Modifier.fillMaxSize().padding(5.dp).clip(CircleShape).background(AvatarColours[c]))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AvatarIcons.size) { k ->
                val ic = AvatarIcons[k]
                PillButton(ic.ifEmpty { "Aa" }, { icon = ic }, selected = icon == ic, compact = true)
            }
        }
        if (kids) {
            Spacer(Modifier.height(14.dp))
            Text("Daily TV time", color = TunerColors.TextSecondary, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                limitChoices.forEach { m ->
                    PillButton(if (m == 0) "No limit" else "$m min", { limit = m }, selected = limit == m, compact = true)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Bedtime – no TV from this time until 6am (they get a 5-minute warning)", color = TunerColors.TextSecondary, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KidsRules.bedtimeChoices.forEach { b ->
                    PillButton(KidsRules.label(b), { bedtime = b }, selected = bedtime == b, compact = true)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Groups they can watch (${groups.size} chosen) – everything else is hidden",
                color = TunerColors.TextSecondary,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                Modifier.weight(1f).widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(vm.allGroups, key = { it }) { g ->
                    val on = g in groups
                    SettingRow(
                        g,
                        detail = if (on) "✓ Allowed" else null,
                        onClick = { groups = if (on) groups - g else groups + g },
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Save", {
                vm.saveProfile(
                    Profile(
                        id = existing?.id ?: vm.newProfileId(),
                        name = name.trim().ifEmpty { if (kids) "Kids" else "Profile" },
                        kids = kids,
                        allowedGroups = if (kids) groups else emptySet(),
                        dailyLimitMinutes = if (kids) limit else 0,
                        bedtimeMinutes = if (kids) bedtime else -1,
                        colour = colour,
                        icon = icon,
                    )
                )
            })
            PillButton("Cancel", { vm.back() })
            if (existing != null && existing.id != Store.MAIN_PROFILE) {
                PillButton("Delete profile", {
                    vm.deleteProfile(existing)
                    vm.back()
                })
            }
        }
        if (kids && groups.isEmpty()) {
            Text("Tip: pick at least one group, otherwise the kids profile shows no channels.", color = TunerColors.Gold, fontSize = 14.sp, textAlign = TextAlign.Start)
        }
    }
}

package com.titanejstudios.tuner.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.titanejstudios.tuner.PinPrompt
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.Reminder
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunerColors {
    // Sky-style palette by default: deep navy, bright blue focus, white text. Changeable in Settings › Look.
    var Background by mutableStateOf(Color(0xFF0A1030)); private set
    var BackgroundBottom by mutableStateOf(Color(0xFF04071A)); private set
    var Panel by mutableStateOf(Color(0xFF141C45)); private set
    var PanelLight by mutableStateOf(Color(0xFF1E2A5E)); private set
    var Accent by mutableStateOf(Color(0xFF1F7BFF)); private set
    var AccentSoft by mutableStateOf(Color(0xFF173A8A)); private set

    val accents = listOf("blue" to "Blue", "red" to "Red", "purple" to "Purple", "green" to "Green", "orange" to "Orange", "teal" to "Teal")
    val backgrounds = listOf("navy" to "Navy", "black" to "Black", "charcoal" to "Charcoal", "plum" to "Plum")

    fun apply(accent: String, background: String) {
        val (a, soft) = when (accent) {
            "red" -> Color(0xFFE5173F) to Color(0xFF7A1430)
            "purple" -> Color(0xFF8A4FFF) to Color(0xFF3E2A85)
            "green" -> Color(0xFF17B26A) to Color(0xFF0E5C3A)
            "orange" -> Color(0xFFFF7A1A) to Color(0xFF8A3F0E)
            "teal" -> Color(0xFF14B8C4) to Color(0xFF0D5A62)
            else -> Color(0xFF1F7BFF) to Color(0xFF173A8A)
        }
        Accent = a
        AccentSoft = soft
        val bg = when (background) {
            "black" -> listOf(Color(0xFF0E0E12), Color(0xFF000000), Color(0xFF1A1A20), Color(0xFF27272F))
            "charcoal" -> listOf(Color(0xFF22262C), Color(0xFF121417), Color(0xFF2C3139), Color(0xFF3A404A))
            "plum" -> listOf(Color(0xFF1C1035), Color(0xFF0A0618), Color(0xFF2B1B4E), Color(0xFF3A2766))
            else -> listOf(Color(0xFF0A1030), Color(0xFF04071A), Color(0xFF141C45), Color(0xFF1E2A5E))
        }
        Background = bg[0]
        BackgroundBottom = bg[1]
        Panel = bg[2]
        PanelLight = bg[3]
    }
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFAAB6DA)
    val Gold = Color(0xFFFFC94A)
    val Error = Color(0xFFFF6B6B)
    val NowLine = Color(0xFFFF3B5C)
    val Catchup = Color(0xFF3DDC97)
}

/** The navy gradient used behind every screen. */
fun Modifier.skyBackground(): Modifier = this.background(
    Brush.verticalGradient(listOf(TunerColors.Background, TunerColors.BackgroundBottom))
)

/**
 * How lists scroll when focus moves with the remote: a short, linear animation (instead of the
 * default slow ease) and a margin so the next items are always visible. Holding ▲▼ stays smooth.
 */
@kotlin.OptIn(ExperimentalFoundationApi::class)
private val TvScrolling = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 110, easing = LinearEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = (containerSize * 0.18f).coerceAtMost((containerSize - size) / 2f).coerceAtLeast(0f)
        val leading = offset
        val trailing = offset + size
        return when {
            leading >= margin && trailing <= containerSize - margin -> 0f
            leading < margin -> leading - margin
            else -> trailing - (containerSize - margin)
        }
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun TunerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TunerColors.Accent,
            background = TunerColors.Background,
            surface = TunerColors.Panel,
            onBackground = TunerColors.TextPrimary,
            onSurface = TunerColors.TextPrimary,
        ),
    ) {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides TvScrolling,
            // Material's default text style has a fixed 24sp line height, so big text that wraps onto a
            // second line overlapped the first. Let the line height follow the font size instead.
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
            content = content,
        )
    }
}

// ---------- Time helpers ----------

private val hourMinute = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

fun hhmm(t: Long): String = hourMinute.format(Date(t))
fun dayLabel(t: Long): String = dayFormat.format(Date(t))

fun progressOf(p: Programme, now: Long): Float =
    ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1L)).coerceIn(0f, 1f)

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ---------- Remote-friendly click handling ----------

/**
 * Focusable + OK/Enter on the remote (fires on key-up so the release doesn't leak into the next
 * screen) + hold OK for a long-click.
 */
fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    var downSeen by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    this
        .onPreviewKeyEvent { e ->
            val isSelect = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (!isSelect) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    val repeat = e.nativeKeyEvent.repeatCount
                    if (repeat == 0) {
                        downSeen = true
                        longFired = false
                    } else if (repeat == 1 && downSeen && !longFired) {
                        val lc = longClick
                        if (lc != null) {
                            longFired = true
                            lc()
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (downSeen && !longFired) click()
                    downSeen = false
                    longFired = false
                    true
                }
                else -> false
            }
        }
        .focusable()
}

/** A focusable tile that turns accent-blue when focused. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    selected: Boolean = false,
    baseColor: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(10.dp),
    zoom: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> TunerColors.Accent
        selected -> TunerColors.AccentSoft
        else -> baseColor
    }
    val scale = if (zoom) animateFloatAsState(if (focused) 1.07f else 1f, tween(120), label = "zoom").value else 1f
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                val f = it.isFocused
                if (f && !focused) onFocused?.invoke()
                focused = f
            }
            .tvClickable(onClick, onLongClick)
            .clip(shape)
            .background(bg),
        contentAlignment = Alignment.CenterStart,
    ) {
        content(focused)
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    compact: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(if (compact) 42.dp else 48.dp),
        selected = selected,
        baseColor = TunerColors.Panel,
        shape = RoundedCornerShape(24.dp),
    ) { focused ->
        Text(
            text,
            modifier = Modifier.padding(horizontal = if (compact) 15.dp else 22.dp),
            color = if (focused || selected) Color.White else TunerColors.TextSecondary,
            fontSize = if (compact) 15.sp else 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ---------- Genres ----------

/** Colour for a programme's XMLTV category (null = no colour). */
fun genreColor(category: String?): Color? {
    val c = category?.lowercase() ?: return null
    return when {
        "sport" in c || "football" in c || "soccer" in c -> Color(0xFF22C55E)
        "film" in c || "movie" in c || "cinema" in c -> Color(0xFFA855F7)
        "news" in c || "weather" in c -> Color(0xFF60A5FA)
        "child" in c || "kid" in c || "animation" in c || "cartoon" in c -> Color(0xFFFACC15)
        "doc" in c || "factual" in c || "nature" in c || "history" in c || "science" in c -> Color(0xFF14B8A6)
        "music" in c -> Color(0xFFEC4899)
        "comedy" in c || "entertainment" in c || "game show" in c || "reality" in c -> Color(0xFFFB923C)
        "drama" in c || "series" in c || "soap" in c || "crime" in c -> Color(0xFFF87171)
        else -> null
    }
}

val genreLegend = listOf(
    "Sport" to Color(0xFF22C55E),
    "Film" to Color(0xFFA855F7),
    "News" to Color(0xFF60A5FA),
    "Kids" to Color(0xFFFACC15),
    "Factual" to Color(0xFF14B8A6),
    "Entertainment" to Color(0xFFFB923C),
    "Drama" to Color(0xFFF87171),
)

@Composable
fun GenreChip(category: String?) {
    val color = genreColor(category) ?: return
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(category ?: "", color = color, fontSize = 13.sp, maxLines = 1)
    }
}

/** Shown when a kids profile has used up today's TV time. */
@Composable
fun TimesUpDialog(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    ModalLayer(onDismiss = { vm.dismissTimesUp() }) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TunerColors.Panel)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bedtime = vm.isBedtimeNow()
            Text(if (bedtime) "🌙" else "⏰", fontSize = 48.sp)
            Text(
                if (bedtime) "It's bedtime, ${vm.activeProfile.name}! Night night." else "That's all the TV for today, ${vm.activeProfile.name}!",
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (bedtime) "TV is back at 6am. A grown-up can allow a bit longer with the PIN." else "A grown-up can add more time with the PIN.",
                color = TunerColors.TextSecondary, fontSize = 17.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("OK", { vm.dismissTimesUp() }, Modifier.focusRequester(first))
                PillButton("+30 minutes (PIN)", { vm.grantExtraTime(30) })
                PillButton("Switch profile", {
                    vm.dismissTimesUp()
                    vm.navigate(Screen.ProfilePicker)
                })
            }
        }
    }
}

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        // A broken logo link falls back to the channel's initials instead of an empty box.
        var failed by remember(url) { mutableStateOf(false) }
        if (url.isNullOrBlank() || failed) {
            Text(
                name.filter { it.isLetterOrDigit() }.take(3).uppercase(),
                color = TunerColors.TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            val context = LocalContext.current
            // Decode logos at the small size they're shown at – provider logos can be huge PNGs.
            val request = remember(url) {
                ImageRequest.Builder(context).data(url).size(160, 100).crossfade(false).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = TunerColors.Accent) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color),
        )
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TunerColors.TextSecondary, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

// ---------- Focus restore after a dialog closes ----------

/** Remembers which list item opened a dialog so focus can go back to it afterwards. */
@Stable
class FocusRestorer {
    val requester = FocusRequester()
    var key by mutableStateOf<String?>(null)
    fun mod(itemKey: String): Modifier = if (itemKey == key) Modifier.focusRequester(requester) else Modifier
    fun restore() {
        runCatching { requester.requestFocus() }
    }
}

@Composable
fun rememberFocusRestorer(): FocusRestorer = remember { FocusRestorer() }

/** Puts focus back on the remembered item whenever [dialogOpen] goes from true to false. */
@Composable
fun RestoreFocusWhenClosed(dialogOpen: Boolean, restorer: FocusRestorer) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        if (wasOpen && !dialogOpen) {
            delay(40)
            restorer.restore()
        }
        wasOpen = dialogOpen
    }
}

// ---------- Dialogs ----------

/** Keeps D-pad focus inside this container (used by dialogs and overlays). */
@kotlin.OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties { exit = { FocusRequester.Cancel } }
    .focusGroup()

/** Dims the screen, keeps D-pad focus inside, and closes on BACK. */
@Composable
fun ModalLayer(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            // BACK closes the dialog straight from the remote's key press as well, so it still works
            // when something else (like the player underneath) would otherwise get the back press.
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) onDismiss()
                true
            }
            .background(Color(0xB3000000))
            .trapFocus(),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

class MenuItem(val label: String, val detail: String? = null, val onClick: () -> Unit)

@Composable
fun MenuDialog(
    title: String,
    items: List<MenuItem>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
    ModalLayer(onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TunerColors.Panel)
                .padding(24.dp)
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 15.sp, color = TunerColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { i, item ->
                    FocusSurface(
                        onClick = {
                            onDismiss()
                            item.onClick()
                        },
                        baseColor = TunerColors.PanelLight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) { focused ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.label, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.detail?.let {
                                Text(it, color = if (focused) Color.White else TunerColors.TextSecondary, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 4-digit PIN pad. Works with the on-screen keys or the remote's number buttons. */
@Composable
fun PinDialog(prompt: PinPrompt, onCancel: () -> Unit) {
    var digits by remember(prompt) { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(prompt) {
        delay(40)
        runCatching { firstKey.requestFocus() }
    }
    fun press(d: String) {
        if (digits.length >= 4) return
        digits += d
        if (digits.length == 4) {
            val entered = digits
            digits = ""
            prompt.onEntered(entered)
        }
    }
    ModalLayer(onCancel) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TunerColors.Panel)
                .onPreviewKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                        if (e.type == KeyEventType.KeyDown) press((code - android.view.KeyEvent.KEYCODE_0).toString())
                        true
                    } else false
                }
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(prompt.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (i < digits.length) TunerColors.Accent else Color(0x33FFFFFF))
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("✕", "0", "⌫"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    row.forEach { label ->
                        FocusSurface(
                            onClick = {
                                when (label) {
                                    "✕" -> onCancel()
                                    "⌫" -> digits = digits.dropLast(1)
                                    else -> press(label)
                                }
                            },
                            baseColor = TunerColors.PanelLight,
                            modifier = Modifier
                                .size(width = 96.dp, height = 60.dp)
                                .then(if (label == "1") Modifier.focusRequester(firstKey) else Modifier),
                        ) {
                            Text(
                                label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                fontSize = 24.sp,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Starting now" pop-up for a reminder; switches automatically after a countdown. */
@Composable
fun ReminderPopup(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    var seconds by remember(reminder) { mutableIntStateOf(15) }
    val watchFocus = remember { FocusRequester() }
    LaunchedEffect(reminder) {
        delay(40)
        runCatching { watchFocus.requestFocus() }
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
        onWatch()
    }
    ModalLayer(onDismiss) {
        Column(
            Modifier
                .width(600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TunerColors.Panel)
                .padding(28.dp)
        ) {
            Text("⏰  Starting now", color = TunerColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(reminder.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${hhmm(reminder.start)} on ${reminder.channelName}", color = TunerColors.TextSecondary, fontSize = 18.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Watch now ($seconds)", onWatch, Modifier.focusRequester(watchFocus))
                PillButton("Dismiss", onDismiss)
            }
        }
    }
}

@Composable
fun NoticeToast(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(bottom = 40.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xEE1F2630))
            .padding(horizontal = 26.dp, vertical = 14.dp)
    ) {
        Text(text, color = Color.White, fontSize = 17.sp)
    }
}

// ---------- Video ----------

/** A PlayerView showing the shared player. Several can exist; the newest one gets the picture. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    exo: Player,
    modifier: Modifier = Modifier,
    showSubtitles: Boolean = true,
    /** AspectRatioFrameLayout.RESIZE_MODE_FIT (normal), _ZOOM (fill, crop edges) or _FILL (stretch). */
    resizeMode: Int = 0,
    /** Keep the last picture on screen while the next channel loads, instead of going black. */
    keepLastFrame: Boolean = false,
) {
    val subSize = SubtitleLook.scale
    val subBox = SubtitleLook.background
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = exo
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(keepLastFrame)
                if (!showSubtitles) subtitleView?.visibility = View.GONE
            }
        },
        // The shared player can change (fast channel switching swaps in the standby player).
        update = { view ->
            if (view.player !== exo) view.player = exo
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
            view.setKeepContentOnPlayerReset(keepLastFrame)
            if (showSubtitles) SubtitleLook.applyTo(view, subSize, subBox)
        },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

/** Subtitle size and background chosen in Settings (read by every video surface). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object SubtitleLook {
    var scale by mutableStateOf(1f)
    var background by mutableStateOf(false)

    fun applyTo(view: PlayerView, scale: Float, box: Boolean) {
        val sv = view.subtitleView ?: return
        sv.setApplyEmbeddedFontSizes(false)
        sv.setApplyEmbeddedStyles(!box)
        sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
        sv.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                if (box) 0xCC000000.toInt() else android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                if (box) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                null,
            )
        )
    }
}

/** Small live preview (Channels and Guide screens). OK goes back to full screen. */
@Composable
fun MiniPreview(vm: TunerViewModel, modifier: Modifier = Modifier) {
    if (vm.mode == null) return
    FocusSurface(
        onClick = { vm.navigate(Screen.Player) },
        baseColor = Color.Black,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) { focused ->
        VideoSurface(
            vm.playback.player,
            Modifier.fillMaxSize().padding(if (focused) 4.dp else 0.dp),
            showSubtitles = false,
        )
    }
}


// ---------- Sky-style top menu ----------

/** The menu bar along the top of the main screens. */
@Composable
fun TopNav(vm: TunerViewModel, current: Screen, modifier: Modifier = Modifier) {
    val items = buildList {
        add("Home" to Screen.Home)
        add("TV Guide" to Screen.Channels)
        add("What's On" to Screen.WhatsOn)
        add("Sports" to Screen.Sports)
        if (!vm.activeProfile.kids) add("Recordings" to Screen.Recordings)
        if (vm.hasVod) add("Films & TV" to Screen.Movies)
        else if (vm.plexLinked && (!vm.activeProfile.kids || vm.kidsHavePlex)) add("Plex" to Screen.PlexLibrary)
        if (!vm.activeProfile.kids) add("Apps" to Screen.Apps)
        add("Search" to Screen.Search)
        add("Settings" to Screen.Settings)
    }
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        // The menu scrolls sideways if it doesn't fit, so the profile and clock always stay in view.
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        items.forEach { (label, target) ->
            val selected = target == current
            FocusSurface(
                onClick = {
                    when {
                        target == Screen.Home -> vm.goHome()
                        selected -> Unit
                        target == Screen.WhatsOn -> vm.openWhatsOn(vm.whatsOnGenre)
                        target == Screen.PlexLibrary -> vm.openPlexLibrary()
                        else -> vm.navigate(target)
                    }
                },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.height(44.dp).padding(end = 2.dp),
            ) { focused ->
                Column(Modifier.padding(horizontal = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        color = if (focused || selected) Color.White else TunerColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (selected && !focused) {
                        Box(Modifier.padding(top = 3.dp).width(22.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(TunerColors.Accent))
                    }
                }
            }
        }
        }
        Spacer(Modifier.width(12.dp))
        if (vm.profiles.size > 1 || vm.activeProfile.kids) {
            FocusSurface(
                onClick = { vm.navigate(Screen.ProfilePicker) },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.height(44.dp),
            ) { focused ->
                Text(
                    (if (vm.activeProfile.kids) "🧸 " else "👤 ") + vm.activeProfile.name,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = if (focused) Color.White else TunerColors.TextSecondary,
                    fontSize = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        Text(hhmm(vm.now), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

/** A 16:9 tile for a channel: logo, what's on and a progress bar. */
@Composable
fun ChannelTile(
    vm: TunerViewModel,
    channel: Channel,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val (current, _) = vm.nowNext(channel.id, vm.now)
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        baseColor = TunerColors.Panel,
        shape = RoundedCornerShape(12.dp),
        zoom = true,
        modifier = modifier.size(width = 232.dp, height = 132.dp),
    ) { focused ->
        val picture = current?.image
        if (picture != null) {
            // Programme picture from the guide, with the channel logo small in the corner.
            AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, if (focused) Color(0x66000000) else Color(0xCC050816)))
            )
        )
        if (picture != null) {
            ChannelLogo(
                channel.logo,
                channel.name,
                Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x99050816), RoundedCornerShape(6.dp)).padding(3.dp).size(width = 56.dp, height = 32.dp),
            )
        } else {
            ChannelLogo(channel.logo, channel.name, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(width = 110.dp, height = 60.dp))
        }
        if (badge != null) {
            Text(
                badge,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                color = TunerColors.NowLine,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                current?.title ?: channel.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                ProgressBar(progressOf(current, vm.now), Modifier.padding(top = 4.dp).fillMaxWidth(), color = if (focused) Color.White else TunerColors.Accent)
            } else {
                Text(channel.name, color = TunerColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * Text that slowly scrolls itself up when it's too long for its space, so you can read all of it
 * without pressing anything: waits a few seconds, scrolls to the end, pauses, then starts again.
 */
@Composable
fun AutoScrollText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFD5DCF2),
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(text) {
        scroll.scrollTo(0)
        delay(3_000)
        while (true) {
            val max = scroll.maxValue
            if (max <= 0) {
                delay(1_000)
                continue
            }
            // About a line every two seconds – comfortable reading speed from the sofa.
            val duration = (max * 55).coerceIn(2_000, 60_000)
            scroll.animateScrollTo(max, androidx.compose.animation.core.tween(durationMillis = duration, easing = LinearEasing))
            delay(4_000)
            scroll.animateScrollTo(0, androidx.compose.animation.core.tween(durationMillis = 700))
            delay(3_000)
        }
    }
    Box(modifier.clipToBounds().verticalScroll(scroll, enabled = false)) {
        Text(text, color = color, fontSize = fontSize)
    }
}

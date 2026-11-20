package com.mts.tikmod.ui

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.focus.Focus
import com.mts.tikmod.focus.FocusLog
import com.mts.tikmod.focus.FocusTopics
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

// ================================================================== "Stop the addiction"

/** Durations on the "for how long" line, in minutes (0 = no limit). */
private val DURATIONS = listOf(15, 30, 45, 60, 90, 120, 180, 0)

/**
 * Re-reads the effective state every few seconds (time windows open and close while the screen is up) and
 * right away whenever [version] changes (something was switched on this screen).
 */
@Composable
private fun rememberFocusState(version: Int): State<Focus.State> {
    val ctx = LocalContext.current
    return produceState(Focus.state(ctx), version) {
        while (true) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Focus.state(ctx) }; delay(3000) }
    }
}

/** Entry card on the Feed screen: the gauge, what's happening, start / stop, and the way into the page. */
@Composable
fun FocusEntryCard(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val state by rememberFocusState(version)
    GlassCard(glow = state.active, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpen() }) {
            Gauge(Focus.boredomNow(ctx, state).takeIf { state.active } ?: Focus.target(ctx), state.active, 84.dp, compact = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L.t(R.string.focus_title), fontWeight = FontWeight.Black, fontSize = 19.sp)
                Text(headline(state), fontSize = 13.sp, color = if (state.active) Neon.Lime else Subtle, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Subtle)
        }
        Spacer(Modifier.height(12.dp))
        StartStop(state) { version++ }
    }
}

/** The full page (shown in the Feed tab): the live hero on top, then swipeable tabs. */
@Composable
fun FocusScreen(onBack: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val state by rememberFocusState(version)
    val changed: () -> Unit = { version++ }
    val pager = rememberPagerState { 5 }

    Appear(0) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L.t(R.string.action_back)) }
            Column {
                Text(L.t(R.string.focus_title), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(L.t(R.string.focus_subtitle), fontSize = 13.sp, color = Subtle)
            }
        }
    }
    Appear(1) { Hero(state, version, changed) }
    Appear(2) { FocusTabs(pager) }
    Appear(3) {
        HorizontalPager(
            state = pager, verticalAlignment = Alignment.Top, pageSpacing = 16.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    0 -> MixTab(state, version, changed)
                    1 -> TopicsTab(version, changed)
                    2 -> ActivityTab()
                    3 -> LearnTab()
                    else -> AutoTab(changed)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ hero

@Composable
private fun Hero(st: Focus.State, version: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val now = Focus.boredomNow(ctx, st)
    val target = remember(version) { Focus.target(ctx) }
    GlassCard(glow = st.active, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gauge(if (st.active) now else target, st.active, 112.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusDot(st.active)
                Text(headline(st), fontWeight = FontWeight.Black, fontSize = 17.sp,
                    color = if (st.active) Neon.Lime else MaterialTheme.colorScheme.onSurface, lineHeight = 21.sp)
                val sub = when {
                    !st.active -> L.t(R.string.f3_off_short, (target * 100).roundToInt())
                    now + 0.01f < target -> L.t(R.string.f3_ramping, (now * 100).roundToInt(), (target * 100).roundToInt())
                    else -> L.t(R.string.f3_now_label, (now * 100).roundToInt())
                }
                Text(sub, fontSize = 12.5.sp, color = Subtle, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        StartStop(st, changed)
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Neon.Lime.copy(alpha = a) else Subtle.copy(alpha = 0.5f)))
        Spacer(Modifier.width(6.dp))
        Text(L.t(if (active) R.string.f3_live else R.string.f2_off_title).uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
            color = if (active) Neon.Lime else Subtle, letterSpacing = 1.2.sp)
    }
}

/** A 270° ring: how much of the feed won't hook you. */
@Composable
private fun Gauge(value: Float, active: Boolean, size: Dp, compact: Boolean = false) {
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "gauge")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.15f, 0.45f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "glow")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (if (compact) 8.dp else 11.dp).toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val tl = Offset(inset, inset)
            if (active) drawArc(Neon.Lime.copy(alpha = glow * 0.5f), 135f, 270f * v, false, tl, arcSize, style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
            drawArc(track, 135f, 270f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val brush = if (active) Brush.sweepGradient(listOf(Neon.Cyan, Neon.Lime, Neon.Violet, Neon.Cyan)) else Brush.linearGradient(listOf(dim, dim))
            if (v > 0.005f) drawArc(brush, 135f, 270f * v, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(v * 100).roundToInt()}%", fontSize = if (compact) 20.sp else 28.sp, fontWeight = FontWeight.Black,
                color = if (active) Neon.Lime else MaterialTheme.colorScheme.onSurface)
            Text(L.t(R.string.f3_gauge_label), fontSize = if (compact) 8.5.sp else 10.sp, color = Subtle, textAlign = TextAlign.Center,
                lineHeight = 11.sp, modifier = Modifier.widthIn(max = size * 0.62f))
        }
    }
}

private fun timeOf(ctx: android.content.Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ms))

private fun whyText(st: Focus.State): String = st.why.filter { it != Focus.Why.MANUAL }.joinToString(" · ") { w ->
    L.t(when (w) {
        Focus.Why.WINDOW -> R.string.focus_why_window
        Focus.Why.BEDTIME -> R.string.focus_why_bedtime
        Focus.Why.WINDDOWN -> R.string.focus_why_winddown
        else -> R.string.focus_why_session
    })
}

/** One line: off, on until 21:30, on until turned off, or on by itself (and why). */
@Composable
private fun headline(st: Focus.State): String {
    val ctx = LocalContext.current
    return when {
        !st.active -> L.t(R.string.f2_off_title)
        Focus.Why.MANUAL in st.why && st.why.size == 1 && st.until > 0 ->
            L.t(R.string.f2_on_until, timeOf(ctx, st.until), ((st.until - System.currentTimeMillis()) / 60_000L + 1).toInt().coerceAtLeast(1))
        Focus.Why.MANUAL in st.why && st.why.size == 1 -> L.t(R.string.f2_on_forever)
        else -> L.t(R.string.f2_on_auto, whyText(st))
    }
}

private fun durationLabel(min: Int): String = when (min) {
    0 -> L.t(R.string.f2_ever)
    60 -> L.t(R.string.f2_60)
    90 -> L.t(R.string.f3_h15)
    120 -> L.t(R.string.f2_120)
    180 -> L.t(R.string.f3_h3)
    else -> L.t(R.string.focus_minutes, min)
}

/** Off: drag "for how long", then one big Start. On: "+30 min" and "turn off". */
@Composable
private fun StartStop(st: Focus.State, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val manual = Focus.Why.MANUAL in st.why
    var idx by rememberSaveable { mutableIntStateOf(3) }
    AnimatedVisibility(!manual, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(L.t(R.string.f3_duration), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Subtle, modifier = Modifier.weight(1f))
                Text(durationLabel(DURATIONS[idx]), fontSize = 17.sp, fontWeight = FontWeight.Black, color = Neon.Lime)
            }
            NeonSlider(idx.toFloat(), 0f..(DURATIONS.size - 1).toFloat(), DURATIONS.size - 2, Neon.Lime) {
                val n = it.roundToInt()
                if (n != idx) { idx = n; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            }
            BigButton(L.t(R.string.f3_start, durationLabel(DURATIONS[idx])), Icons.Rounded.PlayArrow, Neon.Lime, filled = true) {
                Focus.start(ctx, DURATIONS[idx]); onChanged()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                Toast.makeText(ctx, L.t(R.string.f2_toast_on), Toast.LENGTH_SHORT).show()
            }
        }
    }
    AnimatedVisibility(manual, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.until > 0) BigButton(L.t(R.string.f2_extend), Icons.Rounded.MoreTime, Neon.Lime, filled = false, modifier = Modifier.weight(1f)) {
                Focus.extend(ctx, 30); onChanged()
            }
            BigButton(L.t(R.string.f2_stop), Icons.Rounded.PowerSettingsNew, Neon.Pink, filled = false, modifier = Modifier.weight(1f)) {
                Focus.stop(ctx); onChanged()
            }
        }
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, accent: Color, filled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    LaunchedEffect(pressed) { if (pressed) { delay(140); pressed = false } }
    Row(
        modifier.scale(sc).clip(RoundedCornerShape(18.dp))
            .background(if (filled) Brush.horizontalGradient(listOf(accent.copy(alpha = 0.95f), Neon.Cyan.copy(alpha = 0.85f)))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .border(1.dp, accent.copy(alpha = if (filled) 0f else 0.6f), RoundedCornerShape(18.dp))
            .clickable { pressed = true; onClick() }
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (filled) Neon.OnAccent else accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Black, fontSize = 15.sp, color = if (filled) Neon.OnAccent else accent, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeonSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, accent: Color, onChange: (Float) -> Unit) {
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.18f),
            activeTickColor = Neon.OnAccent.copy(alpha = 0.5f), inactiveTickColor = accent.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// ------------------------------------------------------------------ tabs

@Composable
private fun FocusTabs(pager: PagerState) {
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        Icons.Rounded.Tune to R.string.f3_tab_mix, Icons.Rounded.Interests to R.string.f3_tab_topics,
        Icons.Rounded.ManageSearch to R.string.f3_tab_log, Icons.Rounded.Psychology to R.string.f3_tab_learn,
        Icons.Rounded.Schedule to R.string.f3_tab_auto
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, (icon, label) ->
            val sel = pager.currentPage == i
            val bg by animateColorAsState(if (sel) Neon.Lime.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), tween(250), label = "tab")
            val fg by animateColorAsState(if (sel) Neon.Lime else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), tween(250), label = "tabfg")
            Row(
                Modifier.clip(CircleShape).background(bg)
                    .border(1.dp, if (sel) Neon.Lime.copy(alpha = 0.7f) else Color.Transparent, CircleShape)
                    .clickable { scope.launch { pager.animateScrollToPage(i) } }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.t(label), color = fg, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 13.5.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ tab: mix

@Composable
private fun MixTab(st: Focus.State, version: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var pct by remember { mutableIntStateOf((Focus.target(ctx) * 100).roundToInt()) }
    var ramp by remember { mutableIntStateOf(Focus.rampMinutes(ctx)) }
    var gradual by remember { mutableStateOf(ramp > 0) }
    LaunchedEffect(pct) { delay(250); Prefs.setString(ctx, Prefs.KEY_FOCUS_BOREDOM, pct.toString()); changed() }
    LaunchedEffect(ramp, gradual) { delay(250); Prefs.setString(ctx, Prefs.KEY_FOCUS_RAMP, if (gradual) ramp.coerceAtLeast(5).toString() else "0"); changed() }

    GlassCard(padding = 18.dp) {
        Text(L.t(R.string.f3_mix_title), fontWeight = FontWeight.Black, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(L.t(R.string.f3_mix_desc), fontSize = 13.sp, color = Subtle, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val shown by animateIntAsState(pct, tween(250), label = "pct")
            Text("$shown%", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Neon.Lime)
            Spacer(Modifier.width(10.dp))
            Text(mixWord(pct), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        }
        NeonSlider(pct.toFloat(), 0f..100f, 19, Neon.Lime) {
            val n = (it / 5f).roundToInt() * 5
            if (n != pct) { pct = n; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(R.string.f3_mix_l0, R.string.f3_mix_l50, R.string.f3_mix_l100).forEachIndexed { i, s ->
                Text(L.t(s), fontSize = 11.sp, color = Subtle, modifier = Modifier.weight(1f),
                    textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End })
            }
        }
        Spacer(Modifier.height(16.dp))
        TenPreview(pct)
        Spacer(Modifier.height(10.dp))
        val plain = (pct / 10f).roundToInt()
        Text(L.t(R.string.f3_mix_of10, plain, 10 - plain), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    }

    GlassCard(padding = 18.dp) {
        Text(L.t(R.string.f3_how_title), fontWeight = FontWeight.Black, fontSize = 17.sp)
        Spacer(Modifier.height(10.dp))
        Segmented(listOf(Opt("once", L.t(R.string.f3_at_once)), Opt("grad", L.t(R.string.f3_gradual))),
            if (gradual) "grad" else "once", Neon.Lime) { gradual = it == "grad"; if (gradual && ramp == 0) ramp = 20 }
        Spacer(Modifier.height(10.dp))
        Text(L.t(if (gradual) R.string.f3_gradual_desc else R.string.f3_at_once_desc), fontSize = 13.sp, color = Subtle, lineHeight = 18.sp)
        AnimatedVisibility(gradual) {
            Column {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(L.t(R.string.f3_ramp_over), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Subtle, modifier = Modifier.weight(1f))
                    Text(L.t(R.string.focus_minutes, ramp.coerceAtLeast(5)), fontSize = 17.sp, fontWeight = FontWeight.Black, color = Neon.Cyan)
                }
                NeonSlider(ramp.coerceAtLeast(5).toFloat(), 5f..60f, 10, Neon.Cyan) {
                    val n = (it / 5f).roundToInt() * 5
                    if (n != ramp) { ramp = n; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val progress = if (st.active) Focus.rampProgress(ctx, st) else -1f
        RampChart(pct / 100f, if (gradual) ramp.coerceAtLeast(5) else 0, progress)
    }

    Explainer(R.string.f3_what_hook_title, R.string.f3_what_hook_desc)

    Section(Icons.Rounded.VisibilityOff, L.t(R.string.f2_hide_title), L.t(R.string.f3_hide_desc), accent = Neon.Pink) {
        ChipSet(Prefs.KEY_FOCUS_HIDE, listOf(
            ChipOpt("live", "📡  " + L.t(R.string.focus_hide_live)),
            ChipOpt("shop", "🛍️  " + L.t(R.string.focus_hide_shop)),
            ChipOpt("photo", "🖼️  " + L.t(R.string.focus_hide_photo)),
            ChipOpt("short", "⚡  " + L.t(R.string.focus_hide_short)),
            ChipOpt("viral", "🔥  " + L.t(R.string.focus_hide_viral))
        ), Neon.Pink, changed)
    }
}

private fun mixWord(pct: Int): String = L.t(when {
    pct == 0 -> R.string.f3_mix_l0
    pct < 40 -> R.string.f3_mix_w_bit
    pct < 65 -> R.string.f3_mix_l50
    pct < 85 -> R.string.f3_mix_w_most
    pct < 100 -> R.string.f3_mix_w_almost
    else -> R.string.f3_mix_l100
})

/** Ten little phones: which of the next 10 videos would be the hooking kind. Re-colours as the line is dragged. */
@Composable
private fun TenPreview(pct: Int) {
    val plain = (pct / 10f).roundToInt()
    val hooks = 10 - plain
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(10) { i ->
            // Spread the hooks evenly through the ten.
            val hook = hooks > 0 && ((i + 1) * hooks) / 10 > (i * hooks) / 10
            val bg by animateColorAsState(if (hook) Neon.Pink.copy(alpha = 0.32f) else Neon.Cyan.copy(alpha = 0.12f), tween(260, delayMillis = i * 25), label = "ph")
            val sc by animateFloatAsState(if (hook) 1f else 0.9f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "phs")
            Box(
                Modifier.weight(1f).aspectRatio(0.56f).scale(sc).clip(RoundedCornerShape(7.dp)).background(bg)
                    .border(1.dp, if (hook) Neon.Pink.copy(alpha = 0.7f) else Neon.Cyan.copy(alpha = 0.25f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) { Text(if (hook) "🔥" else "😐", fontSize = 13.sp) }
        }
    }
}

/** How the share grows over time: a step (at once) or a ramp; a pulsing dot marks "now" while it's on. */
@Composable
private fun RampChart(target: Float, rampMin: Int, progress: Float) {
    val t by animateFloatAsState(target, tween(400), label = "t")
    val r by animateFloatAsState(if (rampMin <= 0) 0f else (rampMin / 75f).coerceIn(0.08f, 0.8f), tween(400), label = "r")
    val pulse = rememberInfiniteTransition(label = "now")
    val pr by pulse.animateFloat(4f, 9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pr")
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width; val h = size.height
            for (k in 0..4) drawLine(grid, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1.dp.toPx())
            val y = h - h * t
            val x1 = w * r
            val path = Path().apply { moveTo(0f, h); lineTo(x1, y); lineTo(w, y) }
            val fill = Path().apply { addPath(path); lineTo(w, h); close() }
            drawPath(fill, Brush.verticalGradient(listOf(Neon.Lime.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(path, Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Lime)), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            if (progress >= 0f) {
                val px = if (r <= 0f) 0f else x1 * progress
                val py = if (r <= 0f) y else h - (h - y) * progress
                drawCircle(Neon.Lime.copy(alpha = 0.3f), pr.dp.toPx(), Offset(px.coerceAtLeast(4f), py))
                drawCircle(Neon.Lime, 4.dp.toPx(), Offset(px.coerceAtLeast(4f), py))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(L.t(R.string.f3_chart_start), fontSize = 11.sp, color = Subtle, modifier = Modifier.weight(1f))
            if (rampMin > 0) Text(L.t(R.string.focus_minutes, rampMin), fontSize = 11.sp, color = Subtle)
        }
    }
}

@Composable
private fun Explainer(title: Int, body: Int) {
    var open by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "rot")
    GlassCard(padding = 16.dp, modifier = Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HelpOutline, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(L.t(title), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, tint = Subtle, modifier = Modifier.graphicsLayer { rotationZ = rot })
        }
        AnimatedVisibility(open) {
            Text(L.t(body), fontSize = 13.sp, color = Subtle, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}


// ------------------------------------------------------------------ tab: topics

@Composable
private fun TopicsTab(version: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    Section(Icons.Rounded.Interests, L.t(R.string.f2_topics_title), L.t(R.string.f3_topics_desc), accent = Neon.Lime) {
        ChipSet(Prefs.KEY_FOCUS_TOPICS, FocusTopics.ALL.map { ChipOpt(it.id, it.emoji + "  " + L.t(it.label)) }, Neon.Lime, changed)
        FocusWords(L.t(R.string.focus_allow_words), L.t(R.string.focus_allow_hint), Prefs.KEY_FOCUS_ALLOW_WORDS, Icons.Rounded.AddCircleOutline, Neon.Lime, changed)
        ToggleRow(L.t(R.string.f3_strict), L.t(R.string.f3_strict_desc), Prefs.KEY_FOCUS_STRICT, Icons.Rounded.CenterFocusStrong, Neon.Lime) { changed() }
        val warn = remember(version) { Prefs.bool(ctx, Prefs.KEY_FOCUS_STRICT) && Focus.allowList(ctx).isEmpty() }
        AnimatedVisibility(warn) { Warning(L.t(R.string.f3_strict_need)) }
    }
    Section(Icons.Rounded.Block, L.t(R.string.focus_block_words), L.t(R.string.f3_block_desc), accent = Neon.Pink) {
        FocusWords(L.t(R.string.focus_block_words), L.t(R.string.focus_block_hint), Prefs.KEY_FOCUS_BLOCK_WORDS, Icons.Rounded.RemoveCircleOutline, Neon.Pink, changed)
    }
    Explainer(R.string.f3_match_title, R.string.f3_match_desc)
}

@Composable
private fun Warning(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Neon.Amber.copy(alpha = 0.14f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Neon.Amber, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

// ------------------------------------------------------------------ tab: activity

@Composable
private fun ActivityTab() {
    val ctx = LocalContext.current
    var cleared by remember { mutableIntStateOf(0) }
    val entries by produceState(FocusLog.read(ctx), cleared) {
        while (true) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FocusLog.read(ctx) }; delay(2000) }
    }
    GlassCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L.t(R.string.f2_log_title), fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(L.t(R.string.f2_log_desc), fontSize = 12.5.sp, color = Subtle, lineHeight = 17.sp)
            }
            if (entries.isNotEmpty()) TextButton(onClick = { FocusLog.clear(ctx); cleared++ }) { Text(L.t(R.string.f2_clear)) }
        }
        if (entries.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("🫧", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(L.t(R.string.f2_log_empty), fontSize = 13.sp, color = Subtle, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
            return@GlassCard
        }
        Spacer(Modifier.height(14.dp))
        val hidden = entries.count { it.hidden }
        val shown = entries.size - hidden
        SplitBar(shown, hidden)
        Spacer(Modifier.height(6.dp))
        Text(L.t(R.string.f3_log_bar, shown, hidden), fontSize = 12.5.sp, color = Subtle)
    }
    entries.take(40).forEach { LogRow(it) }
}

@Composable
private fun SplitBar(shown: Int, hidden: Int) {
    val total = (shown + hidden).coerceAtLeast(1)
    val f by animateFloatAsState(shown.toFloat() / total, tween(600), label = "split")
    Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) {
        if (f > 0f) Box(Modifier.weight(f.coerceAtLeast(0.001f)).fillMaxHeight().background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Lime))))
        if (f < 1f) Box(Modifier.weight((1f - f).coerceAtLeast(0.001f)).fillMaxHeight().background(Neon.Pink.copy(alpha = 0.7f)))
    }
}

@Composable
private fun LogRow(e: FocusLog.Entry) {
    val accent = if (e.hidden) Neon.Pink else Neon.Lime
    GlassCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(if (e.hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(L.t(if (e.hidden) R.string.f2_hidden else R.string.f2_kept) + " · " + reasonText(e),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                val line = listOfNotNull(e.author?.let { "@$it" }, e.desc.ifBlank { null }).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                e.tags?.let { Text(L.t(R.string.f2_tiktok_tags, it), fontSize = 11.5.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (e.score >= 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(L.t(R.string.f3_score, e.score), fontSize = 11.sp, color = Subtle, modifier = Modifier.width(92.dp))
                        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
                            Box(Modifier.fillMaxWidth(e.score / 100f).fillMaxHeight().clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Pink))))
                        }
                    }
                }
            }
        }
    }
}

private fun reasonText(e: FocusLog.Entry): String = when (e.code) {
    "topic" -> L.t(R.string.f2_r_topic, e.topic?.let { id -> FocusTopics.byId(id)?.let { it.emoji + " " + L.t(it.label) } } ?: e.word.orEmpty())
    "plain" -> L.t(R.string.f3_r_plain)
    "allowed" -> L.t(R.string.f3_r_allowed)
    "hook" -> L.t(R.string.f3_r_hook)
    "offtopic" -> L.t(R.string.f2_r_offtopic)
    "filler" -> L.t(R.string.f2_r_filler)
    "block" -> L.t(R.string.f2_r_block, e.word.orEmpty())
    "live" -> L.t(R.string.focus_hide_live)
    "shop" -> L.t(R.string.focus_hide_shop)
    "photo" -> L.t(R.string.focus_hide_photo)
    "short" -> L.t(R.string.focus_hide_short)
    "viral" -> L.t(R.string.focus_hide_viral)
    "ad", "paid" -> L.t(R.string.f2_r_ad)
    else -> L.t(R.string.f2_r_other)
}

// ------------------------------------------------------------------ tab: learned

@Composable
private fun LearnTab() {
    val ctx = LocalContext.current
    var reset by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf(false) }
    val profile by produceState(FocusLog.profile(ctx), reset) {
        while (true) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FocusLog.profile(ctx) }; delay(4000) }
    }
    GlassCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Psychology, Neon.Violet, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L.t(R.string.f3_learn_title), fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(L.t(R.string.f3_learn_desc), fontSize = 12.5.sp, color = Subtle, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        ToggleRow(L.t(R.string.f3_learn_on), null, Prefs.KEY_FOCUS_LEARN, Icons.Rounded.School, Neon.Violet)
    }
    val hooks = profile.filter { it.second > 0.3f }.take(14)
    val skips = profile.filter { it.second < -0.3f }.sortedBy { it.second }.take(10)
    if (hooks.isEmpty() && skips.isEmpty()) {
        GlassCard(padding = 18.dp) {
            Text("🌱", fontSize = 36.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(L.t(R.string.f3_learn_empty), fontSize = 13.sp, color = Subtle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    } else {
        if (hooks.isNotEmpty()) LearnedGroup(L.t(R.string.f3_learn_hooks), "🔥", hooks, Neon.Pink)
        if (skips.isNotEmpty()) LearnedGroup(L.t(R.string.f3_learn_skips), "💨", skips, Neon.Cyan)
        TextButton(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text(L.t(R.string.f3_learn_reset))
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(L.t(R.string.f3_learn_reset)) },
        text = { Text(L.t(R.string.f3_learn_reset_q)) },
        confirmButton = { TextButton(onClick = { FocusLog.resetProfile(ctx); reset++; ask = false }) { Text(L.t(R.string.f3_forget), color = Neon.Pink) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text(L.t(R.string.f3_cancel)) } }
    )
}

@Composable
private fun LearnedGroup(title: String, emoji: String, items: List<Pair<String, Float>>, accent: Color) {
    val max = items.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(0.1f)
    GlassCard(padding = 16.dp) {
        Text("$emoji  $title", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = accent)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items.forEach { (k, v) ->
                val label = when {
                    k.startsWith("?") -> "🔎 " + k.drop(1)
                    else -> k
                }
                val f by animateFloatAsState(kotlin.math.abs(v) / max, tween(700), label = "lw")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(0.45f).height(6.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(accent.copy(alpha = 0.8f)))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ tab: automatic

@Composable
private fun AutoTab(changed: () -> Unit) {
    Section(Icons.Rounded.EventRepeat, L.t(R.string.focus_schedule), L.t(R.string.focus_schedule_desc), accent = Neon.Cyan) {
        ToggleRow(L.t(R.string.focus_schedule_on), null, Prefs.KEY_FOCUS_SCHEDULE, Icons.Rounded.EventRepeat, Neon.Cyan) { changed() }
        TimeRange(Prefs.KEY_FOCUS_FROM, Prefs.KEY_FOCUS_TO, Neon.Cyan, changed)
        DayPicker(Prefs.KEY_FOCUS_DAYS, Neon.Cyan, changed)
    }
    Section(Icons.Rounded.Bedtime, L.t(R.string.focus_bedtime), L.t(R.string.f3_bedtime_desc, 90), accent = Neon.Violet) {
        ToggleRow(L.t(R.string.focus_bedtime_on), null, Prefs.KEY_BEDTIME_ON, Icons.Rounded.NightsStay, Neon.Violet) { changed() }
        TimeRange(Prefs.KEY_BEDTIME_FROM, Prefs.KEY_BEDTIME_TO, Neon.Violet, changed)
        DayPicker(Prefs.KEY_BEDTIME_DAYS, Neon.Violet, changed)
        ToggleRow(L.t(R.string.focus_bedtime_screen), L.t(R.string.focus_bedtime_screen_desc), Prefs.KEY_BEDTIME_SCREEN, Icons.Rounded.DarkMode, Neon.Violet)
        SegmentedPref(L.t(R.string.focus_winddown), Prefs.KEY_WINDDOWN, listOf(
            Opt("0", L.t(R.string.state_off)), Opt("15", L.t(R.string.focus_minutes, 15)),
            Opt("30", L.t(R.string.focus_minutes, 30)), Opt("60", L.t(R.string.focus_minutes, 60))
        ), description = L.t(R.string.focus_winddown_desc), accent = Neon.Violet) { changed() }
    }
    Section(Icons.Rounded.HourglassBottom, L.t(R.string.focus_session), L.t(R.string.f3_session_desc), accent = Neon.Amber) {
        SegmentedPref(L.t(R.string.focus_session_after), Prefs.KEY_SESSION_LIMIT, listOf(
            Opt("0", L.t(R.string.state_off)), Opt("15", L.t(R.string.focus_minutes, 15)), Opt("30", L.t(R.string.focus_minutes, 30)),
            Opt("45", L.t(R.string.focus_minutes, 45)), Opt("60", L.t(R.string.focus_minutes, 60))
        ), accent = Neon.Amber) { changed() }
        ToggleRow(L.t(R.string.focus_session_remind), L.t(R.string.focus_session_remind_desc), Prefs.KEY_SESSION_REMIND, Icons.Rounded.NotificationsActive, Neon.Amber)
    }
    Hint(L.t(R.string.f2_hint))
    Hint(L.t(R.string.f2_tile_tip))
}

// ------------------------------------------------------------------ pieces

private data class ChipOpt(val value: String, val label: String)

/** Multi-select chips stored as a comma-separated pref. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSet(key: String, options: List<ChipOpt>, accent: Color, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selected by remember(key) { mutableStateOf(Prefs.string(ctx, key).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()) }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { o ->
            val on = o.value in selected
            val bg by animateColorAsState(if (on) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), tween(220), label = "chip")
            val sc by animateFloatAsState(if (on) 1.04f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "chips")
            Text(
                o.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                color = if (on) accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.scale(sc).clip(CircleShape).background(bg)
                    .border(1.dp, if (on) accent.copy(alpha = 0.8f) else Color.Transparent, CircleShape)
                    .clickable {
                        selected = if (on) selected - o.value else selected + o.value
                        Prefs.setString(ctx, key, options.map { it.value }.filter { it in selected }.joinToString(","))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChanged()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/** From / to times, each opening the system time picker. */
@Composable
private fun TimeRange(fromKey: String, toKey: String, accent: Color, onChanged: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TimeButton(L.t(R.string.focus_from), fromKey, accent, Modifier.weight(1f), onChanged)
        TimeButton(L.t(R.string.focus_to), toKey, accent, Modifier.weight(1f), onChanged)
    }
}

@Composable
private fun TimeButton(label: String, key: String, accent: Color, modifier: Modifier, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var value by remember(key) { mutableStateOf(Prefs.string(ctx, key)) }
    val minutes = Focus.minutesOf(value) ?: 0
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable {
                TimePickerDialog(ctx, { _, h, m ->
                    value = "%02d:%02d".format(h, m); Prefs.setString(ctx, key, value); onChanged()
                }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(ctx)).show()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 11.5.sp, color = Subtle)
        Text(formatTime(ctx, minutes), fontSize = 22.sp, fontWeight = FontWeight.Black, color = accent)
    }
}

private fun formatTime(ctx: android.content.Context, minutes: Int): String {
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60) }
    return android.text.format.DateFormat.getTimeFormat(ctx).format(cal.time)
}

/** Seven round day toggles in the week order of the app's language. */
@Composable
private fun DayPicker(key: String, accent: Color, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var days by remember(key) { mutableStateOf(Focus.days(Prefs.string(ctx, key))) }
    val locale = L.locale()
    val first = Calendar.getInstance(locale).firstDayOfWeek
    val names = DateFormatSymbols.getInstance(locale).shortWeekdays
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(L.t(R.string.focus_days), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0 until 7).map { (first - 1 + it) % 7 + 1 }.forEach { d ->
                val on = d in days
                val bg by animateColorAsState(if (on) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), tween(200), label = "day")
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(bg).clickable {
                        val next = if (on) days - d else days + d
                        if (next.isEmpty()) { Toast.makeText(ctx, L.t(R.string.focus_days_none), Toast.LENGTH_SHORT).show(); return@clickable }
                        days = next
                        Prefs.setString(ctx, key, next.sorted().joinToString(","))
                        onChanged()
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text(names.getOrNull(d).orEmpty().take(2), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (on) Neon.OnAccent else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** A comma-separated word list, saved by itself a moment after typing stops. */
@Composable
private fun FocusWords(title: String, hint: String, key: String, icon: ImageVector, accent: Color, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var value by remember(key) { mutableStateOf(Prefs.string(ctx, key)) }
    LaunchedEffect(value) {
        delay(700)
        if (value.trim() != Prefs.string(ctx, key)) { Prefs.setString(ctx, key, value.trim()); onChanged() }
    }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            placeholder = { Text(hint, fontSize = 13.sp, color = Subtle) },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
        )
    }
}

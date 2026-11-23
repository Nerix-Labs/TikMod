package com.mts.tikmod.ui

import com.mts.tikmod.R
import com.mts.tikmod.utils.L

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.Prefs
import com.mts.tikmod.ui.theme.Neon
import kotlinx.coroutines.delay

/*
 * TikMod design system v5 — "Studio".
 * Deep ink background with one slow light source, quiet opaque surfaces with a fine edge, tinted icon tiles,
 * small-caps overlines, a single brand gradient used sparingly. Every control is bound directly to a Prefs key.
 */

/** Secondary text: readable on every scheme (dynamic schemes have a very muted onSurfaceVariant). */
val Subtle: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)

/**
 * Tap feedback for anything you can press: it sinks a little under the finger, ripples, and gives a light tick.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, haptic: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    val h = androidx.compose.ui.platform.LocalHapticFeedback.current
    return this.graphicsLayer { scaleX = s; scaleY = s }
        .clickable(src, androidx.compose.foundation.LocalIndication.current, enabled = enabled) {
            if (haptic) h.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** The fine edge every surface has. */
val Edge: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (Neon.isDark) 0.07f else 0.09f)

// ------------------------------------------------------------------ background

/** One soft light from the top, drifting very slowly, and a faint dot grid: depth without noise. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "light")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    val dot = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.035f else 0.05f)
    val glowMode = Neon.glow
    val grid by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "grid")
    Canvas(modifier.fillMaxSize()) {
        drawRect(bg)
        val w = size.width; val h = size.height
        fun glow(c: Color, x: Float, y: Float, r: Float, alpha: Float) = drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), center = Offset(x, y), radius = r),
            radius = r, center = Offset(x, y)
        )
        if (glowMode) {
            // Neon: three colored lights and a synthwave floor grid rolling towards you at the bottom.
            glow(Neon.Cyan, w * (0.05f + 0.25f * a), -h * 0.03f, w * 1.0f, 0.18f)
            glow(Neon.Violet, w * (0.35f + 0.3f * a), h * 0.95f, w * 0.9f, 0.14f)
            val horizon = h * 0.74f
            val vanish = Offset(w / 2, horizon)
            val line = Neon.Violet
            // A faint floor grid at the very bottom, behind the tab bar.
            for (i in -8..8) {
                val x = w / 2 + i * w * 0.22f
                drawLine(Brush.verticalGradient(listOf(Color.Transparent, line.copy(alpha = 0.08f)), startY = horizon, endY = h),
                    vanish, Offset(x, h), strokeWidth = 1f)
            }
            for (k in 0 until 7) {
                val p = ((k + grid) / 7f)
                val y = horizon + (h - horizon) * p * p
                drawLine(line.copy(alpha = 0.02f + 0.07f * p), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            return@Canvas
        }
        glow(Neon.Cyan, w * (0.10f + 0.20f * a), -h * 0.02f, w * 0.95f, if (dark) 0.16f else 0.10f)
        glow(Neon.Pink, w * (0.95f - 0.15f * a), h * 0.10f, w * 0.65f, if (dark) 0.08f else 0.06f)
        // Dot grid, fading out towards the bottom.
        val step = 22.dp.toPx()
        var y = step / 2
        while (y < h * 0.55f) {
            val fade = 1f - y / (h * 0.55f)
            var x = step / 2
            while (x < w) { drawCircle(dot.copy(alpha = dot.alpha * fade), radius = 1.1f, center = Offset(x, y)); x += step }
            y += step
        }
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ------------------------------------------------------------------ entrance

/** Staggered fade + rise when a screen opens. */
@Composable
fun Appear(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L * index); p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Column(
        Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36f; scaleX = 0.97f + 0.03f * p.value; scaleY = scaleX },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
    }
}

// ------------------------------------------------------------------ cards

/**
 * The one surface everything sits on: opaque, a fine edge, a faint light along the top. `glow` washes it with the
 * brand gradient instead (hero cards, something that's on).
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, glow: Boolean = false, padding: Dp = 18.dp, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (accent != null) 28.dp else 24.dp)
    val neon = Neon.glow
    val fill = when {
        // Sections look like the tiles: washed with their color from one corner.
        accent != null && !glow -> Brush.linearGradient(listOf(accent.copy(alpha = if (Neon.isDark) 0.16f else 0.10f), cs.surfaceContainerHigh.copy(alpha = 0.96f)),
            start = Offset.Zero, end = Offset(700f, 700f))
        glow && neon -> Brush.linearGradient(listOf(Neon.Cyan.copy(alpha = 0.20f), Neon.Violet.copy(alpha = 0.12f), Neon.Pink.copy(alpha = 0.10f), cs.surfaceContainer.copy(alpha = 0.95f)))
        glow -> Brush.linearGradient(listOf(Neon.Cyan.copy(alpha = 0.16f), Neon.Violet.copy(alpha = 0.08f), cs.surfaceContainer.copy(alpha = 0.97f)))
        neon -> Brush.verticalGradient(listOf(cs.surfaceContainerHigh.copy(alpha = 0.90f), cs.surfaceContainer.copy(alpha = 0.86f)))
        else -> Brush.verticalGradient(listOf(cs.surfaceContainerHigh.copy(alpha = 0.97f), cs.surfaceContainer.copy(alpha = 0.97f)))
    }
    // Neon hero cards: the gradient edge slowly travels around the card.
    // Read only while drawing, so the card's content isn't recomposed every frame.
    val spin: State<Float>? = if (glow && neon) {
        val t = rememberInfiniteTransition(label = "edge")
        t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "a")
    } else null
    val brandColors = Neon.brand + Neon.brand.first()
    val edge = when {
        glow && neon -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)) // drawn below, rotating
        accent != null -> Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.05f)))
        glow -> Brush.linearGradient(listOf(Neon.Cyan.copy(alpha = 0.45f), Neon.Violet.copy(alpha = 0.18f), Edge))
        neon -> Brush.verticalGradient(listOf(cs.onSurface.copy(alpha = 0.11f), cs.onSurface.copy(alpha = 0.05f)))
        else -> Brush.verticalGradient(listOf(cs.onSurface.copy(alpha = 0.10f), Edge))
    }
    Column(
        modifier.fillMaxWidth()
            // Neon: the card glows in its colors.
            // Only hero cards glow; the rest stay calm so the screen doesn't shout.
            .then(if (neon && glow) Modifier.shadow(22.dp, shape, clip = false, ambientColor = Neon.Cyan, spotColor = Neon.Pink) else Modifier)
            .clip(shape)
            // An opaque base, so the glow shadow never shows through the tinted fill.
            .then(if (neon) Modifier.background(cs.surfaceContainer) else Modifier)
            .background(fill)
            .then(if (spin == null) Modifier.border(1.dp, edge, shape) else Modifier.drawWithContent {
                drawContent()
                val a = spin.value
                val c = Offset(size.width / 2, size.height / 2)
                val r = maxOf(size.width, size.height) / 2
                val d = Offset(kotlin.math.cos(a) * r, kotlin.math.sin(a) * r)
                val sw = 1.5.dp.toPx()
                drawRoundRect(Brush.linearGradient(brandColors, start = c - d, end = c + d),
                    topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()), style = Stroke(sw))
            })
            .padding(padding),
        content = content
    )
}

/** Card with an icon tile + title; optional collapse. */
@Composable
fun Section(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    accent: Color = Neon.Cyan,
    badge: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "arrow")
    GlassCard(accent = accent) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon, accent, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.5.sp, letterSpacing = (-0.2).sp)
                    if (badge != null) { Spacer(Modifier.width(8.dp)); Tag(badge) }
                }
                if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = Subtle, lineHeight = 16.sp)
            }
            if (collapsible) Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(arrow), tint = Subtle)
        }
        AnimatedVisibility(expanded, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
fun IconTile(icon: ImageVector, accent: Color, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        Modifier.size(size)
            .clip(shape)
            .background(accent.copy(alpha = if (Neon.isDark) 0.15f else 0.12f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = accent, modifier = Modifier.size(size * 0.50f)) }
}

@Composable
fun Tag(text: String, color: Color = when (text) { L.t(R.string.tag_new) -> Neon.Lime; L.t(R.string.tag_experimental) -> Neon.Amber; else -> Neon.Cyan }) {
    Text(
        text.uppercase(), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.8.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Small-caps overline between groups of cards. */
@Composable
fun SectionLabel(text: String) {
    Row(Modifier.padding(start = 6.dp, top = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = Subtle)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Edge, Color.Transparent))))
    }
}

// ------------------------------------------------------------------ controls

/** Switch row bound to a pref. */
@Composable
fun ToggleRow(
    title: String,
    description: String? = null,
    key: String,
    icon: ImageVector? = null,
    accent: Color = Neon.Cyan,
    badge: String? = null,
    onChange: (Boolean) -> Unit = {}
) {
    val ctx = LocalContext.current
    var checked by remember(key) { mutableStateOf(Prefs.bool(ctx, key)) }
    ToggleRowRaw(title, description, checked, icon, accent, badge) {
        checked = it; Prefs.setBool(ctx, key, it); onChange(it)
    }
}

@Composable
fun ToggleRowRaw(
    title: String, description: String?, checked: Boolean, icon: ImageVector? = null, accent: Color = Neon.Cyan,
    badge: String? = null, onChange: (Boolean) -> Unit
) {
    val bg by animateColorAsState(if (checked) accent.copy(alpha = 0.07f) else Color.Transparent, tween(260), label = "bg")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg)
            .pressable { onChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            val tint by animateColorAsState(if (checked) accent else Subtle, label = "tint")
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (badge != null) { Spacer(Modifier.width(6.dp)); Tag(badge) }
            }
            if (description != null) Text(description, fontSize = 13.sp, color = Subtle, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Neon.OnAccent)
        )
    }
}

data class Opt(val value: String, val label: String, val icon: ImageVector? = null)

/** Segmented control with a sliding glowing indicator, bound to a pref. */
@Composable
fun SegmentedPref(title: String, key: String, options: List<Opt>, description: String? = null, accent: Color = Neon.Cyan, onChange: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    var value by remember(key) { mutableStateOf(Prefs.string(ctx, key)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (description != null) Text(description, fontSize = 13.sp, color = Subtle, lineHeight = 16.sp)
        Segmented(options, value, accent) { value = it; Prefs.setString(ctx, key, it); onChange(it) }
    }
}

@Composable
fun Segmented(options: List<Opt>, selected: String, accent: Color = Neon.Cyan, onSelect: (String) -> Unit) {
    val index = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, Edge, RoundedCornerShape(14.dp)).padding(4.dp)
    ) {
        val segW = maxWidth / options.size
        val offset by animateDpAsState(segW * index, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "seg")
        Box(
            Modifier.offset(x = offset).width(segW).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.20f)).border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == index
                val color by animateColorAsState(if (on) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), label = "c")
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(o.value) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (o.icon != null) { Icon(o.icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
                    Text(o.label, color = color, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun Hairline() = Box(
    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(1.dp)
        .background(Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.outlineVariant, Color.Transparent)))
)

@Composable
fun Hint(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .border(1.dp, Edge, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Icon(Icons.Rounded.Info, null, tint = Subtle, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = Subtle, lineHeight = 17.sp)
    }
}

// ------------------------------------------------------------------ dashboard pieces

@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val s by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "s")
    val a by t.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "a")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(s).clip(CircleShape).background(color.copy(alpha = a)))
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

/** Number that counts up when it appears / changes. */
@Composable
fun CountUp(value: Long, fontSize: androidx.compose.ui.unit.TextUnit = 26.sp, color: Color = MaterialTheme.colorScheme.onSurface) {
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(value) { target = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    val shown by animateIntAsState(target, tween(900, easing = FastOutSlowInEasing), label = "count")
    Text(shown.toString(), fontSize = fontSize, fontWeight = FontWeight.Black, color = color)
}

@Composable
fun StatTile(icon: ImageVector, value: Long, label: String, accent: Color, modifier: Modifier = Modifier, suffix: String = "") {
    GlassCard(modifier, padding = 14.dp) {
        IconTile(icon, accent, 30.dp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(value, 24.sp)
            if (suffix.isNotEmpty()) Text(suffix, fontSize = 12.sp, color = Subtle, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        }
        Text(label, fontSize = 11.5.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Big square quick toggle for the home screen. */
@Composable
fun QuickTile(icon: ImageVector, title: String, key: String, accent: Color, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var on by remember(key) { mutableStateOf(Prefs.bool(ctx, key)) }
    val bg by animateColorAsState(if (on) accent.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tween(300), label = "qbg")
    val borderA by animateFloatAsState(if (on) 0.45f else 0f, tween(300), label = "qb")
    val press by animateFloatAsState(if (on) 1f else 0.98f, spring(Spring.DampingRatioMediumBouncy), label = "qs")
    val knob by animateDpAsState(if (on) 14.dp else 0.dp, spring(Spring.DampingRatioMediumBouncy), label = "knob")
    Row(
        modifier.scale(press).clip(RoundedCornerShape(18.dp)).background(bg)
            .border(1.dp, if (on) accent.copy(alpha = borderA) else Edge, RoundedCornerShape(18.dp))
            .pressable { on = !on; Prefs.setBool(ctx, key, on); onChange(on) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, if (on) accent else Subtle, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
            Text(if (on) L.t(R.string.state_on) else L.t(R.string.state_off), fontSize = 11.sp, color = if (on) accent else Subtle)
        }
        // A tiny switch, so it reads as a toggle at a glance.
        Box(
            Modifier.width(30.dp).height(16.dp).clip(CircleShape)
                .background(if (on) accent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)).padding(2.dp)
        ) { Box(Modifier.offset(x = knob).size(12.dp).clip(CircleShape).background(if (on) Neon.OnAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))) }
    }
}

/**
 * Progress ring with a value in the middle (home: today's time on TikTok against a daily reference).
 * [progress] 0..1 fills it; the rest of the track stays faint.
 */
@Composable
fun ProgressRing(progress: Float, size: Dp, stroke: Dp = 9.dp, colors: List<Color> = Neon.brand, content: @Composable () -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "ring")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val inset = s / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(s))
            if (p > 0f && Neon.glow) rotateDraw(-90f) {
                drawArc(Brush.sweepGradient((colors + colors.first()).map { it.copy(alpha = 0.22f) }), 0f, 360f * p, false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(s * 2.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            if (p > 0f) rotateDraw(-90f) {
                drawArc(Brush.sweepGradient(colors + colors.first()), 0f, 360f * p, false, topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        content()
    }
}

/** Status mark for the home header: a breathing dot inside a slowly turning gradient ring. */
@Composable
fun CoreOrb(active: Boolean, size: Dp = 64.dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "r")
    val breathe by t.animateFloat(0.82f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br")
    val core = if (active) Neon.Lime else Neon.Pink
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(r)) {
            drawCircle(Brush.sweepGradient(Neon.brand + Neon.brand.first()), style = Stroke(2.dp.toPx()), radius = this.size.minDimension / 2 - 2.dp.toPx())
        }
        Box(Modifier.size(size * 0.62f).clip(CircleShape).background(core.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.24f).scale(breathe).clip(CircleShape).background(core))
        }
    }
}

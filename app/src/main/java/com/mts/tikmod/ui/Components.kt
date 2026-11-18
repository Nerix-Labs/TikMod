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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * TikMod design system v4 — "glass & neon".
 * Aurora background, glass cards with a hairline gradient border, icon tiles, sliding segmented controls,
 * staggered entrance animations. Every control is bound directly to a Prefs key.
 */

/** Secondary text: readable on every scheme (dynamic schemes have a very muted onSurfaceVariant). */
val Subtle: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

// ------------------------------------------------------------------ background

/** Slowly drifting neon blobs behind everything. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    val strength = if (dark) 0.6f else 0.45f
    Canvas(modifier.fillMaxSize()) {
        drawRect(bg)
        fun blob(c: Color, x: Float, y: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = 0.22f * strength), Color.Transparent), center = Offset(x, y), radius = r),
            radius = r, center = Offset(x, y)
        )
        val w = size.width; val h = size.height
        blob(Neon.Cyan, w * (0.15f + 0.25f * a), h * (0.12f + 0.10f * b), w * 0.75f)
        blob(Neon.Pink, w * (0.95f - 0.30f * b), h * (0.38f + 0.15f * a), w * 0.70f)
        blob(Neon.Violet, w * (0.30f + 0.40f * b), h * (0.85f - 0.12f * a), w * 0.80f)
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ------------------------------------------------------------------ entrance

/** Staggered fade + rise when a screen opens. */
@Composable
fun Appear(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L * index); p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36f; scaleX = 0.97f + 0.03f * p.value; scaleY = scaleX }) {
        content()
    }
}

// ------------------------------------------------------------------ cards

@Composable
fun GlassCard(modifier: Modifier = Modifier, glow: Boolean = false, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val border = if (glow) Brush.linearGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink))
    else Brush.linearGradient(listOf(cs.onSurface.copy(alpha = 0.14f), cs.onSurface.copy(alpha = 0.05f), Neon.Pink.copy(alpha = 0.18f)))
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(cs.surfaceContainerHigh.copy(alpha = 0.94f))
            .border(1.dp, border, RoundedCornerShape(26.dp))
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
    GlassCard {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon, accent, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (badge != null) { Spacer(Modifier.width(8.dp)); Tag(badge) }
                }
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Subtle, lineHeight = 17.sp)
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
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f))))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = accent, modifier = Modifier.size(size * 0.52f)) }
}

@Composable
fun Tag(text: String, color: Color = when (text) { L.t(R.string.tag_new) -> Neon.Lime; L.t(R.string.tag_experimental) -> Neon.Amber; else -> Neon.Cyan }) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Neon.OnAccent,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = Subtle, modifier = Modifier.padding(start = 6.dp, top = 6.dp)
    )
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
            .clickable { onChange(!checked) }
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
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(4.dp)
    ) {
        val segW = maxWidth / options.size
        val offset by animateDpAsState(segW * index, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "seg")
        Box(
            Modifier.offset(x = offset).width(segW).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                .background(accent)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == index
                val color by animateColorAsState(if (on) Neon.OnAccent else MaterialTheme.colorScheme.onSurface, label = "c")
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
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(12.dp)
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
fun StatTile(icon: ImageVector, value: Long, label: String, accent: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier, padding = 14.dp) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        CountUp(value, 24.sp)
        Text(label, fontSize = 11.5.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Big square quick toggle for the home screen. */
@Composable
fun QuickTile(icon: ImageVector, title: String, key: String, accent: Color, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var on by remember(key) { mutableStateOf(Prefs.bool(ctx, key)) }
    val bg by animateColorAsState(if (on) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f), tween(300), label = "qbg")
    val borderA by animateFloatAsState(if (on) 0.9f else 0.12f, tween(300), label = "qb")
    val press by animateFloatAsState(if (on) 1f else 0.98f, spring(Spring.DampingRatioMediumBouncy), label = "qs")
    Column(
        modifier.scale(press).clip(RoundedCornerShape(22.dp)).background(bg)
            .border(1.dp, accent.copy(alpha = borderA), RoundedCornerShape(22.dp))
            .clickable { on = !on; Prefs.setBool(ctx, key, on); onChange(on) }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (on) accent else Subtle, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) accent else MaterialTheme.colorScheme.outline))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 17.sp)
        Text(if (on) L.t(R.string.state_on) else L.t(R.string.state_off), fontSize = 11.sp, color = if (on) accent else Subtle)
    }
}

/** Rotating rings around a status core (home hero). */
@Composable
fun CoreOrb(active: Boolean, size: Dp = 112.dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val r1 by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "r1")
    val r2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween(11000, easing = LinearEasing)), label = "r2")
    val breathe by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br")
    val core = if (active) Neon.Lime else Neon.Pink
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(r1)) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, Neon.Cyan, Neon.Violet, Color.Transparent)), 0f, 280f, false, style = Stroke(3.dp.toPx()))
        }
        Canvas(Modifier.fillMaxSize(0.78f).rotate(r2)) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, Neon.Pink, Neon.Amber, Color.Transparent)), 40f, 220f, false, style = Stroke(2.dp.toPx()))
        }
        Box(
            Modifier.size(size * 0.46f).scale(breathe).clip(CircleShape)
                .background(Brush.radialGradient(listOf(core.copy(alpha = 0.95f), core.copy(alpha = 0.25f))))
        )
        Box(Modifier.size(size * 0.46f).alpha(0.9f), contentAlignment = Alignment.Center) {
            Text(if (active) "ON" else "OFF", fontWeight = FontWeight.Black, fontSize = 15.sp, color = Neon.OnAccent)
        }
    }
}

package com.mts.tikmod.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.mts.tikmod.BuildConfig
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.updates.Changelog
import com.mts.tikmod.updates.Updater
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.TikModDownloader
import kotlinx.coroutines.launch

// ================================================================== "What's new" sheet

/** Shown automatically once after every update; also opened from the version history. */
/**
 * A centered panel that stays put: the content scrolls inside it and the footer button is always visible.
 * (A draggable bottom sheet fought with the inner scrolling and kept jumping.)
 */
@Composable
fun Panel(onDismiss: () -> Unit, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) }
        val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.84f
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Column(
                    Modifier.graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); scaleX = 0.92f + 0.08f * enter.value; scaleY = scaleX }
                        .widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxH)
                        .clip(RoundedCornerShape(30.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, Brush.linearGradient(listOf(Neon.Cyan.copy(alpha = 0.7f), Neon.Violet.copy(alpha = 0.4f), Neon.Pink.copy(alpha = 0.7f))), RoundedCornerShape(30.dp))
                        .clickable(remember { MutableInteractionSource() }, null) {} // taps inside don't dismiss
                ) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 8.dp),
                        content = content
                    )
                    if (footer != null) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 18.dp)) { footer() }
                }
            }
        }
    }
}

/** Shown automatically once after every update; also opened from the version history. */
@Composable
fun WhatsNewSheet(version: Changelog.Version, onDismiss: () -> Unit) {
    Panel(onDismiss, footer = {
        Button(
            onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Neon.Pink, contentColor = Neon.OnAccent)
        ) { Text(L.t(R.string.whatsnew_ok), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SparkleBurst()
            Spacer(Modifier.height(10.dp))
            Text(
                L.t(R.string.whatsnew_title), fontSize = 30.sp, fontWeight = FontWeight.Black,
                style = LocalTextStyle.current.copy(brush = Brush.linearGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink)))
            )
            Spacer(Modifier.height(6.dp))
            Text(
                L.t(R.string.whatsnew_version, version.name), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Neon.OnAccent,
                modifier = Modifier.clip(CircleShape).background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Pink))).padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(18.dp))
            version.items.forEachIndexed { i, item ->
                Appear(i + 1) { ChangeRow(item) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ChangeRow(item: Changelog.Item) {
    val accent = item.accent()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.08f)).border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        IconTile(item.icon, accent, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(L.t(item.title), fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(L.t(item.desc), fontSize = 13.sp, color = Subtle, lineHeight = 18.sp)
        }
    }
}

/** Spinning gradient ring with a pulsing star — the sheet's header. */
@Composable
private fun SparkleBurst() {
    val t = rememberInfiniteTransition(label = "burst")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.88f, 1.08f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    Box(Modifier.size(104.dp).scale(enter.value), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(spin)) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, Neon.Cyan, Neon.Violet, Neon.Pink, Color.Transparent)), 0f, 300f, false, style = Stroke(4.dp.toPx()))
        }
        Canvas(Modifier.fillMaxSize(0.62f)) {
            drawCircle(Brush.radialGradient(listOf(Neon.Pink.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width / 2, size.height / 2)))
        }
        Icon(Icons.Rounded.AutoAwesome, null, tint = Neon.Amber, modifier = Modifier.size(40.dp).scale(pulse))
    }
}

// ================================================================== updates section (home → system)

@Composable
fun UpdatesSection(onShowHistory: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = Updater.status
    Section(Icons.Rounded.SystemUpdate, L.t(R.string.update_title), L.t(R.string.update_current, BuildConfig.VERSION_NAME), accent = Neon.Cyan) {
        AnimatedContent(status, label = "update", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }) { s ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    is Updater.Status.Available -> UpdateAvailable(s.release) { scope.launch { Updater.downloadAndInstall(ctx, s.release) } }
                    is Updater.Status.Downloading -> UpdateProgress(L.t(R.string.update_downloading, "${(s.progress * 100).toInt()}%"), s.progress)
                    Updater.Status.Installing -> UpdateProgress(L.t(R.string.update_installing), null)
                    Updater.Status.Checking -> UpdateProgress(L.t(R.string.update_checking), null)
                    Updater.Status.UpToDate -> StatusLine(Icons.Rounded.CheckCircle, L.t(R.string.update_latest), Neon.Lime)
                    is Updater.Status.Error -> StatusLine(Icons.Rounded.ErrorOutline, s.message, Neon.Amber)
                    Updater.Status.Idle -> {}
                }
                if (s !is Updater.Status.Downloading && s !is Updater.Status.Installing && s !is Updater.Status.Available) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpdateButton(Icons.Rounded.Refresh, L.t(R.string.update_check), Neon.Cyan, Modifier.weight(1f), enabled = s != Updater.Status.Checking) {
                            scope.launch { Updater.check(ctx) }
                        }
                        UpdateButton(Icons.Rounded.History, L.t(R.string.update_history), Neon.Violet, Modifier.weight(1f)) { onShowHistory() }
                    }
                }
            }
        }
        Hairline()
        ToggleRow(L.t(R.string.update_auto), L.t(R.string.update_auto_desc), Prefs.KEY_AUTO_UPDATE_CHECK, Icons.Rounded.Autorenew, Neon.Cyan)
    }
}

@Composable
private fun UpdateAvailable(release: Updater.Release, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val recording = remember {
        val st = Prefs.readAutoLiveState(ctx)
        st.keys().asSequence().any { st.optJSONObject(it)?.optString("status") == "recording" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(Neon.Lime, 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(L.t(R.string.update_available, release.version), fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Changelog.forVersion(release.version)?.items?.take(3)?.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null, tint = item.accent(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(L.t(item.title), fontSize = 13.sp, color = Subtle)
        }
    } ?: Text(cleanNotes(release.notes).take(240), fontSize = 12.5.sp, color = Subtle, lineHeight = 17.sp)
    if (recording) Hint(L.t(R.string.update_recordings_warning))
    Button(
        onClick = onInstall, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Neon.Lime, contentColor = Neon.OnAccent)
    ) {
        Icon(Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(L.t(R.string.update_install) + if (release.apkSize > 0) "  •  ${TikModDownloader.formatSize(release.apkSize)}" else "", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UpdateProgress(label: String, progress: Float?) {
    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val animated by animateFloatAsState(progress ?: 0f, tween(200), label = "p")
    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Neon.Cyan, trackColor = Neon.Cyan.copy(alpha = 0.15f))
    else LinearProgressIndicator({ animated }, Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Neon.Lime, trackColor = Neon.Lime.copy(alpha = 0.15f))
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.5.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UpdateButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, accent: Color,
    modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit
) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(15.dp))
            .background(accent.copy(alpha = if (enabled) 0.12f else 0.05f))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.35f else 0.12f), RoundedCornerShape(15.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Compact "update ready" card at the top of the home screen. */
@Composable
fun UpdateBanner(release: Updater.Release, onOpen: () -> Unit) {
    val t = rememberInfiniteTransition(label = "banner")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Neon.Lime.copy(alpha = 0.22f), Neon.Cyan.copy(alpha = 0.18f)), start = Offset(600f * shift, 0f), end = Offset(600f + 600f * shift, 200f)))
            .border(1.dp, Neon.Lime.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(Icons.Rounded.SystemUpdate, Neon.Lime, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(L.t(R.string.update_banner), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(L.t(R.string.update_available, release.version), fontSize = 12.5.sp, color = Subtle)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Neon.Lime, modifier = Modifier.rotate(if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) 180f else 0f))
    }
}

// ================================================================== version history

@Composable
fun HistorySheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (Updater.releases.isEmpty()) Updater.check(ctx, silent = true) }
    // Local (translated) entries, plus newer releases that only exist on GitHub so far.
    val remoteOnly = Updater.releases.filter { r -> Changelog.forVersion(r.version) == null && Updater.isNewer(r.version) }
    Panel(onDismiss, footer = {
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(L.t(R.string.action_close), fontWeight = FontWeight.Bold) }
    }) {
        Column(Modifier.fillMaxWidth()) {
            Text(L.t(R.string.update_history), fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(L.t(R.string.update_history_sub), fontSize = 13.sp, color = Subtle)
            Spacer(Modifier.height(18.dp))
            var index = 0
            remoteOnly.forEach { r ->
                val i = index++
                Appear(i) {
                    TimelineEntry(r.version, r.publishedAt.take(10), L.t(R.string.update_badge_new), Neon.Lime, last = false) {
                        Text(cleanNotes(r.notes), fontSize = 13.sp, color = Subtle, lineHeight = 18.sp)
                        if (r.apkUrl != null) TextButton(onClick = { scope.launch { Updater.downloadAndInstall(ctx, r) }; onDismiss() }) {
                            Text(L.t(R.string.update_install))
                        }
                    }
                }
            }
            Changelog.versions.forEachIndexed { vi, v ->
                val i = index++
                val installed = v.name == BuildConfig.VERSION_NAME
                val page = Updater.releases.firstOrNull { it.version == v.name }?.pageUrl
                Appear(i) {
                    TimelineEntry(
                        v.name, v.date, if (installed) L.t(R.string.update_badge_installed) else null, Neon.Cyan,
                        last = vi == Changelog.versions.lastIndex
                    ) {
                        v.items.forEach { item ->
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                Icon(item.icon, null, tint = item.accent(), modifier = Modifier.size(18.dp).padding(top = 1.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(L.t(item.title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(L.t(item.desc), fontSize = 12.5.sp, color = Subtle, lineHeight = 17.sp)
                                }
                            }
                        }
                        if (page != null) TextButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(L.t(R.string.update_on_github)) }
                    }
                }
            }
        }
    }
}

/** One version on a vertical timeline: dot + line on the side, card with the changes. */
@Composable
private fun TimelineEntry(
    version: String, date: String, badge: String?, accent: Color, last: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!last) Box(Modifier.padding(top = 18.dp).width(2.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.6f), accent.copy(alpha = 0.08f)))))
            Box(Modifier.padding(top = 6.dp).size(14.dp).clip(CircleShape).background(accent).border(3.dp, accent.copy(alpha = 0.3f), CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(version, fontSize = 19.sp, fontWeight = FontWeight.Black)
                if (badge != null) { Spacer(Modifier.width(8.dp)); Tag(badge, accent) }
                Spacer(Modifier.weight(1f))
                Text(date, fontSize = 12.sp, color = Subtle, textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(12.dp),
                content = content
            )
        }
    }
}

/** GitHub release notes are markdown: keep the text readable without a markdown renderer. */
private fun cleanNotes(md: String): String = md.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("###") }
    .joinToString("\n") { line ->
        line.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""`(.+?)`"""), "$1")
            .let { if (it.startsWith("- ")) "•  " + it.removePrefix("- ") else it }
    }

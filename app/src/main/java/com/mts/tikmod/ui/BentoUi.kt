package com.mts.tikmod.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.history.WatchHistory
import com.mts.tikmod.stories.StoryStore
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.TikModDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Home as a control center: a bento grid of live tiles. Each tile shows its feature's state at a glance (numbers,
 * covers, a pulse while recording), has its own switch where it makes sense, and opens its section when tapped.
 */

/** Everything the tiles show, read off the main thread and refreshed every few seconds. */
class BentoData(
    val storyThumbs: List<ImageBitmap> = emptyList(),
    val storyCount: Int = 0,
    val storyDeleted: Int = 0,
    val watchThumbs: List<ImageBitmap> = emptyList(),
    val watchToday: Int = 0,
    val minutesToday: Long = 0,
    val downloads: Int = 0,
    val ads: Long = 0,
    val channels: Int = 0,
    val recording: List<Pair<String, JSONObject>> = emptyList(),
)

private fun thumbs(files: List<java.io.File>): List<ImageBitmap> = files.take(4).mapNotNull { f ->
    runCatching { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull()
}

private fun liveNow(ctx: Context): List<Pair<String, JSONObject>> {
    val st = Prefs.readAutoLiveState(ctx)
    return st.keys().asSequence().mapNotNull { k -> st.optJSONObject(k)?.let { k to it } }
        .filter { (k, o) -> !k.startsWith("_") && o.optString("status") == "recording" }.toList()
}

fun loadBento(ctx: Context): BentoData {
    val stories = StoryStore.list(ctx)
    val since = startOfToday()
    val watched = WatchHistory.list(ctx)
    val today = watched.filter { it.optLong("time") >= since }
    return BentoData(
        storyThumbs = thumbs(stories.mapNotNull { StoryStore.thumb(ctx, it) }),
        storyCount = stories.size,
        storyDeleted = stories.count { it.optBoolean("deleted") },
        watchThumbs = thumbs(watched.mapNotNull { WatchHistory.thumb(ctx, it) }),
        watchToday = today.size,
        minutesToday = today.sumOf { it.optLong("watched") } / 60_000L,
        downloads = Prefs.readHistory(ctx).size,
        ads = Prefs.readStat(ctx, Prefs.STAT_ADS_BLOCKED),
        channels = Prefs.autoLiveChannels(ctx).size,
        recording = liveNow(ctx),
    )
}

@Composable
fun rememberBento(): State<BentoData> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(BentoData()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.value = withContext(Dispatchers.IO) { runCatching { loadBento(ctx) }.getOrDefault(state.value) }
            delay(2500)
        }
    }
    return state
}

// ------------------------------------------------------------------ tile frame

/**
 * The tile: a rounded block washed with its color from one corner, a big icon, a title and a value. [switchKey]
 * puts a switch in the corner; the rest of the tile opens the section.
 */
@Composable
fun BentoTile(
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    onClick: () -> Unit,
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.height(height)
            .then(if (Neon.glow) Modifier.shadow(16.dp, shape, clip = false, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(cs.surfaceContainerHigh)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = if (Neon.isDark) 0.34f else 0.22f), accent.copy(alpha = 0.04f)),
                start = Offset.Zero, end = Offset(600f, 600f)))
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.05f))), shape)
            .pressable(onClick = onClick)
    ) {
        background?.invoke(this)
        Box(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

/** Big round icon at the top-start of a tile. */
@Composable
private fun TileIcon(icon: ImageVector, accent: Color) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
    }
}

/** A switch bound to a pref, sized for tiles. */
@Composable
private fun TileSwitch(key: String, accent: Color, onChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var on by remember(key) { mutableStateOf(Prefs.bool(ctx, key)) }
    Switch(on, { on = it; Prefs.setBool(ctx, key, it); onChange(it) },
        modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f },
        colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Neon.OnAccent, uncheckedTrackColor = Color.Black.copy(alpha = 0.25f)))
}

@Composable
private fun TileText(title: String, value: String, sub: String?, accent: Color, modifier: Modifier = Modifier, light: Boolean = false) {
    Column(modifier) {
        if (value.isNotEmpty()) Text(value, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, maxLines = 1,
            color = if (light) Color.White else MaterialTheme.colorScheme.onSurface)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (light) Color.White else MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (light) Color.White.copy(alpha = 0.8f) else accent)
    }
}

/** 2×2 mosaic of covers filling a tile, darkened at the bottom for text. */
@Composable
private fun Mosaic(images: List<ImageBitmap>, accent: Color) {
    if (images.isEmpty()) return
    Column(Modifier.fillMaxSize()) {
        images.chunked(2).take(2).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                row.forEach { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Box(Modifier.weight(1f).fillMaxHeight().background(accent.copy(alpha = 0.3f)))
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.45f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.88f))))
}

// ------------------------------------------------------------------ the grid

/**
 * The control center. [go] opens a section: "watch", "stories", "live", "downloads", "feed", "focus", "chat".
 */
@Composable
fun BentoGrid(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val d by rememberBento()
    val gap = 12.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        // LIVE: red and pulsing while something records, otherwise how many channels are watched.
        val rec = d.recording
        val t = rememberInfiniteTransition(label = "rec")
        val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
        BentoTile(Neon.Pink, Modifier.fillMaxWidth(), height = if (rec.isEmpty()) 130.dp else 150.dp, onClick = { go("live") }) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    if (rec.isNotEmpty()) Box(Modifier.size(64.dp).clip(CircleShape).background(Neon.Pink.copy(alpha = 0.25f * pulse)))
                    TileIcon(if (rec.isNotEmpty()) Icons.Rounded.FiberManualRecord else Icons.Rounded.Sensors, Neon.Pink)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (rec.isNotEmpty()) {
                        val (h, st) = rec.first()
                        Text(L.t(R.string.live_recording_now).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = Neon.Pink)
                        Text(st.optString("nick").ifBlank { "@$h" }, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TikModDownloader.formatDuration(st.optLong("dur")) + "  ·  " + TikModDownloader.formatSize(st.optLong("bytes")) +
                            (if (rec.size > 1) "  ·  +${rec.size - 1}" else ""), fontSize = 12.sp, color = Subtle)
                    } else {
                        Text(L.t(R.string.live_auto_title), fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(L.p(R.plurals.live_channels_count, d.channels), fontSize = 12.5.sp, color = Neon.Pink)
                    }
                }
                if (rec.isNotEmpty()) WatchButton { com.mts.tikmod.openLive(ctx, rec.first().first) }
                else TileSwitch(Prefs.KEY_AUTO_LIVE, Neon.Pink) { com.mts.tikmod.live.LiveWatchService.sync(ctx) }
            }
        }

        // Stories (covers) | watch history (covers)
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            BentoTile(Neon.Pink, Modifier.weight(1f), height = 210.dp, onClick = { go("stories") }, background = { Mosaic(d.storyThumbs, Neon.Pink) }) {
                if (d.storyThumbs.isEmpty()) TileIcon(Icons.Rounded.AutoStories, Neon.Pink)
                if (d.storyDeleted > 0) Text("🗑 ${d.storyDeleted}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Neon.Pink).padding(horizontal = 8.dp, vertical = 3.dp))
                TileText(L.t(R.string.stories_title), "${d.storyCount}", L.t(R.string.stories_stat_kept), Neon.Pink,
                    Modifier.align(Alignment.BottomStart), light = d.storyThumbs.isNotEmpty())
            }
            BentoTile(Neon.Cyan, Modifier.weight(1f), height = 210.dp, onClick = { go("watch") }, background = { Mosaic(d.watchThumbs, Neon.Cyan) }) {
                if (d.watchThumbs.isEmpty()) TileIcon(Icons.Rounded.Restore, Neon.Cyan)
                TileText(L.t(R.string.watch_title), "${d.watchToday}", "${d.minutesToday} ${L.t(R.string.today_min)} · ${L.t(R.string.watch_today)}", Neon.Cyan,
                    Modifier.align(Alignment.BottomStart), light = d.watchThumbs.isNotEmpty())
            }
        }

        // Ads blocked (big number) | downloads
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            BentoTile(Neon.Amber, Modifier.weight(1f), onClick = { go("feed") }) {
                TileIcon(Icons.Rounded.Block, Neon.Amber)
                Box(Modifier.align(Alignment.TopEnd)) { TileSwitch(Prefs.KEY_REMOVE_ADS, Neon.Amber) }
                TileText(L.t(R.string.stat_ads_blocked), "%,d".format(d.ads), null, Neon.Amber, Modifier.align(Alignment.BottomStart))
            }
            BentoTile(Neon.Cyan, Modifier.weight(1f), onClick = { go("downloads") }) {
                TileIcon(Icons.Rounded.Download, Neon.Cyan)
                Box(Modifier.align(Alignment.TopEnd)) { TileSwitch(Prefs.KEY_REMOVE_WATERMARK, Neon.Cyan) { Prefs.setForceDownloadEnabled(ctx, it) } }
                TileText(L.t(R.string.nav_downloads), "${d.downloads}", L.t(R.string.quick_download), Neon.Cyan, Modifier.align(Alignment.BottomStart))
            }
        }

        // Dynamic stop (its curve) | chat privacy
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            BentoTile(Neon.Lime, Modifier.weight(1f), onClick = { go("feed") }) {
                TileIcon(Icons.Rounded.AutoGraph, Neon.Lime)
                Box(Modifier.align(Alignment.TopEnd)) { TileSwitch(Prefs.KEY_FOCUS_DYNAMIC, Neon.Lime) }
                TileText(L.t(R.string.focus_dyn_on), "", L.t(R.string.focus_title), Neon.Lime, Modifier.align(Alignment.BottomStart))
            }
            BentoTile(Neon.Violet, Modifier.weight(1f), onClick = { go("chat") }) {
                TileIcon(Icons.Rounded.Shield, Neon.Violet)
                Box(Modifier.align(Alignment.TopEnd)) { TileSwitch(Prefs.KEY_ANTI_RECALL, Neon.Violet) }
                TileText(L.t(R.string.nav_chat), "", L.t(R.string.quick_deleted), Neon.Violet, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

/** The dynamic-stop curve drawn faintly across a tile. */
@Composable
private fun MiniCurve(color: Color) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxSize().padding(top = 50.dp, bottom = 46.dp)) {
        val w = size.width; val h = size.height
        fun x(f: Float) = if (rtl) w * (1 - f) else w * f
        val p = Path().apply { moveTo(x(0f), h); lineTo(x(0.3f), h); lineTo(x(0.3f), h * 0.85f); lineTo(x(0.8f), 0f); lineTo(x(1f), 0f) }
        drawPath(p, color.copy(alpha = 0.45f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

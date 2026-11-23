package com.mts.tikmod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.R
import com.mts.tikmod.history.WatchHistory
import com.mts.tikmod.live.LiveWatchService
import com.mts.tikmod.stories.StoryStore
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.TikModDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What today looked like, from TikMod's own records (nothing is asked from TikTok). */
private data class Today(val videos: Int, val watchedMs: Long, val stories: Int, val deleted: Int, val creators: Int)

private fun today(ctx: android.content.Context): Today {
    val since = startOfToday()
    val watched = WatchHistory.list(ctx).filter { it.optLong("time") >= since }
    val stories = StoryStore.list(ctx)
    return Today(
        videos = watched.size,
        watchedMs = watched.sumOf { it.optLong("watched") },
        stories = stories.count { it.optLong("seen") >= since },
        deleted = stories.count { it.optBoolean("deleted") && it.optLong("deletedAt") >= since },
        creators = watched.map { it.optString("handle") }.filter { it.isNotBlank() }.distinct().size
    )
}

/**
 * Home: today at a glance. A ring with the minutes you watched in the feed (a full ring is an hour), and what
 * TikMod caught for you today. Tapping opens the watch history.
 */
@Composable
fun TodayCard(onOpenWatch: () -> Unit, onOpenStories: () -> Unit) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(Today(0, 0, 0, 0, 0)) }
    LaunchedEffect(Unit) {
        var seen = -1L
        while (true) {
            val rev = WatchHistory.revision + StoryStore.revision
            if (rev != seen) { seen = rev; t = withContext(Dispatchers.IO) { today(ctx) } }
            delay(3000)
        }
    }
    val minutes = (t.watchedMs / 60_000L)
    GlassCard(glow = true, padding = 18.dp, modifier = Modifier.clickable { onOpenWatch() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.t(R.string.watch_today).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = Neon.Cyan)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = Subtle, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(minutes / 60f, 118.dp, 10.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        CountUp(minutes, 30.sp)
                        Text(L.t(R.string.today_min), fontSize = 12.sp, color = Subtle, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
                    }
                    Text(L.t(R.string.today_watched), fontSize = 10.5.sp, color = Subtle, maxLines = 1)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Metric(Icons.Rounded.PlayCircle, t.videos.toLong(), L.t(R.string.today_videos), Neon.Cyan)
                Metric(Icons.Rounded.People, t.creators.toLong(), L.t(R.string.today_creators), Neon.Violet)
                Metric(Icons.Rounded.AutoStories, t.stories.toLong(), L.t(R.string.today_stories), Neon.Pink, onClick = onOpenStories)
                if (t.deleted > 0) Metric(Icons.Rounded.DeleteSweep, t.deleted.toLong(), L.t(R.string.today_deleted), Neon.Amber, onClick = onOpenStories)
            }
        }
        if (t.videos == 0 && t.stories == 0) {
            Spacer(Modifier.height(10.dp))
            Text(L.t(R.string.today_empty), fontSize = 12.sp, color = Subtle, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, value: Long, label: String, accent: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, accent, 28.dp)
        Spacer(Modifier.width(10.dp))
        CountUp(value, 17.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Home: what's being recorded right now, each with "Watch" (opens that LIVE in TikTok) and stop. */
@Composable
fun LiveNowCard(recording: List<Pair<String, JSONObject>>, onOpenLive: () -> Unit) {
    val ctx = LocalContext.current
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpenLive() }.padding(horizontal = 4.dp)) {
            PulsingDot(Neon.Pink, 8.dp)
            Spacer(Modifier.width(10.dp))
            Text(L.t(R.string.live_recording_now).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = Neon.Pink)
            Spacer(Modifier.weight(1f))
            Text("${recording.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Subtle)
        }
        Spacer(Modifier.height(8.dp))
        recording.forEach { (h, st) -> LiveRow(h, st) }
    }
}

/** One stream being recorded: avatar letter, time + size, Watch and Stop. Used on Home and in the LIVE tab. */
@Composable
fun LiveRow(handle: String, st: JSONObject, showStop: Boolean = true) {
    val ctx = LocalContext.current
    val saving = st.optString("status") == "saving"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(Neon.Pink.copy(alpha = 0.08f)).border(1.dp, Neon.Pink.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neon.Pink, Neon.Violet))).padding(2.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) { Text(handle.take(1).uppercase(), fontWeight = FontWeight.Black, fontSize = 16.sp) }
            Box(Modifier.align(Alignment.BottomEnd)) { PulsingDot(if (saving) Neon.Amber else Neon.Pink, 9.dp) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(st.optString("nick").ifBlank { "@$handle" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (saving) L.t(R.string.live_saving) else "${TikModDownloader.formatDuration(st.optLong("dur"))}  ·  ${TikModDownloader.formatSize(st.optLong("bytes"))}",
                fontSize = 11.5.sp, color = Subtle, maxLines = 1
            )
        }
        WatchButton { com.mts.tikmod.openLive(ctx, handle) }
        if (showStop && !saving) IconButton(onClick = { LiveWatchService.stop(ctx, handle) }) {
            Icon(Icons.Rounded.StopCircle, L.t(R.string.action_stop_save), tint = Neon.Pink)
        }
    }
}

class QuickAction(val icon: ImageVector, val label: String, val accent: () -> Color, val onClick: () -> Unit)

/** Home: a row of round shortcuts to the places used most, scrolling sideways. */
@Composable
fun QuickActions(items: List<QuickAction>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { a ->
            val accent = a.accent()
            Column(Modifier.width(74.dp).pressable(onClick = a.onClick), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(60.dp)
                        .then(if (Neon.glow) Modifier.shadow(12.dp, CircleShape, clip = false, ambientColor = accent, spotColor = accent) else Modifier)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.06f))))
                        .border(1.dp, accent.copy(alpha = if (Neon.glow) 0.7f else 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(a.icon, null, tint = accent, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.height(6.dp))
                Text(a.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Watch" pill: opens the stream itself in TikTok. */
@Composable
fun WatchButton(onClick: () -> Unit) {
    Row(
        Modifier.pressable(onClick = onClick).clip(CircleShape)
            .then(if (Neon.glow) Modifier.background(Brush.linearGradient(listOf(Neon.Pink, Neon.Violet))) else Modifier.background(Neon.Pink))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = Neon.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(3.dp))
        Text(L.t(R.string.live_watch), color = Neon.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

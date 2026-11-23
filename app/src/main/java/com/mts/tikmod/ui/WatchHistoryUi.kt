package com.mts.tikmod.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.history.WatchHistory
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

/** The history, re-read when it changes (TikTok adds posts while this screen is open). */
@Composable
private fun rememberWatchHistory(): State<List<JSONObject>> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(Unit) {
        var seen = -1L
        while (true) {
            val rev = WatchHistory.revision
            if (rev != seen) {
                seen = rev
                state.value = withContext(Dispatchers.IO) { WatchHistory.list(ctx) }
            }
            delay(1500)
        }
    }
    return state
}

internal fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Card in the Downloads tab, under Stories. */
@Composable
fun WatchHistoryEntryCard(onOpen: () -> Unit) {
    val list by rememberWatchHistory()
    val today = remember(list) { startOfToday().let { t -> list.count { it.optLong("time") >= t } } }
    GlassCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpen() }) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Neon.Cyan.copy(alpha = 0.35f), Neon.Violet.copy(alpha = 0.25f)))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Restore, null, tint = Neon.Cyan, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t(R.string.watch_title), fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Spacer(Modifier.width(8.dp)); Tag(L.t(R.string.tag_new))
                }
                Text(
                    if (list.isEmpty()) L.t(R.string.watch_entry_empty) else L.t(R.string.watch_entry_counts, list.size, today),
                    fontSize = 13.sp, color = Subtle, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Subtle)
        }
    }
}

/** The full page: on/off, search and the list grouped by day. */
@Composable
fun WatchHistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by rememberWatchHistory()
    var query by rememberSaveable { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(40) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(list, query) { list.filter { WatchHistory.matches(it, query) } }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        icon = { Icon(Icons.Rounded.DeleteSweep, null) },
        title = { Text(L.t(R.string.watch_clear_q)) },
        text = { Text(L.t(R.string.watch_clear_desc)) },
        confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { WatchHistory.clear(ctx) }; confirmClear = false }) { Text(L.t(R.string.action_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.t(R.string.action_cancel)) } }
    )

    Appear(0) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L.t(R.string.action_back)) }
            Column {
                Text(L.t(R.string.watch_title), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(L.t(R.string.watch_subtitle), fontSize = 13.sp, color = Subtle)
            }
        }
    }

    Appear(1) {
        GlassCard(padding = 10.dp) {
            ToggleRow(L.t(R.string.watch_toggle), L.t(R.string.watch_toggle_desc), Prefs.KEY_WATCH_HISTORY, Icons.Rounded.Restore, Neon.Cyan)
        }
    }

    Appear(2) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; limit = 40 }, singleLine = true,
            placeholder = { Text(L.t(R.string.watch_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, L.t(R.string.action_clear)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()
        )
    }

    Appear(3) {
        GlassCard(padding = 12.dp) {
            when {
                list.isEmpty() -> Hint(L.t(R.string.watch_empty))
                shown.isEmpty() -> Hint(L.t(R.string.watch_no_match))
                else -> {
                    val now = System.currentTimeMillis()
                    val today = startOfToday()
                    var lastDay = ""
                    shown.take(limit).forEach { e ->
                        val t = e.optLong("time")
                        val day = when {
                            t >= today -> L.t(R.string.watch_today)
                            t >= today - DateUtils.DAY_IN_MILLIS -> L.t(R.string.watch_yesterday)
                            else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
                        }
                        if (day != lastDay) {
                            lastDay = day
                            Text(day, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Neon.Cyan,
                                modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp))
                        }
                        key(e.optString("aid")) { WatchRow(e, now) }
                    }
                    if (shown.size > limit) TextButton(onClick = { limit += 60 }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(L.p(R.plurals.history_show_all, shown.size))
                    }
                }
            }
            if (list.isNotEmpty()) {
                Hint(L.t(R.string.watch_hint, WatchHistory.MAX_ENTRIES))
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text(L.t(R.string.history_clear_button)) }
            }
        }
    }
}

private fun copyLink(ctx: Context, link: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TikTok", link))
        Toast.makeText(ctx, L.t(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
    }
}

/** Opens the post in TikTok when it's installed, otherwise in the browser. */
private fun openPost(ctx: Context, link: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (pkg in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")) {
        if (runCatching { ctx.startActivity(Intent(view).setPackage(pkg)); true }.getOrDefault(false)) return
    }
    runCatching { ctx.startActivity(view) }
}

private fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "0:%02d".format(s)
}

@Composable
private fun WatchRow(e: JSONObject, now: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = e.optString("handle")
    val link = WatchHistory.link(e)
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, e.optString("thumb")) {
        value = withContext(Dispatchers.IO) {
            WatchHistory.thumb(ctx, e)?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .clickable { openPost(ctx, link) }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(46.dp).height(70.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Icon(if (e.optBoolean("photo")) Icons.Rounded.Collections else Icons.Rounded.PlayArrow, null, tint = Color.White,
                modifier = Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.optString("nick").ifBlank { "@$handle" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.optString("desc").takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = Subtle, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
            val parts = buildList {
                add(DateUtils.getRelativeTimeSpanString(e.optLong("time"), now, DateUtils.MINUTE_IN_MILLIS).toString())
                e.optLong("watched").takeIf { it >= 1000 }?.let { add(L.t(R.string.watch_on_screen, formatClock(it))) }
                e.optInt("count", 1).takeIf { it > 1 }?.let { add(L.p(R.plurals.watch_times, it)) }
            }
            Text(parts.joinToString(" • "), fontSize = 11.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Subtle) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(L.t(R.string.watch_open)) }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) },
                    onClick = { menu = false; openPost(ctx, link) })
                DropdownMenuItem(text = { Text(L.t(R.string.watch_copy_link)) }, leadingIcon = { Icon(Icons.Rounded.Link, null) },
                    onClick = { menu = false; copyLink(ctx, link) })
                DropdownMenuItem(text = { Text(L.t(R.string.action_share)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                    menu = false
                    runCatching {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), L.t(R.string.action_share))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
                if (handle.isNotBlank()) DropdownMenuItem(text = { Text(L.t(R.string.stories_open_profile)) }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    onClick = { menu = false; openPost(ctx, "https://www.tiktok.com/@$handle") })
                DropdownMenuItem(text = { Text(L.t(R.string.action_remove)) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = {
                    menu = false
                    scope.launch(Dispatchers.IO) { WatchHistory.remove(ctx, e.optString("aid")) }
                })
            }
        }
    }
}

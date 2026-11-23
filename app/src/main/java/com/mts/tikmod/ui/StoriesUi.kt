package com.mts.tikmod.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.stories.StoryStore
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.TikModDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The archive, re-read whenever it changes (downloads finish in the background, TikTok adds stories). */
@Composable
private fun rememberStories(): State<List<JSONObject>> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(Unit) {
        // The provider may write from a provider-only process start, so the index file is simply re-read.
        while (true) {
            state.value = withContext(Dispatchers.IO) { StoryStore.list(ctx) }
            delay(2500)
        }
    }
    return state
}

/** Card at the top of the Downloads tab. */
@Composable
fun StoriesEntryCard(onOpen: () -> Unit) {
    val stories by rememberStories()
    val deleted = stories.count { it.optBoolean("deleted") }
    GlassCard(glow = deleted > 0, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpen() }) {
            StoryRing(Icons.Rounded.AutoStories, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t(R.string.stories_title), fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Spacer(Modifier.width(8.dp)); Tag(L.t(R.string.tag_new))
                }
                Text(
                    if (stories.isEmpty()) L.t(R.string.stories_entry_empty) else L.t(R.string.stories_entry_counts, stories.size, deleted),
                    fontSize = 13.sp, color = if (deleted > 0) Neon.Pink else Subtle, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Subtle)
        }
    }
}

/** The gradient ring TikTok draws around unseen stories, around an icon. */
@Composable
private fun StoryRing(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.sweepGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink, Neon.Amber, Neon.Cyan)))
            .padding(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Neon.Pink, modifier = Modifier.size(size * 0.45f)) }
}

/** The full page: settings, auto-save channels and the archive. */
@Composable
fun StoriesScreen(onBack: () -> Unit) {
    val stories by rememberStories()

    Appear(0) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L.t(R.string.action_back)) }
            Column {
                Text(L.t(R.string.stories_title), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(L.t(R.string.stories_subtitle), fontSize = 13.sp, color = Subtle)
            }
        }
    }

    Appear(1) {
        Section(Icons.Rounded.VisibilityOff, L.t(R.string.stories_privacy), accent = Neon.Violet) {
            ToggleRow(L.t(R.string.stories_hide_views), L.t(R.string.stories_hide_views_desc), Prefs.KEY_HIDE_STORY_VIEWS, Icons.Rounded.VisibilityOff, Neon.Violet)
            ToggleRow(L.t(R.string.profile_hide_views), L.t(R.string.profile_hide_views_desc), Prefs.KEY_HIDE_PROFILE_VIEWS, Icons.Rounded.PersonOff, Neon.Violet)
        }
    }

    Appear(2) {
        Section(Icons.Rounded.Inventory2, L.t(R.string.stories_keep), accent = Neon.Pink) {
            ToggleRow(L.t(R.string.stories_archive), L.t(R.string.stories_archive_desc), Prefs.KEY_STORY_ARCHIVE, Icons.Rounded.AutoStories, Neon.Pink)
            Hint(L.t(R.string.stories_download_hint))
        }
    }

    Appear(3) { AutoSaveSection() }

    Appear(4) { ArchiveSection(stories) }
}

@Composable
private fun AutoSaveSection() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.bool(ctx, Prefs.KEY_STORY_AUTO)) }
    var channels by remember { mutableStateOf(Prefs.storyAutoChannels(ctx)) }
    var input by remember { mutableStateOf("") }
    fun add() {
        val h = input.trim().substringAfterLast("tiktok.com/").removePrefix("@").substringBefore('/').substringBefore('?').lowercase()
        if (h.isBlank()) return
        channels = (channels + h).distinct()
        Prefs.setStoryAutoChannels(ctx, channels)
        input = ""
    }
    Section(Icons.Rounded.DownloadForOffline, L.t(R.string.stories_auto), L.t(R.string.stories_auto_desc), accent = Neon.Cyan) {
        ToggleRowRaw(L.t(R.string.stories_auto_toggle), null, enabled, Icons.Rounded.Bolt, Neon.Cyan) {
            enabled = it; Prefs.setBool(ctx, Prefs.KEY_STORY_AUTO, it)
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text(L.t(R.string.live_add_hint)) },
            leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
            trailingIcon = {
                FilledIconButton(onClick = { add() }, enabled = input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Neon.Cyan, contentColor = Neon.OnAccent)) {
                    Icon(Icons.Rounded.Add, L.t(R.string.action_add))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
        )
        if (channels.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            channels.forEach { h ->
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Neon.Cyan.copy(alpha = 0.12f))
                        .border(1.dp, Neon.Cyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("@$h", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                    IconButton(onClick = { channels = channels - h; Prefs.setStoryAutoChannels(ctx, channels) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, L.t(R.string.action_remove), tint = Subtle, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Hint(L.t(R.string.stories_auto_hint))
    }
}

@Composable
private fun ArchiveSection(stories: List<JSONObject>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var showAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val deleted = stories.count { it.optBoolean("deleted") }
    val shown = if (filter == "deleted") stories.filter { it.optBoolean("deleted") } else stories

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        icon = { Icon(Icons.Rounded.DeleteSweep, null) },
        title = { Text(L.t(R.string.stories_clear_q)) },
        text = { Text(L.t(R.string.stories_clear_desc)) },
        confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { StoryStore.clear(ctx) }; confirmClear = false }) { Text(L.t(R.string.action_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.t(R.string.action_cancel)) } }
    )

    Section(
        Icons.Rounded.History, L.t(R.string.stories_archive_title),
        if (stories.isEmpty()) null else L.t(R.string.stories_archive_sub, stories.size, TikModDownloader.formatSize(StoryStore.totalBytes(stories))),
        accent = Neon.Pink
    ) {
        Segmented(
            listOf(Opt("all", L.t(R.string.stories_filter_all), Icons.Rounded.AutoStories), Opt("deleted", L.t(R.string.stories_filter_deleted, deleted), Icons.Rounded.DeleteOutline)),
            filter, Neon.Pink
        ) { filter = it; showAll = false }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) Hint(if (filter == "deleted") L.t(R.string.stories_deleted_empty) else L.t(R.string.stories_archive_empty))
        (if (showAll) shown else shown.take(12)).forEach { e -> key(e.optString("aid")) { StoryRow(e) } }
        if (shown.size > 12) TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) L.t(R.string.history_show_less) else L.p(R.plurals.history_show_all, shown.size))
        }
        if (stories.isNotEmpty()) {
            Hint(L.t(R.string.stories_deleted_hint))
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text(L.t(R.string.history_clear_button)) }
        }
    }
}

@Composable
private fun StoryRow(e: JSONObject) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val aid = e.optString("aid")
    val handle = e.optString("handle")
    val status = e.optString("status")
    val isDeleted = e.optBoolean("deleted")
    val files = remember(e.toString()) { StoryStore.files(ctx, e) }
    val thumb by produceState<ImageBitmap?>(null, e.optString("thumb")) {
        value = withContext(Dispatchers.IO) {
            StoryStore.thumb(ctx, e)?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() }
        }
    }
    var menu by remember { mutableStateOf(false) }

    fun open() {
        val f = files.firstOrNull() ?: return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(StoryStore.uri(ctx, f), StoryStore.mime(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun share() {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { StoryStore.uri(ctx, it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(StoryStore.mime(files[0])).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        runCatching {
            ctx.startActivity(Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t(R.string.action_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
            .background(if (isDeleted) Neon.Pink.copy(alpha = 0.10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(enabled = files.isNotEmpty()) { open() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 9:16 thumbnail with a play / photo-count mark.
        Box(Modifier.width(54.dp).height(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            when {
                status == "pending" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Neon.Pink)
                status == "failed" -> Icon(Icons.Rounded.ErrorOutline, null, tint = Neon.Amber)
                e.optString("kind") == "photo" && files.size > 1 -> Text("${files.size}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp))
                e.optString("kind") == "video" -> Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)))
            }
            if (isDeleted) Icon(Icons.Rounded.Delete, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(3.dp).size(20.dp).clip(CircleShape).background(Neon.Pink).padding(3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.optString("nick").ifBlank { "@$handle" }, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isDeleted) { Spacer(Modifier.width(6.dp)); Tag("🗑 " + L.t(R.string.stories_deleted_badge), Neon.Pink) }
            }
            Text("@$handle", fontSize = 11.5.sp, color = Subtle, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
            val now = System.currentTimeMillis()
            val posted = e.optLong("created").takeIf { it > 0 } ?: e.optLong("seen")
            val line = when {
                status == "pending" -> L.t(R.string.stories_downloading)
                status == "failed" -> L.t(R.string.stories_failed)
                isDeleted -> L.t(R.string.stories_deleted_at, DateUtils.getRelativeTimeSpanString(e.optLong("deletedAt"), now, DateUtils.MINUTE_IN_MILLIS).toString())
                else -> DateUtils.getRelativeTimeSpanString(posted, now, DateUtils.MINUTE_IN_MILLIS).toString()
            }
            Text(line, fontSize = 11.5.sp, color = if (isDeleted) Neon.Pink else Subtle, maxLines = 1)
            val tags = listOfNotNull(
                L.t(R.string.stories_auto_badge).takeIf { e.optBoolean("auto") },
                L.t(R.string.stories_saved_badge).takeIf { e.optBoolean("saved") }
            )
            if (tags.isNotEmpty()) Text(tags.joinToString(" • "), fontSize = 11.sp, color = Neon.Cyan, fontWeight = FontWeight.SemiBold)
            e.optString("desc").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.5.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Subtle) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(L.t(R.string.stories_save)) }, leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) }, enabled = files.isNotEmpty(), onClick = {
                    menu = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { StoryStore.saveToGallery(ctx, aid) }
                        Toast.makeText(ctx, L.t(if (ok) R.string.stories_saved_toast else R.string.stories_save_failed), Toast.LENGTH_SHORT).show()
                    }
                })
                DropdownMenuItem(text = { Text(L.t(R.string.action_share)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, enabled = files.isNotEmpty(), onClick = { menu = false; share() })
                DropdownMenuItem(text = { Text(L.t(R.string.stories_open_profile)) }, leadingIcon = { Icon(Icons.Rounded.Person, null) }, onClick = {
                    menu = false
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/@$handle")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                DropdownMenuItem(text = { Text(L.t(R.string.action_remove)) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = {
                    menu = false
                    scope.launch(Dispatchers.IO) { StoryStore.remove(ctx, aid) }
                })
            }
        }
    }
}

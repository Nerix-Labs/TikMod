package com.mts.tikmod.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
fun StoriesScreen(onBack: (() -> Unit)?) {
    val stories by rememberStories()

    Appear(0) {
        if (onBack == null) com.mts.tikmod.ScreenTitle(L.t(R.string.stories_title), L.t(R.string.stories_subtitle), Neon.Pink)
        else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, L.t(R.string.action_back)) }
            Column {
                Text(L.t(R.string.stories_title), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(L.t(R.string.stories_subtitle), fontSize = 13.sp, color = Subtle)
            }
        }
    }
    // At a glance: what's in the archive, and how much of it was deleted by its creator.
    if (stories.isNotEmpty()) Appear(1) {
        val deleted = stories.count { it.optBoolean("deleted") }
        val creators = stories.map { it.optString("handle").lowercase() }.distinct().size
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Icons.Rounded.AutoStories, stories.size.toLong(), L.t(R.string.stories_stat_kept), Neon.Pink, Modifier.weight(1f))
            StatTile(Icons.Rounded.DeleteSweep, deleted.toLong(), L.t(R.string.stories_stat_deleted), Neon.Amber, Modifier.weight(1f))
            StatTile(Icons.Rounded.People, creators.toLong(), L.t(R.string.today_creators), Neon.Violet, Modifier.weight(1f))
        }
    }

    // The archive first: it's what this tab is for. Settings below.
    Appear(1) { ArchiveSection(stories) }

    Appear(2) { SectionLabel(L.t(R.string.home_system)) }

    Appear(2) {
        Section(Icons.Rounded.VisibilityOff, L.t(R.string.stories_privacy), accent = Neon.Violet) {
            ToggleRow(L.t(R.string.stories_hide_views), L.t(R.string.stories_hide_views_desc), Prefs.KEY_HIDE_STORY_VIEWS, Icons.Rounded.VisibilityOff, Neon.Violet)
            ToggleRow(L.t(R.string.profile_hide_views), L.t(R.string.profile_hide_views_desc), Prefs.KEY_HIDE_PROFILE_VIEWS, Icons.Rounded.PersonOff, Neon.Violet)
        }
    }

    Appear(2) {
        Section(Icons.Rounded.Inventory2, L.t(R.string.stories_keep), accent = Neon.Pink) {
            ToggleRow(L.t(R.string.stories_archive), L.t(R.string.stories_archive_desc), Prefs.KEY_STORY_ARCHIVE, Icons.Rounded.AutoStories, Neon.Pink)
            DeletedNotifyToggle()
            Hint(L.t(R.string.stories_download_hint))
        }
    }

    Appear(3) { AutoSaveSection() }
}

/** "Tell me when a story gets deleted"; asks for the notification permission when it's switched on (Android 13+). */
@Composable
private fun DeletedNotifyToggle() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.bool(ctx, Prefs.KEY_STORY_DELETED_NOTIFY)) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    fun needsPermission() = android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ToggleRowRaw(L.t(R.string.stories_notify), L.t(R.string.stories_notify_desc), on, Icons.Rounded.NotificationsActive, Neon.Pink) {
        on = it
        Prefs.setBool(ctx, Prefs.KEY_STORY_DELETED_NOTIFY, it)
        if (it && needsPermission()) runCatching { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
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
                    Text("@$h", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.clickable { com.mts.tikmod.openProfile(ctx, h) })
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
    // One creator's stories only ("" = everyone).
    var creator by remember { mutableStateOf("") }
    val deleted = stories.count { it.optBoolean("deleted") }
    // Creators by how many of their stories are archived (deleted ones first among equals).
    val creators = remember(stories) {
        stories.groupBy { it.optString("handle").lowercase() }.filterKeys { it.isNotBlank() }
            .map { (h, l) -> Triple(h, l.size, l.count { it.optBoolean("deleted") }) }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
    }
    if (creator.isNotEmpty() && creators.none { it.first == creator }) creator = ""
    val shown = stories.filter {
        (filter != "deleted" || it.optBoolean("deleted")) && (creator.isEmpty() || it.optString("handle").lowercase() == creator)
    }

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
        if (creators.size > 1) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = creator.isEmpty(), onClick = { creator = ""; showAll = false },
                label = { Text(L.t(R.string.stories_creators_all)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Neon.Pink.copy(alpha = 0.22f)))
            creators.forEach { (h, count, del) ->
                FilterChip(selected = creator == h, onClick = { creator = if (creator == h) "" else h; showAll = false },
                    label = { Text("@$h · $count" + if (del > 0) " · 🗑$del" else "", style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Neon.Pink.copy(alpha = 0.22f)))
            }
        }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) Hint(if (filter == "deleted") L.t(R.string.stories_deleted_empty) else L.t(R.string.stories_archive_empty))
        // A grid of tiles, three per row (like a profile), newest first.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
            (if (showAll) shown else shown.take(18)).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { e -> key(e.optString("aid")) { StoryTile(e, Modifier.weight(1f)) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (shown.size > 18) TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) L.t(R.string.history_show_less) else L.p(R.plurals.history_show_all, shown.size))
        }
        if (stories.isNotEmpty()) {
            Hint(L.t(R.string.stories_deleted_hint))
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text(L.t(R.string.history_clear_button)) }
        }
    }
}

/**
 * One archived story as a 9:16 tile: the cover fills it, name and time sit on a dark gradient at the bottom, a 🗑 mark
 * when it was deleted. Tap opens it; ⋮ (or a long press) has save / share / profile / remove.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StoryTile(e: JSONObject, modifier: Modifier = Modifier) {
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

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.aspectRatio(9f / 16f).clip(shape)
            .background(Brush.linearGradient(listOf(Neon.Pink.copy(alpha = 0.25f), Neon.Violet.copy(alpha = 0.20f))))
            .border(if (isDeleted) 2.dp else 1.dp, if (isDeleted) Neon.Pink else Edge, shape)
            .combinedClickable(enabled = true, onClick = { if (files.isNotEmpty()) open() else menu = true }, onLongClick = { menu = true })
    ) {
        thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.40f), 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)
        )))
        // Top: deleted mark / photo count, and the menu.
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                isDeleted -> Text("🗑", fontSize = 12.sp, modifier = Modifier.clip(CircleShape).background(Neon.Pink).padding(horizontal = 6.dp, vertical = 2.dp))
                e.optString("kind") == "photo" && files.size > 1 -> Row(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Collections, null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${files.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                Icon(Icons.Rounded.MoreVert, null, tint = Color.White,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable { menu = true }.padding(2.dp))
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
                        menu = false; com.mts.tikmod.openProfile(ctx, handle)
                    })
                    DropdownMenuItem(text = { Text(L.t(R.string.action_remove)) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = {
                        menu = false
                        scope.launch(Dispatchers.IO) { StoryStore.remove(ctx, aid) }
                    })
                }
            }
        }
        // Middle: still downloading / failed / a play mark.
        Box(Modifier.align(Alignment.Center)) {
            when {
                status == "pending" -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                status == "failed" -> Icon(Icons.Rounded.ErrorOutline, null, tint = Neon.Amber, modifier = Modifier.size(26.dp))
                e.optString("kind") == "video" -> Icon(Icons.Rounded.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(3.dp))
            }
        }
        // Bottom: who and when.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(e.optString("nick").ifBlank { "@$handle" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val now = System.currentTimeMillis()
            val posted = e.optLong("created").takeIf { it > 0 } ?: e.optLong("seen")
            Text(
                if (isDeleted) DateUtils.getRelativeTimeSpanString(e.optLong("deletedAt"), now, DateUtils.MINUTE_IN_MILLIS).toString()
                else DateUtils.getRelativeTimeSpanString(posted, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                color = if (isDeleted) Neon.Pink else Color.White.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (e.optBoolean("auto") || e.optBoolean("saved")) Text(
                listOfNotNull(L.t(R.string.stories_auto_badge).takeIf { e.optBoolean("auto") }, L.t(R.string.stories_saved_badge).takeIf { e.optBoolean("saved") }).joinToString(" · "),
                color = Neon.Cyan, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

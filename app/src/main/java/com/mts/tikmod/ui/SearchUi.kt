package com.mts.tikmod.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L

/**
 * One searchable thing: a switch ([key] set) that can be flipped right in the results, or a place to go.
 * [tab]: where it lives ("downloads", "stories", "live", "chat", "feed", "home", "watch", "focus").
 */
private class Entry(
    val title: Int, val desc: Int, val key: String?, val tab: String, val icon: ImageVector, val accent: () -> Color,
    val onChange: ((android.content.Context, Boolean) -> Unit)? = null
)

private val ENTRIES = listOf(
    // Downloads
    Entry(R.string.dl_engine_toggle, R.string.dl_engine_toggle_desc, Prefs.KEY_REMOVE_WATERMARK, "downloads", Icons.Rounded.Download, { Neon.Cyan }) { c, v -> Prefs.setForceDownloadEnabled(c, v) },
    Entry(R.string.card_photos, R.string.card_photos_desc, Prefs.KEY_DOWNLOAD_PHOTOS, "downloads", Icons.Rounded.Collections, { Neon.Cyan }),
    Entry(R.string.card_jpeg, R.string.card_jpeg_desc, Prefs.KEY_IMAGES_AS_JPEG, "downloads", Icons.Rounded.Image, { Neon.Cyan }),
    Entry(R.string.card_audio, R.string.card_audio_desc, Prefs.KEY_DOWNLOAD_AUDIO, "downloads", Icons.Rounded.MusicNote, { Neon.Cyan }),
    Entry(R.string.card_cover, R.string.card_cover_desc, Prefs.KEY_DOWNLOAD_COVER, "downloads", Icons.Rounded.Wallpaper, { Neon.Cyan }),
    Entry(R.string.card_avatar, R.string.card_avatar_desc, Prefs.KEY_DOWNLOAD_AVATAR, "downloads", Icons.Rounded.AccountCircle, { Neon.Cyan }),
    Entry(R.string.card_link, R.string.card_link_desc, Prefs.KEY_COPY_LINK_BUTTON, "downloads", Icons.Rounded.Link, { Neon.Cyan }),
    Entry(R.string.files_skip_dupes, R.string.files_skip_dupes_desc, Prefs.KEY_SKIP_DUPLICATES, "downloads", Icons.Rounded.FilterNone, { Neon.Cyan }),
    Entry(R.string.files_copy_caption, R.string.files_copy_caption_desc, Prefs.KEY_COPY_CAPTION, "downloads", Icons.Rounded.ContentCopy, { Neon.Cyan }),
    Entry(R.string.files_progress, R.string.files_progress_desc, Prefs.KEY_NOTIFICATION_PROGRESS, "downloads", Icons.Rounded.NotificationsActive, { Neon.Cyan }),
    Entry(R.string.files_history, R.string.files_history_desc, Prefs.KEY_DOWNLOAD_HISTORY, "downloads", Icons.Rounded.History, { Neon.Cyan }),
    Entry(R.string.watch_toggle, R.string.watch_toggle_desc, Prefs.KEY_WATCH_HISTORY, "watch", Icons.Rounded.Restore, { Neon.Cyan }),
    // Stories
    Entry(R.string.stories_archive, R.string.stories_archive_desc, Prefs.KEY_STORY_ARCHIVE, "stories", Icons.Rounded.AutoStories, { Neon.Pink }),
    Entry(R.string.stories_notify, R.string.stories_notify_desc, Prefs.KEY_STORY_DELETED_NOTIFY, "stories", Icons.Rounded.NotificationsActive, { Neon.Pink }),
    Entry(R.string.stories_auto_toggle, R.string.stories_auto_desc, Prefs.KEY_STORY_AUTO, "stories", Icons.Rounded.Bolt, { Neon.Pink }),
    Entry(R.string.stories_hide_views, R.string.stories_hide_views_desc, Prefs.KEY_HIDE_STORY_VIEWS, "stories", Icons.Rounded.VisibilityOff, { Neon.Violet }),
    Entry(R.string.profile_hide_views, R.string.profile_hide_views_desc, Prefs.KEY_HIDE_PROFILE_VIEWS, "stories", Icons.Rounded.PersonOff, { Neon.Violet }),
    // LIVE
    Entry(R.string.live_auto_title, R.string.live_subtitle, Prefs.KEY_AUTO_LIVE, "live", Icons.Rounded.FiberManualRecord, { Neon.Pink }) { c, _ -> com.mts.tikmod.live.LiveWatchService.sync(c) },
    Entry(R.string.live_instant, R.string.live_instant_sub, null, "live", Icons.Rounded.Videocam, { Neon.Pink }),
    Entry(R.string.live_history_toggle, R.string.live_history_toggle_desc, Prefs.KEY_LIVE_HISTORY, "live", Icons.Rounded.ManageHistory, { Neon.Pink }),
    Entry(R.string.captions_original, R.string.captions_original_desc, Prefs.KEY_CAPTION_ORIGINAL, "live", Icons.Rounded.ClosedCaption, { Neon.Cyan }),
    Entry(R.string.captions_always, R.string.captions_always_desc, Prefs.KEY_CAPTION_ALWAYS, "live", Icons.Rounded.ClosedCaption, { Neon.Cyan }),
    Entry(R.string.chat_rec_toggle, R.string.chat_rec_toggle_desc, Prefs.KEY_RECORD_CHAT, "live", Icons.Rounded.Forum, { Neon.Pink }),
    Entry(R.string.chat_in_video, R.string.chat_in_video_desc, Prefs.KEY_CHAT_IN_VIDEO, "live", Icons.Rounded.Subtitles, { Neon.Pink }),
    // Chat
    Entry(R.string.chat_hide_typing, R.string.chat_hide_typing_desc, Prefs.KEY_DISABLE_TYPING_INDICATOR, "chat", Icons.Rounded.KeyboardHide, { Neon.Violet }),
    Entry(R.string.chat_hide_online, R.string.chat_hide_online_desc, Prefs.KEY_HIDE_ONLINE_STATUS, "chat", Icons.Rounded.WifiOff, { Neon.Violet }),
    Entry(R.string.chat_hide_views, R.string.chat_hide_views_desc, Prefs.KEY_HIDE_VIEWS, "chat", Icons.Rounded.PlayDisabled, { Neon.Violet }),
    Entry(R.string.chat_keep_deleted, R.string.chat_keep_deleted_desc, Prefs.KEY_ANTI_RECALL, "chat", Icons.Rounded.Restore, { Neon.Violet }),
    Entry(R.string.chat_save_deleted_media, R.string.chat_save_deleted_media_desc, Prefs.KEY_SAVE_DELETED_MEDIA, "chat", Icons.Rounded.SaveAlt, { Neon.Violet }),
    Entry(R.string.chat_receipts, R.string.chat_receipts_sub, null, "chat", Icons.Rounded.DoneAll, { Neon.Violet }),
    // Feed
    Entry(R.string.feed_ads, R.string.feed_ads_desc, Prefs.KEY_REMOVE_ADS, "feed", Icons.Rounded.Block, { Neon.Amber }),
    Entry(R.string.feed_splash, R.string.feed_splash_desc, Prefs.KEY_BLOCK_SPLASH_ADS, "feed", Icons.Rounded.FastForward, { Neon.Amber }),
    Entry(R.string.feed_comment_ads, R.string.feed_comment_ads_desc, Prefs.KEY_HIDE_COMMENT_ADS, "feed", Icons.Rounded.Comment, { Neon.Amber }),
    Entry(R.string.feed_paid, R.string.feed_paid_desc, Prefs.KEY_HIDE_PAID_PARTNERSHIP, "feed", Icons.Rounded.Paid, { Neon.Amber }),
    Entry(R.string.feed_live, R.string.feed_live_desc, Prefs.KEY_HIDE_FEED_LIVE, "feed", Icons.Rounded.Sensors, { Neon.Amber }),
    Entry(R.string.feed_shop, R.string.feed_shop_desc, Prefs.KEY_HIDE_SHOP, "feed", Icons.Rounded.ShoppingBag, { Neon.Amber }),
    Entry(R.string.feed_seekbar, R.string.feed_seekbar_desc, Prefs.KEY_FORCE_SEEKBAR, "feed", Icons.Rounded.LinearScale, { Neon.Cyan }),
    Entry(R.string.feed_mute, R.string.feed_mute_desc, Prefs.KEY_MUTE_DEFAULT, "feed", Icons.Rounded.VolumeOff, { Neon.Cyan }),
    Entry(R.string.feed_reposts, R.string.feed_reposts_desc, Prefs.KEY_HIDE_REPOSTS, "feed", Icons.Rounded.Repeat, { Neon.Amber }),
    Entry(R.string.focus_dyn_on, R.string.focus_dyn_desc, Prefs.KEY_FOCUS_DYNAMIC, "feed", Icons.Rounded.AutoGraph, { Neon.Lime }),
    Entry(R.string.focus_bedtime_screen, R.string.focus_bedtime_screen_desc, Prefs.KEY_BEDTIME_SCREEN, "focus", Icons.Rounded.Bedtime, { Neon.Violet }),
    Entry(R.string.focus_session_remind, R.string.focus_session_remind_desc, Prefs.KEY_SESSION_REMIND, "focus", Icons.Rounded.HourglassBottom, { Neon.Amber }),
    // App
    Entry(R.string.sys_unrestrict, R.string.sys_unrestrict_desc, Prefs.KEY_UNRESTRICT_ALL, "home", Icons.Rounded.Screenshot, { Neon.Lime }),
    Entry(R.string.tweak_screen_on, R.string.tweak_screen_on_desc, Prefs.KEY_KEEP_SCREEN_ON, "home", Icons.Rounded.ScreenLockPortrait, { Neon.Lime }),
    Entry(R.string.tweak_haptics, R.string.tweak_haptics_desc, Prefs.KEY_HAPTICS_OFF, "home", Icons.Rounded.Vibration, { Neon.Lime }),
    Entry(R.string.tweak_links, R.string.tweak_links_desc, Prefs.KEY_EXTERNAL_LINKS, "home", Icons.Rounded.OpenInBrowser, { Neon.Lime }),
    Entry(R.string.update_auto, R.string.update_auto_desc, Prefs.KEY_AUTO_UPDATE_CHECK, "home", Icons.Rounded.SystemUpdate, { Neon.Cyan }),
)

/** Lower-case, without Hebrew / Arabic marks, so "מחק" finds "נמחקו" and accents don't matter much. */
private fun norm(s: String) = s.lowercase().replace(Regex("[\\u0591-\\u05C7\\u064B-\\u065F]"), "")

/**
 * Home: "Search settings". Matches every word typed against each setting's name and description in the current
 * language; switches flip right in the results, the arrow jumps to where the setting lives.
 */
@Composable
fun SettingsSearch(onGo: (String) -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    val words = remember(query) { norm(query).split(' ').filter { it.isNotBlank() } }
    val results = remember(words, L.revision) {
        if (words.isEmpty()) emptyList()
        else ENTRIES.map { e -> e to norm(L.t(e.title) + " " + (if (e.desc != 0) L.t(e.desc) else "")) }
            .filter { (_, text) -> words.all { it in text } }
            // Name matches first.
            .sortedByDescending { (e, _) -> words.count { it in norm(L.t(e.title)) } }
            .take(8).map { it.first }
    }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .border(1.dp, if (query.isNotEmpty()) Neon.Cyan.copy(alpha = 0.6f) else Edge, shape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = if (query.isNotEmpty()) Neon.Cyan else Subtle, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(L.t(R.string.search_settings), color = Subtle, fontSize = 15.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(Neon.Cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) Icon(Icons.Rounded.Close, L.t(R.string.action_clear), tint = Subtle,
                modifier = Modifier.size(22.dp).clip(CircleShape).clickable { query = ""; focus.clearFocus() })
        }
        AnimatedVisibility(query.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            GlassCard(padding = 8.dp, modifier = Modifier.padding(top = 10.dp)) {
                if (results.isEmpty()) Hint(L.t(R.string.search_none))
                results.forEach { e ->
                    key(e.title, e.key) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (e.key != null) ToggleRow(L.t(e.title), if (e.desc != 0) L.t(e.desc) else null, e.key, e.icon, e.accent()) { v -> e.onChange?.invoke(ctx, v) }
                                else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onGo(e.tab) }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(e.icon, null, tint = e.accent(), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(L.t(e.title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        if (e.desc != 0) Text(L.t(e.desc), fontSize = 13.sp, color = Subtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            IconButton(onClick = { focus.clearFocus(); onGo(e.tab) }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, L.t(R.string.search_go), tint = e.accent())
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.mts.tikmod.updates

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mts.tikmod.R
import com.mts.tikmod.ui.theme.Neon

/**
 * What changed in each version, shown in the "What's new" sheet and the version history.
 * Localized (string resources), unlike GitHub release notes which are English only.
 * Newest first. Add an entry here with every release.
 */
object Changelog {
    class Item(val icon: ImageVector, val title: Int, val desc: Int, val accent: () -> Color)
    class Version(val name: String, val date: String, val items: List<Item>)

    val versions: List<Version> = listOf(
        Version(
            "4.10.0", "2026-11-23", listOf(
                Item(Icons.Rounded.AutoAwesome, R.string.cl4100_design, R.string.cl4100_design_desc) { Neon.Violet },
                Item(Icons.Rounded.Restore, R.string.cl4100_watch, R.string.cl4100_watch_desc) { Neon.Cyan },
                Item(Icons.Rounded.PlayCircle, R.string.cl4100_live, R.string.cl4100_live_desc) { Neon.Pink },
                Item(Icons.Rounded.AutoStories, R.string.cl4100_tabs, R.string.cl4100_tabs_desc) { Neon.Pink },
                Item(Icons.Rounded.NotificationsActive, R.string.cl4100_notify, R.string.cl4100_notify_desc) { Neon.Pink },
                Item(Icons.Rounded.People, R.string.cl4100_creators, R.string.cl4100_creators_desc) { Neon.Violet },
                Item(Icons.Rounded.Search, R.string.cl4100_search, R.string.cl4100_search_desc) { Neon.Amber },
            )
        ),
        Version(
            "4.9.0", "2026-11-22", listOf(
                Item(Icons.Rounded.AutoStories, R.string.cl490_stories, R.string.cl490_stories_desc) { Neon.Pink },
                Item(Icons.Rounded.DeleteSweep, R.string.cl490_deleted, R.string.cl490_deleted_desc) { Neon.Amber },
                Item(Icons.Rounded.VisibilityOff, R.string.cl490_anon, R.string.cl490_anon_desc) { Neon.Violet },
                Item(Icons.Rounded.PlayDisabled, R.string.cl490_views, R.string.cl490_views_desc) { Neon.Cyan },
            )
        ),
        Version(
            "4.8.0", "2026-11-22", listOf(
                Item(Icons.Rounded.Lock, R.string.cl480_private, R.string.cl480_private_desc) { Neon.Pink },
                Item(Icons.Rounded.StopCircle, R.string.cl480_stop, R.string.cl480_stop_desc) { Neon.Amber },
                Item(Icons.Rounded.HighQuality, R.string.cl480_quality, R.string.cl480_quality_desc) { Neon.Cyan },
                Item(Icons.Rounded.BugReport, R.string.cl480_report, R.string.cl480_report_desc) { Neon.Violet },
                Item(Icons.Rounded.Shield, R.string.cl480_safety, R.string.cl480_safety_desc) { Neon.Lime },
            )
        ),
        Version(
            "4.7.0", "2026-11-20", listOf(
                Item(Icons.Rounded.SelfImprovement, R.string.cl470_focus, R.string.cl470_focus_desc) { Neon.Lime },
                Item(Icons.Rounded.FilterList, R.string.cl470_ads, R.string.cl470_ads_desc) { Neon.Amber },
                Item(Icons.Rounded.LinearScale, R.string.cl470_seek, R.string.cl470_seek_desc) { Neon.Cyan },
                Item(Icons.Rounded.VerifiedUser, R.string.cl470_verify, R.string.cl470_verify_desc) { Neon.Pink },
            )
        ),
        Version(
            "4.6.0", "2026-11-19", listOf(
                Item(Icons.Rounded.Subtitles, R.string.cl460_chat, R.string.cl460_chat_desc) { Neon.Pink },
                Item(Icons.Rounded.Redeem, R.string.cl460_gifts, R.string.cl460_gifts_desc) { Neon.Amber },
                Item(Icons.Rounded.NotificationsActive, R.string.cl460_notifs, R.string.cl460_notifs_desc) { Neon.Lime },
            )
        ),
        Version(
            "4.5.1", "2026-11-19", listOf(
                Item(Icons.Rounded.Forum, R.string.cl451_chat, R.string.cl451_chat_desc) { Neon.Pink },
            )
        ),
        Version(
            "4.5.0", "2026-11-19", listOf(
                Item(Icons.Rounded.FilterList, R.string.cl450_feed, R.string.cl450_feed_desc) { Neon.Amber },
                Item(Icons.Rounded.Speed, R.string.cl450_play, R.string.cl450_play_desc) { Neon.Cyan },
                Item(Icons.Rounded.Tune, R.string.cl450_more, R.string.cl450_more_desc) { Neon.Lime },
            )
        ),
        Version(
            "4.4.0", "2026-11-19", listOf(
                Item(Icons.Rounded.Forum, R.string.cl440_chat, R.string.cl440_chat_desc) { Neon.Pink },
                Item(Icons.Rounded.OpenWith, R.string.cl440_move, R.string.cl440_move_desc) { Neon.Cyan },
                Item(Icons.Rounded.Tune, R.string.cl440_style, R.string.cl440_style_desc) { Neon.Violet },
            )
        ),
        Version(
            "4.3.0", "2026-11-18", listOf(
                Item(Icons.Rounded.ClosedCaption, R.string.cl430_captions, R.string.cl430_captions_desc) { Neon.Cyan },
                Item(Icons.Rounded.GraphicEq, R.string.cl430_languages, R.string.cl430_languages_desc) { Neon.Violet },
                Item(Icons.Rounded.History, R.string.cl430_history, R.string.cl430_history_desc) { Neon.Pink },
            )
        ),
        Version(
            "4.2.1", "2026-11-18", listOf(
                Item(Icons.Rounded.PlayCircle, R.string.cl421_resume, R.string.cl421_resume_desc) { Neon.Pink },
                Item(Icons.Rounded.VerifiedUser, R.string.cl421_verify, R.string.cl421_verify_desc) { Neon.Lime },
            )
        ),
        Version(
            "4.2.0", "2026-11-18", listOf(
                Item(Icons.Rounded.History, R.string.cl420_history, R.string.cl420_history_desc) { Neon.Pink },
                Item(Icons.Rounded.VisibilityOff, R.string.cl420_online, R.string.cl420_online_desc) { Neon.Violet },
                Item(Icons.Rounded.PlayDisabled, R.string.cl420_views, R.string.cl420_views_desc) { Neon.Amber },
            )
        ),
        Version(
            "4.1.0", "2026-11-18", listOf(
                Item(Icons.Rounded.Translate, R.string.cl410_lang, R.string.cl410_lang_desc) { Neon.Cyan },
                Item(Icons.Rounded.FiberManualRecord, R.string.cl410_record, R.string.cl410_record_desc) { Neon.Pink },
                Item(Icons.Rounded.Shield, R.string.cl410_stable, R.string.cl410_stable_desc) { Neon.Lime },
                Item(Icons.Rounded.SdStorage, R.string.cl410_storage, R.string.cl410_storage_desc) { Neon.Amber },
                Item(Icons.Rounded.DarkMode, R.string.cl410_dark, R.string.cl410_dark_desc) { Neon.Violet },
                Item(Icons.Rounded.SystemUpdate, R.string.cl410_updates, R.string.cl410_updates_desc) { Neon.Cyan },
            )
        ),
        Version(
            "4.0.0", "2026-11-17", listOf(
                Item(Icons.Rounded.AutoAwesome, R.string.cl400_ui, R.string.cl400_ui_desc) { Neon.Violet },
                Item(Icons.Rounded.Sensors, R.string.cl400_autolive, R.string.cl400_autolive_desc) { Neon.Pink },
                Item(Icons.Rounded.DeleteSweep, R.string.cl400_deleted, R.string.cl400_deleted_desc) { Neon.Violet },
                Item(Icons.Rounded.Share, R.string.cl400_share, R.string.cl400_share_desc) { Neon.Cyan },
                Item(Icons.Rounded.VisibilityOff, R.string.cl400_privacy, R.string.cl400_privacy_desc) { Neon.Violet },
                Item(Icons.Rounded.Block, R.string.cl400_feed, R.string.cl400_feed_desc) { Neon.Amber },
            )
        ),
    )

    fun forVersion(name: String): Version? = versions.firstOrNull { it.name == name }
}

package com.mts.tikmod

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.mts.tikmod.discovery.Discovery
import com.mts.tikmod.utils.XposedXPref
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for every TikMod setting.
 *
 * Every key is declared once in [SPECS] with its default value. The provider, the cache,
 * export/import and defaults all iterate that registry, so adding an option is a one-line change.
 */
object Prefs {
    const val PREF_NAME = "tikmod_preferences"
    const val ACTION_SETTINGS_CHANGED = "com.mts.tikmod.ACTION_SETTINGS_CHANGED"
    const val PROVIDER_URI = "content://com.mts.tikmod.provider"

    // Core Keys
    const val KEY_ANTI_RECALL = "key_anti_recall"
    const val KEY_REMOVE_WATERMARK = "key_remove_watermark"
    const val KEY_FORCE_DOWNLOAD = "key_force_download"
    const val KEY_TARGET_REGION = "key_target_region"
    const val KEY_REMOVE_ADS = "key_remove_ads"
    const val KEY_UNRESTRICT_ALL = "key_unrestrict_all"

    // Download Keys
    const val KEY_DOWNLOAD_AUDIO = "key_download_audio"
    const val KEY_DOWNLOAD_COVER = "key_download_cover"
    const val KEY_NOTIFICATION_PROGRESS = "key_notification_progress"

    const val KEY_DOWNLOAD_UI_MODE = "key_download_ui_mode"
    const val DOWNLOAD_UI_MODE_REPLACE = "replace"
    const val DOWNLOAD_UI_MODE_ADD = "add"

    const val KEY_DOWNLOAD_QUALITY = "key_download_quality"
    const val DOWNLOAD_QUALITY_MAX = "max"
    const val DOWNLOAD_QUALITY_HD = "hd"
    const val DOWNLOAD_QUALITY_SD = "sd"

    // New download options (v3)
    const val KEY_DOWNLOAD_PHOTOS = "key_download_photos"
    const val KEY_VIDEO_CODEC = "key_video_codec"
    const val VIDEO_CODEC_BEST = "best"
    const val VIDEO_CODEC_H264 = "h264"

    const val KEY_SAVE_LOCATION = "key_save_location"
    const val SAVE_LOCATION_MOVIES = "movies"
    const val SAVE_LOCATION_DCIM = "dcim"
    const val SAVE_LOCATION_DOWNLOAD = "download"

    const val KEY_AUTHOR_FOLDERS = "key_author_folders"
    const val KEY_SKIP_DUPLICATES = "key_skip_duplicates"
    const val KEY_COPY_CAPTION = "key_copy_caption"
    const val KEY_COPY_LINK_BUTTON = "key_copy_link_button"
    const val KEY_DOWNLOAD_AVATAR = "key_download_avatar"
    const val KEY_DOWNLOAD_HISTORY = "key_download_history"

    const val KEY_AUDIO_SOURCE = "key_audio_source"
    const val AUDIO_SOURCE_ORIGINAL = "original"
    const val AUDIO_SOURCE_VIDEO = "video"

    // Smart share: what TikTok's "share to app" sends
    const val KEY_SHARE_MODE = "key_share_mode"
    const val SHARE_MODE_MEDIA = "media"            // the file itself
    const val SHARE_MODE_MEDIA_LINK = "media_link"  // file + link as caption
    const val SHARE_MODE_LINK = "link"              // regular TikTok behaviour

    // Feed cleaner
    const val KEY_HIDE_COMMENT_ADS = "key_hide_comment_ads"
    const val KEY_BLOCK_SPLASH_ADS = "key_block_splash_ads"
    const val KEY_HIDE_FEED_LIVE = "key_hide_feed_live"
    const val KEY_HIDE_SHOP = "key_hide_shop"
    const val KEY_HIDE_PAID_PARTNERSHIP = "key_hide_paid_partnership"
    const val KEY_HIDE_PHOTO_POSTS = "key_hide_photo_posts"
    const val STAT_ADS_BLOCKED = "stat_ads_blocked"

    const val KEY_IMAGES_AS_JPEG = "key_images_as_jpeg"

    // Chat privacy
    const val KEY_HIDE_ONLINE_STATUS = "key_hide_online_status"

    // Auto LIVE recorder (runs in TikMod's own app, see LiveWatchService)
    const val KEY_AUTO_LIVE = "key_auto_live"
    const val KEY_AUTO_LIVE_CHANNELS = "key_auto_live_channels"   // comma-separated handles
    const val KEY_AUTO_LIVE_TRIGGER = "key_auto_live_trigger"
    const val AUTO_LIVE_TRIGGER_START = "start"   // only when a broadcast starts while we watch
    const val AUTO_LIVE_TRIGGER_ANY = "any"       // also join a broadcast that is already running
    const val KEY_AUTO_LIVE_NOTIFY = "key_auto_live_notify"
    const val AUTO_LIVE_NOTIFY_PROGRESS = "progress" // live time + size, stop button
    const val AUTO_LIVE_NOTIFY_MINIMAL = "minimal"   // only "started" and "saved"
    const val KEY_AUTO_LIVE_INTERVAL = "key_auto_live_interval" // seconds
    const val KEY_AUTO_LIVE_QUALITY = "key_auto_live_quality"   // origin | uhd | hd | sd
    const val KEY_APP_LANGUAGE = "key_app_language"             // "system" or a language tag (see L.LANGUAGES)
    const val KEY_AUTO_UPDATE_CHECK = "key_auto_update_check"
    const val KEY_LIVE_MAX_PARALLEL = "key_live_max_parallel"   // 0 = unlimited
    const val KEY_AUTO_LIVE_STATE = "auto_live_state_json"      // runtime status for the UI (not exported)

    // App look
    const val KEY_UI_THEME = "key_ui_theme" // neon | dynamic | system

    // Feed / player
    const val KEY_AUTO_SCROLL = "key_auto_scroll"
    const val KEY_PURE_MODE = "key_pure_mode"
    const val KEY_PLAYBACK_SPEED = "key_playback_speed"
    const val KEY_FORCE_SEEKBAR = "key_force_seekbar"
    const val KEY_DISABLE_AUTO_PAUSE = "key_disable_auto_pause"
    const val KEY_BLOCK_LIVE_GIFTS = "key_block_live_gifts"

    // Privacy
    const val KEY_DISABLE_READ_RECEIPTS = "key_disable_read_receipts"
    const val KEY_DISABLE_TYPING_INDICATOR = "key_disable_typing_indicator"
    const val KEY_READ_RECEIPTS_MODE = "key_read_receipts_mode"
    const val READ_RECEIPTS_MODE_ALWAYS_BLOCK = "always_block"
    const val READ_RECEIPTS_MODE_ON_REPLY = "on_reply"
    const val READ_RECEIPTS_MODE_NORMAL = "normal"

    const val KEY_BLOCK_ANALYTICS = "key_block_analytics"
    const val KEY_UNLOCK_HD_UPLOADS = "key_unlock_hd_uploads"
    const val KEY_UNRESTRICT_AUDIO = "key_unrestrict_audio"
    const val KEY_SAVE_DELETED_MEDIA = "key_save_deleted_media"
    const val KEY_CUSTOM_FILENAME_PATTERN = "key_custom_filename_pattern"
    const val DEFAULT_FILENAME_PATTERN = "{author}_{id}"

    const val KEY_HISTORY_JSON = "key_download_history_json"
    private const val KEY_LAST_PING = "last_active_ping"

    /** Registry: key -> default (Boolean or String). Order is the export order. */
    val SPECS: LinkedHashMap<String, Any> = linkedMapOf(
        KEY_ANTI_RECALL to true,
        KEY_REMOVE_WATERMARK to true,
        KEY_FORCE_DOWNLOAD to true,
        KEY_TARGET_REGION to "US",
        KEY_REMOVE_ADS to true,
        KEY_UNRESTRICT_ALL to true,
        KEY_DOWNLOAD_AUDIO to true,
        KEY_DOWNLOAD_COVER to true,
        KEY_NOTIFICATION_PROGRESS to true,
        KEY_DOWNLOAD_UI_MODE to DOWNLOAD_UI_MODE_ADD,
        KEY_DOWNLOAD_QUALITY to DOWNLOAD_QUALITY_MAX,
        KEY_DOWNLOAD_PHOTOS to true,
        KEY_VIDEO_CODEC to VIDEO_CODEC_BEST,
        KEY_SAVE_LOCATION to SAVE_LOCATION_MOVIES,
        KEY_AUTHOR_FOLDERS to false,
        KEY_SKIP_DUPLICATES to true,
        KEY_COPY_CAPTION to false,
        KEY_COPY_LINK_BUTTON to true,
        KEY_DOWNLOAD_AVATAR to true,
        KEY_DOWNLOAD_HISTORY to true,
        KEY_AUDIO_SOURCE to AUDIO_SOURCE_ORIGINAL,
        KEY_SHARE_MODE to SHARE_MODE_MEDIA,
        KEY_UI_THEME to "neon",
        KEY_HIDE_ONLINE_STATUS to false,
        KEY_IMAGES_AS_JPEG to true,
        KEY_HIDE_COMMENT_ADS to true,
        KEY_BLOCK_SPLASH_ADS to true,
        KEY_HIDE_FEED_LIVE to false,
        KEY_HIDE_SHOP to false,
        KEY_HIDE_PAID_PARTNERSHIP to false,
        KEY_HIDE_PHOTO_POSTS to false,
        KEY_AUTO_SCROLL to false,
        KEY_PURE_MODE to false,
        KEY_PLAYBACK_SPEED to "1.0",
        KEY_FORCE_SEEKBAR to true,
        KEY_DISABLE_AUTO_PAUSE to true,
        KEY_BLOCK_LIVE_GIFTS to true,
        KEY_DISABLE_READ_RECEIPTS to true,
        KEY_DISABLE_TYPING_INDICATOR to true,
        KEY_READ_RECEIPTS_MODE to READ_RECEIPTS_MODE_ON_REPLY,
        KEY_BLOCK_ANALYTICS to true,
        KEY_UNLOCK_HD_UPLOADS to true,
        KEY_UNRESTRICT_AUDIO to true,
        KEY_SAVE_DELETED_MEDIA to true,
        KEY_CUSTOM_FILENAME_PATTERN to DEFAULT_FILENAME_PATTERN,
        KEY_AUTO_LIVE to false,
        KEY_AUTO_LIVE_CHANNELS to "",
        KEY_AUTO_LIVE_TRIGGER to AUTO_LIVE_TRIGGER_ANY,
        KEY_AUTO_LIVE_NOTIFY to AUTO_LIVE_NOTIFY_PROGRESS,
        KEY_AUTO_LIVE_INTERVAL to "60",
        KEY_AUTO_LIVE_QUALITY to "origin",
        KEY_LIVE_MAX_PARALLEL to "0",
        KEY_AUTO_UPDATE_CHECK to true,
        KEY_APP_LANGUAGE to "system"
    )

    @Volatile private var cache: Map<String, Any> = HashMap(SPECS)
    @Volatile private var lastCacheTime = 0L

    private fun getSP(context: Context): SharedPreferences? =
        runCatching { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }.getOrNull()

    private fun isOwnProcess(context: Context) = context.packageName == BuildConfig.APPLICATION_ID

    /** Reads one key from SharedPreferences with type safety (a type change never crashes). */
    private fun readTyped(sp: SharedPreferences, key: String, def: Any): Any = runCatching {
        when (def) {
            is Boolean -> sp.getBoolean(key, def)
            else -> sp.getString(key, def as String) ?: def
        }
    }.getOrDefault(def)

    /** Snapshot of all settings as a Bundle (used by the ContentProvider). */
    fun snapshotBundle(sp: SharedPreferences): Bundle = Bundle().apply {
        for ((key, def) in SPECS) {
            when (val v = readTyped(sp, key, def)) {
                is Boolean -> putBoolean(key, v)
                else -> putString(key, v as String)
            }
        }
    }

    fun initDefaultsIfNeeded(context: Context, doRefresh: Boolean = true) {
        runCatching {
            val sp = getSP(context) ?: return
            val editor = sp.edit()
            var changed = false
            for ((key, def) in SPECS) {
                if (!sp.contains(key)) {
                    changed = true
                    when (def) {
                        is Boolean -> editor.putBoolean(key, def)
                        else -> editor.putString(key, def as String)
                    }
                }
            }
            // One-time v3 migration: v1 silently stored "TikTok_{id}" (never shown in its UI). Move users still
            // on that old default to the new "{author}_{id}"; a pattern the user picked is left alone.
            if (!sp.getBoolean("migrated_v3", false)) {
                if (sp.getString(KEY_CUSTOM_FILENAME_PATTERN, null) == "TikTok_{id}") {
                    editor.putString(KEY_CUSTOM_FILENAME_PATTERN, DEFAULT_FILENAME_PATTERN)
                }
                editor.putBoolean("migrated_v3", true)
                changed = true
            }
            if (changed) editor.apply()
            if (doRefresh) refreshCache(context, force = true)
        }
    }

    /**
     * Context of the hooked app, set by HookEntry once TikTok's Application exists. Hooks are installed
     * early (sometimes with a null context), so every read falls back to this one.
     */
    @Volatile var hostContext: Context? = null

    fun refreshCache(contextArg: Context?, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCacheTime) < 2000) return
        lastCacheTime = now
        val context = contextArg ?: hostContext

        runCatching {
            if (context != null) {
                if (isOwnProcess(context)) {
                    val sp = getSP(context)
                    if (sp != null) {
                        cache = SPECS.mapValues { (k, def) -> readTyped(sp, k, def) }
                        return
                    }
                } else {
                    val remote = runCatching { getRemoteBundle(context) }.getOrNull()
                    if (remote != null && remote.containsKey(KEY_ANTI_RECALL)) {
                        cache = SPECS.mapValues { (k, def) ->
                            @Suppress("DEPRECATION")
                            val v = remote.get(k)
                            if (v != null && v::class == def::class) v else def
                        }
                        return
                    }
                }
            }

            // Fallback: XSharedPreferences (only meaningful inside a hooked process)
            cache = SPECS.mapValues { (k, def) ->
                runCatching {
                    when (def) {
                        is Boolean -> XposedXPref.getBoolean(k, def)
                        else -> XposedXPref.getString(k, def as String)
                    }
                }.getOrDefault(def)
            }
        }
    }

    private fun getRemoteBundle(context: Context?): Bundle? {
        if (context == null) return null
        return runCatching {
            context.contentResolver.call(Uri.parse(PROVIDER_URI), "get_prefs", null, null)
        }.getOrNull()
    }

    fun pingActive(context: Context?) {
        if (context == null) return
        runCatching { context.contentResolver.call(Uri.parse(PROVIDER_URI), "ping_active", null, null) }
    }

    fun notifySettingsChanged(context: Context) {
        runCatching {
            refreshCache(context, force = true)
            context.sendBroadcast(Intent(ACTION_SETTINGS_CHANGED))
        }
    }

    // ---- Generic accessors ----
    fun bool(context: Context?, key: String): Boolean {
        refreshCache(context)
        return cache[key] as? Boolean ?: (SPECS[key] as? Boolean ?: false)
    }

    fun string(context: Context?, key: String): String {
        refreshCache(context)
        return cache[key] as? String ?: (SPECS[key] as? String ?: "")
    }

    fun setBool(context: Context, key: String, value: Boolean) {
        runCatching { getSP(context)?.edit()?.putBoolean(key, value)?.apply(); notifySettingsChanged(context) }
    }

    fun setString(context: Context, key: String, value: String) {
        runCatching { getSP(context)?.edit()?.putString(key, value)?.apply(); notifySettingsChanged(context) }
    }

    // ---- Typed getters (kept for the hooks) ----
    fun isAntiRecall(context: Context? = null) = bool(context, KEY_ANTI_RECALL)
    fun isRemoveWatermark(context: Context? = null) = bool(context, KEY_REMOVE_WATERMARK)
    fun isForceDownload(context: Context? = null) = bool(context, KEY_FORCE_DOWNLOAD)
    fun getRegion(context: Context? = null) = string(context, KEY_TARGET_REGION)
    fun isRemoveAds(context: Context? = null) = bool(context, KEY_REMOVE_ADS)
    fun isUnrestrictAll(context: Context? = null) = bool(context, KEY_UNRESTRICT_ALL)
    fun isDownloadAudio(context: Context? = null) = bool(context, KEY_DOWNLOAD_AUDIO)
    fun isDownloadCover(context: Context? = null) = bool(context, KEY_DOWNLOAD_COVER)
    fun isNotificationProgress(context: Context? = null) = bool(context, KEY_NOTIFICATION_PROGRESS)
    fun getDownloadUiMode(context: Context? = null) = string(context, KEY_DOWNLOAD_UI_MODE)
    fun getDownloadQuality(context: Context? = null) = string(context, KEY_DOWNLOAD_QUALITY)
    fun isDownloadPhotos(context: Context? = null) = bool(context, KEY_DOWNLOAD_PHOTOS)
    fun getVideoCodec(context: Context? = null) = string(context, KEY_VIDEO_CODEC)
    fun getSaveLocation(context: Context? = null) = string(context, KEY_SAVE_LOCATION)
    fun isAuthorFolders(context: Context? = null) = bool(context, KEY_AUTHOR_FOLDERS)
    fun isSkipDuplicates(context: Context? = null) = bool(context, KEY_SKIP_DUPLICATES)
    fun isCopyCaption(context: Context? = null) = bool(context, KEY_COPY_CAPTION)
    fun isCopyLinkButton(context: Context? = null) = bool(context, KEY_COPY_LINK_BUTTON)
    fun isDownloadAvatar(context: Context? = null) = bool(context, KEY_DOWNLOAD_AVATAR)
    fun isDownloadHistory(context: Context? = null) = bool(context, KEY_DOWNLOAD_HISTORY)
    fun getAudioSource(context: Context? = null) = string(context, KEY_AUDIO_SOURCE)
    fun getShareMode(context: Context? = null) = string(context, KEY_SHARE_MODE)
    fun isAutoScroll(context: Context? = null) = bool(context, KEY_AUTO_SCROLL)
    fun isPureMode(context: Context? = null) = bool(context, KEY_PURE_MODE)
    fun getPlaybackSpeed(context: Context? = null) = string(context, KEY_PLAYBACK_SPEED)
    fun isForceSeekbar(context: Context? = null) = bool(context, KEY_FORCE_SEEKBAR)
    fun isDisableAutoPause(context: Context? = null) = bool(context, KEY_DISABLE_AUTO_PAUSE)
    fun isBlockLiveGifts(context: Context? = null) = bool(context, KEY_BLOCK_LIVE_GIFTS)
    fun isDisableReadReceipts(context: Context? = null) = getReadReceiptsMode(context) != READ_RECEIPTS_MODE_NORMAL
    fun getReadReceiptsMode(context: Context? = null) = string(context, KEY_READ_RECEIPTS_MODE)
    fun isDisableTypingIndicator(context: Context? = null) = bool(context, KEY_DISABLE_TYPING_INDICATOR)
    fun isBlockAnalytics(context: Context? = null) = bool(context, KEY_BLOCK_ANALYTICS)
    fun isUnlockHDUploads(context: Context? = null) = bool(context, KEY_UNLOCK_HD_UPLOADS)
    fun isUnrestrictAudio(context: Context? = null) = bool(context, KEY_UNRESTRICT_AUDIO)
    fun isSaveDeletedMedia(context: Context? = null) = bool(context, KEY_SAVE_DELETED_MEDIA)
    fun getCustomFilenamePattern(context: Context? = null) =
        string(context, KEY_CUSTOM_FILENAME_PATTERN).ifBlank { DEFAULT_FILENAME_PATTERN }

    fun isModuleActive(context: Context): Boolean = runCatching {
        val day = 24 * 60 * 60 * 1000L
        val filePing = runCatching {
            val file = File("/data/local/tmp/tikmod_active.ping")
            file.exists() && (System.currentTimeMillis() - (file.readText().trim().toLongOrNull() ?: 0L)) < day
        }.getOrDefault(false)
        if (filePing) return true

        if (isOwnProcess(context)) {
            val lastPing = getSP(context)?.getLong(KEY_LAST_PING, 0L) ?: 0L
            return (System.currentTimeMillis() - lastPing) < day
        }
        getRemoteBundle(context)?.getBoolean("module_active", false) ?: false
    }.getOrDefault(false)

    // ---- Legacy setters (kept so existing UI code compiles) ----
    fun setAntiRecallEnabled(c: Context, v: Boolean) = setBool(c, KEY_ANTI_RECALL, v)
    fun setRemoveWatermarkEnabled(c: Context, v: Boolean) = setBool(c, KEY_REMOVE_WATERMARK, v)
    fun setForceDownloadEnabled(c: Context, v: Boolean) = setBool(c, KEY_FORCE_DOWNLOAD, v)
    fun setTargetRegion(c: Context, v: String) = setString(c, KEY_TARGET_REGION, v)
    fun setRemoveAdsEnabled(c: Context, v: Boolean) = setBool(c, KEY_REMOVE_ADS, v)
    fun setUnrestrictAllEnabled(c: Context, v: Boolean) = setBool(c, KEY_UNRESTRICT_ALL, v)
    fun setAutoScrollEnabled(c: Context, v: Boolean) = setBool(c, KEY_AUTO_SCROLL, v)
    fun setPureModeEnabled(c: Context, v: Boolean) = setBool(c, KEY_PURE_MODE, v)
    fun setPlaybackSpeed(c: Context, v: String) = setString(c, KEY_PLAYBACK_SPEED, v)
    fun setForceSeekbarEnabled(c: Context, v: Boolean) = setBool(c, KEY_FORCE_SEEKBAR, v)
    fun setDisableAutoPauseEnabled(c: Context, v: Boolean) = setBool(c, KEY_DISABLE_AUTO_PAUSE, v)
    fun setBlockLiveGiftsEnabled(c: Context, v: Boolean) = setBool(c, KEY_BLOCK_LIVE_GIFTS, v)
    fun setReadReceiptsMode(c: Context, v: String) = setString(c, KEY_READ_RECEIPTS_MODE, v)
    fun setDisableTypingIndicatorEnabled(c: Context, v: Boolean) = setBool(c, KEY_DISABLE_TYPING_INDICATOR, v)
    fun setBlockAnalyticsEnabled(c: Context, v: Boolean) = setBool(c, KEY_BLOCK_ANALYTICS, v)
    fun setUnlockHDUploadsEnabled(c: Context, v: Boolean) = setBool(c, KEY_UNLOCK_HD_UPLOADS, v)
    fun setUnrestrictAudioEnabled(c: Context, v: Boolean) = setBool(c, KEY_UNRESTRICT_AUDIO, v)
    fun setSaveDeletedMediaEnabled(c: Context, v: Boolean) = setBool(c, KEY_SAVE_DELETED_MEDIA, v)

    // ---- Auto LIVE channels ----
    fun autoLiveChannels(context: Context): List<String> =
        string(context, KEY_AUTO_LIVE_CHANNELS).split(',').map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotBlank() }.distinct()

    fun setAutoLiveChannels(context: Context, list: List<String>) =
        setString(context, KEY_AUTO_LIVE_CHANNELS, list.map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(","))

    fun readAutoLiveState(context: Context): JSONObject =
        runCatching { JSONObject(getSP(context)?.getString(KEY_AUTO_LIVE_STATE, "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun writeAutoLiveState(context: Context, json: JSONObject) {
        getSP(context)?.edit()?.putString(KEY_AUTO_LIVE_STATE, json.toString())?.apply()
    }

    // ---- Download history (stored in TikMod's own prefs, written via the provider) ----
    fun appendHistory(sp: SharedPreferences, entry: JSONObject) {
        synchronized(this) {
            val arr = runCatching { org.json.JSONArray(sp.getString(KEY_HISTORY_JSON, "[]")) }
                .getOrDefault(org.json.JSONArray())
            val out = org.json.JSONArray().put(entry)
            for (i in 0 until minOf(arr.length(), 199)) out.put(arr.get(i))
            sp.edit().putString(KEY_HISTORY_JSON, out.toString()).apply()
        }
    }

    fun readHistory(context: Context): List<JSONObject> {
        val sp = getSP(context) ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(sp.getString(KEY_HISTORY_JSON, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun addStat(sp: SharedPreferences, key: String, n: Int) {
        synchronized(this) { sp.edit().putLong(key, sp.getLong(key, 0L) + n).apply() }
    }

    fun readStat(context: Context, key: String): Long = getSP(context)?.getLong(key, 0L) ?: 0L

    fun clearHistory(context: Context) {
        getSP(context)?.edit()?.putString(KEY_HISTORY_JSON, "[]")?.apply()
    }

    // ---- Export & Import ----
    fun exportToJson(context: Context): String {
        refreshCache(context, force = true)
        val json = JSONObject().put("version", 3)
        for (key in SPECS.keys) json.put(key, cache[key])
        return json.toString(2)
    }

    fun importFromJson(context: Context, jsonStr: String): Boolean = runCatching {
        val json = JSONObject(jsonStr)
        val editor = getSP(context)?.edit() ?: return false
        for ((key, def) in SPECS) {
            if (!json.has(key)) continue
            when (def) {
                is Boolean -> editor.putBoolean(key, json.getBoolean(key))
                else -> editor.putString(key, json.getString(key))
            }
        }
        editor.apply()
        notifySettingsChanged(context)
        true
    }.getOrDefault(false)

    fun forceRestartTikTok(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses("com.zhiliaoapp.musically")
            am?.killBackgroundProcesses("com.ss.android.ugc.trill")
        }
    }

    fun resetDiscoveryCache(context: Context) {
        runCatching { Discovery.resetCache(context) }
        runCatching { context.contentResolver.call(Uri.parse(PROVIDER_URI), "reset_discovery", null, null) }
    }
}

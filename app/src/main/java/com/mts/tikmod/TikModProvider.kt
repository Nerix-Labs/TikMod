package com.mts.tikmod

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.mts.tikmod.discovery.Discovery
import org.json.JSONObject

/**
 * Cross-process bridge between the hooked TikTok process and the TikMod app.
 * - get_prefs      -> every setting from [Prefs.SPECS]
 * - ping_active    -> marks the module as alive
 * - add_history    -> appends a finished download to the in-app history
 * - reset_discovery
 */
class TikModProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return try {
            val ctx = context ?: return Bundle()
            val prefs = ctx.getSharedPreferences(Prefs.PREF_NAME, Context.MODE_PRIVATE)

            when (method) {
                "get_prefs" -> {
                    Prefs.initDefaultsIfNeeded(ctx, doRefresh = false)
                    Prefs.snapshotBundle(prefs).apply {
                        val lastPing = prefs.getLong("last_active_ping", 0L)
                        putBoolean("module_active", (System.currentTimeMillis() - lastPing) < 24 * 60 * 60 * 1000L)
                    }
                }
                "ping_active" -> {
                    prefs.edit().putLong("last_active_ping", System.currentTimeMillis()).apply()
                    Bundle().apply { putBoolean("success", true) }
                }
                "add_history" -> {
                    val json = arg ?: extras?.getString("entry")
                    if (!json.isNullOrBlank()) {
                        runCatching { Prefs.appendHistory(prefs, JSONObject(json)) }
                    }
                    Bundle().apply { putBoolean("success", true) }
                }
                "add_live_history" -> {
                    if (!arg.isNullOrBlank() && prefs.getBoolean(Prefs.KEY_LIVE_HISTORY, true)) {
                        runCatching { Prefs.appendLiveHistory(prefs, JSONObject(arg)) }
                    }
                    Bundle().apply { putBoolean("success", true) }
                }
                "add_stat" -> {
                    val key = arg
                    if (key != null && key.startsWith("stat_")) Prefs.addStat(prefs, key, extras?.getInt("n", 1) ?: 1)
                    Bundle().apply { putBoolean("success", true) }
                }
                // LIVE recordings run in TikMod's own service, so they survive TikTok being closed/swiped away.
                "live_recording" -> {
                    val st = Prefs.readAutoLiveState(ctx)
                    val running = com.mts.tikmod.live.LiveWatchService.isRunning
                    val handles = ArrayList<String>()
                    if (running) st.keys().forEach { k -> if (st.optJSONObject(k)?.optString("status") == "recording") handles.add(k) }
                    Bundle().apply { putStringArrayList("handles", handles) }
                }
                "start_live_record" -> {
                    val ok = runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(ctx,
                            android.content.Intent(ctx, com.mts.tikmod.live.LiveWatchService::class.java)
                                .setAction(com.mts.tikmod.live.LiveWatchService.ACTION_RECORD).putExtras(extras ?: Bundle()))
                    }.isSuccess
                    Bundle().apply { putBoolean("success", ok) }
                }
                "stop_live_record" -> {
                    runCatching {
                        ctx.startService(android.content.Intent(ctx, com.mts.tikmod.live.LiveWatchService::class.java)
                            .setAction(com.mts.tikmod.live.LiveWatchService.ACTION_STOP_REC)
                            .putExtra(com.mts.tikmod.live.LiveWatchService.EXTRA_HANDLE, arg))
                    }
                    Bundle().apply { putBoolean("success", true) }
                }
                // Live captions: the recognizer and the translator run in TikMod's own process.
                "captions_start" -> {
                    com.mts.tikmod.captions.CaptionService.command(ctx, com.mts.tikmod.captions.CaptionService.ACTION_START, extras)
                    Bundle().apply { putBoolean("success", true) }
                }
                "captions_ping" -> {
                    com.mts.tikmod.captions.CaptionService.command(ctx, com.mts.tikmod.captions.CaptionService.ACTION_PING, null)
                    Bundle().apply { putBoolean("running", com.mts.tikmod.captions.CaptionService.isRunning) }
                }
                "captions_source" -> {
                    com.mts.tikmod.captions.CaptionService.command(ctx, com.mts.tikmod.captions.CaptionService.ACTION_SOURCE, extras)
                    Bundle().apply { putBoolean("success", true) }
                }
                "captions_download" -> {
                    com.mts.tikmod.captions.CaptionService.command(ctx, com.mts.tikmod.captions.CaptionService.ACTION_DOWNLOAD, null)
                    Bundle().apply { putBoolean("success", true) }
                }
                // A caption setting changed from the quick menu on the stream.
                "caption_pref" -> {
                    val key = extras?.getString("key")
                    val allowed = setOf(Prefs.KEY_LIVE_CAPTIONS, Prefs.KEY_CAPTION_TARGET, Prefs.KEY_CAPTION_ORIGINAL,
                        Prefs.KEY_CAPTION_SIZE, Prefs.KEY_CAPTION_BG, Prefs.KEY_CAPTION_LINES, Prefs.KEY_CAPTION_OUTLINE, Prefs.KEY_CAPTION_ALWAYS, Prefs.KEY_RECORD_CHAT)
                    if (key != null && key in allowed) {
                        if (extras.containsKey("bool")) Prefs.setBool(ctx, key, extras.getBoolean("bool"))
                        else Prefs.setString(ctx, key, extras.getString("value").orEmpty())
                        com.mts.tikmod.captions.CaptionService.command(ctx, com.mts.tikmod.captions.CaptionService.ACTION_REFRESH, null)
                    }
                    Bundle().apply { putBoolean("success", key != null && key in allowed) }
                }
                "captions_transcript" -> Bundle().apply { putString("text", com.mts.tikmod.captions.CaptionService.transcript()) }
                "captions_stop" -> {
                    com.mts.tikmod.captions.CaptionService.command(ctx, com.mts.tikmod.captions.CaptionService.ACTION_STOP, null)
                    Bundle().apply { putBoolean("success", true) }
                }
                "reset_discovery" -> {
                    Discovery.resetCache(ctx)
                    Bundle().apply { putBoolean("success", true) }
                }
                else -> Bundle()
            }
        } catch (t: Throwable) {
            Bundle()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

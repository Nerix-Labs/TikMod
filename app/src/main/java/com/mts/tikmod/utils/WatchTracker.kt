package com.mts.tikmod.utils

import android.net.Uri
import com.mts.tikmod.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch history, inside TikTok's process: each post the feed puts on screen goes to TikMod
 * ([com.mts.tikmod.history.WatchHistory]) with its author, caption and cover, plus how long the post before it
 * stayed on screen. Ads, stories and LIVE previews are left out. Nothing leaves the phone.
 */
object WatchTracker {

    private val bg by lazy { android.os.HandlerThread("TikMod-Watch").apply { start() }.let { android.os.Handler(it.looper) } }

    @Volatile private var lastAid: String? = null
    @Volatile private var lastAt = 0L

    /** The feed selected this post (main thread: only cheap work here). */
    fun onSelected(aweme: Any?) {
        aweme ?: return
        val ctx = Prefs.hostContext ?: return
        val aid = runCatching { Reflect.string(aweme, "getAid", "aid") }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (aid == lastAid && now - lastAt < 5_000) return // the same post selected again (layout pass, back from a sheet)
        val prev = lastAid
        val prevMs = now - lastAt
        lastAid = aid
        lastAt = now
        bg.post {
            runCatching {
                if (!Prefs.bool(ctx, Prefs.KEY_WATCH_HISTORY)) return@post
                if (Reflect.call(aweme, "isAd") == true) return@post
                if (Reflect.call(aweme, "getIsTikTokStory") == true) return@post
                val m = MediaResolver.resolve(aweme) ?: return@post
                if (m.isStory || (!m.hasVideo && !m.isPhotoPost)) return@post // LIVE previews have neither
                val entry = JSONObject()
                    .put("aid", m.awemeId.ifBlank { aid })
                    .put("handle", m.authorHandle.ifBlank { m.authorUniqueId })
                    .put("nick", m.authorNickname)
                    .put("desc", m.desc.take(300))
                    .put("photo", m.isPhotoPost)
                    .put("dur", m.durationMs.toLong())
                    .put("cover", JSONArray(m.coverUrls.take(3)))
                if (prev != null && prev != aid) entry.put("prev", prev).put("prevMs", prevMs)
                ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "add_watch", entry.toString(), null)
            }.onFailure { XLog.log("WatchHistory: hand-over failed: ${it.message}") }
        }
    }
}

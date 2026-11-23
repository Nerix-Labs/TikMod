package com.mts.tikmod.utils

import android.net.Uri
import com.mts.tikmod.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Stories, inside TikTok's process.
 *
 * - [remember]: every story `Aweme` TikTok loads (a creator's story list, a story opened from anywhere) is indexed by id.
 * - [onViewed]: TikTok reported a story as watched (its `story/view/report` request, seen even when TikMod blocks it,
 *   or `Story.setViewed(true)`). That story goes to TikMod's archive.
 * - Stories of the channels on the auto-save list go to TikMod as soon as TikTok loads them.
 * - [onUserStoryList]: a creator's complete, fresh story list. Archived stories of that creator that haven't expired
 *   but are missing from it were deleted; TikMod marks them.
 *
 * Only the story's own media addresses and its author/caption are passed on. The files are downloaded and kept by
 * TikMod (its own storage), so nothing here writes to disk.
 */
object StoryTracker {

    private val stories = ConcurrentHashMap<String, WeakReference<Any>>()
    /** Story model (identity) -> its aweme id, so `Story.setViewed(true)` can be traced back. */
    private val storyModels = ConcurrentHashMap<Int, Pair<WeakReference<Any>, String>>()
    /** What was already handed over, per id: 1 = archived, 2 = auto-saved. */
    private val sent = ConcurrentHashMap<String, Int>()
    private val deletedSent = ConcurrentHashMap<String, Boolean>()
    /** Hot path guard: the last objects seen (getters are called all the time on the same ones). */
    private val recent = arrayOfNulls<WeakReference<Any>>(48)
    @Volatile private var recentPos = 0
    /** Story lists already looked at, by identity (a List's own hashCode would hash every Aweme in it). */
    private val seenLists = ConcurrentHashMap<Int, Pair<WeakReference<Any>, Int>>()

    private val bg by lazy { android.os.HandlerThread("TikMod-Stories").apply { start() }.let { android.os.Handler(it.looper) } }

    private fun seenRecently(obj: Any): Boolean {
        for (r in recent) if (r?.get() === obj) return true
        synchronized(recent) {
            recent[recentPos] = WeakReference(obj)
            recentPos = (recentPos + 1) % recent.size
        }
        return false
    }

    /** A story aweme TikTok has loaded. Cheap when called again with the same object. */
    fun remember(aweme: Any?) {
        if (aweme == null || seenRecently(aweme)) return
        runCatching {
            val aid = Reflect.string(aweme, "getAid", "aid") ?: return
            stories[aid] = WeakReference(aweme)
            if (stories.size > 400) stories.entries.filter { it.value.get() == null }.forEach { stories.remove(it.key) }
            Reflect.get(aweme, "getStory", "story")?.let { model ->
                storyModels[System.identityHashCode(model)] = WeakReference(model) to aid
                if (storyModels.size > 400) storyModels.entries.filter { it.value.first.get() == null }.forEach { storyModels.remove(it.key) }
            }
            // A story TikTok now knows is deleted (opened from a notification, a link…).
            val status = Reflect.get(aweme, "getStatus", "status")
            if (Reflect.call(status, "isDelete") == true) reportDeleted(aid)
            maybeAutoSave(aid, aweme)
        }
    }

    /** `Story.setViewed(true)` on a story model TikTok shows. */
    fun onStoryModelViewed(model: Any?) {
        model ?: return
        val entry = storyModels[System.identityHashCode(model)] ?: return
        if (entry.first.get() === model) onViewed(entry.second)
    }

    /** TikTok counted this story as watched. */
    fun onViewed(aid: String) {
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_STORY_ARCHIVE)) return
        val aweme = stories[aid]?.get()
        if (aweme == null) { XLog.log("Stories: viewed $aid, but it wasn't loaded through a story list"); return }
        handOver(aid, aweme, auto = false)
    }

    private fun maybeAutoSave(aid: String, aweme: Any) {
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_STORY_AUTO)) return
        val channels = Prefs.storyAutoChannels(ctx)
        if (channels.isEmpty()) return
        val author = Reflect.get(aweme, "getAuthor", "author")
        val handle = (Reflect.string(author, "getUniqueId", "uniqueId") ?: return).lowercase()
        if (handle in channels) handOver(aid, aweme, auto = true)
    }

    private fun handOver(aid: String, aweme: Any, auto: Boolean) {
        val bit = if (auto) 2 else 1
        val before = sent[aid] ?: 0
        if (before and bit != 0) return
        sent[aid] = before or bit
        if (sent.size > 2000) sent.clear()
        bg.post {
            runCatching {
                val ctx = Prefs.hostContext ?: return@post
                val media = MediaResolver.resolve(aweme) ?: return@post
                val entry = toJson(media).put("auto", auto)
                ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "add_story", entry.toString(), null)
                XLog.log("Stories: ${if (auto) "auto-saving" else "archiving"} @${media.authorHandle} story $aid " +
                    "(${if (media.isPhotoPost) "${media.images.size} photos" else "${media.videoOptions.size} renditions"})")
            }.onFailure { XLog.log("Stories: hand-over failed: ${it.message}") }
        }
    }

    private fun toJson(m: MediaResolver.AwemeMedia): JSONObject {
        // Best quality first; H.264 renditions go before H.265 of the same size so the copy plays everywhere.
        val ranked = MediaResolver.rankVideoOptions(m.videoOptions, Prefs.DOWNLOAD_QUALITY_MAX, Prefs.VIDEO_CODEC_BEST)
        return JSONObject()
            .put("aid", m.awemeId)
            .put("handle", m.authorHandle.ifBlank { m.authorUniqueId })
            .put("nick", m.authorNickname)
            .put("desc", m.desc.take(300))
            .put("created", if (m.createTime in 1..99_999_999_999L) m.createTime * 1000 else m.createTime)
            .put("expires", m.expiresAt)
            .put("video", JSONArray().apply { ranked.take(4).forEach { o -> put(JSONArray(o.urls.take(3))) } })
            .put("images", JSONArray().apply { m.images.forEach { put(JSONArray(it.take(3))) } })
            .put("cover", JSONArray(m.coverUrls.take(3)))
    }

    /**
     * A creator's story list as TikTok received it. Only a fresh (from the server), complete list says anything
     * about deletions: no more pages before/after, all of the creator's stories in it, not a preview chunk.
     */
    fun onUserStoryList(userStory: Any?, list: List<*>?) {
        if (userStory == null || list == null) return
        // Each list object once (TikTok reads it many times); a list that grew is looked at again.
        val key = System.identityHashCode(list)
        val prev = seenLists[key]
        if (prev != null && prev.first.get() === list && prev.second == list.size) return
        seenLists[key] = WeakReference<Any>(list) to list.size
        if (seenLists.size > 300) seenLists.entries.filter { it.value.first.get() == null }.forEach { seenLists.remove(it.key) }
        list.forEach { remember(it) }
        runCatching {
            if (list.isEmpty()) return
            if (Reflect.call(userStory, "isFromRemote") != true) return
            if (Reflect.call(userStory, "getHasMoreBefore") == true || Reflect.call(userStory, "getHasMoreAfter") == true) return
            if (Reflect.call(userStory, "getFakeAwemeShell") == true || Reflect.call(userStory, "isFromSkylightChunk") == true) return
            val total = Reflect.long(userStory, "getTotalCount", "totalCount")
            if (total > list.size) return
            val handles = list.mapNotNull { a -> Reflect.string(Reflect.get(a, "getAuthor", "author"), "getUniqueId", "uniqueId")?.lowercase() }.distinct()
            if (handles.size != 1) return
            val aids = list.mapNotNull { Reflect.string(it, "getAid", "aid") }
            if (aids.size != list.size) return
            val ctx = Prefs.hostContext ?: return
            if (!Prefs.bool(ctx, Prefs.KEY_STORY_ARCHIVE) && !Prefs.bool(ctx, Prefs.KEY_STORY_AUTO)) return
            val payload = JSONObject().put("handle", handles[0]).put("aids", JSONArray(aids)).put("time", System.currentTimeMillis())
            bg.post {
                runCatching { ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "story_list", payload.toString(), null) }
            }
        }
    }

    private fun reportDeleted(aid: String) {
        if (deletedSent.putIfAbsent(aid, true) != null) return
        val ctx = Prefs.hostContext ?: return
        bg.post { runCatching { ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "story_deleted", aid, null) } }
    }
}

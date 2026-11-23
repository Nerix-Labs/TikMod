package com.mts.tikmod.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Watch history: the videos and photo posts you scrolled to in TikTok's feed, kept on the phone only
 * (`files/watch/`). TikTok's process ([com.mts.tikmod.utils.WatchTracker]) hands each one over through the
 * provider; a small cover is downloaded here, because TikTok's signed cover addresses expire.
 *
 * Index `files/watch/index.json`, newest first. Per entry: aid, handle, nick, desc, photo, dur (ms), first, time
 * (last seen), count, watched (ms on screen, summed), thumb, cover (until the thumb is fetched).
 *
 * Swiping writes often, so the list stays in memory and is written at most every few seconds.
 */
object WatchHistory {
    private const val DIR = "watch"
    private const val INDEX = "index.json"
    const val MAX_ENTRIES = 1000
    private const val WRITE_DELAY_MS = 3000L

    private val lock = Any()
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var cache: MutableList<JSONObject>? = null
    private var writePending = false

    /** Bumped on every change; the screen re-reads when it moves. */
    @Volatile var revision = 0L
        private set

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun load(ctx: Context): MutableList<JSONObject> {
        cache?.let { return it }
        val f = File(dir(ctx), INDEX)
        val arr = if (f.exists()) runCatching { JSONArray(f.readText()) }.getOrNull() else null
        val list = if (arr == null) ArrayList() else (0 until arr.length()).mapNotNullTo(ArrayList()) { arr.optJSONObject(it) }
        cache = list
        return list
    }

    /** A copy of the list (entries are copied too, so the screen never sees one change under it). */
    fun list(ctx: Context): List<JSONObject> = synchronized(lock) { load(ctx).map { JSONObject(it.toString()) } }

    private fun changed(ctx: Context) {
        revision++
        if (writePending) return
        writePending = true
        io.schedule({ flush(ctx) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun flush(ctx: Context) {
        val text = synchronized(lock) {
            writePending = false
            JSONArray(load(ctx)).toString()
        }
        runCatching {
            val f = File(dir(ctx), INDEX)
            val tmp = File(dir(ctx), "$INDEX.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { XLog.log("WatchHistory: write failed: ${it.message}") }
    }

    // ------------------------------------------------------------------ from TikTok (provider)

    /**
     * A post TikTok put on screen. `prev` / `prevMs`: how long the post before it stayed on screen.
     */
    fun add(ctx: Context, json: JSONObject) {
        if (!Prefs.bool(ctx, Prefs.KEY_WATCH_HISTORY)) return
        val aid = json.optString("aid").takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        var fetchCover: String? = null
        synchronized(lock) {
            val list = load(ctx)
            // Time on screen of the post before this one.
            val prev = json.optString("prev")
            val prevMs = json.optLong("prevMs")
            if (prev.isNotBlank() && prevMs in 500..(30 * 60_000L)) {
                list.firstOrNull { it.optString("aid") == prev }?.let { it.put("watched", it.optLong("watched") + prevMs) }
            }
            val existing = list.indexOfFirst { it.optString("aid") == aid }
            if (existing >= 0) {
                val e = list.removeAt(existing)
                e.put("time", now).put("count", e.optInt("count", 1) + 1)
                list.add(0, e)
            } else {
                val cover = json.optJSONArray("cover")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("http") } }.orEmpty()
                val e = JSONObject()
                    .put("aid", aid)
                    .put("handle", json.optString("handle").removePrefix("@"))
                    .put("nick", json.optString("nick"))
                    .put("desc", json.optString("desc").take(300))
                    .put("photo", json.optBoolean("photo"))
                    .put("dur", json.optLong("dur"))
                    .put("first", now)
                    .put("time", now)
                    .put("count", 1)
                    .put("watched", 0L)
                    .put("cover", JSONArray(cover.take(3)))
                list.add(0, e)
                while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1).let { old -> thumbFile(ctx, old)?.delete() }
                fetchCover = aid
            }
            changed(ctx)
        }
        fetchCover?.let { id -> io.execute { fetchThumb(ctx, id) } }
    }

    private fun fetchThumb(ctx: Context, aid: String) {
        val urls = synchronized(lock) {
            val e = load(ctx).firstOrNull { it.optString("aid") == aid } ?: return
            e.optJSONArray("cover")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        }
        val out = File(dir(ctx), "t_$aid.jpg")
        var ok = false
        for (url in urls) {
            val bmp = runCatching {
                val conn = TikModDownloader.open(url)
                try { if (conn.responseCode in 200..299) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null }
                finally { runCatching { conn.disconnect() } }
            }.getOrNull() ?: continue
            runCatching {
                val scale = 320f / maxOf(bmp.width, bmp.height)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
                out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (small !== bmp) small.recycle()
                ok = true
            }
            bmp.recycle()
            if (ok) break
        }
        synchronized(lock) {
            load(ctx).firstOrNull { it.optString("aid") == aid }?.let { e ->
                e.remove("cover") // signed addresses expire anyway
                if (ok) e.put("thumb", out.name)
                changed(ctx)
            }
        }
    }

    // ------------------------------------------------------------------ from the screen

    private fun thumbFile(ctx: Context, e: JSONObject): File? =
        e.optString("thumb").takeIf { it.isNotBlank() }?.let { File(dir(ctx), it) }

    fun thumb(ctx: Context, e: JSONObject): File? = thumbFile(ctx, e)?.takeIf { it.exists() }

    /** The post's address; TikTok opens it when installed. */
    fun link(e: JSONObject): String {
        val h = e.optString("handle")
        val aid = e.optString("aid")
        return if (h.isNotBlank()) "https://www.tiktok.com/@$h/${if (e.optBoolean("photo")) "photo" else "video"}/$aid"
        else "https://www.tiktok.com/video/$aid"
    }

    /** Matches the search box: name, @handle or caption, ignoring case. */
    fun matches(e: JSONObject, query: String): Boolean {
        val q = query.trim().removePrefix("@").lowercase()
        if (q.isEmpty()) return true
        return e.optString("nick").lowercase().contains(q) || e.optString("handle").lowercase().contains(q) ||
            e.optString("desc").lowercase().contains(q)
    }

    fun remove(ctx: Context, aid: String) = synchronized(lock) {
        val list = load(ctx)
        list.filter { it.optString("aid") == aid }.forEach { thumbFile(ctx, it)?.delete() }
        list.removeAll { it.optString("aid") == aid }
        changed(ctx)
    }

    fun clear(ctx: Context) = synchronized(lock) {
        load(ctx).clear()
        dir(ctx).listFiles()?.filter { it.name.startsWith("t_") }?.forEach { it.delete() }
        changed(ctx)
    }
}

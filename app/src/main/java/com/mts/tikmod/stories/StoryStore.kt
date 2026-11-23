package com.mts.tikmod.stories

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The story archive, in TikMod's own process and private storage (`files/stories/`).
 *
 * TikTok's process ([com.mts.tikmod.utils.StoryTracker]) hands over each story you watch (and every story of a
 * channel on the auto-save list) through the provider: author, caption, expiry and the media addresses. TikMod
 * downloads the files here, so a story stays watchable after it expires or its creator deletes it. Deleted stories
 * are marked with the time TikMod noticed it.
 *
 * Index: `files/stories/index.json`, newest first. Per entry: aid, handle, nick, desc, created, expires, seen,
 * status (pending | ok | failed), kind (video | photo), files, thumb, bytes, deleted, deletedAt, auto, saved, src.
 */
object StoryStore {
    private const val DIR = "stories"
    private const val INDEX = "index.json"
    private const val MAX_ENTRIES = 300
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    private const val KEEP_DELETED_MS = 90L * 24 * 3600 * 1000
    private const val DAY_MS = 24L * 3600 * 1000
    const val AUTHORITY = "com.mts.tikmod.files"

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    /** Bumped on every change; the screen re-reads when it moves. */
    @Volatile var revision = 0L
        private set

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun list(ctx: Context): List<JSONObject> = synchronized(lock) { readAll(ctx) }

    private fun readAll(ctx: Context): MutableList<JSONObject> {
        val f = File(dir(ctx), INDEX)
        if (!f.exists()) return ArrayList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return ArrayList()
        return (0 until arr.length()).mapNotNullTo(ArrayList()) { arr.optJSONObject(it) }
    }

    private fun writeAll(ctx: Context, list: List<JSONObject>) {
        val f = File(dir(ctx), INDEX)
        val tmp = File(dir(ctx), "$INDEX.tmp")
        tmp.writeText(JSONArray(list).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        revision++
    }

    private fun update(ctx: Context, aid: String, change: (JSONObject) -> Unit): JSONObject? = synchronized(lock) {
        val list = readAll(ctx)
        val e = list.firstOrNull { it.optString("aid") == aid } ?: return null
        change(e)
        writeAll(ctx, list)
        e
    }

    // ------------------------------------------------------------------ from TikTok (provider)

    /** A story TikTok handed over (watched, or of an auto-save channel). */
    fun add(ctx: Context, json: JSONObject) {
        val aid = json.optString("aid").takeIf { it.isNotBlank() } ?: return
        val auto = json.optBoolean("auto")
        var fetch = false
        var saveNow = false
        synchronized(lock) {
            val list = readAll(ctx)
            val existing = list.firstOrNull { it.optString("aid") == aid }
            if (existing != null) {
                // Already archived; an auto-save channel's story also goes to the gallery.
                if (auto && !existing.optBoolean("saved")) {
                    existing.put("auto", true)
                    writeAll(ctx, list)
                    saveNow = existing.optString("status") == "ok"
                }
                if (existing.optString("status") == "failed") { existing.put("status", "pending").put("src", json.toSource()); writeAll(ctx, list); fetch = true }
            } else {
                if (!auto && !Prefs.bool(ctx, Prefs.KEY_STORY_ARCHIVE)) return
                val entry = JSONObject()
                    .put("aid", aid)
                    .put("handle", json.optString("handle").removePrefix("@"))
                    .put("nick", json.optString("nick"))
                    .put("desc", json.optString("desc"))
                    .put("created", json.optLong("created"))
                    .put("expires", json.optLong("expires"))
                    .put("seen", System.currentTimeMillis())
                    .put("status", "pending")
                    .put("deleted", false)
                    .put("auto", auto)
                    .put("saved", false)
                    .put("src", json.toSource())
                list.add(0, entry)
                prune(ctx, list)
                writeAll(ctx, list)
                fetch = true
            }
        }
        if (fetch) executor.execute { download(ctx, aid) }
        else if (saveNow) executor.execute { saveToGallery(ctx, aid) }
    }

    private fun JSONObject.toSource(): JSONObject = JSONObject()
        .put("video", optJSONArray("video") ?: JSONArray())
        .put("images", optJSONArray("images") ?: JSONArray())
        .put("cover", optJSONArray("cover") ?: JSONArray())

    /**
     * A creator's complete, fresh story list. Archived stories of theirs that should still be up (not expired) and
     * that were archived before this list arrived, but aren't in it, were deleted.
     */
    fun onAuthorList(ctx: Context, json: JSONObject) {
        val handle = json.optString("handle").lowercase().takeIf { it.isNotBlank() } ?: return
        val ids = json.optJSONArray("aids")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: return
        val time = json.optLong("time").takeIf { it > 0 } ?: System.currentTimeMillis()
        synchronized(lock) {
            val list = readAll(ctx)
            var changed = 0
            for (e in list) {
                if (e.optBoolean("deleted") || e.optString("handle").lowercase() != handle) continue
                if (e.optString("aid") in ids || e.optLong("seen") >= time) continue
                if (!stillUp(e, time)) continue
                e.put("deleted", true).put("deletedAt", time)
                changed++
            }
            if (changed > 0) {
                writeAll(ctx, list)
                XLog.log("Stories: @$handle deleted $changed archived ${if (changed == 1) "story" else "stories"}")
            }
        }
    }

    /** TikTok itself says this story is deleted. */
    fun markDeleted(ctx: Context, aid: String) {
        update(ctx, aid) { e -> if (!e.optBoolean("deleted")) e.put("deleted", true).put("deletedAt", System.currentTimeMillis()) }
    }

    /** Should this story still be online at [now]? (Expired stories missing from a list weren't deleted.) */
    private fun stillUp(e: JSONObject, now: Long): Boolean {
        val expires = e.optLong("expires").takeIf { it > 0 } ?: e.optLong("created").takeIf { it > 0 }?.plus(DAY_MS) ?: return false
        return expires > now + 60_000
    }

    private fun prune(ctx: Context, list: MutableList<JSONObject>) {
        val now = System.currentTimeMillis()
        val drop = list.filterIndexed { i, e ->
            val age = now - e.optLong("seen")
            i >= MAX_ENTRIES || age > (if (e.optBoolean("deleted")) KEEP_DELETED_MS else KEEP_MS)
        }
        drop.forEach { deleteFiles(ctx, it) }
        list.removeAll(drop.toSet())
    }

    // ------------------------------------------------------------------ download

    private fun download(ctx: Context, aid: String) {
        val entry = synchronized(lock) { readAll(ctx).firstOrNull { it.optString("aid") == aid } } ?: return
        val src = entry.optJSONObject("src") ?: JSONObject()
        val images = src.optJSONArray("images").toUrlLists()
        val videos = src.optJSONArray("video").toUrlLists()
        val cover = src.optJSONArray("cover").toStrings()
        val files = ArrayList<String>()
        var bytes = 0L
        var kind = "video"
        runCatching {
            if (images.isNotEmpty()) {
                kind = "photo"
                images.forEachIndexed { i, urls ->
                    val f = File(dir(ctx), "${aid}_${i + 1}.jpg")
                    if (fetchImage(urls, f)) { files += f.name; bytes += f.length() }
                }
            } else {
                val f = File(dir(ctx), "$aid.mp4")
                for (urls in videos) if (fetchVideo(urls, f)) { files += f.name; bytes += f.length(); break }
            }
        }.onFailure { XLog.log("Stories: download $aid failed: ${it.message}") }

        val thumb = File(dir(ctx), "${aid}_t.jpg")
        runCatching { makeThumb(ctx, cover, files, thumb) }

        val ok = files.isNotEmpty()
        val auto = update(ctx, aid) { e ->
            e.put("status", if (ok) "ok" else "failed").put("kind", kind).put("files", JSONArray(files)).put("bytes", bytes)
            if (thumb.exists()) e.put("thumb", thumb.name)
            if (ok) e.remove("src") // signed addresses expire anyway
        }?.optBoolean("auto") == true
        XLog.log("Stories: $aid ${if (ok) "archived (${files.size} file(s), ${TikModDownloader.formatSize(bytes)})" else "failed"}")
        if (ok && auto) saveToGallery(ctx, aid)
    }

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.startsWith("http") } }

    private fun JSONArray?.toUrlLists(): List<List<String>> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONArray(it).toStrings().takeIf { l -> l.isNotEmpty() } }

    private fun fetchVideo(urls: List<String>, out: File): Boolean {
        for (url in urls) {
            val ok = runCatching {
                val conn = TikModDownloader.open(url)
                try {
                    if (conn.responseCode !in 200..299) return@runCatching false
                    conn.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                    // MP4 boxes sit at offset 4: ftyp / moov / free / mdat.
                    val head = ByteArray(8)
                    out.inputStream().use { it.read(head) }
                    val box = String(head, 4, 4, Charsets.ISO_8859_1)
                    out.length() > 1024 && box in setOf("ftyp", "moov", "free", "mdat", "skip", "wide")
                } finally { runCatching { conn.disconnect() } }
            }.getOrDefault(false)
            if (ok) return true
            out.delete()
        }
        return false
    }

    /** Photos are kept as JPEG (TikTok serves HEIC/WebP), so they open in every app. */
    private fun fetchImage(urls: List<String>, out: File): Boolean {
        for (url in urls) {
            val data = runCatching {
                val conn = TikModDownloader.open(url)
                try {
                    if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
                } finally { runCatching { conn.disconnect() } }
            }.getOrNull() ?: continue
            if (data.isEmpty()) continue
            val jpeg = data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
            if (jpeg) { out.writeBytes(data); return true }
            val bmp = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull() ?: continue
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            return true
        }
        return false
    }

    private fun makeThumb(ctx: Context, cover: List<String>, files: List<String>, out: File) {
        var bmp: Bitmap? = null
        for (url in cover) {
            bmp = runCatching {
                val conn = TikModDownloader.open(url)
                try { if (conn.responseCode in 200..299) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null }
                finally { runCatching { conn.disconnect() } }
            }.getOrNull()
            if (bmp != null) break
        }
        val first = files.firstOrNull()?.let { File(dir(ctx), it) }
        if (bmp == null && first != null) {
            bmp = if (first.name.endsWith(".mp4")) runCatching {
                val r = android.media.MediaMetadataRetriever()
                try { r.setDataSource(first.absolutePath); r.getFrameAtTime(0) } finally { runCatching { r.release() } }
            }.getOrNull()
            else BitmapFactory.decodeFile(first.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
        val b = bmp ?: return
        val scale = 360f / maxOf(b.width, b.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
        out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (small !== b) small.recycle()
        b.recycle()
    }

    // ------------------------------------------------------------------ from the screen

    fun files(ctx: Context, e: JSONObject): List<File> {
        val arr = e.optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { File(dir(ctx), arr.optString(it)) }.filter { it.exists() }
    }

    fun thumb(ctx: Context, e: JSONObject): File? = e.optString("thumb").takeIf { it.isNotBlank() }?.let { File(dir(ctx), it) }?.takeIf { it.exists() }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, AUTHORITY, f)

    fun mime(f: File) = if (f.name.endsWith(".mp4")) "video/mp4" else "image/jpeg"

    /** Copies the story to the gallery (Movies/TikMod/Stories or Pictures/TikMod/Stories). */
    fun saveToGallery(ctx: Context, aid: String): Boolean {
        val e = list(ctx).firstOrNull { it.optString("aid") == aid } ?: return false
        val files = files(ctx, e)
        if (files.isEmpty()) return false
        val handle = e.optString("handle").ifBlank { "tiktok" }
        var saved = 0
        files.forEachIndexed { i, f ->
            val video = f.name.endsWith(".mp4")
            val name = "story_${handle}_$aid" + (if (files.size > 1) "_${i + 1}" else "") + if (video) ".mp4" else ".jpg"
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime(f))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/TikMod/Stories")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(collection, values) ?: return@runCatching false
                    try {
                        ctx.contentResolver.openOutputStream(uri, "w")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    } catch (t: Throwable) { ctx.contentResolver.delete(uri, null, null); throw t }
                } else {
                    @Suppress("DEPRECATION")
                    val d = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "TikMod/Stories").apply { mkdirs() }
                    f.copyTo(File(d, name), overwrite = true)
                    android.media.MediaScannerConnection.scanFile(ctx, arrayOf(File(d, name).absolutePath), arrayOf(mime(f)), null)
                }
                true
            }.onFailure { XLog.log("Stories: gallery copy failed: ${it.message}") }.getOrDefault(false)
            if (ok) saved++
        }
        if (saved > 0) update(ctx, aid) { it.put("saved", true) }
        return saved > 0
    }

    fun remove(ctx: Context, aid: String) = synchronized(lock) {
        val list = readAll(ctx)
        list.filter { it.optString("aid") == aid }.forEach { deleteFiles(ctx, it) }
        list.removeAll { it.optString("aid") == aid }
        writeAll(ctx, list)
    }

    fun clear(ctx: Context) = synchronized(lock) {
        readAll(ctx).forEach { deleteFiles(ctx, it) }
        writeAll(ctx, emptyList())
    }

    private fun deleteFiles(ctx: Context, e: JSONObject) {
        files(ctx, e).forEach { it.delete() }
        thumb(ctx, e)?.delete()
    }

    fun totalBytes(list: List<JSONObject>): Long = list.sumOf { it.optLong("bytes") }
}

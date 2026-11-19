package com.mts.tikmod.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fetches a gift's full-screen animation (the same file TikTok plays) so the chat burner can put it into a
 * recording. TikTok plays these with its "AlphaPlayer": an ordinary MP4 whose frame holds the colour picture and
 * a grey-scale transparency mask side by side. The layout comes from a JSON config (in the zip next to the video,
 * or a `vapc` box inside the MP4); without one, the AlphaPlayer default is used (mask left, colour right).
 *
 * Kept in TikMod's cache by effect id, so a gift that is sent often is downloaded once.
 */
object GiftEffects {
    private const val MAX_BYTES = 25L shl 20

    fun prepare(ctx: Context, asset: ChatLog.Asset, portrait: Boolean): ChatBurner.Anim? = runCatching {
        val type = asset.type
        if (type.isNotBlank() && type != "mp4" && !type.startsWith("t")) {
            XLog.log("GiftEffects: ${asset.id} is $type, not a video; skipped"); return@runCatching null
        }
        val dir = File(ctx.cacheDir, "giftfx/${asset.id}").apply { mkdirs() }
        val raw = File(dir, "raw")
        if (raw.length() == 0L && !(asset.urls + asset.hevc).any { download(it, raw) }) return@runCatching null
        // A zip (video + config) or the video itself.
        val isZip = raw.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
        val files = if (isZip) unzip(raw, File(dir, "x")) else listOf(raw)
        val videos = files.filter { it.name.endsWith(".mp4", true) || it == raw && !isZip }
        if (videos.isEmpty()) { XLog.log("GiftEffects: ${asset.id} has no video (${files.map { it.name }.take(8)})"); return@runCatching null }
        val configs = files.filter { it.name.endsWith(".json", true) }.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }

        // AlphaPlayer config: {"portrait":{"path":"x.mp4","align":…},"landscape":{…}} picks the file per orientation.
        var video = videos.first()
        for (c in configs) {
            val o = c.optJSONObject(if (portrait) "portrait" else "landscape") ?: c.optJSONObject("portrait") ?: continue
            videos.firstOrNull { it.name == o.optString("path") }?.let { video = it }
        }
        // Frame layout: rgbFrame / alphaFrame ([x, y, w, h]) in a config or in the MP4's vapc box.
        val layout = configs.firstNotNullOfOrNull { findLayout(it) } ?: vapc(video)?.let { findLayout(it) }
        XLog.log("GiftEffects: ${asset.id} ready (${video.name}, ${video.length() / 1024} KB, layout ${layout?.let { "rgb ${it.rgb.toList()} a ${it.alpha.toList()}" } ?: "default"})")
        ChatBurner.Anim(video, layout?.rgb, layout?.alpha, layout?.w ?: 0, layout?.h ?: 0)
    }.onFailure { XLog.log("GiftEffects: ${asset.id} failed (${it.javaClass.simpleName}: ${it.message})") }.getOrNull()

    private class Layout(val rgb: IntArray, val alpha: IntArray, val w: Int, val h: Int)

    /** Looks anywhere in [o] for an object that has rgb and alpha rectangles. */
    private fun findLayout(o: JSONObject): Layout? {
        fun rect(a: JSONArray?) = a?.takeIf { it.length() >= 4 }?.let { IntArray(4) { i -> it.optInt(i) } }
        val rgb = rect(o.optJSONArray("rgbFrame")) ?: rect(o.optJSONArray("rgbArea"))
        val alpha = rect(o.optJSONArray("alphaFrame")) ?: rect(o.optJSONArray("aFrame")) ?: rect(o.optJSONArray("alphaArea"))
        if (rgb != null && alpha != null) return Layout(rgb, alpha, o.optInt("videoW", o.optInt("videoWidth")), o.optInt("videoH", o.optInt("videoHeight")))
        for (k in o.keys()) {
            o.optJSONObject(k)?.let { c -> findLayout(c)?.let { return it } }
        }
        return null
    }

    /** The JSON in a VAP-style `vapc` box, if the MP4 has one. */
    private fun vapc(f: File): JSONObject? = runCatching {
        val b = ByteArray(minOf(f.length(), 512 * 1024L).toInt())
        java.io.DataInputStream(f.inputStream()).use { it.readFully(b) }
        val tag = "vapc".toByteArray()
        var i = 4
        while (i < b.size - 4) {
            if (b[i] == tag[0] && b[i + 1] == tag[1] && b[i + 2] == tag[2] && b[i + 3] == tag[3]) {
                val size = ((b[i - 4].toInt() and 0xff) shl 24) or ((b[i - 3].toInt() and 0xff) shl 16) or
                    ((b[i - 2].toInt() and 0xff) shl 8) or (b[i - 1].toInt() and 0xff)
                if (size in 9..200_000 && i - 4 + size <= b.size) return@runCatching JSONObject(String(b, i + 4, size - 8))
            }
            i++
        }
        null
    }.getOrNull()

    private fun download(url: String, to: File): Boolean = runCatching {
        if (!url.startsWith("http")) return@runCatching false
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        try {
            if (c.responseCode !in 200..299) return@runCatching false
            val tmp = File(to.path + ".part")
            var n = 0L
            c.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > MAX_BYTES) error("too big"); out.write(buf, 0, r) }
            } }
            tmp.renameTo(to) && to.length() > 0
        } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun unzip(zip: File, dir: File): List<File> {
        if (dir.exists()) return dir.walkTopDown().filter { it.isFile }.toList()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream()).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(root)) continue // stays inside our folder
                out.parentFile?.mkdirs()
                out.outputStream().use { o -> total += z.copyTo(o) }
                if (total > MAX_BYTES * 2) break
            }
        }
        return dir.walkTopDown().filter { it.isFile }.toList()
    }
}

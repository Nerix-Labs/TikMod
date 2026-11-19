package com.mts.tikmod.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live chat lines captured in TikTok, kept in TikMod's own storage so the LIVE recorder can put them into the
 * recorded video. One append-only file per room (JSON lines); files older than a day are removed.
 *
 * The hooked TikTok process sends batches through the provider ([append]); the recorder, which runs in
 * TikMod's process, reads the lines of the room it recorded ([lines]).
 */
object ChatLog {
    /**
     * [kind] "c" = a comment ([text] is the comment), "g" = a gift ([text] is the gift's name, [count] its combo
     * count, [diamonds] its price, [effect] whether it has a full-screen animation, [url] its picture, [group]
     * the combo it belongs to).
     */
    class Entry(
        val t: Long, val nick: String, val text: String, val kind: String = "c", val count: Int = 1,
        val diamonds: Int = 0, val effect: Boolean = false, val url: String = "", val group: String = "",
        val effectId: Long = 0L
    )

    /** A gift's full-screen animation as TikTok describes it: [type] mp4/webp/lottie/…, download [urls]. */
    class Asset(val id: Long, val type: String, val urls: List<String>, val hevc: List<String>, val md5: String)

    private val lock = Any()
    private const val MAX_AGE = 24 * 3600_000L
    private const val MAX_BYTES = 8L shl 20
    private var lastPrune = 0L

    private fun dir(ctx: Context) = File(ctx.filesDir, "chatlog").apply { mkdirs() }
    private fun safe(room: String) = room.filter { it.isLetterOrDigit() }.take(40).ifBlank { "unknown" }

    /** [json]: `[{"r":room,"t":wallMs,"n":nick,"x":text,"i":messageId}, …]` from the TikTok process. */
    fun append(ctx: Context, json: String) {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        val byRoom = HashMap<String, StringBuilder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val room = o.optString("r")
            if (room.isBlank() || o.optString("x").isBlank()) continue
            byRoom.getOrPut(safe(room)) { StringBuilder() }.append(o.toString()).append('\n')
        }
        if (byRoom.isEmpty()) return
        synchronized(lock) {
            val d = dir(ctx)
            for ((room, sb) in byRoom) {
                val f = File(d, "$room.jsonl")
                if (f.length() > MAX_BYTES) f.delete()
                runCatching { f.appendText(sb.toString()) }
            }
            val now = System.currentTimeMillis()
            if (now - lastPrune > 3600_000L) {
                lastPrune = now
                d.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE) it.delete() }
            }
        }
    }

    /** Lines of [room] captured between [from] and [to] (wall-clock ms), oldest first, without repeats. */
    fun lines(ctx: Context, room: String, from: Long, to: Long): List<Entry> {
        val out = ArrayList<Entry>()
        synchronized(lock) {
            val f = File(dir(ctx), "${safe(room)}.jsonl")
            if (!f.exists()) return out
            val seen = HashSet<String>()
            f.forEachLine { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val t = o.optLong("t")
                if (t < from || t > to) return@forEachLine
                val text = o.optString("x")
                val key = o.optString("i").ifBlank { "$t|$text" }
                if (!seen.add(key)) return@forEachLine
                out += Entry(
                    t, o.optString("n"), text, o.optString("k", "c"), o.optInt("c", 1).coerceAtLeast(1),
                    o.optInt("d"), o.optBoolean("e"), o.optString("u"), o.optString("g"), o.optLong("p")
                )
            }
        }
        out.sortBy { it.t }
        return out
    }

    private fun assetsFile(ctx: Context) = File(dir(ctx), "assets.json")

    /** [json]: `[{"id":effectId,"type":"mp4","urls":[…],"hevc":[…],"md5":…}, …]` from the TikTok process. */
    fun addAssets(ctx: Context, json: String) {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        synchronized(lock) {
            val f = assetsFile(ctx)
            val all = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id"); if (id == 0L) continue
                all.put(id.toString(), o.put("seen", System.currentTimeMillis()))
            }
            runCatching { f.writeText(all.toString()) }
        }
    }

    fun asset(ctx: Context, id: Long): Asset? {
        if (id == 0L) return null
        val o = synchronized(lock) {
            runCatching { JSONObject(assetsFile(ctx).readText()).optJSONObject(id.toString()) }.getOrNull()
        } ?: return null
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return Asset(id, o.optString("type"), list("urls"), list("hevc"), o.optString("md5"))
    }
}

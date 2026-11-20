package com.mts.tikmod.focus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last posts focus mode looked at (newest first), kept in TikMod's own process. TikTok sends each feed page
 * through TikModProvider ("focus_log"); the same post arriving twice (TikTok hands out copies of a page) replaces
 * its earlier line.
 */
object FocusLog {
    private const val FILE = "tikmod_focus_log"
    private const val KEY = "log"
    private const val MAX = 80

    class Entry(
        val id: String, val time: Long, val hidden: Boolean, val code: String, val word: String?, val topic: String?,
        val desc: String, val author: String?, val tags: String?, val score: Int = -1, val boredom: Int = 0
    )

    @Synchronized
    fun append(ctx: Context, page: JSONArray) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(sp.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val fresh = ArrayList<JSONObject>()
        val ids = HashSet<String>()
        for (i in 0 until page.length()) page.optJSONObject(i)?.let { o -> if (ids.add(o.optString("id"))) fresh += o }
        val out = JSONArray()
        // Newest page first, in feed order.
        fresh.forEach { out.put(it) }
        for (i in 0 until old.length()) {
            if (out.length() >= MAX) break
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("id") !in ids) out.put(o)
        }
        sp.edit().putString(KEY, out.toString()).apply()
    }

    fun read(ctx: Context): List<Entry> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                Entry(o.optString("id"), o.optLong("t"), o.optBoolean("h"), o.optString("c"),
                    o.optString("w").ifEmpty { null }, o.optString("tp").ifEmpty { null },
                    o.optString("d"), o.optString("a").ifEmpty { null }, o.optString("s").ifEmpty { null },
                    o.optInt("sc", -1), o.optInt("x", 0))
            }
        }
    }.getOrDefault(emptyList())

    // ---- what FocusLearn learned (written by TikTok through the provider)

    fun saveProfile(ctx: Context, json: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("profile", JSONObject(json).toString()).apply()
    }

    fun profileJson(ctx: Context): String = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("profile", "") ?: ""

    /** Strongest learned signals, most hooking first: ("#tag" / "?words" / "@creator", weight). */
    fun profile(ctx: Context): List<Pair<String, Float>> = runCatching {
        val o = JSONObject(profileJson(ctx).ifBlank { "{}" })
        o.keys().asSequence().map { it to o.optDouble(it).toFloat() }.sortedByDescending { it.second }.toList()
    }.getOrDefault(emptyList())

    fun resetProfile(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("profile").apply()
        com.mts.tikmod.Prefs.setString(ctx, com.mts.tikmod.Prefs.KEY_FOCUS_PROFILE_RESET, System.currentTimeMillis().toString())
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

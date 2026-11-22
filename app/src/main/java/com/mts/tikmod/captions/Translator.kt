package com.mts.tikmod.captions

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Text translation through Google Translate's public web endpoints (no key, no account).
 * Only the recognized caption text is sent. Recent results are cached, since live captions repeat
 * the beginning of a sentence while it grows.
 *
 * Two endpoints: when the first one fails or rate-limits, it rests for a while and the second one is used.
 * When both fail, ML Kit's on-device translator takes over ([OnDeviceTranslator]).
 */
object Translator {

    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 400
    }
    @Volatile private var primaryRestsUntil = 0L

    /** Google still uses the old code for Hebrew. */
    fun code(tag: String): String = when (val base = tag.substringBefore('-').lowercase()) {
        "he" -> "iw"
        "zh", "cmn" -> if (tag.contains("TW", true) || tag.contains("Hant", true)) "zh-TW" else "zh-CN"
        else -> base
    }

    /** @param source a language tag, or "auto". Throws when both endpoints fail. */
    fun translate(text: String, source: String, target: String): String {
        val sl = if (source == "auto") "auto" else code(source)
        val tl = code(target)
        val key = "$sl>$tl>$text"
        synchronized(cache) { cache[key] }?.let { return it }
        val q = URLEncoder.encode(text, "UTF-8")
        val result = try {
            online(sl, tl, q)
        } catch (t: Throwable) {
            if (t is InterruptedException) throw t
            OnDeviceTranslator.translate(text, sl, tl) ?: throw t
        }
        // Single Hebrew words come back with vowel points, which nobody writes in running text.
        val clean = if (tl == "iw") result.replace(Regex("[\\u0591-\\u05C7]"), "") else result
        synchronized(cache) { cache[key] = clean }
        return clean
    }

    private fun online(sl: String, tl: String, q: String): String =
        if (System.currentTimeMillis() < primaryRestsUntil) secondary(sl, tl, q) else try {
            primary(sl, tl, q)
        } catch (t: Throwable) {
            if (t is InterruptedException) throw t
            primaryRestsUntil = System.currentTimeMillis() + 60_000
            secondary(sl, tl, q)
        }

    private fun primary(sl: String, tl: String, q: String): String {
        val root = JSONArray(get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$q"))
        val parts = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) parts.optJSONArray(i)?.optString(0)?.takeIf { it != "null" }?.let(sb::append)
        return sb.toString().trim().ifEmpty { error("empty translation") }
    }

    private fun secondary(sl: String, tl: String, q: String): String {
        val root = JSONArray(get("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$sl&tl=$tl&q=$q"))
        // ["text"] for a fixed source language, [["text","en"]] for "auto".
        val first = root.opt(0)
        val text = if (first is JSONArray) first.optString(0) else first?.toString().orEmpty()
        return text.trim().ifEmpty { error("empty translation") }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

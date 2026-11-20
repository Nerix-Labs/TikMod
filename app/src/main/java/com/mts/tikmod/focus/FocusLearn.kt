package com.mts.tikmod.focus

import android.content.Context
import android.net.Uri
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.XLog
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Learns what hooks you, inside TikTok, from how long you stay on each post: watched to the end (or 30 s+) counts
 * for its hashtags, TikTok's search words and its creator; swiped away within 2.5 s counts against them. Weights
 * fade slowly so old habits don't stick forever. Nothing leaves the phone: the profile is kept in TikMod's own
 * storage (TikModProvider "focus_profile_*") so the app can show what was learned and reset it.
 */
object FocusLearn {
    private val w = HashMap<String, Float>()
    @Volatile private var loaded = false
    @Volatile private var resetMark: String? = null
    private var cur: WeakReference<Any>? = null
    private var curStart = 0L
    private var updates = 0

    /** Features of a post: "#tag", "?search words", "@creator". */
    fun features(a: Any): List<String> {
        val out = ArrayList<String>()
        Focus.hashtags(a).take(8).forEach { out += "#$it" }
        Focus.suggestWords(a).take(4).forEach { out += "?" + it.lowercase() }
        Reflect.get(a, "getAuthor", "author")?.let { au -> Reflect.string(au, "getUniqueId", "uniqueId")?.let { out += "@" + it.lowercase() } }
        return out
    }

    /** How much you tend to linger on posts like [a] (around -3 … +3+). */
    fun affinity(a: Any): Float = synchronized(w) {
        if (w.isEmpty()) return 0f
        var s = 0f
        for (f in features(a)) s += (w[f] ?: 0f) * (if (f.startsWith("@")) 1.5f else 1f)
        s
    }

    /** All the work (settings over a binder call, reflection, learning) runs here, never on TikTok's main thread. */
    private val bg by lazy { android.os.HandlerThread("TikMod-FocusLearn").apply { start() }.let { android.os.Handler(it.looper) } }

    /** A new post became the current one in the feed. Cheap: called on the main thread while swiping. */
    fun onSelected(ctx: Context?, aweme: Any?) {
        val at = System.currentTimeMillis()
        val ref = aweme?.let { WeakReference(it) }
        bg.post {
            runCatching {
                if (!Prefs.bool(ctx, Prefs.KEY_FOCUS_LEARN)) { cur = null; return@post }
                ensureLoaded(ctx)
                finish(ctx, at)
                cur = ref
                curStart = at
            }
        }
    }

    /** TikTok went to the background: the current post's time ends here. */
    fun onPaused(ctx: Context?) {
        val at = System.currentTimeMillis()
        bg.post { runCatching { finish(ctx, at); cur = null } }
    }

    private fun finish(ctx: Context?, now: Long) {
        val a = cur?.get() ?: return
        cur = null
        val d = now - curStart
        if (d < 400) return // flung past
        val durMs = Focus.durationSec(a) * 1000L
        val full = if (durMs > 0) minOf(durMs * 85 / 100, 25_000L) else 8_000L
        val signal = when {
            d < 2_500 -> -0.5f
            d >= full || d >= 30_000 -> 1f
            else -> (d.toFloat() / full) - 0.35f
        }
        val feats = features(a)
        if (feats.isEmpty()) return
        synchronized(w) {
            val it = w.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.setValue(e.value * 0.995f); if (abs(e.value) < 0.02f) it.remove() }
            feats.forEach { f -> w[f] = ((w[f] ?: 0f) + signal).coerceIn(-6f, 8f) }
            if (w.size > 600) w.entries.sortedBy { abs(it.value) }.take(w.size - 500).map { it.key }.forEach { w.remove(it) }
        }
        if (++updates % 8 == 0) save(ctx)
    }

    private fun ensureLoaded(ctx: Context?) {
        val c = ctx ?: Prefs.hostContext ?: return
        val mark = Prefs.string(c, Prefs.KEY_FOCUS_PROFILE_RESET)
        if (loaded && mark == resetMark) return
        if (loaded) { // the app asked to forget
            synchronized(w) { w.clear() }
            resetMark = mark
            XLog.log("Focus: learned profile reset")
            return
        }
        loaded = true
        resetMark = mark
        Thread({
            runCatching {
                val json = c.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "focus_profile_get", null, null)?.getString("profile")
                if (!json.isNullOrBlank()) {
                    val o = JSONObject(json)
                    synchronized(w) { o.keys().forEach { k -> if (k !in w) w[k] = o.optDouble(k).toFloat() } }
                    XLog.log("Focus: learned profile loaded (${o.length()} signals)")
                }
            }
        }, "TikMod-FocusLoad").start()
    }

    private fun save(ctx: Context?) {
        val c = ctx ?: Prefs.hostContext ?: return
        val json = synchronized(w) {
            JSONObject().apply { w.entries.sortedByDescending { abs(it.value) }.take(400).forEach { put(it.key, (it.value * 100).toInt() / 100.0) } }
        }.toString()
        Thread({ runCatching { c.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "focus_profile_put", json, null) } }, "TikMod-FocusSave").start()
    }
}

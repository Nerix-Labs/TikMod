package com.mts.tikmod.focus

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.Reflect
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.log10
import kotlin.math.tanh

/**
 * "Stop the addiction": decides, for each For You / Following post, whether it stays while the mode is active.
 *
 * Every post gets an **attraction score** (0–100): how likely it is to keep you scrolling. It comes from what
 * TikMod learned about you ([FocusLearn]: posts you watch to the end vs. posts you skip), whether you follow the
 * creator, how viral the post is, and how short it is. Posts above the running median are "hooks", the rest are
 * "plain". The **boredom dial** ([Prefs.KEY_FOCUS_BOREDOM], 0–100 %) sets which share of what you see is plain:
 * at 90 % only about 1 in 10 posts is a hook. The dial can apply at once or grow over [Prefs.KEY_FOCUS_RAMP]
 * minutes. On top of that: topics you allow always pass (or, in strict mode, nothing else does), blocked words and
 * hidden content types always go. Anything that can't be read is kept, and a page never ends up empty.
 */
object Focus {
    const val MIN_KEEP = 1

    enum class Why { MANUAL, WINDOW, BEDTIME, WINDDOWN, SESSION }

    /** [strength]: 0 = off … 1 = full. [until]: when the manual switch ends (0 = no end / not manual). */
    data class State(val strength: Float, val why: Set<Why>, val bedtime: Boolean, val until: Long = 0L) {
        val active get() = strength > 0f
    }

    val OFF = State(0f, emptySet(), false)

    // ---------------------------------------------------------------- switching (TikMod app side)

    fun untilMs(ctx: Context?): Long = Prefs.string(ctx, Prefs.KEY_FOCUS_UNTIL).toLongOrNull() ?: 0L

    /** Switched on by hand, and not past the chosen end time. */
    fun manualOn(ctx: Context?, now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.bool(ctx, Prefs.KEY_FOCUS_ON)) return false
        val until = untilMs(ctx)
        return until <= 0L || now < until
    }

    /** On now, for [minutes] (0 = until turned off). */
    fun start(ctx: Context, minutes: Int) {
        val now = System.currentTimeMillis()
        Prefs.setString(ctx, Prefs.KEY_FOCUS_UNTIL, if (minutes > 0) (now + minutes * 60_000L).toString() else "0")
        Prefs.setString(ctx, Prefs.KEY_FOCUS_STARTED, now.toString())
        Prefs.setBool(ctx, Prefs.KEY_FOCUS_ON, true)
        refreshTile(ctx)
    }

    fun stop(ctx: Context) {
        Prefs.setBool(ctx, Prefs.KEY_FOCUS_ON, false)
        Prefs.setString(ctx, Prefs.KEY_FOCUS_UNTIL, "0")
        refreshTile(ctx)
    }

    fun extend(ctx: Context, minutes: Int) {
        val until = untilMs(ctx)
        if (until <= 0L) return
        Prefs.setString(ctx, Prefs.KEY_FOCUS_UNTIL, (maxOf(until, System.currentTimeMillis()) + minutes * 60_000L).toString())
    }

    /** Keeps the Quick Settings tile in step when the switch moves inside the app. */
    fun refreshTile(ctx: Context) {
        runCatching { android.service.quicksettings.TileService.requestListeningState(ctx, android.content.ComponentName(ctx, FocusTileService::class.java)) }
    }

    // ---------------------------------------------------------------- session (TikTok in front)

    @Volatile private var sessionStart = 0L
    @Volatile private var lastSeen = 0L
    private const val SESSION_GAP_MS = 10 * 60_000L

    /** TikTok came to the front (or is still there). A pause of 10 minutes starts a new session. */
    fun onForeground(now: Long = System.currentTimeMillis()) {
        if (sessionStart == 0L || now - lastSeen > SESSION_GAP_MS) sessionStart = now
        lastSeen = now
    }

    fun onBackground(now: Long = System.currentTimeMillis()) { lastSeen = now }

    fun sessionMinutes(now: Long = System.currentTimeMillis()): Int =
        if (sessionStart == 0L || now - lastSeen > SESSION_GAP_MS) 0 else ((now - sessionStart) / 60_000L).toInt()

    // ---------------------------------------------------------------- state

    fun state(ctx: Context?, cal: Calendar = Calendar.getInstance()): State = runCatching {
        val why = HashSet<Why>()
        var s = 0f
        var until = 0L
        if (manualOn(ctx, cal.timeInMillis)) { why += Why.MANUAL; s = 1f; until = untilMs(ctx) }
        if (Prefs.bool(ctx, Prefs.KEY_FOCUS_SCHEDULE) &&
            inWindow(cal, Prefs.string(ctx, Prefs.KEY_FOCUS_FROM), Prefs.string(ctx, Prefs.KEY_FOCUS_TO), days(Prefs.string(ctx, Prefs.KEY_FOCUS_DAYS)))) {
            why += Why.WINDOW; s = 1f
        }
        var bedtime = false
        if (Prefs.bool(ctx, Prefs.KEY_BEDTIME_ON)) {
            val from = Prefs.string(ctx, Prefs.KEY_BEDTIME_FROM)
            val bedDays = days(Prefs.string(ctx, Prefs.KEY_BEDTIME_DAYS))
            if (inWindow(cal, from, Prefs.string(ctx, Prefs.KEY_BEDTIME_TO), bedDays)) {
                bedtime = true; why += Why.BEDTIME; s = 1f
            } else {
                val wind = Prefs.string(ctx, Prefs.KEY_WINDDOWN).toIntOrNull() ?: 0
                val left = minutesUntil(cal, from, bedDays)
                if (wind > 0 && left in 0..wind) {
                    why += Why.WINDDOWN
                    s = maxOf(s, (1f - left.toFloat() / wind).coerceIn(0.25f, 1f))
                }
            }
        }
        val limit = Prefs.string(ctx, Prefs.KEY_SESSION_LIMIT).toIntOrNull() ?: 0
        if (limit > 0 && sessionMinutes() >= limit) {
            why += Why.SESSION
            if (s == 0f) s = 1f
        }
        State(s, why, bedtime, if (why == setOf(Why.MANUAL)) until else 0L)
    }.getOrDefault(OFF)

    /** Calendar.DAY_OF_WEEK numbers from "1,2,…". Empty = every day. */
    fun days(s: String): Set<Int> = s.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

    fun minutesOf(hhmm: String): Int? {
        val p = hhmm.trim().split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * Inside [from, to) on one of [days]. A window that crosses midnight (23:00 → 07:00) belongs to the day it
     * starts on, so "Friday night" also covers early Saturday morning.
     */
    fun inWindow(cal: Calendar, from: String, to: String, days: Set<Int>): Boolean {
        val f = minutesOf(from) ?: return false
        val t = minutesOf(to) ?: return false
        if (f == t) return false
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = cal.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (today == Calendar.SUNDAY) Calendar.SATURDAY else today - 1
        fun dayOk(d: Int) = days.isEmpty() || d in days
        return if (f < t) now in f until t && dayOk(today)
        else (now >= f && dayOk(today)) || (now < t && dayOk(yesterday))
    }

    /** Minutes until the window starting at [from] opens today (only if it's later today), else -1. */
    fun minutesUntil(cal: Calendar, from: String, days: Set<Int>): Int {
        val f = minutesOf(from) ?: return -1
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = cal.get(Calendar.DAY_OF_WEEK)
        if (days.isNotEmpty() && today !in days) return -1
        return if (f >= now) f - now else -1
    }

    // ---------------------------------------------------------------- boredom dial

    /** When the mode was last seen switching on in this process (for the gradual ramp of automatic modes). */
    @Volatile private var activeSince = 0L

    fun target(ctx: Context?): Float = ((Prefs.string(ctx, Prefs.KEY_FOCUS_BOREDOM).toIntOrNull() ?: 70).coerceIn(0, 100)) / 100f

    fun rampMinutes(ctx: Context?): Int = (Prefs.string(ctx, Prefs.KEY_FOCUS_RAMP).toIntOrNull() ?: 0).coerceAtLeast(0)

    /** 0..1 along the ramp ([rampMinutes] = 0: straight to 1). */
    fun rampProgress(ctx: Context?, st: State, now: Long = System.currentTimeMillis()): Float {
        val ramp = rampMinutes(ctx)
        if (ramp <= 0) return 1f
        val since = if (Why.MANUAL in st.why) (Prefs.string(ctx, Prefs.KEY_FOCUS_STARTED).toLongOrNull() ?: 0L).takeIf { it > 0 } ?: activeSince
        else activeSince.takeIf { it > 0 } ?: now
        return ((now - since).toFloat() / (ramp * 60_000f)).coerceIn(0f, 1f)
    }

    /** The share of plain (non-hook) posts the feed aims for right now, 0..1. */
    fun boredomNow(ctx: Context?, st: State): Float {
        if (!st.active) return 0f
        var x = target(ctx) * rampProgress(ctx, st) * st.strength
        if (Why.SESSION in st.why) x = minOf(1f, x + 0.15f)
        if (st.bedtime) x = maxOf(x, 0.9f)
        return x
    }

    // ---------------------------------------------------------------- per-page settings snapshot

    /** An allowed word and the topic it came from (null = the user's own word). */
    class Allow(val word: String, val topic: String?)

    class Rules(
        val state: State,
        val boredom: Float,
        val strict: Boolean,
        val allow: List<Allow>,
        val block: List<String>,
        val hide: Set<String>
    )

    fun allowList(ctx: Context?): List<Allow> {
        val topics = Prefs.string(ctx, Prefs.KEY_FOCUS_TOPICS).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val out = LinkedHashMap<String, Allow>()
        words(Prefs.string(ctx, Prefs.KEY_FOCUS_ALLOW_WORDS)).forEach { out[it] = Allow(it, null) }
        topics.forEach { id -> FocusTopics.byId(id)?.words?.forEach { w -> val l = w.lowercase(); if (l !in out) out[l] = Allow(l, id) } }
        return out.values.toList()
    }

    fun rules(ctx: Context?): Rules? {
        val st = state(ctx)
        if (!st.active) { activeSince = 0L; return null }
        if (activeSince == 0L) activeSince = System.currentTimeMillis()
        val hide = Prefs.string(ctx, Prefs.KEY_FOCUS_HIDE).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        if (st.bedtime) { hide += "viral"; hide += "short" }
        val allow = allowList(ctx)
        return Rules(st, boredomNow(ctx, st), Prefs.bool(ctx, Prefs.KEY_FOCUS_STRICT) && allow.isNotEmpty(), allow,
            words(Prefs.string(ctx, Prefs.KEY_FOCUS_BLOCK_WORDS)), hide)
    }

    fun words(s: String) = s.split(',', '\n', ';').map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- one post

    /**
     * [code]: hidden — block, live, photo, shop, short, viral, offtopic (strict), hook (too attractive for the dial);
     * kept — topic, plain, allowed (a hook the dial still lets through). [score]: attraction 0..100 (-1 = not scored).
     */
    class Decision(val hide: Boolean, val code: String, val word: String? = null, val topic: String? = null, val score: Int = -1)

    // Running balance of what was let through, so the dial holds across pages (slowly forgets).
    private var plainShown = 0f
    private var hooksShown = 0f
    private val recent = ArrayDeque<Int>()

    /**
     * One decision per post, remembered: TikTok asks for the same feed page several times (and keeps copies), and
     * the dial's running balance would otherwise answer differently each time. Copies of a page that disagree make
     * TikTok's own lists drift apart: the feed gets stuck or jumps back to an old, frozen video.
     */
    private val memo = object : LinkedHashMap<String, Decision>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Decision>?) = size > 3000
    }

    private fun idOf(a: Any): String? = Reflect.string(a, "getAid", "aid")?.takeIf { it.isNotBlank() }

    /** Whether [aweme] was already decided (a repeat of a page seen before). */
    @Synchronized
    fun known(aweme: Any): Boolean = idOf(aweme)?.let { memo.containsKey(it) } == true

    /** Some post was hidden by this mode since TikTok started (so later copies of the feed must keep it hidden). */
    @Volatile var anyHidden = false
        private set

    /**
     * While the mode is off: a post already handed to TikTok is remembered as shown, so switching the mode on
     * mid-scroll never pulls a loaded post out from under the feed; one hidden earlier stays hidden after switching
     * it off. Only new posts follow the new setting.
     */
    @Synchronized
    fun passive(aweme: Any): Decision {
        val id = idOf(aweme) ?: return Decision(false, "plain")
        return memo.getOrPut(id) { Decision(false, "plain") }
    }

    /** [aweme] was put back so a page isn't empty: it stays shown in every later copy of the feed too. */
    @Synchronized
    fun markKept(aweme: Any) {
        val id = idOf(aweme) ?: return
        val old = memo[id]
        memo[id] = Decision(false, "filler", old?.word, old?.topic, old?.score ?: -1)
    }

    /** What happens to [aweme] under [r]. Never throws (an unreadable post is kept). */
    @Synchronized
    fun decide(aweme: Any, r: Rules): Decision {
        val id = runCatching { idOf(aweme) }.getOrNull()
        id?.let { memo[it] }?.let { return it }
        val d = runCatching { decideOrThrow(aweme, r) }.getOrElse { Decision(false, "plain") }
        if (id != null) memo[id] = d
        if (d.hide) anyHidden = true
        return d
    }

    private fun decideOrThrow(aweme: Any, r: Rules): Decision {
        val type = Reflect.int(aweme, "getAwemeType", "awemeType")
        val text = textOf(aweme, withSuggest = true)
        val tokens = tokens(text)
        r.block.firstOrNull { matches(text, tokens, it) }?.let { return Decision(true, "block", it) }
        if ("live" in r.hide && (type == 101 || Reflect.get(aweme, "getRoomFeedCellStruct", "roomFeedCellStruct") != null)) return Decision(true, "live")
        if ("photo" in r.hide && type == 150) return Decision(true, "photo")
        if ("shop" in r.hide && isShop(aweme)) return Decision(true, "shop")
        val sec = durationSec(aweme)
        if ("short" in r.hide && sec in 1..14) return Decision(true, "short")
        if ("viral" in r.hide && isViral(aweme)) return Decision(true, "viral")

        r.allow.firstOrNull { matches(text, tokens, it.word) }?.let {
            plainShown += 1f
            return Decision(false, "topic", it.word, it.topic)
        }
        if (r.strict) return Decision(true, "offtopic")

        val score = attraction(aweme)
        val hook = score >= threshold()
        remember(score)
        plainShown *= 0.97f; hooksShown *= 0.97f
        val x = r.boredom
        if (!hook) { plainShown += 1f; return Decision(false, "plain", score = score) }
        // Let a hook through only while hooks stay within (1 - x) of what was shown.
        val ok = x <= 0f || (x < 1f && (hooksShown + 1f) * x <= (1f - x) * (plainShown + 1f))
        if (ok) { hooksShown += 1f; return Decision(false, "allowed", score = score) }
        return Decision(true, "hook", score = score)
    }

    private fun remember(score: Int) {
        recent.addLast(score)
        while (recent.size > 150) recent.removeFirst()
    }

    /** "Hook" = at or above the median of recent posts (40 until there's enough to tell). */
    private fun threshold(): Int {
        if (recent.size < 12) return 40
        val s = recent.sorted()
        return s[s.size / 2]
    }

    /**
     * How likely [a] is to keep you scrolling, 0..100: what TikMod learned you linger on (or skip), a creator you
     * follow, how viral it is, and how short.
     */
    fun attraction(a: Any): Int {
        var s = 15f
        s += 40f * tanh(FocusLearn.affinity(a) / 3f)
        val author = Reflect.get(a, "getAuthor", "author")
        if (author != null && Reflect.int(author, "getFollowStatus", "followStatus") in 1..2) s += 18f
        val st = Reflect.get(a, "getStatistics", "statistics")
        val likes = if (st != null) Reflect.long(st, "getDiggCount", "diggCount") else 0L
        if (likes > 0) s += ((log10(likes.toDouble()) - 3.0).coerceIn(0.0, 3.5) * 7.0).toFloat()
        val sec = durationSec(a)
        if (sec in 1..15) s += 10f else if (sec in 16..30) s += 5f
        return s.toInt().coerceIn(0, 100)
    }

    /** Which hidden post comes back first when a page would end up empty (the least harmful one). */
    fun restorePriority(d: Decision?): Int = when (d?.code) {
        "hook" -> 200 - d.score
        "offtopic" -> 150
        "short", "viral" -> 100
        else -> 0
    }

    /** Caption + hashtags (+ TikTok's own search words for the post), lower case. */
    fun textOf(a: Any, withSuggest: Boolean = false): String {
        val sb = StringBuilder(Reflect.string(a, "getDesc", "desc") ?: "")
        hashtags(a).forEach { sb.append(" #").append(it) }
        if (withSuggest) suggestWords(a).forEach { sb.append(" | ").append(it) }
        return sb.toString().lowercase()
    }

    fun hashtags(a: Any): List<String> {
        val out = LinkedHashSet<String>()
        (Reflect.get(a, "getChallengeList", "challengeList") as? List<*>)?.forEach {
            Reflect.string(it, "getChallengeName", "challengeName")?.takeIf { s -> s.isNotBlank() }?.let { h -> out += h.lowercase() }
        }
        (Reflect.get(a, "getTextExtra", "textExtra") as? List<*>)?.forEach {
            Reflect.string(it, "getHashTagName", "hashTagName")?.takeIf { s -> s.isNotBlank() }?.let { h -> out += h.lowercase() }
        }
        return out.toList()
    }

    /** The "Search: …" words TikTok attaches to a post (what it thinks the post is about). */
    fun suggestWords(a: Any): List<String> = runCatching {
        val list = Reflect.get(a, "getCommentSuggestWordList", "commentSuggestWordList") ?: return emptyList()
        val out = ArrayList<String>()
        (Reflect.get(list, "getSuggestWords", "suggestWords") as? List<*>)?.forEach { s ->
            (Reflect.get(s, "getWords", "words") as? List<*>)?.forEach { w ->
                Reflect.string(w, "getWord", "word")?.trim()?.takeIf { it.isNotEmpty() && it !in out }?.let { out += it }
            }
        }
        out.take(6)
    }.getOrDefault(emptyList())

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private const val HE_PREFIXES = "הובלמשכ"

    fun tokens(text: String): Set<String> {
        val out = HashSet<String>()
        for (w in text.split(SPLIT)) {
            if (w.isEmpty()) continue
            out += w
            // Hebrew glues one-letter prefixes to words ("המדע", "ולימוד").
            if (w.length > 3 && w[0] in HE_PREFIXES) out += w.substring(1)
        }
        return out
    }

    /** Multi-word phrases: substring. Short words: whole word. Longer words: word prefix (plural, #tags). */
    fun matches(text: String, tokens: Set<String>, word: String): Boolean = when {
        word.contains(' ') -> text.contains(word)
        word.length >= 5 -> word in tokens || tokens.any { it.startsWith(word) }
        else -> word in tokens
    }

    fun durationSec(a: Any): Long {
        val ms = Reflect.long(Reflect.get(a, "getVideo", "video"), "getDuration", "duration")
        return if (ms > 0) ms / 1000 else 0
    }

    /** A hit: 300k+ likes or 3M+ plays. */
    fun isViral(a: Any): Boolean {
        val st = Reflect.get(a, "getStatistics", "statistics") ?: return false
        return Reflect.long(st, "getDiggCount", "diggCount") >= 300_000 || Reflect.long(st, "getPlayCount", "playCount") >= 3_000_000
    }

    private fun isShop(a: Any): Boolean =
        Reflect.int(a, "getProductsCount", "productsCount") > 0 ||
            (Reflect.get(a, "getProductsInfo", "productsInfo") as? List<*>)?.isNotEmpty() == true ||
            Reflect.get(a, "getEcomAnchorViewObject", "ecomAnchorViewObject") != null

    // ---------------------------------------------------------------- activity log (shown in the TikMod app)

    /** One line of the "what happened in your feed" list. */
    fun logEntry(a: Any, d: Decision?, code: String = d?.code ?: "plain", hide: Boolean = d?.hide ?: false, boredom: Float = 0f): JSONObject = JSONObject().apply {
        put("id", Reflect.string(a, "getAid", "aid") ?: "")
        put("t", System.currentTimeMillis())
        put("h", hide)
        put("c", code)
        d?.word?.let { put("w", it) }
        d?.topic?.let { put("tp", it) }
        put("sc", d?.score ?: -1)
        put("x", (boredom * 100).toInt())
        val desc = (Reflect.string(a, "getDesc", "desc") ?: "").replace('\n', ' ').trim()
        put("d", if (desc.length > 90) desc.take(88) + "…" else desc)
        Reflect.get(a, "getAuthor", "author")?.let { au -> Reflect.string(au, "getUniqueId", "uniqueId")?.let { put("a", it) } }
        suggestWords(a).take(3).takeIf { it.isNotEmpty() }?.let { put("s", it.joinToString(" · ")) }
    }
}

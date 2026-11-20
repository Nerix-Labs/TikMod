package com.mts.tikmod.hooks

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.mts.tikmod.Prefs
import com.mts.tikmod.focus.Focus
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feed cleaner for TikTok 47.9.x.
 *
 * Posts reach the vertical feed two ways, and both are filtered:
 *  1. feed pages from the server, converted into `FeedItemList`: every list handed out by `getItems()` is cleaned
 *     once (ads, LIVE / shop / paid partnership / photo posts if chosen, keyword & creator filters, and the
 *     "Stop the addiction" mode);
 *  2. posts TikTok inserts on the phone afterwards — client-side ad re-ranking ("ad_rerank"), mid-feed ad
 *     slots — through `BaseListFragmentPanel` and the feed pager adapter. Only ads and paid content are removed
 *     there (those lists are also used for a profile's or a search's video list, which must stay intact).
 * Forcing `isAd()=false` is never done: it only makes an ad play as a normal video.
 * Plus: comment ads and the open-screen splash ad activity.
 */
class AdsHook : BaseHook() {
    override val name: String = "Feed Cleaner (ads, LIVE, shop, splash)"

    private companion object {
        const val FEED_LIST = "com.ss.android.ugc.aweme.feed.model.FeedItemList"
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        const val PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel"
    }

    private val filtered: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private val pendingStats = AtomicInteger(0)
    private val pendingFocus = AtomicInteger(0)
    @Volatile private var lastPageSig = 0
    @Volatile private var awemeClass: Class<*>? = null
    @Volatile private var insertLogs = 0

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            awemeClass = XposedHelpers.findClassIfExists(AWEME, classLoader)
            val feedList = XposedHelpers.findClassIfExists(FEED_LIST, classLoader)
            status = status.copy(foundClass = feedList?.name ?: "FeedItemList not found")

            if (feedList != null) {
                hookAllMethodsSafely(feedList, "getItems", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<Any?> ?: return
                        if (list.isEmpty() || !filtered.add(list)) return
                        if (filtered.size > 256) filtered.clear()
                        runCatching { clean(list, param.thisObject, context) }
                            .onFailure { XLog.log("AdsHook: ${it.message}") }
                    }
                })
            }

            hookInsertions(classLoader, context)

            // Comment ads
            XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.comment.model.CommentItemList", classLoader)?.let { cls ->
                hookAllMethodsSafely(cls, "getItems", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Prefs.bool(context, Prefs.KEY_HIDE_COMMENT_ADS)) return
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<Any?> ?: return
                        runCatching {
                            val removed = list.removeAll { c -> c != null && (Reflect.int(c, "getAdFlag", "adFlag") != 0 || Reflect.get(c, "getAwemeRawAd", "awemeRawAd") != null) }
                            if (removed) bumpStats(context, 1)
                        }
                    }
                })
            }

            // Open-screen (splash) ads
            XposedHelpers.findClassIfExists("com.bytedance.ies.ugc.aweme.commercialize.splash.show.NormalSplashAdActivity", classLoader)?.let { cls ->
                hookAllMethodsSafely(cls, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Prefs.bool(context, Prefs.KEY_BLOCK_SPLASH_ADS)) return
                        (param.thisObject as? Activity)?.let {
                            XLog.log("AdsHook: splash ad skipped")
                            bumpStats(context, 1)
                            it.finish()
                        }
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------ server pages (FeedItemList)

    private fun clean(list: MutableList<Any?>, feed: Any?, context: Context?) {
        // Diagnostics: one line per *new* feed page (getItems hands out several copies of the same page).
        runCatching {
            val nn = list.filterNotNull()
            val sig = nn.joinToString(",") { Reflect.string(it, "getAid", "aid") ?: "" }.hashCode()
            if (sig == lastPageSig) return@runCatching
            lastPageSig = sig
            XLog.log("AdsHook: page items=${nn.size} ads=${nn.count { isAd(it) }} paid=${nn.count { isPaid(it) }} live=${nn.count { isLive(it) }} " +
                "shop=${nn.count { isShop(it) }} types=${nn.groupingBy { Reflect.int(it, "getAwemeType", "awemeType") }.eachCount()}")
        }
        val removeAds = Prefs.isRemoveAds(context)
        val hideLive = Prefs.bool(context, Prefs.KEY_HIDE_FEED_LIVE)
        val hideShop = Prefs.bool(context, Prefs.KEY_HIDE_SHOP)
        val hidePaid = Prefs.bool(context, Prefs.KEY_HIDE_PAID_PARTNERSHIP)
        val hidePhotos = Prefs.bool(context, Prefs.KEY_HIDE_PHOTO_POSTS)
        val hideReposts = Prefs.bool(context, Prefs.KEY_HIDE_REPOSTS)
        val cleanFeed = Prefs.bool(context, Prefs.KEY_CLEAN_FEED)
        val keywords = splitList(Prefs.string(context, Prefs.KEY_FEED_KEYWORDS))
        val creators = splitList(Prefs.string(context, Prefs.KEY_MUTED_CREATORS)).map { it.removePrefix("@") }
        val minSec = Prefs.string(context, Prefs.KEY_HIDE_SHORT).toIntOrNull() ?: 0
        val maxSec = Prefs.string(context, Prefs.KEY_HIDE_LONG).toIntOrNull() ?: 0
        val focus = runCatching { Focus.rules(context) }.getOrNull()
        if (!removeAds && !hideLive && !hideShop && !hidePaid && !hidePhotos && !hideReposts && !cleanFeed &&
            keywords.isEmpty() && creators.isEmpty() && minSec <= 0 && maxSec <= 0 && focus == null && !Focus.anyHidden) {
            list.forEach { a -> if (a != null) runCatching { Focus.passive(a) } }
            return
        }

        val before = list.size
        val original = list.filterNotNull()
        // Only posts not decided before go to the activity log (repeats of a page would show twice).
        val fresh: Set<Any> = if (focus != null) original.filterTo(Collections.newSetFromMap(IdentityHashMap())) { !Focus.known(it) } else emptySet()
        val reasons = HashMap<String, Int>()
        val focusOut = ArrayList<Any>()
        val decisions = IdentityHashMap<Any, Focus.Decision>()
        val reasonOf = IdentityHashMap<Any, String>()
        val keep = list.filter { a ->
            if (a == null) return@filter true
            val reason = runCatching {
                when {
                    removeAds && isAd(a) -> "ad"
                    hidePaid && isPaid(a) -> "paid"
                    hideLive && isLive(a) -> "live"
                    hideShop && isShop(a) -> "shop"
                    hidePhotos && Reflect.int(a, "getAwemeType", "awemeType") == 150 -> "photo"
                    hideReposts && isRepost(a) -> "repost"
                    creators.isNotEmpty() && isFrom(a, creators) -> "creator"
                    keywords.isNotEmpty() && matchesKeyword(a, keywords) -> "keyword"
                    (minSec > 0 || maxSec > 0) && matchesDuration(a, minSec, maxSec) -> "duration"
                    cleanFeed && isCard(a) -> "card"
                    focus != null -> Focus.decide(a, focus).let { d ->
                        decisions[a] = d
                        if (d.hide) { focusOut += a; "focus:" + d.code } else null
                    }
                    // Mode off: posts it hid earlier stay out, everything else is remembered as shown.
                    else -> Focus.passive(a).takeIf { it.hide }?.let { "focus:" + it.code }
                }
            }.getOrNull() // a post that can't be classified stays
            if (reason != null) { reasons[reason] = (reasons[reason] ?: 0) + 1; reasonOf[a] = reason }
            reason == null
        }.toMutableList()

        // Focus mode never empties a page (TikTok would stall): the post closest to the rules comes back.
        var back: Set<Any> = emptySet()
        if (focus != null && keep.size < Focus.MIN_KEEP && focusOut.isNotEmpty()) {
            back = focusOut.sortedByDescending { Focus.restorePriority(decisions[it]) }.take(Focus.MIN_KEEP - keep.size).toSet()
            back.forEach { Focus.markKept(it) }
            val restored = list.filter { it != null && (it in keep || it in back) }
            keep.clear(); keep.addAll(restored)
            reasons["focus:kept"] = back.size
        }
        if (focus != null && fresh.isNotEmpty()) sendFocusLog(context, original.filter { it in fresh }.map { a ->
            val d = decisions[a]
            when {
                a in back -> Focus.logEntry(a, d, "filler", false, focus.boredom)
                d != null -> Focus.logEntry(a, d, boredom = focus.boredom)
                else -> Focus.logEntry(a, null, reasonOf[a] ?: "plain", reasonOf[a] != null, focus.boredom)
            }
        })
        // Never empty the page completely (TikTok would treat it as "no more content").
        if (keep.size == before || keep.isEmpty()) return
        list.clear()
        list.addAll(keep)
        if (removeAds && feed != null) {
            runCatching { XposedHelpers.callMethod(feed, "setHasAd", false) }
            runCatching { (Reflect.get(feed, "getPreloadAds", "preloadAds") as? MutableList<*>)?.clear() }
        }
        XLog.log("AdsHook: feed page $before -> ${keep.size} $reasons${focus?.let { " focus=${"%.2f".format(it.state.strength)} ${it.state.why}" } ?: ""}")
        val nonFocus = reasons.filterKeys { !it.startsWith("focus:") }.values.sum()
        bumpStats(context, nonFocus)
        bumpFocus(context, before - keep.size - nonFocus)
    }

    // ------------------------------------------------------------------ posts inserted on the phone

    /**
     * Client-side insertions: `BaseListFragmentPanel` receives batches (an insert event holding a List, an index and
     * a source such as "ad_rerank") and hands them to the feed pager adapter (`getItem(int)` / `setData(List)`).
     * Every method there that takes posts gets its ads removed before it runs; a call left with nothing to insert
     * is skipped. Names are obfuscated, so methods are picked by their parameter types.
     */
    private fun hookInsertions(cl: ClassLoader, context: Context?) {
        val aweme = awemeClass ?: return
        val panel = XposedHelpers.findClassIfExists(PANEL, cl) ?: run { XLog.log("AdsHook: feed panel not found"); return }
        // The pager adapter: a field of the panel whose class has getItem(int) -> Aweme and setData(List).
        val adapter = generateSequence<Class<*>>(panel) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .map { it.type }.firstOrNull { t ->
                t.declaredMethods.any { it.name == "getItem" && it.returnType == aweme && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) } &&
                    t.declaredMethods.any { it.name == "setData" && it.parameterTypes.contentEquals(arrayOf(List::class.java)) }
            }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!Prefs.isRemoveAds(context) && !Prefs.bool(context, Prefs.KEY_HIDE_PAID_PARTNERSHIP)) return
                runCatching { scrubCall(param, context) }.onFailure { XLog.log("AdsHook: insert scrub failed: ${it.message}") }
            }
        }
        var n = 0
        for (c in listOfNotNull(panel, adapter)) {
            for (m in c.declaredMethods) {
                // The panel also has many per-post callbacks (play, like, share…): only its batch methods are touched.
                if (Modifier.isAbstract(m.modifiers) || !takesPosts(m, aweme, single = c === adapter)) continue
                runCatching { XposedBridge.hookMethod(m, hook); n++ }
            }
        }
        XLog.log("AdsHook: watching $n insert method(s) on ${panel.simpleName}${adapter?.let { " + ${it.name}" } ?: " (adapter not found)"}")
    }

    /** (…, Aweme, …), (…, List, …) or an event object carrying a List plus a String source (the insert event). */
    private fun takesPosts(m: Method, aweme: Class<*>, single: Boolean): Boolean {
        val ps = m.parameterTypes
        if (ps.isEmpty() || ps.size > 4) return false
        if (m.returnType != Void.TYPE && m.returnType != java.lang.Boolean.TYPE) return false
        // Adapter: only (int, Aweme), one post inserted at a position. Its list methods (setData…) carry the
        // pager's whole live list: changing that under the pager makes the feed stick or jump to an old video.
        if (single) return ps.size == 2 && ps[0] == Int::class.javaPrimitiveType && ps[1] == aweme
        return ps.any { p ->
            p == List::class.java || p == java.util.ArrayList::class.java ||
                (!p.isPrimitive && !p.name.startsWith("java.") && !p.name.startsWith("android") && isInsertEvent(p))
        }
    }

    private fun isInsertEvent(c: Class<*>): Boolean {
        val fs = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        return fs.size in 2..5 && fs.any { it.type == List::class.java } && fs.any { it.type == String::class.java }
    }

    private fun scrubCall(param: XC_MethodHook.MethodHookParam, context: Context?) {
        val aweme = awemeClass ?: return
        val args = param.args
        var emptied = false
        var removedTotal = 0
        for (i in args.indices) {
            val a = args[i] ?: continue
            when {
                aweme.isInstance(a) -> if (isUnwanted(a, context)) { emptied = true; removedTotal++ }
                a is MutableList<*> -> {
                    // A list that comes with a count or a position is TikTok's own bookkeeping: left alone.
                    if (args.any { it is Int }) continue
                    val r = scrubList(a, context)
                    if (r > 0) { removedTotal += r; if (a.isEmpty()) emptied = true }
                }
                !a.javaClass.name.startsWith("java.") && isInsertEvent(a.javaClass) -> {
                    val f = a.javaClass.declaredFields.first { it.type == List::class.java }.apply { isAccessible = true }
                    val list = f.get(a) as? MutableList<*> ?: continue
                    val r = scrubList(list, context)
                    if (r > 0) { removedTotal += r; if (list.isEmpty()) emptied = true }
                }
            }
        }
        if (removedTotal == 0) return
        val src = args.firstNotNullOfOrNull { a -> a?.takeIf { !it.javaClass.name.startsWith("java.") && isInsertEvent(it.javaClass) }
            ?.let { e -> e.javaClass.declaredFields.firstOrNull { it.type == String::class.java }?.apply { isAccessible = true }?.get(e) as? String } }
        if (insertLogs++ < 40) XLog.log("AdsHook: removed $removedTotal inserted ad(s) in ${param.method.name}${src?.let { " (source $it)" } ?: ""}")
        bumpStats(context, removedTotal)
        if (emptied) param.result = if ((param.method as Method).returnType == java.lang.Boolean.TYPE) false else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun scrubList(list: MutableList<*>, context: Context?): Int {
        val aweme = awemeClass ?: return 0
        if (list.isEmpty() || list.none { aweme.isInstance(it) }) return 0
        val before = list.size
        runCatching { (list as MutableList<Any?>).removeAll { it != null && aweme.isInstance(it) && isUnwanted(it, context) } }
        return before - list.size
    }

    private fun isUnwanted(a: Any, context: Context?): Boolean = runCatching {
        (Prefs.isRemoveAds(context) && isAd(a)) || (Prefs.bool(context, Prefs.KEY_HIDE_PAID_PARTNERSHIP) && isPaid(a))
    }.getOrDefault(false)

    // ------------------------------------------------------------------ classification

    /**
     * Ads in every shape 47.9 sends: regular and soft ads, Spark / Promote (a creator's boosted post, labelled
     * "Sponsored" / "Promoted"), pseudo ads and ad-styled posts (`AwemeCommerceStruct.pseudoAdData` / `styleInfo`),
     * contextual / diversion ads and link ads.
     */
    private fun isAd(a: Any): Boolean {
        if (Reflect.field(a, "_isAd") == true || Reflect.call(a, "isAd") == true ||
            Reflect.field(a, "_isSoftAd") == true || Reflect.call(a, "isSoftAd") == true ||
            Reflect.get(a, "getAwemeRawAd", "awemeRawAd") != null ||
            Reflect.get(a, "getLinkAdData", "linkAdData") != null) return true
        Reflect.get(a, "getPromoteModel", "promoteModel")?.let { pm ->
            if (Reflect.call(pm, "isPromoteAdLabel") == true) return true
        }
        val c = commerce(a) ?: return false
        return Reflect.call(c, "isPseudoAd") == true || Reflect.get(c, "getPseudoAdData", "pseudoAdData") != null ||
            Reflect.get(c, "getStyleInfo", "styleInfo") != null ||
            Reflect.call(c, "isContextualAd") == true || Reflect.call(c, "isDiversionAd") == true ||
            positiveId(Reflect.string(c, "getAdId", "adId")) || positiveId(Reflect.string(c, "getCreativeId", "creativeId"))
    }

    private fun positiveId(s: String?) = (s?.trim()?.toLongOrNull() ?: 0L) > 0L

    private fun commerce(a: Any): Any? = Reflect.get(a, "getCommerceVideoAuthInfo", "mCommerceVideoAuthInfo")

    private fun isLive(a: Any): Boolean =
        Reflect.int(a, "getAwemeType", "awemeType") == 101 || Reflect.get(a, "getRoomFeedCellStruct", "roomFeedCellStruct") != null

    /** TikTok Shop: product anchors and links, shop LIVE inserts, POI products. */
    private fun isShop(a: Any): Boolean {
        if (Reflect.int(a, "getProductsCount", "productsCount") > 0) return true
        if ((Reflect.get(a, "getProductsInfo", "productsInfo") as? List<*>)?.isNotEmpty() == true) return true
        if (Reflect.get(a, "getEcomAnchorViewObject", "ecomAnchorViewObject") != null) return true
        if (Reflect.call(a, "getIsLiveHasProduct") == true) return true
        if (Reflect.get(a, "getOutflowProductData", "outflowProductData") != null) return true
        if (Reflect.int(a, "getProductRecallType", "productRecallType") > 0) return true
        val anchors = Reflect.get(a, "getAnchors", "anchors") as? List<*> ?: return false
        return anchors.any { Reflect.int(it, "getType", "type") in setOf(3, 6, 33, 35) }
    }

    /**
     * Paid partnership / branded content: the "Paid partnership" and "Promotional content" labels
     * (`AwemeCommerceStruct.isBrandedContent` / `brandedContentType` / `brandOrganicType` / `bcHashtag`),
     * brand accounts, Creator Marketplace (TCM) and Star Atlas orders, promote labels, and paid series.
     */
    private fun isPaid(a: Any): Boolean {
        if (Reflect.call(a, "isPaidContent") == true) return true
        if ((Reflect.get(a, "getBrandContentAccounts", "brandContentAccounts") as? List<*>)?.isNotEmpty() == true) return true
        if (Reflect.long(a, "getStarAtlasOrderId", "starAtlasOrderId") != 0L) return true
        if (Reflect.get(a, "getTcmInfo", "tcmInfo") != null) return true
        Reflect.get(a, "getPromoteModel", "promoteModel")?.let { pm ->
            if (!Reflect.string(pm, "getLabelText", "labelText").isNullOrBlank()) return true
        }
        val c = commerce(a) ?: return false
        return Reflect.call(c, "isBrandedContent") == true || Reflect.call(c, "isBrandOrganicContent") == true ||
            Reflect.long(c, "getBrandedContentType", "brandedContentType") != 0L ||
            Reflect.long(c, "getBrandOrganicType", "brandOrganicType") != 0L ||
            !Reflect.string(c, "getBCHashtag", "bcHashtag").isNullOrBlank()
    }

    private fun splitList(s: String) = s.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun matchesKeyword(a: Any, kws: List<String>): Boolean { val t = Focus.textOf(a); return kws.any { t.contains(it) } }

    private fun isFrom(a: Any, handles: List<String>): Boolean {
        val author = Reflect.get(a, "getAuthor", "author") ?: return false
        val uid = Reflect.string(author, "getUniqueId", "uniqueId")?.lowercase()
        val nick = Reflect.string(author, "getNickName", "nickName")?.lowercase()
        return (uid != null && uid in handles) || (nick != null && nick in handles)
    }

    private fun isRepost(a: Any): Boolean =
        Reflect.get(a, "getForwardItem", "forwardItem") != null ||
            Reflect.long(a, "getRepostFromGroupId", "repostFromGroupId") != 0L ||
            Reflect.call(a, "isForwardAweme") == true

    private fun matchesDuration(a: Any, minSec: Int, maxSec: Int): Boolean {
        val video = Reflect.get(a, "getVideo", "video") ?: return false
        val ms = Reflect.long(video, "getDuration", "duration")
        if (ms <= 0) return false
        val sec = ms / 1000
        return (minSec > 0 && sec < minSec) || (maxSec > 0 && sec > maxSec)
    }

    /** A feed cell with no playable media of its own: a "suggested accounts" / search / widget card. */
    private fun isCard(a: Any): Boolean {
        if (isLive(a) || isAd(a)) return false
        if (Reflect.int(a, "getAwemeType", "awemeType") == 150) return false
        if (Reflect.get(a, "getVideo", "video") != null) return false
        if (Reflect.get(a, "getImageInfos", "imageInfos") != null) return false
        if (Reflect.get(a, "getImagePostStruct", "imagePostStruct") != null) return false
        return true
    }

    /** Batched "blocked" counter shown in the TikMod app. */
    private fun bumpStats(contextArg: Context?, n: Int) = bump(contextArg, n, pendingStats, Prefs.STAT_ADS_BLOCKED)

    private fun bumpFocus(contextArg: Context?, n: Int) = bump(contextArg, n, pendingFocus, Prefs.STAT_FOCUS_FILTERED)

    /** What focus mode did with a page, for the live list in the TikMod app. */
    private fun sendFocusLog(contextArg: Context?, entries: List<org.json.JSONObject>) {
        val context = contextArg ?: Prefs.hostContext ?: return
        if (entries.isEmpty()) return
        val json = org.json.JSONArray(entries).toString()
        Thread({
            runCatching { context.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "focus_log", json, null) }
        }, "TikMod-FocusLog").start()
    }

    private fun bump(contextArg: Context?, n: Int, counter: AtomicInteger, key: String) {
        val context = contextArg ?: Prefs.hostContext
        if (n <= 0 || context == null) return
        val total = counter.addAndGet(n)
        if (total < 3) return
        counter.set(0)
        Thread({
            runCatching { context.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "add_stat", key, android.os.Bundle().apply { putInt("n", total) }) }
        }, "TikMod-Stats").start()
    }
}

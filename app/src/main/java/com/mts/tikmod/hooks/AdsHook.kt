package com.mts.tikmod.hooks

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feed cleaner for TikTok 47.7.x.
 *
 * The feed arrives as protobuf and is converted into `FeedItemList`, so JSON rewriting never sees it, and
 * forcing `isAd()=false` only makes ads play as regular videos. Instead, every list handed out by
 * `FeedItemList.getItems()` is filtered (once per list instance):
 *  - ads & soft ads (also TopView/splash items that come through the feed)
 *  - optional: LIVE rooms injected in the feed, TikTok Shop videos, paid partnerships, photo posts
 * Plus: comment ads and the open-screen splash ad activity.
 */
class AdsHook : BaseHook() {
    override val name: String = "Feed Cleaner (ads, LIVE, shop, splash)"

    private val filtered: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private val pendingStats = AtomicInteger(0)
    @Volatile private var lastPageSig = 0

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val feedList = XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.model.FeedItemList", classLoader)
            status = status.copy(foundClass = feedList?.name ?: "FeedItemList not found")

            if (feedList != null) {
                hookAllMethodsSafely(feedList, "getItems", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? MutableList<Any?> ?: return
                        if (list.isEmpty() || !filtered.add(list)) return
                        if (filtered.size > 256) filtered.clear()
                        runCatching { clean(list, param.thisObject, context) }
                            .onFailure { XLog.log("AdsHook: ${it.message}") }
                    }
                })
            }

            // Comment ads
            XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.comment.model.CommentItemList", classLoader)?.let { cls ->
                hookAllMethodsSafely(cls, "getItems", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Prefs.bool(context, Prefs.KEY_HIDE_COMMENT_ADS)) return
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

    private fun clean(list: MutableList<Any?>, feed: Any?, context: Context?) {
        // Diagnostics: one line per *new* feed page (getItems hands out several copies of the same page).
        runCatching {
            val nn = list.filterNotNull()
            val sig = nn.joinToString(",") { Reflect.string(it, "getAid", "aid") ?: "" }.hashCode()
            if (sig == lastPageSig) return@runCatching
            lastPageSig = sig
            XLog.log("AdsHook: page items=${nn.size} ads=${nn.count { isAd(it) }} live=${nn.count { isLive(it) }} " +
                "shop=${nn.count { isShop(it) }} photo=${nn.count { Reflect.int(it, "getAwemeType", "awemeType") == 150 }} " +
                "types=${nn.groupingBy { Reflect.int(it, "getAwemeType", "awemeType") }.eachCount()} " +
                "photoIds=${nn.filter { Reflect.int(it, "getAwemeType", "awemeType") == 150 }.mapNotNull { Reflect.string(it, "getAid", "aid") }}")
        }
        val removeAds = Prefs.isRemoveAds(context)
        val hideLive = Prefs.bool(context, Prefs.KEY_HIDE_FEED_LIVE)
        val hideShop = Prefs.bool(context, Prefs.KEY_HIDE_SHOP)
        val hidePaid = Prefs.bool(context, Prefs.KEY_HIDE_PAID_PARTNERSHIP)
        val hidePhotos = Prefs.bool(context, Prefs.KEY_HIDE_PHOTO_POSTS)
        if (!removeAds && !hideLive && !hideShop && !hidePaid && !hidePhotos) return

        val before = list.size
        val reasons = HashMap<String, Int>()
        val keep = list.filter { a ->
            if (a == null) return@filter true
            val reason = when {
                removeAds && isAd(a) -> "ad"
                hideLive && isLive(a) -> "live"
                hideShop && isShop(a) -> "shop"
                hidePaid && isPaid(a) -> "paid"
                hidePhotos && Reflect.int(a, "getAwemeType", "awemeType") == 150 -> "photo"
                else -> null
            }
            if (reason != null) reasons[reason] = (reasons[reason] ?: 0) + 1
            reason == null
        }
        // Never empty the page completely (TikTok would treat it as "no more content").
        if (keep.size == before || keep.isEmpty()) return
        list.clear()
        list.addAll(keep)
        if (removeAds && feed != null) {
            runCatching { XposedHelpers.callMethod(feed, "setHasAd", false) }
            runCatching { (Reflect.get(feed, "getPreloadAds", "preloadAds") as? MutableList<*>)?.clear() }
        }
        XLog.log("AdsHook: feed page $before -> ${keep.size} $reasons")
        bumpStats(context, before - keep.size)
    }

    private fun isAd(a: Any): Boolean =
        Reflect.field(a, "_isAd") == true || Reflect.call(a, "isAd") == true ||
            Reflect.field(a, "_isSoftAd") == true || Reflect.call(a, "isSoftAd") == true ||
            Reflect.get(a, "getAwemeRawAd", "awemeRawAd") != null

    private fun isLive(a: Any): Boolean =
        Reflect.int(a, "getAwemeType", "awemeType") == 101 || Reflect.get(a, "getRoomFeedCellStruct", "roomFeedCellStruct") != null

    private fun isShop(a: Any): Boolean {
        if (Reflect.int(a, "getProductsCount", "productsCount") > 0) return true
        if ((Reflect.get(a, "getProductsInfo", "productsInfo") as? List<*>)?.isNotEmpty() == true) return true
        if (Reflect.get(a, "getEcomAnchorViewObject", "ecomAnchorViewObject") != null) return true
        val anchors = Reflect.get(a, "getAnchors", "anchors") as? List<*> ?: return false
        return anchors.any { Reflect.int(it, "getType", "type") in setOf(3, 6, 33, 35) }
    }

    private fun isPaid(a: Any): Boolean {
        if (Reflect.call(a, "isPaidContent") == true) return true
        if ((Reflect.get(a, "getBrandContentAccounts", "brandContentAccounts") as? List<*>)?.isNotEmpty() == true) return true
        val commerce = Reflect.get(a, "getCommerceVideoAuthInfo", "commerceVideoAuthInfo") ?: return false
        return Reflect.long(commerce, "getBrandedContentType", "brandedContentType") != 0L
    }

    /** Batched "blocked" counter shown in the TikMod app. */
    private fun bumpStats(contextArg: Context?, n: Int) {
        val context = contextArg ?: Prefs.hostContext
        if (n <= 0 || context == null) return
        val total = pendingStats.addAndGet(n)
        if (total < 3) return
        pendingStats.set(0)
        runCatching { context.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "add_stat", "ads_blocked", android.os.Bundle().apply { putInt("n", total) }) }
    }
}

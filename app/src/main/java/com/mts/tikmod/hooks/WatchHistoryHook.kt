package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.WatchTracker
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Watch history: the post the feed puts on screen (`VideoViewCell.onPageSelected`, not obfuscated; the same target
 * "Stop the addiction" learns from) goes to [WatchTracker].
 */
class WatchHistoryHook : BaseHook() {
    override val name: String = "Watch history"

    private companion object {
        const val CELL = "com.ss.android.ugc.aweme.feed.adapter.VideoViewCell"
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val cell = XposedHelpers.findClassIfExists(CELL, classLoader) ?: throw IllegalStateException("VideoViewCell not found")
            val n = hookAllMethodsSafely(cell, "onPageSelected", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { WatchTracker.onSelected(Reflect.call(param.thisObject, "getAweme")) }
                }
            })
            if (n == 0) throw IllegalStateException("onPageSelected not found")
            status = status.copy(foundClass = "VideoViewCell.onPageSelected")
            XLog.log("WatchHistory: following the feed ($n)")
        }
    }
}

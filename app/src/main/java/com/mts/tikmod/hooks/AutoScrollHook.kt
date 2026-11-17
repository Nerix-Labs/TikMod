package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

class AutoScrollHook : BaseHook() {
    override val name: String = "Hands-Free Auto-Scroll Feed"

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "Feed Player Completion Listener")

            val playerControllerClass = XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.video.VideoPlayerController", classLoader)
                ?: XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder", classLoader)

            if (playerControllerClass != null) {
                val autoScrollCallback = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Prefs.isAutoScroll(context)) {
                            runCatching {
                                val viewHolder = param.thisObject
                                val viewPager = runCatching { XposedHelpers.getObjectField(viewHolder, "mViewPager") }.getOrNull()
                                    ?: runCatching { XposedHelpers.callMethod(viewHolder, "getViewPager") }.getOrNull()

                                if (viewPager != null) {
                                    val currentItem = XposedHelpers.callMethod(viewPager, "getCurrentItem") as? Int ?: 0
                                    XposedHelpers.callMethod(viewPager, "setCurrentItem", currentItem + 1, true)
                                    XLog.log("AutoScrollHook: Automatically scrolled to video #${currentItem + 1}")
                                }
                            }
                        }
                    }
                }

                hookAllMethodsSafely(playerControllerClass, "onRenderPlayCompletion", autoScrollCallback)
                hookAllMethodsSafely(playerControllerClass, "onPlayCompleted", autoScrollCallback)
            }
        }
    }
}

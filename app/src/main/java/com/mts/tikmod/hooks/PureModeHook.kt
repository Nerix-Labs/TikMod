package com.mts.tikmod.hooks

import android.content.Context
import android.view.View
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

class PureModeHook : BaseHook() {
    override val name: String = "Pure / Immersive Viewing Mode"

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "Feed UI Elements")

            val feedViewHolderClass = XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.adapter.VideoBaseViewHolder", classLoader)
                ?: XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder", classLoader)

            if (feedViewHolderClass != null) {
                hookAllMethodsSafely(feedViewHolderClass, "onBind", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Prefs.isPureMode(context)) {
                            runCatching {
                                val holder = param.thisObject
                                // Hide Right action bar buttons (like, comment, share)
                                val rightBar = runCatching { XposedHelpers.getObjectField(holder, "mRightMenuContainer") as? View }.getOrNull()
                                rightBar?.visibility = View.GONE

                                // Hide Bottom caption, author name & music title
                                val descView = runCatching { XposedHelpers.getObjectField(holder, "mTitleView") as? View }.getOrNull()
                                descView?.visibility = View.GONE

                                XLog.log("PureModeHook: Cleaned UI overlay elements!")
                            }
                        }
                    }
                })
            }
        }
    }
}

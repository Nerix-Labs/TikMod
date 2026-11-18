package com.mts.tikmod

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import com.mts.tikmod.hooks.*
import com.mts.tikmod.model.HookStatus
import com.mts.tikmod.utils.XLog
import java.util.Collections
import java.util.WeakHashMap

object ModuleManager {

    /** Package of the hooked app (set by HookEntry before the first apply). */
    @Volatile var hostPackage: String = ""

    val hooks: List<BaseHook> by lazy {
        listOf(
            SharePanelInjector(),
            ShareInterceptHook(),
            SettingsHook(),
            DownloadHook(),
            AntiRecallHook(),
            AdsHook(),
            ChatPrivacyHook(),
            PresenceHook(),
            LiveCaptionHook(),
            LiveChatHook(),
            TweaksHook(),
            RegionHook(),
            VideoControlHook(),
            AutoScrollHook(),
            PureModeHook()
        )
    }

    private val appliedClassLoaders: MutableSet<ClassLoader> = Collections.newSetFromMap(WeakHashMap())

    fun applyAllHooks(classLoader: ClassLoader, context: Context?) {
        try {
            synchronized(appliedClassLoaders) {
                if (appliedClassLoaders.contains(classLoader)) return
                appliedClassLoaders.add(classLoader)
            }

            XLog.log("TikMod ModuleManager: Applying ${hooks.size} isolated hooks on ClassLoader: $classLoader")
            // Diagnostic: `adb shell setprop debug.tikmod.skip PresenceHook,AdsHook` leaves those hooks out.
            val skip = runCatching {
                (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.tikmod.skip") as String)
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }.getOrDefault(emptySet())
            if (skip.isNotEmpty()) XLog.log("TikMod: skipping hooks $skip")
            for (hook in hooks) {
                if (hook.javaClass.simpleName in skip) continue
                try {
                    hook.apply(classLoader, context)
                } catch (t: Throwable) {
                    XLog.log("TikMod ModuleManager: Error in ${hook.name}: ${t.message}")
                }
            }
            // The dex index is only needed while hooks look their targets up.
            com.mts.tikmod.discovery.Resolver.finish()
        } catch (t: Throwable) {
            XLog.log("TikMod ModuleManager applyAllHooks error: ${t.message}")
        }
    }

    fun onActivityResumed(activity: Activity, classLoader: ClassLoader) {
        try {
            applyAllHooks(classLoader, activity)

            if (Prefs.isUnrestrictAll(activity)) {
                runCatching {
                    activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            runCatching {
                if (Prefs.bool(activity, Prefs.KEY_KEEP_SCREEN_ON)) activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val className = activity.javaClass.name
            val live = className.contains("LivePlay")
            com.mts.tikmod.utils.MediaContext.liveScreenVisible = live
            (hooks.firstOrNull { it is LiveCaptionHook } as? LiveCaptionHook)?.onScreen(if (live) activity else null)
            (hooks.firstOrNull { it is LiveChatHook } as? LiveChatHook)?.onScreen(live)
            if (className.contains("Setting", ignoreCase = true)) {
                XLog.log("TikMod Settings Activity Detected: $className")
                (hooks.firstOrNull { it is SettingsHook } as? SettingsHook)?.injectIntoActivity(activity)
            }
        } catch (t: Throwable) {
            XLog.log("TikMod ModuleManager onActivityResumed error: ${t.message}")
        }
    }

    /** The LIVE screen went to the background (home button, another app): nothing is being watched. */
    fun onActivityStopped(activity: Activity) {
        runCatching {
            if (!activity.javaClass.name.contains("LivePlay")) return
            com.mts.tikmod.utils.MediaContext.liveScreenVisible = false
            (hooks.firstOrNull { it is LiveCaptionHook } as? LiveCaptionHook)?.onScreen(null)
            (hooks.firstOrNull { it is LiveChatHook } as? LiveChatHook)?.onScreen(false)
        }
    }

    fun getStatuses(): List<HookStatus> {
        return hooks.map { it.status }
    }
}

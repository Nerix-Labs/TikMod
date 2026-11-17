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
            FrameworkJsonHook(),
            SharePanelInjector(),
            ShareInterceptHook(),
            SettingsHook(),
            DownloadHook(),
            AntiRecallHook(),
            AdsHook(),
            ChatPrivacyHook(),
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
            for (hook in hooks) {
                try {
                    hook.apply(classLoader, context)
                } catch (t: Throwable) {
                    XLog.log("TikMod ModuleManager: Error in ${hook.name}: ${t.message}")
                }
            }
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

            val className = activity.javaClass.name
            if (className.contains("Setting", ignoreCase = true)) {
                XLog.log("TikMod Settings Activity Detected: $className")
                (hooks.firstOrNull { it is SettingsHook } as? SettingsHook)?.injectIntoActivity(activity)
            }
        } catch (t: Throwable) {
            XLog.log("TikMod ModuleManager onActivityResumed error: ${t.message}")
        }
    }

    fun getStatuses(): List<HookStatus> {
        return hooks.map { it.status }
    }
}

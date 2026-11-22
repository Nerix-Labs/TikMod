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
            PureModeHook(),
            FocusHook()
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
            reportDiagnostics(context)
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
            (hooks.firstOrNull { it is FocusHook } as? FocusHook)?.onResumed(activity)
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
        runCatching { (hooks.firstOrNull { it is FocusHook } as? FocusHook)?.onStopped(activity) }
        runCatching {
            if (!activity.javaClass.name.contains("LivePlay")) return
            com.mts.tikmod.utils.MediaContext.liveScreenVisible = false
            (hooks.firstOrNull { it is LiveCaptionHook } as? LiveCaptionHook)?.onScreen(null)
            (hooks.firstOrNull { it is LiveChatHook } as? LiveChatHook)?.onScreen(false)
        }
    }

    /**
     * Which hooks went in and how each obfuscated target was found, logged and handed to TikMod, where "Report a
     * bug" includes it. Runs off the main thread; waits for TikTok's context when hooks went in before it existed.
     */
    private fun reportDiagnostics(context: Context?) {
        Thread({
            runCatching {
                var c = context ?: Prefs.hostContext
                var tries = 0
                while (c == null && tries++ < 20) { Thread.sleep(3000); c = Prefs.hostContext }
                val ctx: Context = c ?: return@runCatching
                val ver = runCatching {
                    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    @Suppress("DEPRECATION")
                    val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                    "${pi.versionName} ($code)"
                }.getOrDefault("?")
                val process = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else ctx.packageName
                val hooksText = hooks.joinToString("\n") { h ->
                    val s = h.status
                    (if (s.success) "  ✓ " else "  ✗ ") + h.name +
                        (s.foundClass?.let { " — $it" } ?: "") + (s.error?.let { " — error: $it" } ?: "")
                }
                val text = buildString {
                    appendLine("${ctx.packageName} $ver • process $process • ${java.util.Date()}")
                    appendLine("Hooks (${hooks.count { it.status.success }}/${hooks.size}):")
                    appendLine(hooksText)
                    appendLine("Obfuscated targets:")
                    append(com.mts.tikmod.discovery.Resolver.summary().ifBlank { "  (none looked up)" })
                }
                XLog.log("TikMod diagnostics:\n$text")
                ctx.contentResolver.call(android.net.Uri.parse(Prefs.PROVIDER_URI), "diag_report", text, null)
            }.onFailure { XLog.log("TikMod diagnostics failed: ${it.message}") }
        }, "TikMod-Diag").start()
    }

    fun getStatuses(): List<HookStatus> {
        return hooks.map { it.status }
    }
}

package com.mts.tikmod.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Small global tweaks that don't belong to a single feature:
 *  - Disable haptic feedback (the little vibrations when tapping around the app).
 *  - Open outside web links in the phone's real browser instead of TikTok's in-app browser.
 *
 * Both are gated by a setting read live, so toggling them takes effect without a restart. Internal TikTok
 * web pages (login, help, settings…) always stay in-app, so only genuinely external links leave.
 */
class TweaksHook : BaseHook() {
    override val name: String = "Tweaks (haptics, external links)"

    private companion object {
        val INTERNAL_HOSTS = listOf(
            "tiktok.com", "tiktokv.com", "tiktokv.us", "tiktokw.us", "bytedance.com", "byteoversea.com",
            "musical.ly", "tiktokcdn.com", "ibyteimg.com", "ttwstatic.com", "bytedance.net", "ibytedtos.com"
        )
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            hookHaptics(context)
            hookExternalLinks(classLoader, context)
            status = status.copy(foundClass = "global tweaks")
        }
    }

    private fun hookHaptics(context: Context?) {
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Prefs.bool(context, Prefs.KEY_HAPTICS_OFF)) param.result = false
            }
        }
        runCatching { XposedHelpers.findAndHookMethod(View::class.java, "performHapticFeedback", Int::class.javaPrimitiveType, skip) }
        runCatching { XposedHelpers.findAndHookMethod(View::class.java, "performHapticFeedback", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, skip) }
    }

    private fun hookExternalLinks(classLoader: ClassLoader, context: Context?) {
        val cls = XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.crossplatform.activity.CrossPlatformActivity", classLoader) ?: return
        hookAllMethodsSafely(cls, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!Prefs.bool(context, Prefs.KEY_EXTERNAL_LINKS)) return
                val act = param.thisObject as? Activity ?: return
                val url = extractUrl(act.intent) ?: return
                if (!url.startsWith("http", ignoreCase = true)) return
                val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return
                if (INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) return
                runCatching {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    act.finish()
                    XLog.log("Tweaks: opened $host in the external browser")
                }.onFailure { XLog.log("Tweaks: external-link open failed: ${it.message}") }
            }
        })
    }

    /** The real URL behind TikTok's in-app browser intent (query param, extra, or the data URI itself). */
    private fun extractUrl(intent: Intent?): String? {
        intent ?: return null
        val data = intent.data
        runCatching { data?.getQueryParameter("url") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching { intent.getBundleExtra("bundle")?.getString("url") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        if (data?.scheme?.startsWith("http", ignoreCase = true) == true) return data.toString()
        return null
    }
}

package com.mts.tikmod.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.mts.tikmod.BuildConfig
import com.mts.tikmod.Prefs

/**
 * Localized strings that work everywhere TikMod's code runs:
 *  - in TikMod's own app/service, with its own resources;
 *  - inside TikTok, through a context of the TikMod package (TikTok has its own in-app language,
 *    so we follow the phone's system language instead).
 * The default resources are English; a language without a translation falls back to it.
 */
object L {
    @Volatile private var res: Resources? = null

    fun init(context: Context) {
        if (context.packageName == BuildConfig.APPLICATION_ID) res = context.applicationContext.resources
    }

    private fun resources(): Resources? {
        res?.let { return it }
        val host = Prefs.hostContext ?: return null
        val viaPackage = runCatching {
            val pkg = host.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            val cfg = Configuration(pkg.resources.configuration)
            cfg.setLocales(Resources.getSystem().configuration.locales)
            pkg.createConfigurationContext(cfg).resources
        }.getOrNull()
        // TikTok may not be allowed to "see" TikMod's package: read the resources straight from the module APK.
        val r = viaPackage ?: runCatching {
            val apk = moduleApkPath() ?: error("module APK path unknown")
            val am = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
            android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, apk)
            val cfg = Configuration(host.resources.configuration)
            cfg.setLocales(Resources.getSystem().configuration.locales)
            @Suppress("DEPRECATION") Resources(am, host.resources.displayMetrics, cfg)
        }.onFailure { XLog.log("L: can't load TikMod resources inside the host: ${it.message}") }.getOrNull()
        return r?.also { res = it }
    }

    /** Path of TikMod's own APK, read from the class loader LSPosed created for the module. */
    fun moduleApkPath(): String? =
        Regex("""(/data/app/[^\s,\]\[!]+?\.apk)""").find(L::class.java.classLoader?.toString().orEmpty())?.groupValues?.get(1)

    fun t(id: Int, vararg args: Any?): String = runCatching {
        val r = resources() ?: return ""
        if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
    }.getOrDefault("")

    fun p(id: Int, count: Int, vararg args: Any?): String = runCatching {
        val r = resources() ?: return ""
        r.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any?>(count) else args))
    }.getOrDefault("")
}

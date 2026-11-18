package com.mts.tikmod.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mts.tikmod.BuildConfig
import com.mts.tikmod.Prefs
import java.util.Locale

/**
 * Localized strings that work everywhere TikMod's code runs:
 *  - in TikMod's own app/service, with its own resources;
 *  - inside TikTok, through a context of the TikMod package (or straight from the module APK).
 * The language is the one picked in TikMod ([Prefs.KEY_APP_LANGUAGE]) or, by default, the phone's.
 * The default resources are English; a language without a translation falls back to it.
 */
object L {
    const val SYSTEM = "system"

    /** Language tag → native name, in the order shown in the picker. */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "en" to "English", "he" to "עברית", "ar" to "العربية", "zh-CN" to "简体中文", "fr" to "Français",
        "es" to "Español", "ru" to "Русский", "de" to "Deutsch", "pt-BR" to "Português (Brasil)", "tr" to "Türkçe"
    )

    /** Bumped when the language changes, so Compose screens re-read every string. */
    var revision by mutableIntStateOf(0)
        private set

    @Volatile private var appContext: Context? = null
    @Volatile private var cached: Pair<String, Resources>? = null

    fun init(context: Context) {
        if (context.packageName == BuildConfig.APPLICATION_ID) appContext = context.applicationContext
    }

    fun language(): String = Prefs.string(appContext ?: Prefs.hostContext, Prefs.KEY_APP_LANGUAGE).ifBlank { SYSTEM }

    fun setLanguage(context: Context, tag: String) {
        Prefs.setString(context, Prefs.KEY_APP_LANGUAGE, tag)
        cached = null
        revision++
    }

    private fun locales(tag: String): LocaleList =
        if (tag == SYSTEM) Resources.getSystem().configuration.locales else LocaleList(Locale.forLanguageTag(tag))

    fun locale(): Locale = locales(language()).get(0) ?: Locale.ENGLISH

    fun isRtl(): Boolean = TextUtils.getLayoutDirectionFromLocale(locale()) == View.LAYOUT_DIRECTION_RTL

    private fun resources(): Resources? {
        val tag = language()
        val want = locales(tag)
        val key = tag + "|" + want.toLanguageTags()
        cached?.takeIf { it.first == key }?.let { return it.second }

        fun localized(ctx: Context): Resources {
            val cfg = Configuration(ctx.resources.configuration)
            cfg.setLocales(want)
            return ctx.createConfigurationContext(cfg).resources
        }

        val r = appContext?.let { runCatching { localized(it) }.getOrNull() } ?: run {
            val host = Prefs.hostContext ?: return null
            runCatching { localized(host.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)) }.getOrNull()
                // TikTok may not be allowed to "see" TikMod's package: read the resources straight from the module APK.
                ?: runCatching {
                    val apk = moduleApkPath() ?: error("module APK path unknown")
                    val am = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
                    android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, apk)
                    val cfg = Configuration(host.resources.configuration)
                    cfg.setLocales(want)
                    @Suppress("DEPRECATION") Resources(am, host.resources.displayMetrics, cfg)
                }.onFailure { XLog.log("L: can't load TikMod resources inside the host: ${it.message}") }.getOrNull()
        }
        return r?.also { cached = key to it }
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

package com.mts.tikmod.discovery

import android.content.Context
import android.content.SharedPreferences
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

object Discovery {

    private const val PREF_CACHE_NAME = "tikmod_discovery_cache"
    @Volatile private var cachedSharedPreferences: SharedPreferences? = null

    private fun getCache(context: Context?): SharedPreferences? {
        if (cachedSharedPreferences != null) return cachedSharedPreferences
        if (context == null) return null
        return try {
            context.getSharedPreferences(PREF_CACHE_NAME, Context.MODE_PRIVATE).also {
                cachedSharedPreferences = it
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun findClass(
        key: String,
        defaultClassName: String,
        classLoader: ClassLoader,
        context: Context? = null
    ): Class<*>? {
        return try {
            val cache = getCache(context)
            val cachedName = try { cache?.getString(key, null) } catch (t: Throwable) { null }

            if (cachedName != null) {
                val cachedClazz = DexFinder.findClassSafely(cachedName, classLoader)
                if (cachedClazz != null) {
                    XLog.log("TikMod Discovery: [$key] hit cache -> $cachedName")
                    return cachedClazz
                }
            }

            val directClazz = DexFinder.findClassSafely(defaultClassName, classLoader)
            if (directClazz != null) {
                try { cache?.edit()?.putString(key, defaultClassName)?.apply() } catch (t: Throwable) {}
                XLog.log("TikMod Discovery: [$key] found standard -> $defaultClassName")
                return directClazz
            }

            XLog.log("TikMod Discovery: [$key] class $defaultClassName not found directly")
            null
        } catch (t: Throwable) {
            null
        }
    }

    fun findMethod(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>): Method? {
        return runCatching {
            XposedHelpers.findMethodExactIfExists(clazz, methodName, *paramTypes)
        }.getOrNull() ?: try {
            clazz.declaredMethods.firstOrNull { it.name == methodName }
        } catch (t: Throwable) {
            null
        }
    }

    fun resetCache(context: Context) {
        try {
            getCache(context)?.edit()?.clear()?.apply()
            cachedSharedPreferences = null
            XLog.log("TikMod Discovery: Cache cleared successfully.")
        } catch (t: Throwable) {}
    }
}

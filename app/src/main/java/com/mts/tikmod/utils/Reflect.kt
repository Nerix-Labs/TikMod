package com.mts.tikmod.utils

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny, cached, exception-free reflection helpers.
 * Pure java.lang.reflect (no Xposed classes) so it is safe to load in any process.
 */
object Reflect {
    private val NONE = Any()
    private val methodCache = ConcurrentHashMap<String, Any>()
    private val fieldCache = ConcurrentHashMap<String, Any>()

    private fun findMethod(clazz: Class<*>, name: String): Method? {
        val key = clazz.name + "#" + name
        val cached = methodCache[key]
        if (cached != null) return cached as? Method
        var c: Class<*>? = clazz
        var found: Method? = null
        while (c != null && found == null) {
            found = runCatching { c.getDeclaredMethod(name) }.getOrNull()
            c = c.superclass
        }
        found?.isAccessible = true
        methodCache[key] = found ?: NONE
        return found
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + "." + name
        val cached = fieldCache[key]
        if (cached != null) return cached as? Field
        var c: Class<*>? = clazz
        var found: Field? = null
        while (c != null && found == null) {
            found = runCatching { c.getDeclaredField(name) }.getOrNull()
            c = c.superclass
        }
        found?.isAccessible = true
        fieldCache[key] = found ?: NONE
        return found
    }

    /** Calls the first existing no-arg method among [names]; returns its non-null result. */
    fun call(obj: Any?, vararg names: String): Any? {
        if (obj == null) return null
        for (n in names) {
            val m = findMethod(obj.javaClass, n) ?: continue
            val r = runCatching { m.invoke(obj) }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /** Reads the first existing non-null field among [names]. */
    fun field(obj: Any?, vararg names: String): Any? {
        if (obj == null) return null
        for (n in names) {
            val f = findField(obj.javaClass, n) ?: continue
            val r = runCatching { f.get(obj) }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /** Method first, then field — the usual shape of TikTok's Gson models. */
    fun get(obj: Any?, getter: String, fieldName: String): Any? = call(obj, getter) ?: field(obj, fieldName)

    fun string(obj: Any?, getter: String, fieldName: String): String? =
        (get(obj, getter, fieldName) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }

    fun int(obj: Any?, getter: String, fieldName: String): Int =
        (get(obj, getter, fieldName) as? Number)?.toInt() ?: 0

    fun long(obj: Any?, getter: String, fieldName: String): Long =
        (get(obj, getter, fieldName) as? Number)?.toLong() ?: 0L

    /** Returns the non-blank URL strings of a TikTok UrlModel (getUrlList / url_list). */
    fun urls(urlModel: Any?): List<String> {
        if (urlModel == null) return emptyList()
        val list = get(urlModel, "getUrlList", "urlList") as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? String)?.takeIf { s -> s.startsWith("http") } }
            .map { if (it.contains("/playwm/")) it.replace("/playwm/", "/play/") else it }
            .distinct()
    }
}

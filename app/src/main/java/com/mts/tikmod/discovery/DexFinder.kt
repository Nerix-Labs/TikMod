package com.mts.tikmod.discovery

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object DexFinder {

    /**
     * Safely searches for a class by name across classLoader hierarchy.
     */
    fun findClassSafely(className: String, classLoader: ClassLoader): Class<*>? {
        return runCatching {
            XposedHelpers.findClassIfExists(className, classLoader)
        }.getOrNull()
    }

    /**
     * Scans for a class containing a specific method name.
     */
    fun findClassWithMethod(
        classLoader: ClassLoader,
        possibleClassNames: List<String>,
        methodName: String
    ): Class<*>? {
        for (name in possibleClassNames) {
            val clazz = findClassSafely(name, classLoader) ?: continue
            val hasMethod = clazz.declaredMethods.any { it.name == methodName }
            if (hasMethod) return clazz
        }
        return null
    }

    /**
     * Inspects classLoader dexElements array safely if available.
     */
    fun scanLoadedClasses(classLoader: ClassLoader, predicate: (Class<*>) -> Boolean): Class<*>? {
        return runCatching {
            val pathList = XposedHelpers.getObjectField(classLoader, "pathList")
            val dexElements = XposedHelpers.getObjectField(pathList, "dexElements") as? Array<*> ?: return null

            for (element in dexElements) {
                val dexFile = XposedHelpers.getObjectField(element, "dexFile") ?: continue
                val entries = XposedHelpers.callMethod(dexFile, "entries") as? java.util.Enumeration<String> ?: continue
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    val clazz = findClassSafely(className, classLoader) ?: continue
                    if (predicate(clazz)) {
                        return clazz
                    }
                }
            }
            null
        }.getOrNull()
    }
}

package com.mts.tikmod.utils

object XposedXPref {
    @Volatile private var xPrefObj: Any? = null

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        return try {
            if (xPrefObj == null) {
                synchronized(this) {
                    if (xPrefObj == null) {
                        val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
                        val constr = clazz.getConstructor(String::class.java, String::class.java)
                        val inst = constr.newInstance("com.mts.tikmod", "tikmod_preferences")
                        runCatching { clazz.getMethod("makeWorldReadable").invoke(inst) }
                        xPrefObj = inst
                    }
                }
            }
            val inst = xPrefObj ?: return defValue
            val clazz = inst::class.java
            runCatching { clazz.getMethod("reload").invoke(inst) }
            val method = clazz.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            (method.invoke(inst, key, defValue) as? Boolean) ?: defValue
        } catch (t: Throwable) {
            defValue
        }
    }

    fun getString(key: String, defValue: String): String {
        return try {
            if (xPrefObj == null) {
                synchronized(this) {
                    if (xPrefObj == null) {
                        val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
                        val constr = clazz.getConstructor(String::class.java, String::class.java)
                        val inst = constr.newInstance("com.mts.tikmod", "tikmod_preferences")
                        runCatching { clazz.getMethod("makeWorldReadable").invoke(inst) }
                        xPrefObj = inst
                    }
                }
            }
            val inst = xPrefObj ?: return defValue
            val clazz = inst::class.java
            runCatching { clazz.getMethod("reload").invoke(inst) }
            val method = clazz.getMethod("getString", String::class.java, String::class.java)
            (method.invoke(inst, key, defValue) as? String) ?: defValue
        } catch (t: Throwable) {
            defValue
        }
    }
}

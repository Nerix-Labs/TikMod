package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.model.HookStatus
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

abstract class BaseHook {
    abstract val name: String
    var status: HookStatus = HookStatus(name = "")

    protected fun hookMethodSafely(
        clazz: Class<*>?,
        methodName: String,
        vararg parameterTypesAndHook: Any
    ): Boolean {
        if (clazz == null || parameterTypesAndHook.isEmpty()) return false
        val hook = parameterTypesAndHook.last() as? XC_MethodHook ?: return false
        val parameterTypes = parameterTypesAndHook.dropLast(1).toTypedArray()

        return runCatching {
            val method = XposedHelpers.findMethodExactIfExists(clazz, methodName, *parameterTypes)
            if (method != null) {
                XposedBridge.hookMethod(method, hook)
                XLog.log("TikMod [$name]: Hooked $clazz#$methodName")
                true
            } else {
                XLog.log("TikMod [$name]: Method $methodName not found in ${clazz.name}")
                false
            }
        }.getOrElse { e ->
            XLog.log("TikMod [$name]: Error hooking $methodName in ${clazz.name}: ${e.message}")
            false
        }
    }

    protected fun hookAllMethodsSafely(
        clazz: Class<*>?,
        methodName: String,
        hook: XC_MethodHook
    ): Int {
        if (clazz == null) return 0
        var count = 0
        runCatching {
            for (m in clazz.declaredMethods) {
                if (m.name == methodName) {
                    XposedBridge.hookMethod(m, hook)
                    count++
                }
            }
        }
        if (count > 0) {
            XLog.log("TikMod [$name]: Hooked $count overloads of $methodName in ${clazz.name}")
        }
        return count
    }

    protected fun tryHook(block: () -> Unit) {
        status = HookStatus(name = name, success = false)
        try {
            block()
            status = status.copy(success = true, error = null)
            XLog.log("TikMod Feature Applied: $name")
        } catch (e: Throwable) {
            status = status.copy(success = false, error = e.message ?: e.toString())
            XLog.log("TikMod Feature Failed [$name]: ${e.message}")
        }
    }

    abstract fun apply(classLoader: ClassLoader, context: Context?)
}

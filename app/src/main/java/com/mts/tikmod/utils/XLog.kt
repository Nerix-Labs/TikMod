package com.mts.tikmod.utils

import android.util.Log

object XLog {
    fun log(msg: String) {
        runCatching {
            val bridgeClass = Class.forName("de.robv.android.xposed.XposedBridge")
            bridgeClass.getMethod("log", String::class.java).invoke(null, msg)
        }
        runCatching {
            Log.d("TikMod", msg)
        }
    }
}

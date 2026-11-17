package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.discovery.Discovery
import com.mts.tikmod.discovery.Fingerprints
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

class RegionHook : BaseHook() {
    override val name: String = "Region Lock Spoofing"

    override fun apply(classLoader: ClassLoader, context: Context?) {
        val targetRegion = Prefs.getRegion(context)
        if (targetRegion.isBlank()) return

        tryHook {
            val regionLower = targetRegion.lowercase()
            val regionUpper = targetRegion.uppercase()

            val regionHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { param.result = regionLower }
                }
            }

            val tmClass = runCatching { XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader) }.getOrNull()
            if (tmClass != null) {
                hookMethodSafely(tmClass, "getSimCountryIso", regionHook)
                hookMethodSafely(tmClass, "getSimCountryIso", Int::class.javaPrimitiveType!!, regionHook)
                hookMethodSafely(tmClass, "getNetworkCountryIso", regionHook)
                hookMethodSafely(tmClass, "getNetworkCountryIso", Int::class.javaPrimitiveType!!, regionHook)
            }

            val pnsServiceClass = Discovery.findClass("PNSService", Fingerprints.PNS_REGION_SERVICE_CLASS, classLoader, context)
            if (pnsServiceClass != null) {
                status = status.copy(foundClass = pnsServiceClass.name)
                hookMethodSafely(pnsServiceClass, "getCountry", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { param.result = regionUpper }
                    }
                })
            } else {
                status = status.copy(foundClass = "TelephonyManager Spoofed")
            }
        }
    }
}

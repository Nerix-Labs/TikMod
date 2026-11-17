package com.mts.tikmod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object NativeXposedHooks {

    private fun hookMethodSafely(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndHook: Any
    ) {
        if (parameterTypesAndHook.isEmpty()) return
        val hook = parameterTypesAndHook.last() as? XC_MethodHook ?: return
        val parameterTypes = parameterTypesAndHook.dropLast(1).toTypedArray()

        runCatching {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return
            val method = XposedHelpers.findMethodExactIfExists(clazz, methodName, *parameterTypes)
            if (method != null) {
                XposedBridge.hookMethod(method, hook)
                XposedBridge.log("✅ TikMod Hooked: $className#$methodName")
            } else {
                XposedBridge.log("⚠️ TikMod Method not found: $className#$methodName")
            }
        }.onFailure {
            XposedBridge.log("❌ TikMod Error hooking $className#$methodName: ${it.message}")
        }
    }

    fun applyHooks(classLoader: ClassLoader, context: Context? = null) {
        XposedBridge.log("⚡ TikMod: applyHooks() safely starting...")
        Prefs.pingActive(context)

        val isAdsEnabled = Prefs.isRemoveAds(context)
        val isWatermarkEnabled = Prefs.isRemoveWatermark(context)
        val isForceDownloadEnabled = Prefs.isForceDownload(context)
        val isAntiRecallEnabled = Prefs.isAntiRecall(context)
        val isUnrestrictEnabled = Prefs.isUnrestrictAll(context)
        val targetRegion = Prefs.getRegion(context)

        XposedBridge.log("📋 TikMod Prefs: Ads=$isAdsEnabled, Watermark=$isWatermarkEnabled, ForceDL=$isForceDownloadEnabled, AntiRecall=$isAntiRecallEnabled, Unrestrict=$isUnrestrictEnabled, Region=$targetRegion")

        // 1. Aweme Ads & Restriction Hooks
        if (isAdsEnabled) {
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "isAd", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "getAwemeRawAd", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
        }

        if (isForceDownloadEnabled || isUnrestrictEnabled) {
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "isPreventDownload", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "getPreventDownload", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = 0 }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "getDownloadSetting", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = 0 }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "getDuetSetting", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = 0 }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "getStitchSetting", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = 0 }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Aweme", classLoader, "getCommentSetting", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = 0 }
            })
        }

        // 2. AwemeControl & AwemeStatus
        if (isForceDownloadEnabled || isUnrestrictEnabled) {
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.AwemeControl", classLoader, "canDownload", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.AwemeControl", classLoader, "canForward", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.AwemeControl", classLoader, "canShare", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.AwemeControl", classLoader, "canComment", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
            })

            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.AwemeStatus", classLoader, "isDownload", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.AwemeStatus", classLoader, "getDownloadStatus", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = 0 }
            })
        }

        // 3. Video Download - Watermark Removal
        if (isWatermarkEnabled) {
            val replaceWatermark = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val video = param.thisObject
                    val playAddr = runCatching { XposedHelpers.callMethod(video, "getPlayAddr") }.getOrNull()
                        ?: runCatching { XposedHelpers.callMethod(video, "getPlayAddrH264") }.getOrNull()
                    if (playAddr != null) {
                        param.result = playAddr
                        XposedBridge.log("🎯 TikMod: Replaced watermarked download URL with clean playAddr!")
                    }
                }
            }
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Video", classLoader, "getDownloadAddr", replaceWatermark)
            hookMethodSafely("com.ss.android.ugc.aweme.feed.model.Video", classLoader, "getDownloadSuffixLogoAddr", replaceWatermark)
        }

        // 4. Anti-Recall (Deleted Messages)
        if (isAntiRecallEnabled) {
            hookMethodSafely("com.ss.android.ugc.aweme.im.sdk.chat.data.model.BaseContent", classLoader, "isRecalled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
            })
            hookMethodSafely("com.ss.android.ugc.aweme.im.service.model.IMMessage", classLoader, "setMsgStatus", Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val status = param.args[0] as? Int
                    if (status == 2 || status == 3) {
                        runCatching {
                            val content = XposedHelpers.callMethod(param.thisObject, "getContent") as? String
                            if (content != null && !content.startsWith("📌 [נמחק] ")) {
                                XposedHelpers.callMethod(param.thisObject, "setContent", "📌 [נמחק / Deleted] $content")
                            }
                        }
                        param.result = null
                        XposedBridge.log("📌 TikMod: Anti-Recall prevented message deletion!")
                    }
                }
            })
        }

        // 5. Region Lock Spoofing
        if (targetRegion.isNotBlank()) {
            val regionHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = targetRegion.lowercase() }
            }
            hookMethodSafely("android.telephony.TelephonyManager", classLoader, "getSimCountryIso", regionHook)
            hookMethodSafely("android.telephony.TelephonyManager", classLoader, "getSimCountryIso", Int::class.javaPrimitiveType!!, regionHook)
            hookMethodSafely("android.telephony.TelephonyManager", classLoader, "getNetworkCountryIso", regionHook)
            hookMethodSafely("android.telephony.TelephonyManager", classLoader, "getNetworkCountryIso", Int::class.javaPrimitiveType!!, regionHook)

            hookMethodSafely("com.ss.android.ugc.aweme.changeregion.impl.PNSChangeRegionServiceImpl", classLoader, "getCountry", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = targetRegion.uppercase() }
            })
        }

        // 6. Settings Menu Injection
        hookMethodSafely("com.ss.android.ugc.aweme.setting.ui.SettingNewVersionActivity", classLoader, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val containerId = activity.resources.getIdentifier("setting_container", "id", activity.packageName)
                if (containerId == 0) return

                val container = activity.findViewById<LinearLayout>(containerId) ?: return
                if (container.findViewWithTag<TextView>("tikmod_setting_item") == null) {
                    val tikModBtn = TextView(activity).apply {
                        tag = "tikmod_setting_item"
                        text = "⚡ TikMod Settings | הגדרות TikMod"
                        textSize = 16f
                        setPadding(40, 35, 40, 35)
                        setOnClickListener {
                            val intent = Intent().apply {
                                setClassName("com.mts.tikmod", "com.mts.tikmod.MainActivity")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            activity.startActivity(intent)
                        }
                    }
                    container.addView(tikModBtn, 0)
                    XposedBridge.log("⚡ TikMod: Injected item into TikTok settings menu!")
                }
            }
        })
    }
}

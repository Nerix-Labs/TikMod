package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.discovery.Discovery
import com.mts.tikmod.discovery.Fingerprints
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 1. Knows *which* post is being shared (AwemeSharePackage.aweme) and which live room is playing.
 * 2. Makes TikTok's own download path watermark-free and always available.
 */
class DownloadHook : BaseHook() {
    override val name: String = "Exact Media Targeting & No-Watermark Download"

    override fun apply(classLoader: ClassLoader, context: Context?) {
        if (!Prefs.isRemoveWatermark(context) && !Prefs.isForceDownload(context)) return

        tryHook {
            val videoClass = Discovery.findClass("Video", Fingerprints.VIDEO_CLASS, classLoader, context)
            status = status.copy(foundClass = videoClass?.name ?: "SharePackage")

            hookSharePackages(classLoader)
            hookLiveRooms(classLoader)

            // TikTok's own "Save video" reads getDownloadAddr (watermarked). Point it at a clean source.
            if (videoClass != null) {
                val cleanAddr = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val video = param.thisObject ?: return
                        val clean = Reflect.get(video, "getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr")
                            ?.takeIf { Reflect.urls(it).isNotEmpty() }
                            ?: Reflect.get(video, "getPlayAddrH264", "h264PlayAddrValue")?.takeIf { Reflect.urls(it).isNotEmpty() }
                            ?: Reflect.get(video, "getPlayAddr", "playAddrValue")?.takeIf { Reflect.urls(it).isNotEmpty() }
                        if (clean != null && param.method.let { m -> (m as java.lang.reflect.Method).returnType.isInstance(clean) }) {
                            param.result = clean
                        }
                    }
                }
                hookMethodSafely(videoClass, "getDownloadAddr", cleanAddr)
                hookMethodSafely(videoClass, "getDownloadSuffixLogoAddr", cleanAddr)
            }
        }
    }

    private fun hookSharePackages(classLoader: ClassLoader) {
        val awemePkgs = listOf(
            "com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage",
            "com.ss.android.ugc.aweme.share.improve.pkg.AwemePhotoDownloadSharePackage"
        )
        for (cn in awemePkgs) {
            val clazz = XposedHelpers.findClassIfExists(cn, classLoader) ?: continue
            val count = runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { MediaContext.onAwemeSharePackage(param.thisObject) }
                    }
                }).size
            }.getOrDefault(0)
            XLog.log("DownloadHook: hooked $count ctor(s) of ${clazz.simpleName}")
        }

        XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.share.improve.pkg.LiveSharePackage", classLoader)?.let { clazz ->
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { MediaContext.onLiveSharePackage(param.thisObject) }
                    }
                })
            }
        }
    }

    private fun hookLiveRooms(classLoader: ClassLoader) {
        val roomClass = XposedHelpers.findClassIfExists("com.bytedance.android.livesdkapi.depend.model.live.Room", classLoader) ?: return
        hookAllMethodsSafely(roomClass, "getStreamUrl", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                MediaContext.onLiveRoom(param.thisObject, result)
            }
        })
    }
}

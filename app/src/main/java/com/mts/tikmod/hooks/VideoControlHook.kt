package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.discovery.Discovery
import com.mts.tikmod.discovery.Fingerprints
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

class VideoControlHook : BaseHook() {
    override val name: String = "Video Control, SeekBar & Speed Control"

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "Aweme & Player Engine")

            val awemeClass = Discovery.findClass("Aweme", Fingerprints.AWEME_CLASS, classLoader, context)
            val awemeControlClass = Discovery.findClass("AwemeControl", Fingerprints.AWEME_CONTROL_CLASS, classLoader, context)

            // 1. Force SeekBar everywhere
            if (Prefs.isForceSeekbar(context)) {
                val returnTrue = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }

                if (awemeClass != null) {
                    hookAllMethodsSafely(awemeClass, "isCanProgressBar", returnTrue)
                    hookAllMethodsSafely(awemeClass, "isCanSeek", returnTrue)
                }
                if (awemeControlClass != null) {
                    hookAllMethodsSafely(awemeControlClass, "canProgressBar", returnTrue)
                    hookAllMethodsSafely(awemeControlClass, "canSeek", returnTrue)
                }
                XLog.log("VideoControlHook: Enabled SeekBar on all videos!")
            }

            // 2. Playback Speed Hook
            hookPlaybackSpeed(classLoader, context)

            // 3. Prevent Auto-Pause when switching apps/background
            if (Prefs.isDisableAutoPause(context)) {
                hookAntiAutoPause(classLoader)
            }
        }
    }

    private fun hookPlaybackSpeed(classLoader: ClassLoader, context: Context?) {
        runCatching {
            val playerEngineClass = XposedHelpers.findClassIfExists("com.ss.ttvideoengine.TTVideoEngine", classLoader)
            if (playerEngineClass != null) {
                hookAllMethodsSafely(playerEngineClass, "setSpeed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val customSpeedStr = Prefs.getPlaybackSpeed(context)
                        val customSpeed = customSpeedStr.toFloatOrNull() ?: 1.0f
                        if (customSpeed != 1.0f && param.args.isNotEmpty() && param.args[0] is Float) {
                            param.args[0] = customSpeed
                            XLog.log("VideoControlHook: Override player speed -> $customSpeed")
                        }
                    }
                })
            }
        }
    }

    private fun hookAntiAutoPause(classLoader: ClassLoader) {
        runCatching {
            val playerControllerClass = XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.video.VideoPlayerController", classLoader)
            if (playerControllerClass != null) {
                hookAllMethodsSafely(playerControllerClass, "pauseByBackground", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                        XLog.log("VideoControlHook: Intercepted pauseByBackground!")
                    }
                })
            }
        }
    }
}

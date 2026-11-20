package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class VideoControlHook : BaseHook() {
    override val name: String = "Video Control, SeekBar & Speed Control"

    private companion object {
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        const val VIDEO_CONTROL = "com.ss.android.ugc.aweme.feed.model.VideoControl"
        const val SEEKBAR_MASK = "com.ss.android.ugc.aweme.feed.ui.seekbar.VideoSeekBarMaskView"

        /**
         * Show types of TikTok's feed seek bar (`setSeekBarShowType`, 47.9.x):
         * 0 = visible thin bar, 1 = expanded (paused), 2 = expanded, 3 = gone, 4 = transparent (alpha 0, still draggable).
         * While a video plays TikTok picks 0 for videos of 30 s or longer and 4 for shorter ones.
         */
        const val TYPE_VISIBLE = 0
        const val TYPE_TRANSPARENT = 4
    }

    @Volatile private var loggedTypes = 0

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "VideoControl & seek bar")

            // 1. Seek bar on every video (read live, so the switch applies without restarting TikTok).
            hookSeekbar(classLoader, context)

            // 2. Playback Speed Hook
            hookPlaybackSpeed(classLoader, context)

            // 2b. Keep videos muted
            hookMute(classLoader, context)

            // 3. Prevent Auto-Pause when switching apps/background
            if (Prefs.isDisableAutoPause(context)) {
                hookAntiAutoPause(classLoader)
            }
        }
    }

    /**
     * TikTok 47.9 decides the feed seek bar in two places:
     *  - per video, from the server's `VideoControl.showProgressBar` / `draftProgressBar` flags (a video without
     *    them never gets a bar and can't be dragged);
     *  - while playing, the bar's show type: short videos (< 30 s) get type 4, an invisible bar.
     * Both are turned on. Ads, LIVE cells and photo posts are still excluded by TikTok itself.
     */
    private fun hookSeekbar(cl: ClassLoader, context: Context?) {
        val vcClass = XposedHelpers.findClassIfExists(VIDEO_CONTROL, cl)
        val awemeClass = XposedHelpers.findClassIfExists(AWEME, cl)
        if (vcClass != null && awemeClass != null) {
            val fields = listOf("showProgressBar", "draftProgressBar").mapNotNull { f ->
                runCatching { vcClass.getDeclaredField(f).apply { isAccessible = true } }.getOrNull()?.takeIf { it.type == Int::class.javaPrimitiveType }
            }
            val n = hookAllMethodsSafely(awemeClass, "getVideoControl", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val vc = param.result ?: return
                    if (!Prefs.isForceSeekbar(context)) return
                    for (f in fields) runCatching { if (f.getInt(vc) == 0) f.setInt(vc, 1) }
                }
            })
            XLog.log("VideoControlHook: VideoControl flags ${fields.map { it.name }} via $n getter(s)")
        }

        // The bar view: VideoSeekBarMaskView.getVideoSeekBar() returns it (its own class name is obfuscated).
        val bar = XposedHelpers.findClassIfExists(SEEKBAR_MASK, cl)?.declaredMethods
            ?.firstOrNull { it.name == "getVideoSeekBar" && it.parameterTypes.isEmpty() }?.returnType
        val setType = bar?.declaredMethods?.firstOrNull { it.name == "setSeekBarShowType" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
        if (setType == null) { XLog.log("VideoControlHook: seek bar view not found"); return }
        XposedBridge.hookMethod(setType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = param.args[0] as? Int ?: return
                if (loggedTypes < 12) { loggedTypes++; XLog.log("VideoControlHook: seek bar show type $type") }
                if (type == TYPE_TRANSPARENT && Prefs.isForceSeekbar(context)) param.args[0] = TYPE_VISIBLE
            }
        })
        XLog.log("VideoControlHook: always-visible seek bar on ${bar.name}")
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

    private fun hookMute(classLoader: ClassLoader, context: Context?) {
        runCatching {
            val engine = XposedHelpers.findClassIfExists("com.ss.ttvideoengine.TTVideoEngine", classLoader) ?: return
            hookAllMethodsSafely(engine, "setIsMute", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Prefs.bool(context, Prefs.KEY_MUTE_DEFAULT) && param.args.isNotEmpty() && param.args[0] is Boolean) param.args[0] = true
                }
            })
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

package com.mts.tikmod

import android.app.Activity
import android.app.Application
import android.content.Context
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class HookEntry : IXposedHookLoadPackage {

    private var isReceiverRegistered = false

    private fun registerSettingsReceiver(context: Context) {
        if (Prefs.hostContext == null) {
            Prefs.hostContext = context.applicationContext ?: context
            Prefs.refreshCache(Prefs.hostContext, force = true)
        }
        if (isReceiverRegistered) return
        runCatching {
            val filter = android.content.IntentFilter(Prefs.ACTION_SETTINGS_CHANGED)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                    XLog.log("TikMod: ACTION_SETTINGS_CHANGED received in TikTok! Refreshing cache in real time...")
                    Prefs.refreshCache(ctx, force = true)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
            XLog.log("TikMod: Settings BroadcastReceiver registered successfully in $context")
        }
    }

    private fun writePing(context: Context? = null) {
        runCatching {
            val file = File("/data/local/tmp/tikmod_active.ping")
            file.writeText(System.currentTimeMillis().toString())
        }
        if (context != null) {
            runCatching {
                Prefs.pingActive(context)
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName
            val isTargetApp = pkg == "com.zhiliaoapp.musically" ||
                    pkg == "com.ss.android.ugc.trill" ||
                    pkg.contains("musically") ||
                    pkg.contains("trill") ||
                    pkg.contains("tiktok")

            if (!isTargetApp) {
                return
            }

            XLog.log("==========================================")
            XLog.log("TikMod Loaded into $pkg (${lpparam.processName})")
            XLog.log("==========================================")

            writePing()
            ModuleManager.hostPackage = pkg

            // 1. Immediate application attempt
            runCatching {
                ModuleManager.applyAllHooks(lpparam.classLoader, null)
            }

            // 2. Safely hook the specific Application class to avoid ART crashes on Android 15
            val appClassName = lpparam.appInfo.className
            if (appClassName != null) {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        appClassName,
                        lpparam.classLoader,
                        "attachBaseContext",
                        Context::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val ctx = param.args[0] as? Context ?: return
                                    val appClassLoader = ctx.classLoader ?: return
                                    XLog.log("TikMod: $appClassName.attachBaseContext fired in $pkg!")
                                    writePing(ctx)
                                    registerSettingsReceiver(ctx)
                                    ModuleManager.applyAllHooks(appClassLoader, ctx)
                                }
                            }
                        }
                    )
                }

                runCatching {
                    XposedHelpers.findAndHookMethod(
                        appClassName,
                        lpparam.classLoader,
                        "onCreate",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val app = param.thisObject as? Application ?: return
                                    val appClassLoader = app.classLoader ?: lpparam.classLoader
                                    XLog.log("TikMod: $appClassName.onCreate fired in $pkg!")
                                    writePing(app)
                                    registerSettingsReceiver(app)
                                    ModuleManager.applyAllHooks(appClassLoader, app)

                                    // Instead of hooking Activity.onResume globally (which crashes Android 15),
                                    // we use standard Android Application lifecycle callbacks!
                                    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                                        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
                                        override fun onActivityStarted(activity: Activity) {}
                                        override fun onActivityResumed(activity: Activity) {
                                            runCatching {
                                                val classLoader = activity.classLoader
                                                writePing(activity)
                                                ModuleManager.onActivityResumed(activity, classLoader)
                                            }
                                        }
                                        override fun onActivityPaused(activity: Activity) {}
                                        override fun onActivityStopped(activity: Activity) {}
                                        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                                        override fun onActivityDestroyed(activity: Activity) {}
                                    })
                                }
                            }
                        }
                    )
                }
            } else {
                // Fallback for missing Application class (very rare)
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        "android.app.Instrumentation",
                        lpparam.classLoader,
                        "callApplicationOnCreate",
                        Application::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val app = param.args[0] as? Application ?: return
                                    val appClassLoader = app.classLoader ?: lpparam.classLoader
                                    XLog.log("TikMod: Instrumentation.callApplicationOnCreate fired in $pkg!")
                                    writePing(app)
                                    registerSettingsReceiver(app)
                                    ModuleManager.applyAllHooks(appClassLoader, app)

                                    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                                        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
                                        override fun onActivityStarted(activity: Activity) {}
                                        override fun onActivityResumed(activity: Activity) {
                                            runCatching {
                                                val classLoader = activity.classLoader
                                                writePing(activity)
                                                ModuleManager.onActivityResumed(activity, classLoader)
                                            }
                                        }
                                        override fun onActivityPaused(activity: Activity) {}
                                        override fun onActivityStopped(activity: Activity) {}
                                        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                                        override fun onActivityDestroyed(activity: Activity) {}
                                    })
                                }
                            }
                        }
                    )
                }
            }
        } catch (t: Throwable) {
            XLog.log("TikMod handleLoadPackage fatal catch: ${t.message}")
        }
    }
}

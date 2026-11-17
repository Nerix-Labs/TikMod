package com.mts.tikmod.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mts.tikmod.discovery.Discovery
import com.mts.tikmod.discovery.Fingerprints
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class SettingsHook : BaseHook() {
    override val name: String = "TikTok Settings Injection"

    fun injectIntoActivity(activity: Activity) {
        runCatching {
            val rootContent = activity.findViewById<FrameLayout>(android.R.id.content) ?: return@runCatching

            if (rootContent.findViewWithTag<TextView>("tikmod_setting_item") == null) {
                val tikModBtn = TextView(activity).apply {
                    tag = "tikmod_setting_item"
                    text = "⚡ TikMod Settings | הגדרות TikMod"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#E91E63"))
                    setPadding(30, 20, 30, 20)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        runCatching {
                            val intent = Intent().apply {
                                setClassName("com.mts.tikmod", "com.mts.tikmod.MainActivity")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            activity.startActivity(intent)
                        }
                    }
                }

                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, 150, 24, 0)
                }

                rootContent.addView(tikModBtn, params)
                XposedBridge.log("⚡ TikMod SettingsHook: Injected floating button into ${activity.javaClass.name}!")
            }
        }.onFailure { e ->
            XposedBridge.log("⚠️ TikMod SettingsHook error: ${e.message}")
        }
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val settingsClass = Discovery.findClass(
                "SettingsActivity",
                Fingerprints.SETTINGS_ACTIVITY_CLASS,
                classLoader,
                context
            )

            status = status.copy(foundClass = settingsClass?.name ?: "Activity Listener")

            if (settingsClass != null) {
                XposedHelpers.findAndHookMethod(
                    settingsClass,
                    "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            injectIntoActivity(activity)
                        }
                    }
                )
            }
        }
    }
}

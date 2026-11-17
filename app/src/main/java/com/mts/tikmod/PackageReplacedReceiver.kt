package com.mts.tikmod

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mts.tikmod.discovery.Discovery
import com.mts.tikmod.utils.XLog
import java.io.File

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            kotlin.concurrent.thread {
                runCatching {
                    XLog.log("TikMod Package Replaced / Updated! Cleaning up stale processes...")

                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    am?.killBackgroundProcesses("com.zhiliaoapp.musically")
                    am?.killBackgroundProcesses("com.ss.android.ugc.trill")

                    // Background thread execution to prevent ANR and Provider blocks
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.zhiliaoapp.musically")).waitFor()
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.ss.android.ugc.trill")).waitFor()
                    }

                    Discovery.resetCache(context)
                    runCatching { File("/data/local/tmp/tikmod_active.ping").delete() }

                    XLog.log("TikMod Package Replaced cleanup complete!")
                }
                pendingResult.finish()
            }
        }
    }
}

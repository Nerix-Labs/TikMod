package com.mts.tikmod.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.Toast
import com.mts.tikmod.BuildConfig
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * "Report a bug": one text with everything needed to understand a problem — versions, which hooks went in on the
 * last TikTok start and how every obfuscated target was found ([com.mts.tikmod.ModuleManager]), and the recent
 * TikMod log — handed to the share sheet (Telegram, mail, GitHub…). Nothing is sent anywhere by itself.
 */
object BugReport {
    const val DIAG_FILE = "diag_report.txt"
    private const val MAX_CHARS = 90_000 // stays well under the Binder limit of a share Intent

    fun share(context: Context) {
        val ctx = context.applicationContext
        Toast.makeText(ctx, L.t(R.string.report_collecting), Toast.LENGTH_SHORT).show()
        thread(name = "TikMod-BugReport") {
            val text = runCatching { build(ctx) }.getOrElse { "TikMod ${BuildConfig.VERSION_NAME}\nreport failed: $it" }
            Handler(Looper.getMainLooper()).post {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "TikMod ${BuildConfig.VERSION_NAME} — bug report")
                    .putExtra(Intent.EXTRA_TEXT, text.takeLast(MAX_CHARS))
                runCatching {
                    ctx.startActivity(Intent.createChooser(send, L.t(R.string.about_report_bug)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    fun build(ctx: Context): String = buildString {
        appendLine("TikMod ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}")
        for (p in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")) {
            runCatching { ctx.packageManager.getPackageInfo(p, 0) }.getOrNull()?.let { appendLine("$p ${it.versionName}") }
        }
        val ping = ctx.getSharedPreferences(Prefs.PREF_NAME, Context.MODE_PRIVATE).getLong("last_active_ping", 0L)
        appendLine("Module seen in TikTok: " + if (ping > 0) DateUtils.getRelativeTimeSpanString(ping) else "never")
        appendLine()
        appendLine("== Last TikTok start ==")
        appendLine(File(ctx.filesDir, DIAG_FILE).takeIf { it.exists() }?.readText() ?: "(nothing yet — open TikTok once)")
        appendLine()
        appendLine("== Log ==")
        append(log())
    }

    /** With root the log includes TikTok's process; without it only TikMod's own lines are visible. */
    private fun log(): String {
        val cmds = listOf(
            arrayOf("su", "-c", "logcat -d -v time -t 800 -s TikMod:* LSPosed-Bridge:*"),
            arrayOf("logcat", "-d", "-v", "time", "-t", "800", "-s", "TikMod:*")
        )
        for (c in cmds) run(c)?.takeIf { it.isNotBlank() }?.let { return it }
        return "(no log access)"
    }

    /** Runs a command for at most 8 s (su may wait for the root prompt) and returns its output. */
    private fun run(cmd: Array<String>): String? = runCatching {
        val p = Runtime.getRuntime().exec(cmd)
        var out = ""
        val reader = thread { out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") }
        val done = if (Build.VERSION.SDK_INT >= 26) p.waitFor(8, TimeUnit.SECONDS) else { p.waitFor(); true }
        if (!done) { p.destroy(); return@runCatching null }
        reader.join(2000)
        out
    }.getOrNull()
}

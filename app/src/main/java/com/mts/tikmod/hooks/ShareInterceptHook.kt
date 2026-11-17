package com.mts.tikmod.hooks

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.MediaResolver
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * Smart share.
 *
 * When the user picks an app in TikTok's share sheet (WhatsApp, Status, SMS, "More"…), TikTok fires
 * an ACTION_SEND intent carrying just the link (or a watermarked file). We intercept it and, according
 * to the share mode, send the real clean media instead:
 *  - media       -> the no-watermark video / every carousel image
 *  - media_link  -> the media, with the original text (link) as caption
 *  - link        -> untouched TikTok behaviour
 */
class ShareInterceptHook : BaseHook() {
    override val name: String = "Smart Share (send the media, not just the link)"

    private companion object {
        const val HANDLED = "tikmod_smart_share_handled"
        val TIKTOK_LINK = Regex("https?://(\\w+\\.)?tiktok(v)?\\.com/\\S+|https?://vm\\.tiktok\\.com/\\S+|https?://vt\\.tiktok\\.com/\\S+")
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "Activity.startActivityForResult / ContextImpl.startActivity")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.firstOrNull() as? Intent ?: return
                    val ctx = (param.thisObject as? Context) ?: return
                    runCatching {
                        if (intercept(ctx, intent)) param.result = null
                    }.onFailure { XLog.log("SmartShare: ${it.message}") }
                }
            }
            runCatching {
                XposedHelpers.findAndHookMethod(Activity::class.java, "startActivityForResult",
                    Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java, hook)
            }
            runCatching {
                val contextImpl = XposedHelpers.findClass("android.app.ContextImpl", null)
                XposedHelpers.findAndHookMethod(contextImpl, "startActivity", Intent::class.java, Bundle::class.java, hook)
            }
        }
    }

    /** Returns true when the original launch must be cancelled (we take over). */
    private fun intercept(ctx: Context, original: Intent): Boolean {
        if (original.getBooleanExtra(HANDLED, false)) return false

        // Unwrap system chooser ("More…") intents.
        val isChooser = original.action == Intent.ACTION_CHOOSER
        @Suppress("DEPRECATION")
        val send = if (isChooser) original.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return false else original
        if (send.action != Intent.ACTION_SEND) return false

        val text = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val hasStream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) != null
        if (!TIKTOK_LINK.containsMatchIn(text) && !hasStream) return false

        // Only for a sheet the user just opened on a post (never for unrelated shares).
        if (!MediaContext.hasRecentShare(60_000) || MediaContext.shareIsLive) return false
        val mode = MediaContext.shareModeOverride ?: Prefs.getShareMode(ctx)
        if (mode == Prefs.SHARE_MODE_LINK) return false
        val media = MediaContext.currentMedia() ?: return false
        if (!media.hasVideo && !media.isPhotoPost) return false

        val target = send.component?.packageName ?: send.`package`
        val label = target?.let { pkg ->
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: "שיתוף"
        XLog.log("SmartShare: intercept -> target=${send.component ?: target} chooser=$isChooser mode=$mode aweme=${media.awemeId}")

        val launcher = WeakReference(ctx)
        TikModDownloader.prepareForShare(ctx, media, label,
            onReady = { uris, mime -> relaunch(launcher.get() ?: ctx.applicationContext, original, send, isChooser, uris, mime, mode, text, media) },
            onFail = { reason ->
                TikModDownloader.toast(ctx, "⚠️ לא הצלחתי להכין את הקובץ ($reason) — שולח קישור")
                runCatching { (launcher.get() ?: ctx.applicationContext).startActivity(original.putExtra(HANDLED, true).withNewTaskIfNeeded(ctx)) }
            })
        return true
    }

    private fun relaunch(
        ctx: Context, original: Intent, send: Intent, isChooser: Boolean,
        uris: List<Uri>, mime: String, mode: String, text: String, media: MediaResolver.AwemeMedia
    ) {
        val out = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.size > 1) "image/*" else mime
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else putExtra(Intent.EXTRA_STREAM, uris[0])
            clipData = ClipData.newRawUri("TikMod", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            val caption = when (mode) {
                Prefs.SHARE_MODE_MEDIA_LINK -> text.ifBlank { media.webUrl }
                else -> null
            }
            if (caption != null) putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(HANDLED, true)
            // Same destination TikTok targeted (e.g. a WhatsApp chat picker / Status activity).
            when {
                send.component != null -> {
                    // Multi-image goes to the package (the specific activity may only accept a single item).
                    if (uris.size > 1) setPackage(send.component!!.packageName) else component = send.component
                }
                send.`package` != null -> setPackage(send.`package`)
            }
        }
        val launch = if (isChooser || (out.component == null && out.`package` == null)) {
            Intent.createChooser(out, "שליחה").putExtra(HANDLED, true)
        } else out
        launch.withNewTaskIfNeeded(ctx)
        runCatching { ctx.startActivity(launch) }
            .onSuccess { XLog.log("SmartShare: sent ${uris.size} item(s) ($mime) mode=$mode") }
            .onFailure {
                // The exact activity refused the new type — fall back to the system chooser.
                XLog.log("SmartShare: direct launch failed (${it.message}), using chooser")
                runCatching { ctx.startActivity(Intent.createChooser(out.setComponent(null).setPackage(null), "שליחה").putExtra(HANDLED, true).withNewTaskIfNeeded(ctx)) }
            }
    }

    private fun Intent.withNewTaskIfNeeded(ctx: Context): Intent {
        if (ctx !is Activity || ctx.isFinishing || (Build.VERSION.SDK_INT >= 17 && ctx.isDestroyed)) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return this
    }
}

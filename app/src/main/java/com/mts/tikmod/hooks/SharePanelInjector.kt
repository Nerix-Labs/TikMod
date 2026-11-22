package com.mts.tikmod.hooks

import com.mts.tikmod.R
import com.mts.tikmod.utils.L

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.MediaResolver
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Injects the TikMod download card into TikTok's share sheet.
 * Everything it downloads comes from [MediaContext] — the exact post the sheet was opened for.
 */
class SharePanelInjector : BaseHook() {
    override val name: String = "Share Sheet Download Card"

    private companion object {
        const val TAG = "tikmod_share_card"
        val SHEET_TITLES = listOf("share to", "send to", "share", "שליחה", "שתף", "שיתוף", "שליחה אל", "שתפו", "شارك")
        val SAVE_LABELS = listOf("save video", "save photo", "save photos", "download", "שמירת סרטון", "שמירת הסרטון",
            "שמור סרטון", "שמירת וידאו", "הורדה", "שמירת תמונה", "שמירת תמונות", "שמור תמונה")
        val ACTION_ROW_LABELS = listOf("copy link", "העתק קישור", "העתקת קישור", "whatsapp", "sms", "repost", "פרסום שנית")
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            if (!Prefs.isForceDownload(context) && !Prefs.isRemoveWatermark(context)) return@tryHook
            status = status.copy(foundClass = "Dialog share sheet")

            val dialogHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    scheduleInjection(dialog)
                }
            }
            runCatching { XposedHelpers.findAndHookMethod(Dialog::class.java, "onStart", dialogHook) }
            runCatching { XposedHelpers.findAndHookMethod(Dialog::class.java, "show", dialogHook) }
        }
    }

    /** Sheet content is bound lazily — retry a few times until the panel is populated. */
    private fun scheduleInjection(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        for (delay in longArrayOf(0, 200, 500, 1000)) {
            decor.postDelayed({
                runCatching { tryInject(dialog, decor) }.onFailure { XLog.log("SharePanel: ${it.message}") }
            }, delay)
        }
    }

    private fun tryInject(dialog: Dialog, decor: ViewGroup) {
        if (!dialog.isShowing || decor.findViewWithTag<View>(TAG) != null) return

        var titleFound = false
        val saveViews = ArrayList<View>()
        val anchors = ArrayList<View>()

        fun scan(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView) {
                val t = v.text?.toString()?.trim()?.lowercase().orEmpty()
                if (t.isNotEmpty() && SHEET_TITLES.any { t == it || (it.length > 4 && t.startsWith(it)) }) titleFound = true
                if (SAVE_LABELS.any { t == it }) saveViews += v
                if (ACTION_ROW_LABELS.any { t == it }) anchors += v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
        }
        scan(decor)

        val recentShare = MediaContext.hasRecentShare()
        // Only touch real share sheets: a share package was just built, or the sheet title is visible.
        if (!recentShare && !titleFound) return

        /**
         * Anchor on a *visible, laid-out* action row ("Copy link", "WhatsApp", "Download"…), climb to the first
         * vertical LinearLayout that actually has height, and insert the card right above that row.
         * (TikTok keeps hidden 0-height containers next to the title — inserting there makes the card invisible.)
         */
        fun insertionPoint(): Pair<ViewGroup, Int>? {
            for (anchor in anchors + saveViews) {
                if (!anchor.isShown || anchor.height == 0) continue
                var cur: View = anchor
                var parent = cur.parent as? ViewGroup
                var passedList = false
                while (parent != null && parent !== decor) {
                    val cn = cur.javaClass.name.lowercase()
                    if (cn.contains("recyclerview") || cn.contains("scrollview") || cn.contains("listview")) passedList = true
                    if (passedList && parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL && parent.height > 0 && parent.isShown) {
                        return parent to parent.indexOfChild(cur)
                    }
                    cur = parent
                    parent = cur.parent as? ViewGroup
                }
            }
            return null
        }
        val target = insertionPoint()
        if (target == null) {
            XLog.log("… SharePanel: sheet not laid out yet (anchors=${anchors.size}), retrying")
            return
        }

        val ctx = dialog.context
        val isLive = MediaContext.shareIsLive && recentShare
        val media = if (isLive) null else MediaContext.currentMedia()
        val live = if (isLive) MediaContext.currentLive() else null
        if (media == null && live == null) {
            XLog.log("SharePanel: no resolvable media for this sheet (recentShare=$recentShare)")
            return
        }

        val card = buildCard(ctx, dialog, media, live)
        val (container, index) = target
        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val m = dp(ctx, 12); setMargins(m, dp(ctx, 8), m, dp(ctx, 8))
        }
        val finalLp = if (container is LinearLayout) LinearLayout.LayoutParams(lp) else lp
        runCatching { container.addView(card, index.coerceIn(0, container.childCount), finalLp) }
            .onFailure { decor.addView(card, lp) }
        // Entrance: slide up + fade, then the chips cascade in.
        card.alpha = 0f
        card.translationY = dp(ctx, 18).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        XLog.log("SharePanel: card injected (aweme=${media?.awemeId}, images=${media?.images?.size ?: 0}, live=${live?.roomId}) into ${container.javaClass.name}@$index " +
            "h=${container.height} lpH=${container.layoutParams?.height} chain=" +
            generateSequence(container as View) { it.parent as? View }.take(6).joinToString(" < ") { "${it.javaClass.simpleName}(${it.height},${it.layoutParams?.height})" })

        // Diagnostics: where each TikMod control landed on screen (used for automated device tests).
        card.post {
            val loc = IntArray(2)
            fun report(v: View) {
                if (v is TextView && v.isClickable || (v is ViewGroup && v.hasOnClickListeners())) {
                    v.getLocationOnScreen(loc)
                    val label = (v as? TextView)?.text ?: "main"
                    XLog.log("SharePanel: [$label] at ${loc[0] + v.width / 2},${loc[1] + v.height / 2}")
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) report(v.getChildAt(i))
            }
            report(card)
        }

        // "Replace" mode: TikTok's own Save button goes through TikMod as well.
        if (media != null && Prefs.getDownloadUiMode(ctx) == Prefs.DOWNLOAD_UI_MODE_REPLACE) {
            for (label in saveViews) {
                val clickable = generateSequence(label) { it.parent as? View }.take(4).firstOrNull { it.isClickable } ?: continue
                clickable.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (media.isPhotoPost && Prefs.isDownloadPhotos(ctx)) TikModDownloader.downloadImages(ctx, media)
                    else TikModDownloader.downloadVideo(ctx, media)
                    runCatching { dialog.dismiss() }
                }
                XLog.log("SharePanel: native Save button routed to TikMod")
            }
        }
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCard(ctx: Context, dialog: Dialog, media: MediaResolver.AwemeMedia?, live: MediaContext.LiveStream?): View {
        val root = LinearLayout(ctx).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // Per stream: another LIVE being recorded doesn't turn this one's button into "Stop".
        val allRecording = if (live != null) TikModDownloader.recordingHandles(ctx) else emptyList()
        val recording = live != null && live.owner.isNotBlank() && live.owner.trim().removePrefix("@").lowercase() in allRecording
        val others = allRecording.filter { live == null || it != live.owner.trim().removePrefix("@").lowercase() }
        val photoMode = media?.isPhotoPost == true && Prefs.isDownloadPhotos(ctx)
        val best = media?.let {
            MediaResolver.rankVideoOptions(it.videoOptions, Prefs.getDownloadQuality(ctx), Prefs.getVideoCodec(ctx)).firstOrNull()
        }

        val (icon, title, subtitle, badge) = when {
            live != null && recording -> Quad("⏹", L.t(R.string.card_stop_recording), L.t(R.string.card_recording_bg, live.owner.ifBlank { live.roomId }), "REC")
            live != null -> Quad("🔴", L.t(R.string.card_record_live), L.t(R.string.card_record_live_sub, live.owner.ifBlank { live.roomId }), "LIVE")
            photoMode -> Quad("🖼️", L.p(R.plurals.card_download_photos, media!!.images.size), L.t(R.string.card_photos_sub, media.authorUniqueId), "${media.images.size} IMG")
            else -> Quad(
                "⬇", L.t(R.string.card_download_video),
                listOfNotNull(
                    best?.qualityTag,
                    best?.let { if (it.isH265) "H.265" else "H.264" },
                    best?.sizeBytes?.takeIf { it > 0 }?.let { TikModDownloader.formatSize(it) },
                    media?.authorUniqueId?.takeIf { it.isNotBlank() }?.let { "@$it" }
                ).joinToString(" • "),
                best?.qualityTag ?: "HD"
            )
        }

        val colors = when {
            live != null && recording -> intArrayOf(0xFF2B0A12.toInt(), 0xFF4A0F1E.toInt())
            live != null -> intArrayOf(0xFFFF0844.toInt(), 0xFFFF4E50.toInt())
            else -> intArrayOf(0xFF25F4EE.toInt(), 0xFFFE2C55.toInt())
        }

        val main = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            val bg = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, colors).apply { cornerRadius = dp(ctx, 18).toFloat() }
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
            elevation = dp(ctx, 3).toFloat()
            pressScale(this)
        }
        main.addView(TextView(ctx).apply {
            text = icon; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(dp(ctx, 4), 0, dp(ctx, 12), 0)
        })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        if (subtitle.isNotBlank()) texts.addView(TextView(ctx).apply {
            text = subtitle; textSize = 11.5f; setTextColor(0xE6FFFFFF.toInt()); maxLines = 1
        })
        main.addView(texts)
        main.addView(TextView(ctx).apply {
            text = if (live != null && recording) "● $badge" else badge
            textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 10).toFloat(); setColor(0x40000000) }
            setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
            if (live != null && recording) {
                // Pulsing REC badge.
                android.animation.ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.35f).apply {
                    duration = 700; repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                }.start()
            }
        })

        main.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            when {
                live != null -> TikModDownloader.toggleLiveRecording(ctx, live)
                photoMode -> TikModDownloader.downloadImages(ctx, media!!)
                media != null -> TikModDownloader.downloadVideo(ctx, media)
            }
            runCatching { dialog.dismiss() }
        }
        root.addView(main)

        // Other streams recording right now: each one can be stopped from here, without leaving this LIVE.
        if (live != null && others.isNotEmpty()) {
            root.addView(TextView(ctx).apply {
                text = L.t(R.string.card_also_recording, others.size)
                textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xCCFFFFFF.toInt())
                setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 2))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 4), 0, 0) }
            for (h in others) {
                row.addView(TextView(ctx).apply {
                    text = "⏹  @$h"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 16).toFloat(); setColor(0xE62B0A12.toInt()); setStroke(dp(ctx, 1), 0x66FF4E50)
                    }
                    background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null)
                    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(ctx, 8) }
                    pressScale(this)
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        TikModDownloader.stopRecordingOf(ctx, h)
                        // Gone from the row right away; the rest stay.
                        v.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
                    }
                })
            }
            root.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                addView(row)
            })
        }

        if (media != null) {
            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 8), 0, 0)
            }
            fun chip(label: String, dismiss: Boolean = true, action: (TextView) -> Unit) {
                chips.addView(TextView(ctx).apply {
                    text = label; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 16).toFloat(); setColor(0xE6202024.toInt()); setStroke(dp(ctx, 1), 0x33FFFFFF)
                    }
                    background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null)
                    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(ctx, 8) }
                    pressScale(this)
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        action(this)
                        if (dismiss) runCatching { dialog.dismiss() }
                    }
                })
            }

            // Smart share mode for this sheet: tap to cycle (default comes from the module settings).
            if (media.hasVideo || media.isPhotoPost) {
                val modes = listOf(Prefs.SHARE_MODE_MEDIA, Prefs.SHARE_MODE_MEDIA_LINK, Prefs.SHARE_MODE_LINK)
                fun label(m: String) = when (m) {
                    Prefs.SHARE_MODE_MEDIA -> if (photoMode) L.t(R.string.chip_send_photos) else L.t(R.string.chip_send_file)
                    Prefs.SHARE_MODE_MEDIA_LINK -> L.t(R.string.chip_send_file_link)
                    else -> L.t(R.string.chip_send_link)
                }
                chip(label(MediaContext.shareModeOverride ?: Prefs.getShareMode(ctx)), dismiss = false) { tv ->
                    val cur = MediaContext.shareModeOverride ?: Prefs.getShareMode(ctx)
                    val next = modes[(modes.indexOf(cur).coerceAtLeast(0) + 1) % modes.size]
                    MediaContext.shareModeOverride = next
                    tv.text = label(next)
                    tv.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).withEndAction {
                        tv.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                }
            }

            if (photoMode && media.hasVideo) chip(L.t(R.string.chip_video)) { TikModDownloader.downloadVideo(ctx, media) }
            if (media.isPhotoPost && !photoMode) chip(L.t(R.string.chip_photos)) { TikModDownloader.downloadImages(ctx, media) }
            if (Prefs.isDownloadAudio(ctx) && (media.musicUrls.isNotEmpty() || media.hasVideo))
                chip(L.t(R.string.chip_audio)) { TikModDownloader.downloadAudio(ctx, media) }
            if (Prefs.isDownloadCover(ctx) && media.coverUrls.isNotEmpty())
                chip(L.t(R.string.chip_cover)) { TikModDownloader.downloadCover(ctx, media) }
            if (Prefs.isDownloadAvatar(ctx) && media.avatarUrls.isNotEmpty())
                chip(L.t(R.string.chip_profile)) { TikModDownloader.downloadAvatar(ctx, media) }
            if (Prefs.isCopyLinkButton(ctx))
                chip(L.t(R.string.chip_link)) { TikModDownloader.copyLink(ctx, media) }
            if (media.desc.isNotBlank())
                chip(L.t(R.string.chip_caption)) { TikModDownloader.copyCaption(ctx, media) }

            if (chips.childCount > 0) {
                root.addView(HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    addView(chips)
                })
            }
        }
        return root
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    @SuppressLint("ClickableViewAccessibility")
    private fun pressScale(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

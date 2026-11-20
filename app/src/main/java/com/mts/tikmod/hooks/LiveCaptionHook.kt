package com.mts.tikmod.hooks

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.captions.CaptionLanguages
import com.mts.tikmod.captions.CaptionService
import com.mts.tikmod.utils.CaptionGate
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.XLog
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Translated captions over the LIVE stream on screen.
 *
 * Nothing in TikTok's UI is hooked for this: the activity lifecycle says when the LIVE screen is in front,
 * [MediaContext.watchingNow] says which room is on screen, and TikMod's own app does the listening and
 * translating ([CaptionService]). This side starts/stops it and draws what comes back.
 *
 * The caption bubble: drag it up or down, tap it for the quick menu (languages, text size, transcript…),
 * hold it to hide the captions for this stream.
 */
class LiveCaptionHook : BaseHook() {
    override val name: String = "LIVE Captions"

    private val main = Handler(Looper.getMainLooper())
    private var activity: WeakReference<Activity>? = null
    private var bubble: Bubble? = null
    private var room: String? = null          // captions are running for this room
    private var candidate: String? = null
    private var candidateSince = 0L
    private var hiddenRoom: String? = null    // the viewer hid the captions for this one
    private var lastPing = 0L
    private var ticking = false
    private var receiver: BroadcastReceiver? = null
    private var lastStatus = ""
    private var listeningLang = ""
    // Settings changed from the quick menu apply at once here; the shared prefs catch up a moment later.
    private var sizeNow: String? = null
    private var originalNow: Boolean? = null
    private var targetNow: String? = null
    private var bgNow: String? = null
    private var linesNow: String? = null
    private var outlineNow: Boolean? = null
    private var alwaysNow: Boolean? = null

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook { status = status.copy(foundClass = "activity lifecycle") }
    }

    /** The LIVE room screen came to the front ([live] != null) or left it. */
    fun onScreen(live: Activity?) {
        main.post {
            if (live == null) { activity = null; stop(); return@post }
            if (activity?.get() !== live) { detach(); activity = WeakReference(live) }
            if (!ticking) { ticking = true; tick() }
        }
    }

    private fun tick() {
        val act = activity?.get()
        if (act == null || act.isFinishing) { ticking = false; stop(); return }
        runCatching {
            val now = System.currentTimeMillis()
            val live = if (Prefs.bool(act, Prefs.KEY_LIVE_CAPTIONS)) MediaContext.watchingNow() else null
            val id = live?.roomId
            if (id != candidate) { candidate = id; candidateSince = now }
            when {
                // Only once the viewer settled on a stream, and new stream connections spaced out (see CaptionGate):
                // swiping through LIVE never opens a connection per room.
                live != null && id != room && id != hiddenRoom && now - candidateSince >= CaptionGate.DWELL_MS &&
                    CaptionGate.tryStart(live.roomId, now) -> start(act, live)
                id == null && room != null && now - candidateSince > 4000 -> stop()
                room != null && now - lastPing > 10_000 -> { lastPing = now; call("captions_ping", null) }
            }
        }
        main.postDelayed({ tick() }, 1000)
    }

    private fun start(act: Activity, live: MediaContext.LiveStream) {
        room = live.roomId
        lastPing = System.currentTimeMillis()
        lastStatus = ""
        sizeNow = null; originalNow = null; targetNow = null; bgNow = null; linesNow = null; outlineNow = null; alwaysNow = null
        ensureReceiver(act)
        attach(act).showStatus(L.t(R.string.caption_listening))
        XLog.log("Captions: asking for captions on @${live.owner} (${live.light.size} addresses)")
        call("captions_start", Bundle().apply {
            putString(CaptionService.EXTRA_ROOM, live.roomId)
            putString(CaptionService.EXTRA_HANDLE, live.owner)
            putString(CaptionService.EXTRA_TITLE, live.title)
            putStringArrayList(CaptionService.EXTRA_URLS, ArrayList(live.light))
            putString(CaptionService.EXTRA_PACKAGE, act.packageName)
        })
    }

    private fun stop() {
        if (room != null) call("captions_stop", null)
        room = null
        CaptionGate.activeRoom = null
        detach()
    }

    private fun call(method: String, extras: Bundle?, onResult: ((Bundle?) -> Unit)? = null) {
        val ctx = Prefs.hostContext ?: activity?.get()?.applicationContext ?: return
        thread(name = "TikMod-Captions") {
            val result = runCatching { ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), method, null, extras) }
                .onFailure { XLog.log("Captions: $method failed: ${it.message}") }.getOrNull()
            if (onResult != null) main.post { onResult(result) }
        }
    }

    private fun setPref(key: String, value: Any) = call("caption_pref", Bundle().apply {
        putString("key", key)
        if (value is Boolean) putBoolean("bool", value) else putString("value", value.toString())
    })

    private fun ensureReceiver(context: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                if (intent.getStringExtra("room") != room || room == null) return
                val st = intent.getStringExtra("status")
                if (st != null) onStatus(st, intent.getStringExtra("lang").orEmpty())
                else bubble?.showCaption(intent.getStringExtra("text").orEmpty(), intent.getStringExtra("original").orEmpty(), intent.getBooleanExtra("final", false))
            }
        }
        val app = context.applicationContext ?: context
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, IntentFilter(CaptionService.ACTION_CAPTION), Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, IntentFilter(CaptionService.ACTION_CAPTION))
            receiver = r
        }
    }

    private fun onStatus(st: String, lang: String) {
        if (st == "heard") { bubble?.showStatus(L.t(R.string.caption_heard, CaptionLanguages.name(lang)), holdMs = 8000); return }
        lastStatus = st
        if (lang.isNotBlank()) listeningLang = lang
        val b = bubble ?: return
        val langName = if (lang.isBlank()) "" else CaptionLanguages.name(lang)
        when {
            st == "starting" || st == "listening" -> if (!b.hasCaption()) b.showStatus(L.t(R.string.caption_listening_lang, langName))
            st == "language_missing" -> b.showStatus(L.t(R.string.caption_lang_missing, langName), sticky = true)
            st == "language_unsupported" -> b.showStatus(L.t(R.string.caption_lang_unsupported, langName), sticky = true)
            st.startsWith("download:") -> b.showStatus(L.t(R.string.caption_downloading, langName, st.substringAfter(':').toIntOrNull() ?: 0), sticky = true)
            st == "download_system" -> b.showStatus(L.t(R.string.caption_download_system, langName), sticky = true)
            st == "download_failed" -> b.showStatus(L.t(R.string.caption_download_failed), sticky = true)
            st == "permission" -> b.showStatus(L.t(R.string.caption_permission), sticky = true)
            st == "unsupported" -> b.showStatus(L.t(R.string.caption_unsupported), sticky = false)
            st == "ended" -> { room = null; detach() }
        }
    }

    // ------------------------------------------------------------------ bubble

    private fun attach(act: Activity): Bubble {
        bubble?.let { if (it.parent != null && it.context === act) return it }
        detach()
        val root = act.window.decorView as ViewGroup
        return Bubble(act).also { b ->
            root.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            bubble = b
        }
    }

    private fun detach() {
        bubble?.let { b -> runCatching { (b.parent as? ViewGroup)?.removeView(b) } }
        bubble = null
    }

    private fun size(act: Activity) = sizeNow ?: Prefs.string(act, Prefs.KEY_CAPTION_SIZE).ifBlank { "m" }
    private fun showOriginal(act: Activity) = originalNow ?: Prefs.bool(act, Prefs.KEY_CAPTION_ORIGINAL)
    private fun target(act: Activity) = targetNow ?: Prefs.string(act, Prefs.KEY_CAPTION_TARGET)
    private fun bg(act: Activity) = bgNow ?: Prefs.string(act, Prefs.KEY_CAPTION_BG).ifBlank { "dim" }
    private fun lines(act: Activity) = (linesNow ?: Prefs.string(act, Prefs.KEY_CAPTION_LINES)).toIntOrNull()?.coerceIn(1, 4) ?: 2
    private fun outline(act: Activity) = outlineNow ?: Prefs.bool(act, Prefs.KEY_CAPTION_OUTLINE)
    private fun always(act: Activity) = alwaysNow ?: Prefs.bool(act, Prefs.KEY_CAPTION_ALWAYS)

    private fun dialog(act: Activity) = AlertDialog.Builder(ContextThemeWrapper(act, android.R.style.Theme_DeviceDefault_Dialog_Alert))

    private fun onTap(act: Activity) {
        // A missing speech pack: the tap downloads it. Otherwise it opens the quick menu.
        if (lastStatus == "language_missing" || lastStatus == "download_failed") { call("captions_download", null); return }
        menu(act)
    }

    private fun menu(act: Activity) {
        val sizes = mapOf("s" to R.string.caption_size_s, "m" to R.string.caption_size_m, "l" to R.string.caption_size_l)
        val bgs = mapOf("solid" to R.string.caption_bg_solid, "dim" to R.string.caption_bg_dim, "none" to R.string.caption_bg_none)
        val original = showOriginal(act)
        val tgt = target(act)
        val outlineOn = outline(act)
        val alwaysOn = always(act)
        val actions = listOf<Pair<String, () -> Unit>>(
            L.t(R.string.caption_menu_source, if (listeningLang.isBlank()) L.t(R.string.caption_auto) else CaptionLanguages.name(listeningLang)) to { chooseLanguage(act) },
            L.t(R.string.caption_menu_target, if (tgt.isBlank()) L.t(R.string.captions_target_app) else CaptionLanguages.name(tgt)) to { chooseTarget(act) },
            L.t(R.string.caption_menu_size, L.t(sizes[size(act)] ?: R.string.caption_size_m)) to {
                val next = when (size(act)) { "s" -> "m"; "m" -> "l"; else -> "s" }
                sizeNow = next; setPref(Prefs.KEY_CAPTION_SIZE, next); bubble?.restyle()
            },
            L.t(R.string.caption_menu_lines, lines(act)) to {
                val next = (lines(act) % 4) + 1
                linesNow = next.toString(); setPref(Prefs.KEY_CAPTION_LINES, next.toString()); bubble?.restyle()
            },
            L.t(R.string.caption_menu_bg, L.t(bgs[bg(act)] ?: R.string.caption_bg_dim)) to {
                val next = when (bg(act)) { "dim" -> "solid"; "solid" -> "none"; else -> "dim" }
                bgNow = next; setPref(Prefs.KEY_CAPTION_BG, next); bubble?.restyle()
            },
            L.t(if (outlineOn) R.string.caption_menu_outline_off else R.string.caption_menu_outline_on) to {
                outlineNow = !outlineOn; setPref(Prefs.KEY_CAPTION_OUTLINE, !outlineOn); bubble?.restyle()
            },
            L.t(if (alwaysOn) R.string.caption_menu_always_off else R.string.caption_menu_always_on) to {
                alwaysNow = !alwaysOn; setPref(Prefs.KEY_CAPTION_ALWAYS, !alwaysOn); bubble?.onAlwaysChanged()
            },
            L.t(if (original) R.string.caption_menu_original_hide else R.string.caption_menu_original_show) to {
                originalNow = !original; setPref(Prefs.KEY_CAPTION_ORIGINAL, !original); bubble?.restyle()
            },
            L.t(R.string.caption_menu_reset) to { bubble?.resetPlacement() },
            L.t(R.string.caption_menu_copy) to { copyTranscript(act) },
            L.t(R.string.caption_menu_hide) to { onHide(act) },
            L.t(R.string.caption_menu_off) to { setPref(Prefs.KEY_LIVE_CAPTIONS, false); hiddenRoom = room; stop() },
        )
        runCatching {
            dialog(act).setTitle(L.t(R.string.captions_title))
                .setItems(actions.map { it.first }.toTypedArray()) { d, which -> d.dismiss(); actions[which].second() }
                .show()
        }.onFailure { XLog.log("Captions: menu failed: ${it.message}") }
    }

    private fun chooseLanguage(act: Activity) {
        val tags = listOf(CaptionLanguages.AUTO) + CaptionLanguages.SPEECH
        val names = tags.map { if (it == CaptionLanguages.AUTO) L.t(R.string.caption_auto) else CaptionLanguages.name(it) }.toTypedArray()
        runCatching {
            dialog(act).setTitle(L.t(R.string.caption_pick_language))
                .setSingleChoiceItems(names, tags.indexOfFirst { it == listeningLang }) { d, which ->
                    call("captions_source", Bundle().apply { putString(CaptionService.EXTRA_LANG, tags[which]) })
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.onFailure { XLog.log("Captions: language dialog failed: ${it.message}") }
    }

    private fun chooseTarget(act: Activity) {
        val tags = listOf("") + CaptionLanguages.TARGETS
        val names = tags.map { if (it.isBlank()) L.t(R.string.captions_target_app) else CaptionLanguages.name(it) }.toTypedArray()
        runCatching {
            dialog(act).setTitle(L.t(R.string.captions_target))
                .setSingleChoiceItems(names, tags.indexOf(target(act))) { d, which ->
                    targetNow = tags[which]; setPref(Prefs.KEY_CAPTION_TARGET, tags[which])
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun copyTranscript(act: Activity) = call("captions_transcript", null) { result ->
        val text = result?.getString("text").orEmpty()
        runCatching {
            if (text.isNotBlank()) (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TikMod", text))
            Toast.makeText(act, L.t(if (text.isBlank()) R.string.caption_copy_empty else R.string.caption_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onHide(act: Activity) {
        hiddenRoom = room
        stop()
        runCatching { Toast.makeText(act, L.t(R.string.caption_hidden), Toast.LENGTH_SHORT).show() }
    }

    /**
     * The caption bubble. The sentence being spoken is the main line; the sentence before it stays above it,
     * dimmed, so nothing disappears before it could be read. The original words can be shown underneath.
     *
     * Gestures: one finger drags it anywhere on screen, two fingers pinch to resize, a tap opens the quick
     * menu, a double-tap freezes/unfreezes the current line, a long press hides captions for this stream.
     * Position and size are remembered separately for portrait and landscape.
     */
    private inner class Bubble(private val act: Activity) : LinearLayout(act) {
        private val previous = TextView(act)
        private val text = TextView(act)
        private val original = TextView(act)
        private val store = act.getSharedPreferences("tikmod_captions_ui", Context.MODE_PRIVATE)
        private val slop = ViewConfiguration.get(act).scaledTouchSlop
        private val bgDrawable = GradientDrawable().apply { }
        private var fade: ValueAnimator? = null
        private var captioned = false
        private var lastFinal = ""
        private var lastFinalSource = ""
        private var liveText = ""
        private var liveSource = ""
        private var pendingFinal: String? = null       // arrived while frozen
        private var pendingSource = ""
        private var frozen = false
        private var userScale = 1f

        private var downX = 0f; private var downY = 0f
        private var startTx = 0f; private var startTy = 0f
        private var dragging = false
        private var multi = false
        private val hide = Runnable { if (!always(act) && !frozen) fadeTo(0f) }
        private val longPress = Runnable { if (!dragging && !multi) onHide(act) }

        private val scaleDetector = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                userScale = (userScale * d.scaleFactor).coerceIn(0.6f, 2.4f); applyScale(); return true
            }
        })
        private val gestures = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(act); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { toggleFreeze(); return true }
        }).apply { setIsLongpressEnabled(false) }

        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
        private fun portrait() = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        private fun key(k: String) = if (portrait()) k + "_p" else k + "_l"

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(9f))
            bgDrawable.cornerRadius = dp(16f).toFloat()
            background = bgDrawable
            elevation = dp(6f).toFloat()
            val maxW = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            previous.apply {
                setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER; maxWidth = maxW; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG; visibility = View.GONE
                setPadding(0, 0, 0, dp(3f))
            }
            text.apply {
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER; maxWidth = maxW; setLineSpacing(0f, 1.12f)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
            original.apply {
                setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER; maxWidth = maxW; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                setPadding(0, dp(3f), 0, 0)
            }
            addView(previous); addView(text); addView(original)
            alpha = 0f
            userScale = store.getFloat(key("scale"), 1f).coerceIn(0.6f, 2.4f)
            restyle()
            post { placeFromStore(); applyScale() }
        }

        /** Size, colours, outline, line count — from the settings or the quick menu. */
        fun restyle() {
            val base = when (size(act)) { "s" -> 14f; "l" -> 19.5f; else -> 16.5f }
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (captioned) base else 13f)
            previous.setTextSize(TypedValue.COMPLEX_UNIT_SP, base - 3f)
            original.setTextSize(TypedValue.COMPLEX_UNIT_SP, base - 4f)
            text.maxLines = lines(act)
            val heavy = outline(act)
            val shadow = if (heavy) 9f else 4f
            text.setShadowLayer(shadow, 0f, if (heavy) 0f else 1f, 0xE6000000.toInt())
            previous.setShadowLayer(shadow, 0f, 0f, 0xCC000000.toInt())
            original.setShadowLayer(shadow, 0f, 0f, 0xCC000000.toInt())
            val fill = when (bg(act)) { "solid" -> 0xF0000000.toInt(); "none" -> 0x00000000; else -> 0xC2000000.toInt() }
            bgDrawable.setColor(if (frozen) 0xF01A1A1A.toInt() else fill)
            bgDrawable.setStroke(dp(0.6f).coerceAtLeast(1), if (frozen) 0x66FFFFFF else 0x33FFFFFF)
            if (captioned) render()
        }

        private fun applyScale() { scaleX = userScale; scaleY = userScale }

        private fun placeFromStore() {
            val p = parent as? View ?: return
            translationX = store.getFloat(key("x"), 0f) * p.width
            translationY = store.getFloat(key("y"), 0.5f) * p.height
            clampIntoParent()
        }

        private fun savePlacement() {
            val p = parent as? View ?: return
            store.edit()
                .putFloat(key("x"), translationX / p.width.coerceAtLeast(1))
                .putFloat(key("y"), translationY / p.height.coerceAtLeast(1))
                .putFloat(key("scale"), userScale)
                .apply()
        }

        fun resetPlacement() {
            userScale = 1f; applyScale()
            translationX = 0f
            (parent as? View)?.let { translationY = it.height * 0.5f }
            store.edit().remove(key("x")).remove(key("y")).remove(key("scale")).apply()
            reveal(4000)
        }

        fun onAlwaysChanged() = reveal(4000)

        private fun clampIntoParent() {
            val p = parent as? View ?: return
            val marginX = p.width / 2f - dp(24f)
            translationX = translationX.coerceIn(-marginX, marginX)
            translationY = translationY.coerceIn(0f, (p.height - dp(36f)).toFloat())
        }

        private fun toggleFreeze() {
            frozen = !frozen
            if (!frozen) { pendingFinal?.let { lastFinal = it; lastFinalSource = pendingSource; pendingFinal = null }; render() }
            restyle()
            reveal(0)
            runCatching { Toast.makeText(act, L.t(if (frozen) R.string.caption_frozen else R.string.caption_unfrozen), Toast.LENGTH_SHORT).show() }
        }

        fun hasCaption() = captioned && alpha > 0.5f

        fun showStatus(message: String, sticky: Boolean = false, holdMs: Long = 4000) {
            captioned = false
            restyle()
            text.text = message
            previous.visibility = View.GONE
            original.visibility = View.GONE
            reveal(if (sticky) 0 else holdMs)
        }

        fun showCaption(translated: String, source: String, final: Boolean) {
            if (translated.isBlank()) return
            if (frozen) { if (final) { pendingFinal = translated; pendingSource = source }; return }
            if (final) { lastFinal = translated; lastFinalSource = source; liveText = ""; liveSource = "" }
            else { liveText = translated; liveSource = source }
            if (!captioned) { captioned = true; restyle() } else render()
            reveal(8000)
        }

        private fun render() {
            val growing = liveText.isNotBlank()
            val mainText = if (growing) liveText else lastFinal
            val mainSource = if (growing) liveSource else lastFinalSource
            if (text.text.toString() != mainText) text.text = mainText
            val showPrevious = growing && lastFinal.isNotBlank() && lines(act) >= 2
            previous.visibility = if (showPrevious) View.VISIBLE else View.GONE
            if (showPrevious && previous.text.toString() != lastFinal) previous.text = lastFinal
            val showOrig = showOriginal(act) && mainSource.isNotBlank() && mainSource != mainText
            original.visibility = if (showOrig) View.VISIBLE else View.GONE
            if (showOrig) original.text = mainSource
        }

        private fun reveal(hideAfterMs: Long) {
            removeCallbacks(hide)
            fadeTo(1f)
            if (hideAfterMs > 0 && !always(act) && !frozen) postDelayed(hide, hideAfterMs)
        }

        private fun fadeTo(target: Float) {
            if (alpha == target) return
            fade?.cancel()
            fade = ValueAnimator.ofFloat(alpha, target).apply {
                duration = 180
                addUpdateListener { alpha = it.animatedValue as Float }
                start()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (alpha < 0.3f) return false // invisible: touches go to the stream
            scaleDetector.onTouchEvent(e)
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startTx = translationX; startTy = translationY
                    dragging = false; multi = false
                    postDelayed(longPress, 600)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> { multi = true; dragging = false; removeCallbacks(longPress) }
                MotionEvent.ACTION_MOVE -> {
                    if (multi || scaleDetector.isInProgress) return true
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; removeCallbacks(longPress) }
                    if (dragging) { translationX = startTx + dx; translationY = startTy + dy; clampIntoParent(); reveal(0) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (dragging || userScale != store.getFloat(key("scale"), 1f)) savePlacement()
                    if (multi || dragging) reveal(8000)
                    multi = false; dragging = false
                }
            }
            return true
        }
    }
}

package com.mts.tikmod.hooks

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.focus.Focus
import com.mts.tikmod.focus.FocusLearn
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.XLog
import java.lang.ref.WeakReference

/**
 * The parts of "Stop the addiction" that live inside TikTok besides the feed filter (see [AdsHook]):
 *  - tracks how long TikTok has been in front (a session ends after 10 minutes away);
 *  - at bedtime, a calm full-screen "time to sleep" card over TikTok (close TikTok, or 5 more minutes);
 *  - when the session limit is reached, a one-time gentle reminder (and the feed gets stricter by itself).
 * Nothing here blocks TikTok for good: every card can be dismissed.
 */
class FocusHook : BaseHook() {
    override val name: String = "Stop the addiction (sessions, bedtime)"

    private val main = Handler(Looper.getMainLooper())
    private var activity: WeakReference<Activity>? = null
    private var overlay: WeakReference<View>? = null
    private var snoozeUntil = 0L
    private var remindedSession = -1L
    private var ticking = false
    private var lastState = ""
    private var lastActive: Boolean? = null

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "activity lifecycle")
            // Which post is on screen, and for how long: what FocusLearn learns from.
            val cell = de.robv.android.xposed.XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.adapter.VideoViewCell", classLoader)
            if (cell == null) { XLog.log("Focus: VideoViewCell not found (no learning)"); return@tryHook }
            hookAllMethodsSafely(cell, "onPageSelected", object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val aweme = com.mts.tikmod.utils.Reflect.call(param.thisObject, "getAweme") ?: return
                        FocusLearn.onSelected(context, aweme)
                    }
                }
            })
            XLog.log("Focus: watching which post is on screen (VideoViewCell.onPageSelected)")
        }
    }

    fun onResumed(act: Activity) {
        Focus.onForeground()
        // A card left on another TikTok screen goes; the check below puts it on this one if it still applies.
        if (overlay?.get()?.let { it.rootView !== act.window?.decorView } == true) removeOverlay()
        activity = WeakReference(act)
        if (!ticking) { ticking = true; main.post(tick) }
        else main.post { check() }
    }

    fun onStopped(act: Activity) {
        if (activity?.get() === act) {
            Focus.onBackground()
            FocusLearn.onPaused(act)
            activity = null
            removeOverlay()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val act = activity?.get()
            if (act == null || act.isFinishing) { ticking = false; return }
            Focus.onForeground()
            check()
            main.postDelayed(this, 8_000)
        }
    }

    private val bg by lazy { android.os.HandlerThread("TikMod-Focus").apply { start() }.let { Handler(it.looper) } }

    /** Settings come from TikMod over a binder call: read them off the main thread, then act on it. */
    private fun check() {
        val act = activity?.get() ?: return
        bg.post {
            val st = runCatching { Focus.state(act) }.getOrNull() ?: return@post
            val screen = Prefs.bool(act, Prefs.KEY_BEDTIME_SCREEN)
            val remind = Prefs.bool(act, Prefs.KEY_SESSION_REMIND)
            main.post { if (activity?.get() === act) onState(act, st, screen, remind) }
        }
    }

    private fun onState(act: Activity, st: Focus.State, bedtimeScreen: Boolean, sessionRemind: Boolean) {
        runCatching {
            val sig = "${"%.2f".format(st.strength)} ${st.why}"
            if (sig != lastState) { lastState = sig; XLog.log("Focus: state $sig (session ${Focus.sessionMinutes()} min)") }
            // Switching it on / off from TikMod or the Quick Settings tile shows up here within seconds.
            val was = lastActive
            lastActive = st.active
            if (was != null && was != st.active) {
                android.widget.Toast.makeText(act, L.t(if (st.active) R.string.f2_toast_on else R.string.f2_toast_off), android.widget.Toast.LENGTH_LONG).show()
            }
            val now = System.currentTimeMillis()
            when {
                st.bedtime && bedtimeScreen && now >= snoozeUntil -> show(act, bedtime = true)
                Focus.Why.SESSION in st.why && sessionRemind && remindedSession != sessionKey() -> {
                    remindedSession = sessionKey()
                    show(act, bedtime = false)
                }
                !st.bedtime && overlay?.get()?.tag == "bedtime" -> removeOverlay()
            }
        }.onFailure { XLog.log("Focus: check failed: ${it.message}") }
    }

    /** Identifies the current session (its start minute). */
    private fun sessionKey(): Long = System.currentTimeMillis() / 60_000L - Focus.sessionMinutes()

    private fun removeOverlay() {
        overlay?.get()?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        overlay = null
    }

    private fun show(act: Activity, bedtime: Boolean) {
        val tag = if (bedtime) "bedtime" else "session"
        if (overlay?.get()?.let { it.tag == tag && it.isAttachedToWindow } == true) return
        removeOverlay()
        val root = act.window?.decorView as? ViewGroup ?: return
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics).toInt()

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28f), dp(32f), dp(28f), dp(24f))
            background = GradientDrawable().apply { cornerRadius = dp(28f).toFloat(); setColor(Color.parseColor("#F21B1F3B")) }
            layoutDirection = if (L.isRtl()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(4f))
        }
        card.addView(text(if (bedtime) "🌙" else "🌿", 52f, Color.WHITE))
        card.addView(text(L.t(if (bedtime) R.string.focus_sleep_title else R.string.focus_break_title), 22f, Color.WHITE, bold = true))
        card.addView(text(
            if (bedtime) L.t(R.string.focus_sleep_msg) else L.t(R.string.focus_break_msg, Focus.sessionMinutes()),
            15f, Color.parseColor("#CCD6E0FF")
        ))
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(act).apply {
            text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.parseColor("#0B1020") else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                if (primary) setColor(Color.parseColor("#9EE7FF")) else { setColor(Color.TRANSPARENT); setStroke(dp(1f), Color.parseColor("#66FFFFFF")) }
            }
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setOnClickListener { runCatching(onClick) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) }
        }
        card.addView(View(act), LinearLayout.LayoutParams(1, dp(12f)))
        card.addView(button(L.t(R.string.focus_close_tiktok), primary = true) {
            removeOverlay(); act.moveTaskToBack(true)
        })
        card.addView(button(L.t(if (bedtime) R.string.focus_five_more else R.string.focus_keep_watching), primary = false) {
            if (bedtime) snoozeUntil = System.currentTimeMillis() + 5 * 60_000L
            removeOverlay()
        })

        val shade = FrameLayout(act).apply {
            this.tag = tag
            setBackgroundColor(Color.parseColor("#D9050814"))
            isClickable = true // swallows touches: the feed behind doesn't scroll
            addView(card, FrameLayout.LayoutParams(dp(320f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            alpha = 0f
        }
        root.addView(shade, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shade.animate().alpha(1f).setDuration(450).start()
        overlay = WeakReference(shade)
        XLog.log("Focus: showing the ${tag} card")
    }
}

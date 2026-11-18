package com.mts.tikmod.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mts.tikmod.utils.XLog

/**
 * Entry to TikMod from TikTok's settings screen: a brand card in the app's own style
 * (dark glass, cyan→violet→pink hairline, logo tile). It sits at the top of the settings list when the
 * layout allows it, and otherwise docks at the bottom of the screen.
 */
class SettingsHook : BaseHook() {
    override val name: String = "TikTok Settings Entry"

    private companion object {
        const val TAG = "tikmod_settings_entry"
        const val CYAN = 0xFF25F4EE.toInt()
        const val VIOLET = 0xFF8E6BFF.toInt()
        const val PINK = 0xFFFE2C55.toInt()
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        // Nothing to hook: ModuleManager calls injectIntoActivity when a settings activity resumes.
        status = status.copy(foundClass = "Activity listener")
    }

    fun injectIntoActivity(activity: Activity) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        // Wait for TikTok to lay its list out, then place the card.
        root.post { runCatching { place(activity, root) }.onFailure { XLog.log("SettingsEntry: ${it.message}") } }
    }

    private fun place(activity: Activity, root: FrameLayout) {
        if (root.findViewWithTag<View>(TAG) != null) return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val card = buildCard(activity, d)

        // Preferred: the first row above the settings list (when the list lives in a vertical LinearLayout).
        val list = findList(root)
        val parent = list?.parent as? LinearLayout
        if (list != null && parent != null && parent.orientation == LinearLayout.VERTICAL) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(16f), dp(10f), dp(16f), dp(6f))
            parent.addView(card, parent.indexOfChild(list), lp)
            XLog.log("SettingsEntry: placed above the list in ${activity.javaClass.name}")
        } else {
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            lp.setMargins(dp(16f), 0, dp(16f), dp(28f))
            root.addView(card, lp)
            card.elevation = dp(8f).toFloat()
            XLog.log("SettingsEntry: docked at the bottom of ${activity.javaClass.name}")
        }
        // Entrance: fade + rise.
        card.alpha = 0f
        card.translationY = dp(14f).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(320).start()
    }

    /** The biggest scrolling container on screen (RecyclerView / ScrollView / ListView). */
    private fun findList(root: ViewGroup): View? {
        var best: View? = null
        fun walk(v: View) {
            val scrolling = v is ScrollView || v is android.widget.AbsListView ||
                generateSequence(v.javaClass as Class<*>) { it.superclass }.any { it.name.endsWith("RecyclerView") }
            if (scrolling && v.height > (best?.height ?: 0)) best = v
            if (v is ViewGroup && !scrolling) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return best
    }

    private fun buildCard(activity: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        val row = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            background = CardBackground(d)
            setPadding(dp(14f), dp(13f), dp(16f), dp(13f))
            isClickable = true
            isFocusable = true
        }
        // Logo tile
        row.addView(View(activity).apply { background = LogoDrawable(d) }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        // Texts
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            text = "TikMod"
            textSize = 17f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            includeFontPadding = false
        })
        texts.addView(TextView(activity).apply {
            text = "by Nerix Labs"
            textSize = 12.5f
            letterSpacing = 0.03f
            setTextColor(0xFFA9B4C4.toInt())
            includeFontPadding = false
            setPadding(0, dp(3f), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14f) })
        // Chevron (drawn, mirrors in RTL)
        row.addView(View(activity).apply { background = ChevronDrawable(d) }, LinearLayout.LayoutParams(dp(22f), dp(22f)))

        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
        row.setOnClickListener {
            runCatching {
                activity.startActivity(Intent().setClassName("com.mts.tikmod", "com.mts.tikmod.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return row
    }

    /** Dark glass card with a gradient hairline. */
    private class CardBackground(private val d: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        private val rect = RectF()

        override fun onBoundsChange(b: android.graphics.Rect) {
            val w = b.width().toFloat(); val h = b.height().toFloat()
            fill.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF10141D.toInt(), 0xFF171327.toInt(), 0xFF1E1020.toInt()), null, Shader.TileMode.CLAMP)
            stroke.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(CYAN, VIOLET, PINK), null, Shader.TileMode.CLAMP)
            val i = stroke.strokeWidth / 2
            rect.set(i, i, w - i, h - i)
        }

        override fun draw(c: Canvas) {
            val r = 20f * d
            c.drawRoundRect(rect, r, r, fill)
            c.drawRoundRect(rect, r, r, stroke)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Gradient rounded tile with a white bolt. */
    private class LogoDrawable(private val d: Float) : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            bg.shader = LinearGradient(0f, 0f, w, h, intArrayOf(CYAN, VIOLET, PINK), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, w, h), 14f * d, 14f * d, bg)
            val p = Path().apply {
                moveTo(w * 0.57f, h * 0.18f); lineTo(w * 0.30f, h * 0.55f); lineTo(w * 0.47f, h * 0.55f)
                lineTo(w * 0.42f, h * 0.82f); lineTo(w * 0.70f, h * 0.44f); lineTo(w * 0.53f, h * 0.44f); close()
            }
            c.drawPath(p, bolt)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class ChevronDrawable(private val d: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CYAN; style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        override fun isAutoMirrored() = true

        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL ||
                android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
            c.save()
            if (rtl) c.scale(-1f, 1f, w / 2, h / 2)
            c.drawPath(Path().apply { moveTo(w * 0.38f, h * 0.24f); lineTo(w * 0.64f, h * 0.5f); lineTo(w * 0.38f, h * 0.76f) }, p)
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

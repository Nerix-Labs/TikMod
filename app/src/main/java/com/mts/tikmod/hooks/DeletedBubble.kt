package com.mts.tikmod.hooks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Visual marking for restored (deleted) DMs: a small, faded trash-can sign — nothing else.
 *
 * The restored text carries an invisible marker: MARK + 11 bits of "minute of day" (U+200B = 0,
 * U+200C = 1) + END + original text (the deletion time stays encoded for future use).
 *  - Chat bubbles (X.19NP, a custom view fed a pre-built Layout and sized ahead of time): the text is left
 *    untouched and a faded sign is drawn inside the bubble, in its padding at the left edge.
 *  - Plain TextViews (chat list preview etc.): the marker is replaced by an inline faded sign before the text.
 */
object DeletedBubble {
    const val MARK = "⁣⁣"
    private const val END = "⁣"
    private const val LEGACY_VISIBLE = "🗑️ "
    private const val TAG_KEY = 0x7E7D0001 // app-specific View tag key (decoration state)

    fun encode(minuteOfDay: Int): String {
        val bits = if (minuteOfDay in 0..1439) (10 downTo 0).joinToString("") { if ((minuteOfDay shr it) and 1 == 1) "‌" else "​" } else ""
        return MARK + bits + END
    }

    private data class Parsed(val minute: Int, val text: String)

    private fun parse(s: CharSequence): Parsed? {
        if (s.length < 3) return null
        val str = s.toString()
        // TikTok may prepend bidi marks or a "name: " prefix, so look for the marker anywhere.
        val at = str.indexOf(MARK).takeIf { it >= 0 } ?: return null
        val end = str.indexOf(END, at + 2).takeIf { it >= 0 } ?: return null
        val bits = str.substring(at + 2, end)
        val minute = if (bits.length == 11) bits.fold(0) { acc, c -> (acc shl 1) or (if (c == '‌') 1 else 0) } else -1
        val text = str.substring(0, at) + str.substring(end + 1).removePrefix(LEGACY_VISIBLE)
        return Parsed(minute, text)
    }

    @Volatile private var installed = false

    /** Plain TextViews: inline faded sign + clean text. */
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args[0] as? CharSequence ?: return
                        val parsed = parse(text) ?: return
                        val sb = SpannableStringBuilder()
                        sb.append("⁣")
                        sb.setSpan(IconSpan(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.append(parsed.text)
                        param.args[0] = sb
                    }
                })
            XLog.log("DeletedBubble: renderer installed")
        }.onFailure { XLog.log("DeletedBubble: ${it.message}") }
    }

    /** Chat bubbles: hook the custom view's setTextLayout(Layout) and decorate around the text. */
    fun installCustom(classLoader: ClassLoader, className: String = "X.19NP") {
        runCatching {
            val cls = XposedHelpers.findClassIfExists(className, classLoader) ?: run {
                XLog.log("DeletedBubble: $className not found"); return
            }
            val setters = generateSequence(cls as Class<*>) { it.superclass }
                .takeWhile { !it.name.startsWith("android.") && !it.name.startsWith("java.") }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { m -> m.parameterTypes.size == 1 && m.parameterTypes[0] == Layout::class.java && !java.lang.reflect.Modifier.isAbstract(m.modifiers) }
                .toList()
            setters.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val layout = param.args[0] as? Layout
                        if (layout?.text?.let { parse(it) } == null) { restore(v); return }
                        runCatching { decorate(v, layout.paint.color) }.onFailure { XLog.log("DeletedBubble decorate: ${it.message}") }
                    }
                })
            }
            XLog.log("DeletedBubble: hooked ${setters.size} layout setters on $className")
        }.onFailure { XLog.log("DeletedBubble custom: ${it.message}") }
    }

    private class Decoration(val drawable: SignDrawable) {
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var host: View? = null
    }

    private fun decorate(v: View, textColor: Int) {
        (v.getTag(TAG_KEY) as? Decoration)?.let { it.drawable.color = textColor; it.host?.invalidate(); return }
        val dark = (v.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val deco = Decoration(SignDrawable(textColor, v.resources.displayMetrics.density, dark))
        val listener = ViewTreeObserver.OnPreDrawListener { position(v, deco); true }
        deco.listener = listener
        v.setTag(TAG_KEY, deco)
        v.viewTreeObserver.addOnPreDrawListener(listener)
        v.post { position(v, deco) }
    }

    private val tmpA = IntArray(2)
    private val tmpB = IntArray(2)

    /** Place the sign beside the bubble, on its open side. */
    private fun position(v: View, deco: Decoration) {
        val d = deco.drawable
        if (!v.isAttachedToWindow || !v.isShown || v.width == 0) {
            if (d.visible) { d.visible = false; deco.host?.invalidate() }
            return
        }
        val screenW = v.resources.displayMetrics.widthPixels
        val host = deco.host ?: generateSequence(v.parent as? View) { it.parent as? View }
            .firstOrNull { it.width >= screenW * 0.9f }?.also { h -> deco.host = h; h.overlay.add(d) } ?: return
        val bubble = generateSequence(v) { it.parent as? View }.takeWhile { it !== host }.take(5)
            .firstOrNull { it.background != null } ?: v
        host.getLocationInWindow(tmpB)
        bubble.getLocationInWindow(tmpA)
        val bl = (tmpA[0] - tmpB[0]).toFloat(); val bt = (tmpA[1] - tmpB[1]).toFloat()
        val br = bl + bubble.width
        val dens = v.resources.displayMetrics.density
        // Fixed size, on the open side of the bubble (like a status icon), vertically centered.
        val size = 15f * dens
        val margin = 7f * dens
        val cx = if ((bl + br) / 2 > host.width / 2f) bl - margin - size / 2 else br + margin + size / 2
        val cy = bt + bubble.height / 2f
        if (d.visible && d.cx == cx && d.cy == cy && d.size == size && d.bounds.width() == host.width && d.bounds.height() == host.height) return
        d.cx = cx; d.cy = cy; d.size = size; d.visible = true
        d.setBounds(0, 0, host.width, host.height)
        host.invalidate()
    }

    private fun restore(v: View) {
        val deco = v.getTag(TAG_KEY) as? Decoration ?: return
        deco.listener?.let { v.viewTreeObserver.removeOnPreDrawListener(it) }
        deco.host?.let { it.overlay.remove(deco.drawable); it.invalidate() }
        v.setTag(TAG_KEY, null)
    }

    /** Faded tone of the bubble's own text color, so it fits light/dark and incoming/outgoing bubbles. */
    private fun faded(color: Int) = (color and 0x00FFFFFF) or (0x66 shl 24)

    private class SignDrawable(var color: Int, private val density: Float, private val dark: Boolean) : Drawable() {
        var cx = 0f; var cy = 0f; var size = 0f
        var visible = false

        override fun draw(canvas: Canvas) {
            // Neutral secondary gray (same family as TikTok's timestamps), softly faded.
            if (visible) drawTrash(canvas, cx, cy, size, if (dark) 0x99A0A1A7.toInt() else 0x998A8B91.toInt())
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Inline faded sign (for plain TextViews), sized to the text, followed by a small gap. */
    private class IconSpan : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.textSize * 0.85f + paint.textSize * 0.3f).toInt()

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val s = paint.textSize * 0.8f
            val fm = paint.fontMetrics
            val cy = y + (fm.ascent + fm.descent) / 2
            // In an RTL paragraph x is the left edge of our slot; keep the gap on the text's side.
            drawTrash(canvas, x + paint.textSize * 0.15f + s / 2, cy, s, faded(paint.color))
        }
    }

    /** Outline-style trash can: handle, lid and a tapered body with two slits, in a single color. */
    private fun drawTrash(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.11f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val top = cy - s / 2
        // handle
        val hw = s * 0.32f
        c.drawRoundRect(RectF(cx - hw / 2, top, cx + hw / 2, top + s * 0.16f), s * 0.05f, s * 0.05f, fill)
        // lid
        val lw = s * 0.9f
        c.drawRoundRect(RectF(cx - lw / 2, top + s * 0.12f, cx + lw / 2, top + s * 0.24f), s * 0.06f, s * 0.06f, fill)
        // body outline
        val bTop = top + s * 0.32f; val bBot = top + s * 0.98f
        val wTop = s * 0.70f; val wBot = s * 0.56f
        val body = Path().apply {
            moveTo(cx - wTop / 2, bTop); lineTo(cx - wBot / 2, bBot); lineTo(cx + wBot / 2, bBot); lineTo(cx + wTop / 2, bTop)
        }
        c.drawPath(body, line)
        // slits
        val sTop = bTop + s * 0.14f; val sBot = bBot - s * 0.14f
        c.drawLine(cx - s * 0.11f, sTop, cx - s * 0.09f, sBot, line)
        c.drawLine(cx + s * 0.11f, sTop, cx + s * 0.09f, sBot, line)
    }
}

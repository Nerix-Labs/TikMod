package com.mts.tikmod.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.min

/**
 * Burns a LIVE chat into a recorded video, the way TikTok shows it: the latest comments stacked at the
 * bottom-left, each in a dark pill with the name in colour.
 *
 * Decoder → SurfaceTexture → OpenGL (video + chat layer) → hardware H.264 encoder. The audio track is copied
 * unchanged. Runs once after a recording ends; on any failure the caller keeps the original file.
 */
object ChatBurner {

    /** A comment and the moment (video time, ms) it appears. */
    class Line(val ms: Long, val nick: String, val text: String)

    private const val VISIBLE_MS = 30_000L   // a comment stays this long…
    private const val MAX_VISIBLE = 7        // …among the newest few

    fun burn(input: File, lines: List<Line>, output: File, onProgress: (Int) -> Unit = {}): Boolean =
        try {
            Job(input, lines.sortedBy { it.ms }, output, onProgress).run()
            output.length() > 0
        } catch (t: Throwable) {
            XLog.log("ChatBurner: failed (${t.javaClass.simpleName}: ${t.message})")
            output.delete()
            false
        }

    private class Job(val input: File, val lines: List<Line>, val output: File, val onProgress: (Int) -> Unit) {
        private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
        private val frameLock = Object()
        private var frameReady = false

        fun run() {
            val vEx = MediaExtractor().apply { setDataSource(input.path) }
            val aEx = MediaExtractor().apply { setDataSource(input.path) }
            var dec: MediaCodec? = null
            var enc: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var muxStarted = false
            var st: SurfaceTexture? = null
            var decSurface: Surface? = null
            var encSurface: Surface? = null
            val stThread = HandlerThread("TikMod-ChatBurner").apply { start() }
            try {
                val vTrack = (0 until vEx.trackCount).first { vEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                val inFmt = vEx.getTrackFormat(vTrack)
                vEx.selectTrack(vTrack)
                val aTrack = (0 until aEx.trackCount).firstOrNull { aEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                val aFmt = aTrack?.let { aEx.selectTrack(it); aEx.getTrackFormat(it) }

                val w = inFmt.getInteger(MediaFormat.KEY_WIDTH)
                val h = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
                val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
                val fps = runCatching { inFmt.getInteger(MediaFormat.KEY_FRAME_RATE) }
                    .recoverCatching { inFmt.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }.getOrDefault(30).coerceIn(10, 60)
                val bitrate = if (durUs > 0) (input.length() * 8 * 1_000_000L / durUs).toInt().coerceIn(2_500_000, 16_000_000) else 8_000_000

                // Encoder (H.264 plays everywhere).
                val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                enc = encoder
                encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encSurface = inputSurface
                encoder.start()

                setupEgl(inputSurface)
                val gl = Gl(w, h)

                val tex = SurfaceTexture(gl.oesTex).apply { setDefaultBufferSize(w, h) }
                st = tex
                tex.setOnFrameAvailableListener({ synchronized(frameLock) { frameReady = true; frameLock.notifyAll() } }, Handler(stThread.looper))
                val outSurface = Surface(tex)
                decSurface = outSurface
                val decoder = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
                dec = decoder
                decoder.configure(inFmt, outSurface, null, 0)
                decoder.start()

                val mux = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux
                var vMux = -1
                var aMux = -1
                val overlay = Overlay(w, h, lines)
                val mtx = FloatArray(16)
                val info = MediaCodec.BufferInfo()
                val eInfo = MediaCodec.BufferInfo()
                val aInfo = MediaCodec.BufferInfo()
                val aBuf = ByteBuffer.allocateDirect(512 * 1024)
                var audioDone = aFmt == null
                var lastPct = -1

                fun writeAudioUpTo(ptsUs: Long) {
                    if (!muxStarted || audioDone) return
                    while (true) {
                        val t = aEx.sampleTime
                        if (t < 0) { audioDone = true; return }
                        if (t > ptsUs) return
                        aBuf.clear()
                        val n = aEx.readSampleData(aBuf, 0)
                        if (n < 0) { audioDone = true; return }
                        val flags = if (aEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        aInfo.set(0, n, t, flags)
                        mux.writeSampleData(aMux, aBuf, aInfo)
                        aEx.advance()
                    }
                }

                fun drainEncoder(end: Boolean) {
                    var idle = 0
                    while (true) {
                        val oi = encoder.dequeueOutputBuffer(eInfo, if (end) 10_000 else 0)
                        when {
                            oi == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++idle > 300) return }
                            oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                vMux = mux.addTrack(encoder.outputFormat)
                                if (aFmt != null) aMux = mux.addTrack(aFmt)
                                mux.start(); muxStarted = true
                            }
                            oi >= 0 -> {
                                val b = encoder.getOutputBuffer(oi)!!
                                if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                                if (eInfo.size > 0 && muxStarted) {
                                    b.position(eInfo.offset); b.limit(eInfo.offset + eInfo.size)
                                    mux.writeSampleData(vMux, b, eInfo)
                                }
                                encoder.releaseOutputBuffer(oi, false)
                                if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val ii = decoder.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val n = vEx.readSampleData(decoder.getInputBuffer(ii)!!, 0)
                            if (n < 0) { decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { decoder.queueInputBuffer(ii, 0, n, vEx.sampleTime, 0); vEx.advance() }
                        }
                    }
                    val oi = decoder.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0
                        decoder.releaseOutputBuffer(oi, render)
                        if (render) {
                            awaitFrame()
                            tex.updateTexImage()
                            tex.getTransformMatrix(mtx)
                            gl.draw(mtx, overlay, info.presentationTimeUs / 1000)
                            EGLExt.eglPresentationTimeANDROID(dpy, surf, info.presentationTimeUs * 1000)
                            EGL14.eglSwapBuffers(dpy, surf)
                            drainEncoder(false)
                            writeAudioUpTo(info.presentationTimeUs)
                            if (durUs > 0) {
                                val pct = (info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 99)
                                if (pct >= lastPct + 5) { lastPct = pct; onProgress(pct) }
                            }
                        }
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            drainEncoder(true)
                            outDone = true
                        }
                    }
                }
                writeAudioUpTo(Long.MAX_VALUE)
                if (!muxStarted) error("encoder produced no output")
                mux.stop()
                onProgress(100)
            } finally {
                runCatching { dec?.stop() }; runCatching { dec?.release() }
                runCatching { enc?.stop() }; runCatching { enc?.release() }
                runCatching { muxer?.release() }
                runCatching { decSurface?.release() }
                runCatching { st?.release() }
                releaseEgl()
                runCatching { encSurface?.release() }
                runCatching { vEx.release() }; runCatching { aEx.release() }
                stThread.quitSafely()
            }
        }

        private fun awaitFrame() {
            synchronized(frameLock) {
                val until = System.currentTimeMillis() + 3000
                while (!frameReady) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) error("decoder frame timeout")
                    frameLock.wait(left)
                }
                frameReady = false
            }
        }

        private fun setupEgl(target: Surface) {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize" }
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent" }
        }

        private fun releaseEgl() {
            if (dpy == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(dpy)
            dpy = EGL14.EGL_NO_DISPLAY
        }
    }

    /** The two shader programs: the decoded frame (external texture) and the chat layer (2D texture). */
    private class Gl(val w: Int, val h: Int) {
        val oesTex: Int
        private val chatTex: Int
        private val videoProg: Int
        private val chatProg: Int
        private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val quadUv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private val chatPos: FloatBuffer
        private val chatUv = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        private var chatUploaded = false

        init {
            val t = IntArray(2)
            GLES20.glGenTextures(2, t, 0)
            oesTex = t[0]; chatTex = t[1]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, chatTex)
            texParams(GLES20.GL_TEXTURE_2D)
            videoProg = program(
                "uniform mat4 uTex; attribute vec4 aPos; attribute vec4 aUv; varying vec2 vUv;" +
                    "void main(){ gl_Position = aPos; vUv = (uTex * aUv).xy; }",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv;" +
                    "uniform samplerExternalOES sTex; void main(){ gl_FragColor = texture2D(sTex, vUv); }"
            )
            chatProg = program(
                "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = aPos; vUv = aUv; }",
                "precision mediump float; varying vec2 vUv; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vUv); }"
            )
            // The chat layer sits over the bottom-left part of the frame (see Overlay for its size).
            val x0 = Overlay.left(w, h); val x1 = x0 + Overlay.width(w, h)
            val y0 = Overlay.bottom(w, h); val y1 = y0 + Overlay.height(w, h)
            fun nx(px: Int) = px * 2f / w - 1f
            fun ny(py: Int) = py * 2f / h - 1f
            chatPos = floats(nx(x0), ny(y0), nx(x1), ny(y0), nx(x0), ny(y1), nx(x1), ny(y1))
        }

        fun draw(texMatrix: FloatArray, overlay: Overlay, ms: Long) {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(videoProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(videoProg, "uTex"), 1, false, texMatrix, 0)
            drawQuad(videoProg, quad, quadUv)

            val changed = overlay.update(ms)
            if (!overlay.empty) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, chatTex)
                if (changed || !chatUploaded) {
                    if (chatUploaded) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlay.bitmap)
                    else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay.bitmap, 0)
                    chatUploaded = true
                }
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // bitmaps are premultiplied
                GLES20.glUseProgram(chatProg)
                drawQuad(chatProg, chatPos, chatUv)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }

        private fun drawQuad(prog: Int, pos: FloatBuffer, uv: FloatBuffer) {
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aUv = GLES20.glGetAttribLocation(prog, "aUv")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos.position(0))
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv.position(0))
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun texParams(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
                val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }

    /** The chat layer: redrawn only when the set of visible comments changes. */
    private class Overlay(w: Int, h: Int, val lines: List<Line>) {
        companion object {
            // Portrait (most lives): a wide column low on the left. Landscape: a narrower, taller column.
            private fun portrait(w: Int, h: Int) = h >= w
            fun left(w: Int, h: Int) = (w * if (portrait(w, h)) 0.03f else 0.02f).toInt()
            fun bottom(w: Int, h: Int) = (h * if (portrait(w, h)) 0.07f else 0.05f).toInt()
            fun width(w: Int, h: Int) = (w * if (portrait(w, h)) 0.74f else 0.42f).toInt()
            fun height(w: Int, h: Int) = (h * if (portrait(w, h)) 0.32f else 0.58f).toInt()
        }

        val bitmap: Bitmap = Bitmap.createBitmap(width(w, h), height(w, h), Bitmap.Config.ARGB_8888)
        var empty = true; private set
        private val canvas = Canvas(bitmap)
        private val textSize = maxOf(w, h) / 46f
        private val padX = textSize * 0.55f
        private val padY = textSize * 0.30f
        private val gap = textSize * 0.32f
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = this@Overlay.textSize
            setShadowLayer(textSize * 0.12f, 0f, 0f, 0x99000000.toInt())
        }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private var shownFrom = -1
        private var shownTo = -1

        /** Returns true when the bitmap was redrawn for time [ms]. */
        fun update(ms: Long): Boolean {
            // Visible: comments already said, said less than VISIBLE_MS ago, newest MAX_VISIBLE of them.
            var to = upperBound(ms)                 // exclusive
            var from = to
            while (from > 0 && to - from < MAX_VISIBLE && ms - lines[from - 1].ms < VISIBLE_MS) from--
            if (from == to) { to = -1; from = -1 }
            if (from == shownFrom && to == shownTo) return false
            shownFrom = from; shownTo = to
            draw(from, to)
            return true
        }

        private fun upperBound(ms: Long): Int {
            var lo = 0; var hi = lines.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (lines[mid].ms <= ms) lo = mid + 1 else hi = mid }
            return lo
        }

        private fun draw(from: Int, to: Int) {
            bitmap.eraseColor(Color.TRANSPARENT)
            empty = from < 0
            if (empty) return
            val maxW = (bitmap.width - padX * 2).toInt()
            var y = bitmap.height.toFloat()
            for (i in to - 1 downTo from) {
                val l = lines[i]
                val sb = SpannableStringBuilder()
                if (l.nick.isNotBlank()) {
                    sb.append(l.nick)
                    sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(0xFFA8DFFF.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("  ")
                }
                sb.append(l.text)
                val desired = ceil(Layout.getDesiredWidth(sb, paint)).toInt()
                val lw = min(desired, maxW).coerceAtLeast(1)
                val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, lw)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false).build()
                val boxH = layout.height + padY * 2
                val top = y - boxH
                if (top < 0) break
                val r = RectF(0f, top, lw + padX * 2, y)
                canvas.drawRoundRect(r, boxH / 2.4f, boxH / 2.4f, pill)
                canvas.save()
                canvas.translate(padX, top + padY)
                layout.draw(canvas)
                canvas.restore()
                y = top - gap
            }
        }
    }
}

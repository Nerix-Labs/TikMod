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

    /** A gift (a whole combo: [count] is its final count) shown as a banner from [ms]. [label] is e.g. "sent Rose". */
    class Gift(val ms: Long, val nick: String, val label: String, val count: Int, val icon: Bitmap?, val anim: Anim? = null)

    /**
     * A gift's full-screen animation: a video with the colour picture and its transparency mask side by side.
     * [rgb] / [alpha] are their rectangles ([x, y, w, h], in a [frameW]x[frameH] frame; 0 = the decoded size);
     * null = mask on the left half, colour on the right (TikTok's AlphaPlayer default).
     */
    class Anim(val file: File, val rgb: IntArray?, val alpha: IntArray?, val frameW: Int = 0, val frameH: Int = 0)

    private const val VISIBLE_MS = 30_000L   // a comment stays this long…
    private const val MAX_VISIBLE = 7        // …among the newest few
    private const val GIFT_MS = 5_000L       // a gift banner stays this long…
    private const val MAX_GIFTS = 2          // …at most this many at once
    private const val ANIM_LATE_MS = 12_000L // an animation that would start later than this (queue) is skipped

    fun burn(input: File, lines: List<Line>, gifts: List<Gift>, output: File, onProgress: (Int) -> Unit = {}): Boolean =
        try {
            Job(input, lines.sortedBy { it.ms }, gifts.sortedBy { it.ms }, output, onProgress).run()
            output.length() > 0
        } catch (t: Throwable) {
            XLog.log("ChatBurner: failed (${t.javaClass.simpleName}: ${t.message})")
            output.delete()
            false
        }

    private class Job(val input: File, val lines: List<Line>, val gifts: List<Gift>, val output: File, val onProgress: (Int) -> Unit) {
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
                val overlay = Overlay(w, h, lines, gifts)
                // Gift animations play one at a time, like in TikTok: each starts when it was sent, or after the
                // one before it ends.
                val animQueue = ArrayDeque(gifts.mapNotNull { g -> g.anim?.let { g.ms to it } })
                var player: AnimPlayer? = null
                var animsPlayed = 0
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
                            val nowMs = info.presentationTimeUs / 1000
                            if (player == null) {
                                while (animQueue.isNotEmpty() && nowMs - animQueue.first().first > ANIM_LATE_MS) animQueue.removeFirst()
                                if (animQueue.isNotEmpty() && animQueue.first().first <= nowMs) {
                                    val a = animQueue.removeFirst().second
                                    player = runCatching { AnimPlayer(a, gl.animTex, Handler(stThread.looper), nowMs) }
                                        .onFailure { XLog.log("ChatBurner: animation ${a.file.name} failed to start (${it.message})") }.getOrNull()
                                }
                            }
                            val p = player
                            val showAnim = p != null && runCatching { p.advance(nowMs) }.getOrDefault(false)
                            if (p != null && !showAnim) { XLog.log("ChatBurner: animation done: ${p.frames} frames, ${nowMs - p.startMs}ms ${p.why}"); p.release(); player = null; animsPlayed++ }
                            gl.draw(mtx, overlay, nowMs, if (showAnim) p else null)
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
                player?.release()
                if (animsPlayed > 0) XLog.log("ChatBurner: played $animsPlayed gift animation(s)")
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

    /** Decodes one gift animation in step with the recording, onto its own texture. */
    private class AnimPlayer(val anim: Anim, texId: Int, handler: Handler, val startMs: Long) {
        private val ex = MediaExtractor().apply { setDataSource(anim.file.path) }
        private val codec: MediaCodec
        private val st = SurfaceTexture(texId)
        private val surface: Surface
        private val lock = Object()
        private var ready = false
        private val info = MediaCodec.BufferInfo()
        private var held = -1
        private var heldPts = 0L
        private var heldEos = false
        private var inDone = false
        private var outDone = false
        var hasFrame = false; private set
        var frames = 0; private set
        var why = ""
        val mtx = FloatArray(16)
        val videoW: Int
        val videoH: Int

        init {
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            videoW = fmt.getInteger(MediaFormat.KEY_WIDTH); videoH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            st.setDefaultBufferSize(videoW, videoH)
            st.setOnFrameAvailableListener({ synchronized(lock) { ready = true; lock.notifyAll() } }, handler)
            surface = Surface(st)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, surface, null, 0)
            codec.start()
        }

        /** Moves to the animation frame for recording time [ms]; false once the animation is over. */
        fun advance(ms: Long): Boolean {
            val relUs = (ms - startMs) * 1000
            var idle = 0
            while (true) {
                if (held < 0) {
                    if (outDone) return false
                    feed()
                    val oi = codec.dequeueOutputBuffer(info, 5_000)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) { if (++idle > 400) { why = "decoder idle"; return false }; continue }
                    if (oi < 0) continue
                    idle = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size <= 0) { codec.releaseOutputBuffer(oi, false); if (eos) { outDone = true; return false }; continue }
                    held = oi; heldPts = info.presentationTimeUs; heldEos = eos
                }
                if (heldPts > relUs) return hasFrame
                codec.releaseOutputBuffer(held, true)
                held = -1
                synchronized(lock) {
                    val until = System.currentTimeMillis() + 1500
                    while (!ready) { val left = until - System.currentTimeMillis(); if (left <= 0) { why = "frame timeout"; return false }; lock.wait(left) }
                    ready = false
                }
                st.updateTexImage()
                st.getTransformMatrix(mtx)
                hasFrame = true; frames++
                if (heldEos) outDone = true
            }
        }

        private fun feed() {
            while (!inDone) {
                val ii = codec.dequeueInputBuffer(0)
                if (ii < 0) return
                val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
            }
        }

        fun release() {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { surface.release() }; runCatching { st.release() }
            runCatching { ex.release() }
        }
    }

    /** The shader programs: the decoded frame (external texture), gift animations, and the chat layer (2D texture). */
    private class Gl(val w: Int, val h: Int) {
        val oesTex: Int
        val animTex: Int
        private val animProg: Int
        private val chatTex: Int
        private val videoProg: Int
        private val chatProg: Int
        private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val quadUv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private val chatPos: FloatBuffer
        private val chatUv = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        private var chatUploaded = false

        init {
            val t = IntArray(3)
            GLES20.glGenTextures(3, t, 0)
            oesTex = t[0]; chatTex = t[1]; animTex = t[2]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, animTex)
            texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
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
            // Colour from one rectangle of the frame, transparency (grey level) from another.
            animProg = program(
                "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = aPos; vUv = aUv; }",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv;" +
                    "uniform samplerExternalOES sTex; uniform mat4 uTex; uniform vec4 uRgb; uniform vec4 uA;" +
                    "void main(){ vec2 c = (uTex * vec4(uRgb.xy + vUv * uRgb.zw, 0.0, 1.0)).xy;" +
                    " vec2 m = (uTex * vec4(uA.xy + vUv * uA.zw, 0.0, 1.0)).xy;" +
                    " float a = texture2D(sTex, m).r; gl_FragColor = vec4(texture2D(sTex, c).rgb * a, a); }"
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

        fun draw(texMatrix: FloatArray, overlay: Overlay, ms: Long, anim: AnimPlayer? = null) {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(videoProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(videoProg, "uTex"), 1, false, texMatrix, 0)
            drawQuad(videoProg, quad, quadUv)

            if (anim != null && anim.hasFrame) drawAnim(anim)

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

        /** The animation, as large as fits the frame, centred (TikTok plays them full screen). */
        private fun drawAnim(p: AnimPlayer) {
            val a = p.anim
            val fw = (if (a.frameW > 0) a.frameW else p.videoW).toFloat()
            val fh = (if (a.frameH > 0) a.frameH else p.videoH).toFloat()
            val rgb = a.rgb ?: intArrayOf((fw / 2).toInt(), 0, (fw / 2).toInt(), fh.toInt())
            val al = a.alpha ?: intArrayOf(0, 0, (fw / 2).toInt(), fh.toInt())
            // Rectangles are top-left based; texture space starts bottom-left.
            fun uv(r: IntArray) = floatArrayOf(r[0] / fw, 1f - (r[1] + r[3]) / fh, r[2] / fw, r[3] / fh)
            val c = uv(rgb); val m = uv(al)
            val aspect = rgb[2].toFloat() / rgb[3].coerceAtLeast(1)
            var dw = w.toFloat(); var dh = dw / aspect
            if (dh > h) { dh = h.toFloat(); dw = dh * aspect }
            val x0 = (w - dw) / 2 * 2f / w - 1f; val x1 = -x0
            val y0 = (h - dh) / 2 * 2f / h - 1f; val y1 = -y0
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(animProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, animTex)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(animProg, "uTex"), 1, false, p.mtx, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(animProg, "uRgb"), c[0], c[1], c[2], c[3])
            GLES20.glUniform4f(GLES20.glGetUniformLocation(animProg, "uA"), m[0], m[1], m[2], m[3])
            drawQuad(animProg, floats(x0, y0, x1, y0, x0, y1, x1, y1), quadUv)
            GLES20.glDisable(GLES20.GL_BLEND)
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

    /**
     * The chat layer, redrawn only when what is visible changes: gift banners on top (newest lowest), the
     * comments under them (newest at the bottom), like TikTok.
     */
    private class Overlay(w: Int, h: Int, val lines: List<Line>, val gifts: List<Gift>) {
        companion object {
            // Portrait (most lives): a wide column low on the left. Landscape: a narrower, taller column.
            private fun portrait(w: Int, h: Int) = h >= w
            fun left(w: Int, h: Int) = (w * if (portrait(w, h)) 0.03f else 0.02f).toInt()
            fun bottom(w: Int, h: Int) = (h * if (portrait(w, h)) 0.07f else 0.05f).toInt()
            fun width(w: Int, h: Int) = (w * if (portrait(w, h)) 0.74f else 0.42f).toInt()
            private fun chatHeight(w: Int, h: Int) = (h * if (portrait(w, h)) 0.32f else 0.50f).toInt()
            fun giftHeight(w: Int, h: Int) = (h * if (portrait(w, h)) 0.14f else 0.28f).toInt()
            fun height(w: Int, h: Int) = chatHeight(w, h) + giftHeight(w, h)
        }

        val bitmap: Bitmap = Bitmap.createBitmap(width(w, h), height(w, h), Bitmap.Config.ARGB_8888)
        var empty = true; private set
        private val canvas = Canvas(bitmap)
        private val chatTop = giftHeight(w, h).toFloat()
        private val textSize = maxOf(w, h) / 46f
        private val padX = textSize * 0.55f
        private val padY = textSize * 0.30f
        private val gap = textSize * 0.32f
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = this@Overlay.textSize
            setShadowLayer(textSize * 0.12f, 0f, 0f, 0x99000000.toInt())
        }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val nickPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = textSize * 0.92f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(textSize * 0.1f, 0f, 0f, 0x88000000.toInt())
        }
        private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEEFFFFFF.toInt(); this.textSize = textSize * 0.80f
            setShadowLayer(textSize * 0.1f, 0f, 0f, 0x88000000.toInt())
        }
        private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFE14D.toInt(); this.textSize = textSize * 1.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            setShadowLayer(textSize * 0.15f, 0f, textSize * 0.05f, 0xAA000000.toInt())
        }
        private val giftPill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var shown = longArrayOf(-2, -2, -2, -2)

        /** Returns true when the bitmap was redrawn for time [ms]. */
        fun update(ms: Long): Boolean {
            val cTo = upperBound(lines.size) { lines[it].ms <= ms }
            var cFrom = cTo
            while (cFrom > 0 && cTo - cFrom < MAX_VISIBLE && ms - lines[cFrom - 1].ms < VISIBLE_MS) cFrom--
            val gTo = upperBound(gifts.size) { gifts[it].ms <= ms }
            var gFrom = gTo
            while (gFrom > 0 && gTo - gFrom < MAX_GIFTS && ms - gifts[gFrom - 1].ms < GIFT_MS) gFrom--
            val key = longArrayOf(cFrom.toLong(), cTo.toLong(), gFrom.toLong(), gTo.toLong())
            if (key.contentEquals(shown)) return false
            shown = key
            bitmap.eraseColor(Color.TRANSPARENT)
            empty = cFrom == cTo && gFrom == gTo
            if (!empty) drawGifts(gFrom, gTo, drawChat(cFrom, cTo))
            return true
        }

        /** First index in 0 until n for which [le] is false (items are sorted, [le] is "time <= now"). */
        private inline fun upperBound(n: Int, le: (Int) -> Boolean): Int {
            var lo = 0; var hi = n
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (le(mid)) lo = mid + 1 else hi = mid }
            return lo
        }

        /** Draws the comments from the bottom up; returns where the space above them starts. */
        private fun drawChat(from: Int, to: Int): Float {
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
                if (top < chatTop) break
                canvas.drawRoundRect(RectF(0f, top, lw + padX * 2, y), boxH / 2.4f, boxH / 2.4f, pill)
                canvas.save()
                canvas.translate(padX, top + padY)
                layout.draw(canvas)
                canvas.restore()
                y = top - gap
            }
            return y
        }

        /** TikTok-style gift banners: name / "sent X" on a pink pill, the gift's picture, then ×count. */
        private fun drawGifts(from: Int, to: Int, above: Float) {
            val bh = textSize * 2.55f
            val icon = bh * 0.86f
            var y = above  // right above the comments, like TikTok
            for (i in to - 1 downTo from) {
                val g = gifts[i]
                val top = y - bh
                if (top < 0) break
                val count = if (g.count > 1) "×${g.count}" else ""
                val countW = if (count.isEmpty()) 0f else countPaint.measureText(count)
                val maxText = bitmap.width - (padX * 3 + icon + countW + padX)
                val nick = TextUtils.ellipsize(g.nick.ifBlank { "—" }, nickPaint, maxText, TextUtils.TruncateAt.END).toString()
                val label = TextUtils.ellipsize(g.label, subPaint, maxText, TextUtils.TruncateAt.END).toString()
                val textW = maxOf(nickPaint.measureText(nick), subPaint.measureText(label))
                val width = min(padX + textW + padX * 0.7f + icon + (if (count.isEmpty()) 0f else padX * 0.5f + countW) + padX,
                    bitmap.width.toFloat())
                giftPill.shader = android.graphics.LinearGradient(0f, 0f, width, 0f,
                    0xE6FF2D72.toInt(), 0x8C7B2CFF.toInt(), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0f, top, width, top + bh), bh / 2, bh / 2, giftPill)
                canvas.drawText(nick, padX, top + bh * 0.44f, nickPaint)
                canvas.drawText(label, padX, top + bh * 0.80f, subPaint)
                val ix = padX + textW + padX * 0.7f
                val iconRect = RectF(ix, top + (bh - icon) / 2, ix + icon, top + (bh + icon) / 2)
                val bmp = g.icon
                if (bmp != null) canvas.drawBitmap(bmp, null, iconRect, iconPaint)
                else {
                    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = icon * 0.78f }
                    canvas.drawText("🎁", ix, iconRect.bottom - icon * 0.16f, p)
                }
                if (count.isNotEmpty()) canvas.drawText(count, ix + icon + padX * 0.5f, top + bh * 0.68f, countPaint)
                y = top - gap
            }
        }
    }
}

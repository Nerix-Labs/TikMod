package com.mts.tikmod.captions

import android.media.MediaCodec
import android.media.MediaFormat
import com.mts.tikmod.utils.FlvRemuxer
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import java.io.OutputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Pulls a LIVE FLV stream and turns its AAC audio into what a speech recognizer wants:
 * 16 kHz, mono, 16-bit PCM. Video tags are skipped without being decoded.
 *
 * The PCM goes to [sink], which can be swapped at any time (each recognition session reads from its own pipe).
 * While there is no sink the audio is dropped, so the feed always stays at the live edge.
 */
class AudioFeed(private val urls: () -> List<String>, private val onEnded: () -> Unit) {

    companion object {
        const val RATE = 16_000
    }

    @Volatile var sink: OutputStream? = null
    @Volatile private var running = true
    @Volatile private var conn: HttpURLConnection? = null
    /** Milliseconds of audio decoded so far (for logs and for noticing a dead stream). */
    @Volatile var decodedMs = 0L; private set

    private val worker = thread(name = "TikMod-CaptionAudio") { loop() }

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
        runCatching { sink?.close() }
        sink = null
        worker.interrupt()
    }

    private fun loop() {
        var failures = 0
        var index = 0
        var list = urls()
        while (running && failures < 6) {
            if (list.isEmpty()) { failures++; XLog.log("Captions: no stream address (#$failures)"); sleep(2000); list = urls(); continue }
            val before = decodedMs
            try {
                val c = TikModDownloader.open(list[index % list.size], readTimeoutMs = 15_000)
                conn = c
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                c.inputStream.use { input ->
                    val reader = FlvRemuxer.Reader(input)
                    reader.readHeader()
                    pump(reader)
                }
            } catch (t: Throwable) {
                if (running) XLog.log("Captions: audio reconnect (${t.message})")
            } finally {
                runCatching { conn?.disconnect() }
                conn = null
            }
            if (!running) break
            XLog.log("Captions: audio connection closed after ${decodedMs - before} ms of sound")
            if (decodedMs - before > 3000) failures = 0 else { failures++; index++ }
            if (failures % 2 == 1) list = urls()
            sleep(1000)
        }
        if (running) { running = false; onEnded() }
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    private fun pump(reader: FlvRemuxer.Reader) {
        var codec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var inRate = 44100
        var inChannels = 2
        val down = Downsampler()
        try {
            while (running) {
                val tag = reader.next() ?: break
                if (!tag.isAac || tag.body.size <= 2) continue
                if (tag.isConfig) {
                    if (codec != null) continue
                    val asc = tag.body.copyOfRange(2, tag.body.size)
                    val (rate, ch) = parseAsc(asc)
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(format, null, null, 0); start() }
                    inRate = rate; inChannels = ch
                    continue
                }
                val dec = codec ?: continue
                val inIdx = dec.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)!!
                    buf.clear(); buf.put(tag.body, 2, tag.body.size - 2)
                    dec.queueInputBuffer(inIdx, 0, tag.body.size - 2, tag.timestamp * 1000, 0)
                }
                while (true) {
                    val outIdx = dec.dequeueOutputBuffer(info, 0)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        continue
                    }
                    if (outIdx < 0) break
                    if (info.size > 0) {
                        val out = dec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val shorts = out.asShortBuffer()
                        val pcm = ShortArray(shorts.remaining()); shorts.get(pcm)
                        decodedMs += pcm.size / inChannels * 1000L / inRate
                        deliver(down.process(pcm, inChannels, inRate))
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun deliver(bytes: ByteArray) {
        val s = sink ?: return
        if (bytes.isEmpty()) return
        try {
            s.write(bytes)
        } catch (t: Throwable) {
            // The recognizer closed its end: this session is over, the next one brings a new pipe.
            if (sink === s) sink = null
            runCatching { s.close() }
        }
    }

    /** Sample rate and channel count from an AAC AudioSpecificConfig. */
    private fun parseAsc(asc: ByteArray): Pair<Int, Int> {
        val table = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val b0 = asc[0].toInt() and 0xff
        val b1 = asc[1].toInt() and 0xff
        val idx = ((b0 and 0x07) shl 1) or (b1 ushr 7)
        return table.getOrElse(idx) { 44100 } to ((b1 ushr 3) and 0x0f).coerceIn(1, 2)
    }

    /**
     * Interleaved PCM at any rate -> 16 kHz mono, by averaging the input samples that fall into each output sample.
     * Quiet streams are raised towards a normal speaking level (slow automatic gain, at most 4x): recognizers
     * are trained on speech at that level and miss words that are much quieter.
     */
    private class Downsampler {
        private var acc = 0L
        private var count = 0
        private var phase = 0.0
        private var envelope = 4000f
        private var gain = 1f

        fun process(pcm: ShortArray, channels: Int, rate: Int): ByteArray {
            val step = rate.toDouble() / RATE
            val frames = pcm.size / channels
            val out = ByteBuffer.allocate(((frames / step).toInt() + 2) * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < frames) {
                var mono = 0
                for (c in 0 until channels) mono += pcm[i * channels + c]
                acc += mono / channels
                count++
                phase += 1.0
                if (phase >= step) {
                    phase -= step
                    val v = (acc / count).toInt()
                    val level = kotlin.math.abs(v).toFloat()
                    envelope = if (level > envelope) level else envelope * 0.9997f // peak follower, ~0.2 s release
                    gain += ((9000f / envelope.coerceAtLeast(300f)).coerceIn(1f, 4f) - gain) * 0.002f
                    out.putShort((v * gain).toInt().coerceIn(-32768, 32767).toShort())
                    acc = 0; count = 0
                }
                i++
            }
            return out.array().copyOf(out.position())
        }
    }
}

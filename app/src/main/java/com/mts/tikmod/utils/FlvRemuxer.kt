package com.mts.tikmod.utils

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * FLV tools for TikTok LIVE recordings (pure JVM: no Android APIs, unit-testable).
 *
 * TikTok serves LIVE as FLV with H.265 using the non-standard codec id 12 (or Enhanced-RTMP 'hvc1'),
 * and with absolute stream timestamps (a 1-minute clip starts at e.g. 1:14:56). Raw dumps therefore
 * show a bogus 1h+ duration and play in almost no player.
 *
 * - [Writer] rebuilds a clean FLV from the network: whole tags only, clock starts at 0 on a key frame
 *   and stays continuous across reconnects.
 * - [toMp4] remuxes it (no re-encode) into a standards-compliant MP4 written by hand: the decoder
 *   config records are copied verbatim (hvcC / avcC / esds), samples keep their NAL length prefixes,
 *   and DTS + composition offsets come straight from FLV, so B-frames get exact stts/ctts tables.
 *   (Android's MediaMuxer rebuilds DTS from PTS and produces duplicate DTS with B-frames.)
 */
object FlvRemuxer {

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xff

    class Tag(val type: Int, var timestamp: Long, val body: ByteArray) {
        val isVideo get() = type == 9
        val isAudio get() = type == 8
        val isScript get() = type == 18
        private val b0 get() = if (body.isEmpty()) 0 else body.u8(0)
        val isEnhanced get() = isVideo && (b0 and 0x80) != 0
        val isAac get() = isAudio && (b0 ushr 4) == 10

        /** Decoder-config tags: AVC/HEVC sequence header, Enhanced-RTMP SequenceStart, AAC AudioSpecificConfig. */
        val isConfig: Boolean
            get() = when {
                isEnhanced -> (b0 and 0x0f) == 0
                isVideo && body.size > 1 -> body.u8(1) == 0
                isAac && body.size > 1 -> body.u8(1) == 0
                else -> false
            }

        val isKeyFrame: Boolean get() = isVideo && ((b0 ushr 4) and 0x07) == 1
    }

    /** Reads FLV tags from a stream (one HTTP connection or a file). */
    class Reader(input: InputStream) {
        private val din = DataInputStream(BufferedInputStream(input, 256 * 1024))
        var hasAudio = true; private set
        var hasVideo = true; private set

        fun readHeader() {
            val sig = ByteArray(3); din.readFully(sig)
            if (String(sig, Charsets.ISO_8859_1) != "FLV") throw IllegalStateException("not an FLV stream")
            din.readUnsignedByte() // version
            val flags = din.readUnsignedByte()
            hasAudio = flags and 0x04 != 0
            hasVideo = flags and 0x01 != 0
            val headerLen = din.readInt()
            if (headerLen > 9) din.skipBytes(headerLen - 9)
            din.readInt() // PreviousTagSize0
        }

        /** Returns the next complete tag, or null at end of stream (a truncated last tag is dropped). */
        fun next(): Tag? {
            return try {
                val type = din.readUnsignedByte() and 0x1f
                val size = (din.readUnsignedByte() shl 16) or (din.readUnsignedByte() shl 8) or din.readUnsignedByte()
                val tsLow = (din.readUnsignedByte() shl 16) or (din.readUnsignedByte() shl 8) or din.readUnsignedByte()
                val tsExt = din.readUnsignedByte()
                din.skipBytes(3) // stream id
                if (size > 32 * 1024 * 1024) throw IllegalStateException("corrupt tag size $size")
                val body = ByteArray(size)
                din.readFully(body)
                din.readInt() // PreviousTagSize
                Tag(type, ((tsExt.toLong() shl 24) or tsLow.toLong()) and 0xFFFFFFFFL, body)
            } catch (e: EOFException) {
                null
            }
        }
    }

    /**
     * Writes a clean FLV: only whole tags, timestamps starting at 0 on the first key frame, continuous
     * across reconnects ([startSegment]). Decoder-config tags are written once, right before media.
     */
    class Writer(private val out: OutputStream, private val hasAudio: Boolean, private val hasVideo: Boolean) {
        var durationMs = 0L; private set
        var bytes = 0L; private set
        private var segStart = -1L
        private var segOffset = 0L
        private var lastSrcTs = -1L
        private var waitingKey = hasVideo
        private var wroteScript = false
        private var wroteVideoCfg = false
        private var wroteAudioCfg = false
        private val pendingConfigs = ArrayList<Tag>()

        init {
            val flags = (if (hasAudio) 0x04 else 0) or (if (hasVideo) 0x01 else 0)
            out.write(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, flags.toByte(), 0, 0, 0, 9, 0, 0, 0, 0))
            bytes += 13
        }

        /** Call when a new connection starts: the new stream's clock is unrelated to the old one. */
        fun startSegment() {
            segStart = -1L
            lastSrcTs = -1L
            waitingKey = hasVideo
            if (durationMs > 0) segOffset = durationMs + 40
        }

        fun write(tag: Tag) {
            if (tag.isScript) {
                if (!wroteScript) { wroteScript = true; emit(tag, 0) }
                return
            }
            if (tag.isConfig) {
                val already = if (tag.isVideo) wroteVideoCfg else wroteAudioCfg
                if (!already) {
                    if (tag.isVideo) wroteVideoCfg = true else wroteAudioCfg = true
                    pendingConfigs += tag
                }
                return
            }
            if (!tag.isVideo && !tag.isAudio) return
            if (tag.isAudio && !tag.isAac) return

            // Each segment starts on a video key frame (earlier frames reference missing pictures).
            if (waitingKey) {
                if (!tag.isKeyFrame) return
                waitingKey = false
            }
            if (segStart < 0) segStart = tag.timestamp
            // Clock jump inside one connection (server-side restart): splice seamlessly.
            if (lastSrcTs >= 0 && (tag.timestamp < lastSrcTs - 1000 || tag.timestamp > lastSrcTs + 10_000)) {
                segOffset = durationMs + 40
                segStart = tag.timestamp
            }
            lastSrcTs = tag.timestamp
            val ts = (tag.timestamp - segStart + segOffset).coerceAtLeast(0)

            if (pendingConfigs.isNotEmpty()) {
                pendingConfigs.forEach { emit(it, ts) }
                pendingConfigs.clear()
            }
            emit(tag, ts)
            if (ts > durationMs) durationMs = ts
        }

        private fun emit(tag: Tag, ts: Long) {
            val size = tag.body.size
            val h = ByteArray(11)
            h[0] = tag.type.toByte()
            h[1] = (size ushr 16).toByte(); h[2] = (size ushr 8).toByte(); h[3] = size.toByte()
            h[4] = (ts ushr 16).toByte(); h[5] = (ts ushr 8).toByte(); h[6] = ts.toByte(); h[7] = (ts ushr 24).toByte()
            out.write(h)
            out.write(tag.body)
            val prev = 11 + size
            out.write(byteArrayOf((prev ushr 24).toByte(), (prev ushr 16).toByte(), (prev ushr 8).toByte(), prev.toByte()))
            bytes += prev + 4
        }
    }

    // ================================================================== FLV -> MP4

    data class Result(val file: File, val mime: String, val durationMs: Long, val hasVideo: Boolean)

    private enum class Codec(val sampleEntry: String, val configBox: String) {
        AVC("avc1", "avcC"), HEVC("hvc1", "hvcC")
    }

    /** Growable primitive lists for the sample tables (an hour of LIVE is ~200k samples). */
    private class Longs { var a = LongArray(4096); var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        operator fun get(i: Int) = a[i] }
    private class Ints { var a = IntArray(4096); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        operator fun get(i: Int) = a[i] }

    private class Track {
        val offsets = Longs()
        val sizes = Ints()
        val dts = Longs()       // ms (video) / ms (audio, converted later)
        val cts = Ints()        // ms composition offset (video)
        val keys = Ints()       // 1-based sample numbers
        val count get() = sizes.n
    }

    /**
     * Remuxes a clean FLV (from [Writer]) into MP4 without re-encoding.
     * H.264 (id 7 / 'avc1'), H.265 (id 12 / 'hvc1'), AAC. Audio-only streams become M4A.
     */
    fun toMp4(input: File, outDir: File): Result {
        // ---- pass 1: decoder configs + dimensions
        var codec: Codec? = null
        var videoConfig: ByteArray? = null
        var asc: ByteArray? = null
        var width = 0
        var height = 0
        input.inputStream().use { ins ->
            val r = Reader(ins); r.readHeader()
            while (true) {
                val t = r.next() ?: break
                if (t.isScript && width == 0) { width = amfNumber(t.body, "width"); height = amfNumber(t.body, "height") }
                if (t.isConfig && t.isVideo && videoConfig == null) {
                    val (c, payload) = videoConfigPayload(t.body)
                    if (c != null && payload.size > 6) { codec = c; videoConfig = payload }
                }
                if (t.isConfig && t.isAudio && asc == null && t.body.size > 3) asc = t.body.copyOfRange(2, t.body.size)
                if (videoConfig != null && asc != null && width > 0) break
            }
        }
        if (videoConfig == null && asc == null) throw IllegalStateException("no decodable tracks")
        if (videoConfig != null && (width <= 0 || height <= 0)) { width = 720; height = 1280 }

        val hasVideo = videoConfig != null
        val out = File(outDir, "tikmod_live_${System.nanoTime()}.${if (hasVideo) "mp4" else "m4a"}")
        val video = Track()
        val audio = Track()

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            // ftyp
            val ftyp = box("ftyp") {
                writeAscii("isom"); writeInt(512)
                listOf("isom", "iso2", "mp41", if (codec == Codec.HEVC) "hvc1" else "avc1").forEach { writeAscii(it) }
            }
            raf.write(ftyp)
            // mdat with 64-bit size, patched at the end
            val mdatStart = raf.filePointer
            raf.writeInt(1); raf.write("mdat".toByteArray()); raf.writeLong(0)
            val buffered = java.io.BufferedOutputStream(java.io.FileOutputStream(raf.fd), 512 * 1024)
            var pos = raf.filePointer

            // ---- pass 2: samples
            input.inputStream().use { ins ->
                val r = Reader(ins); r.readHeader()
                while (true) {
                    val t = r.next() ?: break
                    if (t.isConfig || t.isScript) continue
                    if (t.isVideo && hasVideo) {
                        val frame = videoFrame(t.body) ?: continue
                        val (start, cts) = frame
                        val len = t.body.size - start
                        if (len <= 0) continue
                        buffered.write(t.body, start, len)
                        video.offsets.add(pos); video.sizes.add(len)
                        video.dts.add(t.timestamp); video.cts.add(cts.toInt())
                        if (t.isKeyFrame) video.keys.add(video.count)
                        pos += len
                    } else if (t.isAac && asc != null && t.body.size > 2) {
                        val len = t.body.size - 2
                        buffered.write(t.body, 2, len)
                        audio.offsets.add(pos); audio.sizes.add(len); audio.dts.add(t.timestamp)
                        pos += len
                    }
                }
            }
            buffered.flush()
            raf.seek(pos)

            // patch mdat size
            val mdatSize = pos - mdatStart
            raf.seek(mdatStart + 8); raf.writeLong(mdatSize)
            raf.seek(pos)

            // ---- moov
            val sampleRate = asc?.let { aacSampleRate(it) } ?: 44100
            val channels = asc?.let { ((it.u8(1) ushr 3) and 0x0f).coerceIn(1, 8) } ?: 2
            val videoDurMs = if (video.count > 0) video.dts[video.count - 1] + lastDelta(video.dts, video.count) else 0L
            val audioDurMs = if (audio.count > 0) audio.dts[audio.count - 1] + 23 else 0L
            val movieDur = maxOf(videoDurMs, audioDurMs)

            val traks = ArrayList<ByteArray>()
            var nextId = 1
            if (hasVideo && video.count > 0) traks += videoTrak(nextId++, video, codec!!, videoConfig!!, width, height, videoDurMs)
            if (asc != null && audio.count > 0) traks += audioTrak(nextId++, audio, asc!!, sampleRate, channels)
            if (traks.isEmpty()) throw IllegalStateException("no samples")

            val moov = box("moov") {
                write(fullBox("mvhd", 0, 0) {
                    writeInt(0); writeInt(0); writeInt(1000); writeInt(movieDur.toInt())
                    writeInt(0x00010000); writeShort(0x0100); writeShort(0); writeInt(0); writeInt(0)
                    writeMatrix(); repeat(6) { writeInt(0) }; writeInt(nextId)
                })
                traks.forEach { write(it) }
            }
            raf.write(moov)
            raf.setLength(raf.filePointer)
            return Result(out, if (hasVideo) "video/mp4" else "audio/mp4", movieDur, hasVideo)
        }
    }

    private fun lastDelta(dts: Longs, n: Int): Long =
        if (n >= 2) (dts[n - 1] - dts[n - 2]).coerceIn(1, 1000) else 33

    private fun videoTrak(id: Int, t: Track, codec: Codec, config: ByteArray, w: Int, h: Int, durMs: Long): ByteArray {
        val n = t.count
        return box("trak") {
            write(fullBox("tkhd", 0, 3) {
                writeInt(0); writeInt(0); writeInt(id); writeInt(0); writeInt(durMs.toInt())
                writeInt(0); writeInt(0); writeShort(0); writeShort(0); writeShort(0); writeShort(0)
                writeMatrix(); writeInt(w shl 16); writeInt(h shl 16)
            })
            write(box("mdia") {
                write(fullBox("mdhd", 0, 0) { writeInt(0); writeInt(0); writeInt(1000); writeInt(durMs.toInt()); writeShort(0x55c4); writeShort(0) })
                write(hdlr("vide", "TikMod Video"))
                write(box("minf") {
                    write(fullBox("vmhd", 0, 1) { writeShort(0); repeat(3) { writeShort(0) } })
                    write(dinf())
                    write(box("stbl") {
                        write(fullBox("stsd", 0, 0) {
                            writeInt(1)
                            write(box(codec.sampleEntry) {
                                repeat(6) { writeByte(0) }; writeShort(1)            // reserved + data_reference_index
                                repeat(16) { writeByte(0) }                           // pre_defined / reserved
                                writeShort(w); writeShort(h)
                                writeInt(0x00480000); writeInt(0x00480000); writeInt(0); writeShort(1)
                                val name = "TikMod".toByteArray(); writeByte(name.size); write(name); repeat(31 - name.size) { writeByte(0) }
                                writeShort(0x0018); writeShort(-1)
                                write(box(codec.configBox) { write(config) })
                            })
                        })
                        // stts: decode-time deltas (run-length)
                        write(runLength("stts", n) { i -> if (i < n - 1) (t.dts[i + 1] - t.dts[i]).coerceAtLeast(0).toInt() else lastDelta(t.dts, n).toInt() })
                        // ctts v1 (signed): composition offsets — exact B-frame reordering
                        var anyCts = false
                        var negative = false
                        for (i in 0 until n) { if (t.cts[i] != 0) anyCts = true; if (t.cts[i] < 0) negative = true }
                        if (anyCts) write(runLength("ctts", n, version = if (negative) 1 else 0) { i -> t.cts[i] })
                        write(fullBox("stss", 0, 0) { writeInt(t.keys.n); for (i in 0 until t.keys.n) writeInt(t.keys[i]) })
                        writeSampleLocation(t)
                    })
                })
            })
        }
    }

    private fun audioTrak(id: Int, t: Track, asc: ByteArray, rate: Int, channels: Int): ByteArray {
        val n = t.count
        val firstMs = t.dts[0]
        // AAC frames are 1024 samples. Keep 1024 per frame, except across real gaps (reconnects).
        val deltas = IntArray(n) { i ->
            if (i < n - 1) {
                val gapMs = t.dts[i + 1] - t.dts[i]
                val frameMs = 1024_000.0 / rate
                if (gapMs > frameMs * 4) (gapMs * rate / 1000).toInt() else 1024
            } else 1024
        }
        val mediaDur = deltas.fold(0L) { a, d -> a + d }
        val durMs = mediaDur * 1000 / rate
        return box("trak") {
            write(fullBox("tkhd", 0, 3) {
                writeInt(0); writeInt(0); writeInt(id); writeInt(0); writeInt((durMs + firstMs).toInt())
                writeInt(0); writeInt(0); writeShort(0); writeShort(1); writeShort(0x0100); writeShort(0)
                writeMatrix(); writeInt(0); writeInt(0)
            })
            if (firstMs > 0) {
                // Audio starts later than video: an empty edit keeps A/V in sync.
                write(box("edts") {
                    write(fullBox("elst", 0, 0) {
                        writeInt(2)
                        writeInt(firstMs.toInt()); writeInt(-1); writeInt(0x00010000)
                        writeInt(durMs.toInt()); writeInt(0); writeInt(0x00010000)
                    })
                })
            }
            write(box("mdia") {
                write(fullBox("mdhd", 0, 0) { writeInt(0); writeInt(0); writeInt(rate); writeInt(mediaDur.toInt()); writeShort(0x55c4); writeShort(0) })
                write(hdlr("soun", "TikMod Audio"))
                write(box("minf") {
                    write(fullBox("smhd", 0, 0) { writeShort(0); writeShort(0) })
                    write(dinf())
                    write(box("stbl") {
                        write(fullBox("stsd", 0, 0) {
                            writeInt(1)
                            write(box("mp4a") {
                                repeat(6) { writeByte(0) }; writeShort(1)
                                writeInt(0); writeInt(0)
                                writeShort(channels); writeShort(16); writeShort(0); writeShort(0)
                                writeInt(rate shl 16)
                                write(fullBox("esds", 0, 0) { write(esDescriptor(asc)) })
                            })
                        })
                        write(runLength("stts", n) { i -> deltas[i] })
                        writeSampleLocation(t)
                    })
                })
            })
        }
    }

    /** stsc (1 sample per chunk) + stsz + co64. */
    private fun DataOutputStream.writeSampleLocation(t: Track) {
        val n = t.count
        write(fullBox("stsc", 0, 0) { writeInt(1); writeInt(1); writeInt(1); writeInt(1) })
        write(fullBox("stsz", 0, 0) { writeInt(0); writeInt(n); for (i in 0 until n) writeInt(t.sizes[i]) })
        write(fullBox("co64", 0, 0) { writeInt(n); for (i in 0 until n) writeLong(t.offsets[i]) })
    }

    private fun runLength(type: String, n: Int, version: Int = 0, value: (Int) -> Int): ByteArray {
        val counts = Ints(); val values = Ints()
        for (i in 0 until n) {
            val v = value(i)
            if (values.n > 0 && values[values.n - 1] == v) counts.a[counts.n - 1]++
            else { counts.add(1); values.add(v) }
        }
        return fullBox(type, version, 0) { writeInt(counts.n); for (i in 0 until counts.n) { writeInt(counts[i]); writeInt(values[i]) } }
    }

    private fun esDescriptor(asc: ByteArray): ByteArray {
        fun desc(tag: Int, body: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(tag)
            // 4-byte expandable length (widely compatible form)
            val len = body.size
            o.write(0x80 or ((len ushr 21) and 0x7f)); o.write(0x80 or ((len ushr 14) and 0x7f))
            o.write(0x80 or ((len ushr 7) and 0x7f)); o.write(len and 0x7f)
            o.write(body)
            return o.toByteArray()
        }
        val dsi = desc(0x05, asc)
        val dcd = desc(0x04, ByteArrayOutputStream().apply {
            write(0x40); write(0x15); write(0); write(0x18); write(0)  // AAC, audio stream, buffer 6144
            write(byteArrayOf(0, 0x01, 0xF4.toByte(), 0)); write(byteArrayOf(0, 0x01, 0xF4.toByte(), 0)) // max/avg bitrate 128k
            write(dsi)
        }.toByteArray())
        val sl = desc(0x06, byteArrayOf(0x02))
        return desc(0x03, ByteArrayOutputStream().apply { write(0); write(0); write(0); write(dcd); write(sl) }.toByteArray())
    }

    private fun aacSampleRate(asc: ByteArray): Int {
        val table = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val idx = ((asc.u8(0) and 0x07) shl 1) or (asc.u8(1) ushr 7)
        return table.getOrElse(idx) { 44100 }
    }

    private fun hdlr(type: String, name: String) = fullBox("hdlr", 0, 0) {
        writeInt(0); writeAscii(type); writeInt(0); writeInt(0); writeInt(0); write(name.toByteArray()); writeByte(0)
    }

    private fun dinf() = box("dinf") { write(fullBox("dref", 0, 0) { writeInt(1); write(fullBox("url ", 0, 1) {}) }) }

    private fun DataOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    private fun DataOutputStream.writeMatrix() =
        intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { writeInt(it) }

    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val content = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
        return ByteArrayOutputStream(content.size + 8).also {
            DataOutputStream(it).apply { writeInt(content.size + 8); writeAscii(type); write(content) }
        }.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: DataOutputStream.() -> Unit) = box(type) {
        writeInt((version shl 24) or (flags and 0xffffff)); body()
    }

    /** Codec + raw decoder configuration record (avcC/hvcC payload) of a video sequence header. */
    private fun videoConfigPayload(body: ByteArray): Pair<Codec?, ByteArray> {
        if (body.size < 6) return null to ByteArray(0)
        val b0 = body.u8(0)
        return if (b0 and 0x80 != 0) codecOf(String(body, 1, 4, Charsets.ISO_8859_1)) to body.copyOfRange(5, body.size)
        else codecOf(b0 and 0x0f) to body.copyOfRange(5, body.size)
    }

    /** For a coded video frame: start offset of the length-prefixed NAL data + composition offset (ms). */
    private fun videoFrame(body: ByteArray): Pair<Int, Long>? {
        if (body.size < 5) return null
        val b0 = body.u8(0)
        if (b0 and 0x80 != 0) { // Enhanced RTMP
            return when (b0 and 0x0f) {
                1 -> if (body.size > 8) 8 to signed24(body, 5) else null
                3 -> 5 to 0L
                else -> null
            }
        }
        if (body.u8(1) != 1) return null // 0 = config, 2 = end of sequence
        return 5 to signed24(body, 2)
    }

    private fun codecOf(id: Int) = when (id) { 7 -> Codec.AVC; 12 -> Codec.HEVC; else -> null }
    private fun codecOf(fourcc: String) = when (fourcc) { "avc1" -> Codec.AVC; "hvc1", "hev1" -> Codec.HEVC; else -> null }

    private fun signed24(b: ByteArray, o: Int): Long {
        val v = (b.u8(o) shl 16) or (b.u8(o + 1) shl 8) or b.u8(o + 2)
        return (if (v and 0x800000 != 0) v or 0xFF000000.toInt() else v).toLong()
    }

    /** Reads a numeric property (e.g. "width") from an onMetaData AMF0 body. */
    private fun amfNumber(body: ByteArray, key: String): Int {
        val k = key.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 2 until body.size - k.size - 9) {
            if (body.u8(i - 2) != 0 || body.u8(i - 1) != k.size) continue
            for (j in k.indices) if (body[i + j] != k[j]) continue@outer
            if (body.u8(i + k.size) != 0) continue
            var bits = 0L
            for (j in 0 until 8) bits = (bits shl 8) or body.u8(i + k.size + 1 + j).toLong()
            return java.lang.Double.longBitsToDouble(bits).toInt()
        }
        return 0
    }
}

package com.mts.tikmod

import com.mts.tikmod.utils.FlvRemuxer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs a real TikTok LIVE FLV dump through the recorder pipeline (Reader -> Writer -> toMp4).
 * Provide the input with env TIKMOD_FLV and the output folder with TIKMOD_OUT; skipped otherwise.
 */
class FlvRemuxerTest {
    @Test
    fun remuxRealLiveDump() {
        val inPath = System.getenv("TIKMOD_FLV")
        assumeTrue("TIKMOD_FLV not set", !inPath.isNullOrBlank())
        val outDir = File(System.getenv("TIKMOD_OUT") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }

        // 1. Simulate the network recorder, including a reconnect halfway through.
        val clean = File(outDir, "clean.flv")
        val tags = File(inPath).inputStream().use { ins ->
            val r = FlvRemuxer.Reader(ins); r.readHeader()
            generateSequence { r.next() }.toList()
        }
        clean.outputStream().buffered().use { out ->
            val w = FlvRemuxer.Writer(out, hasAudio = true, hasVideo = true)
            w.startSegment()
            val half = tags.size / 2
            tags.take(half).forEach { w.write(it) }
            w.startSegment() // reconnect: new connection re-sends configs, clock unrelated
            tags.filter { it.isConfig || it.isScript }.forEach { w.write(it) }
            tags.drop(half).forEach { w.write(FlvRemuxer.Tag(it.type, it.timestamp + 900_000, it.body)) }
            println("clean FLV duration=${w.durationMs}ms bytes=${w.bytes}")
            assertTrue(w.durationMs in 1000..600_000)
        }

        // 2. Remux to MP4.
        val res = FlvRemuxer.toMp4(clean, outDir)
        val target = File(outDir, "out.mp4"); target.delete(); res.file.renameTo(target)
        println("mp4=${target.absolutePath} duration=${res.durationMs}ms size=${target.length()} video=${res.hasVideo}")
        assertTrue(target.length() > 100_000)
    }

    /** A recording that is cut off mid-tag (process killed) and then continued into the same file. */
    @Test
    fun resumeInterruptedRecording() {
        val inPath = System.getenv("TIKMOD_FLV")
        assumeTrue("TIKMOD_FLV not set", !inPath.isNullOrBlank())
        val outDir = File(System.getenv("TIKMOD_OUT") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }
        val tags = File(inPath).inputStream().use { ins ->
            val r = FlvRemuxer.Reader(ins); r.readHeader()
            generateSequence { r.next() }.toList()
        }
        val half = tags.size / 2
        val file = File(outDir, "resume.flv")

        // 1. First run: half the stream, then the process "dies" in the middle of writing a tag.
        var firstMs = 0L
        file.outputStream().buffered().use { out ->
            val w = FlvRemuxer.Writer(out, hasAudio = true, hasVideo = true)
            w.startSegment()
            tags.take(half).forEach { w.write(it) }
            firstMs = w.durationMs
        }
        java.io.FileOutputStream(file, true).use { it.write(byteArrayOf(9, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3)) } // torn tag

        // 2. Second run: repair, then append a new connection (configs re-sent, unrelated clock).
        val existing = FlvRemuxer.prepareForAppend(file)!!
        println("existing duration=${existing.durationMs}ms bytes=${existing.bytes} (first run ${firstMs}ms)")
        assertTrue(kotlin.math.abs(existing.durationMs - firstMs) < 200)
        var totalMs = 0L
        java.io.FileOutputStream(file, true).buffered().use { out ->
            val w = FlvRemuxer.Writer(out, existing.hasAudio, existing.hasVideo, existing)
            w.startSegment()
            tags.filter { it.isConfig || it.isScript }.forEach { w.write(it) }
            tags.drop(half).forEach { w.write(FlvRemuxer.Tag(it.type, it.timestamp + 5_000_000, it.body)) }
            totalMs = w.durationMs
        }
        println("after resume duration=${totalMs}ms")
        assertTrue(totalMs > firstMs + 1000)

        // 3. The combined file is one valid recording.
        val res = FlvRemuxer.toMp4(file, outDir)
        val target = File(outDir, "resume.mp4"); target.delete(); res.file.renameTo(target)
        println("mp4 duration=${res.durationMs}ms size=${target.length()}")
        assertTrue(kotlin.math.abs(res.durationMs - totalMs) < 1500)
    }
}

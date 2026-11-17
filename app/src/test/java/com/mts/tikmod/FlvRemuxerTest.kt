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
}

package com.mts.tikmod

import com.mts.tikmod.captions.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmenterTest {

    private val finals = ArrayList<String>()
    private var live = ""
    private val seg = Segmenter { text, final -> if (final) { finals += text; live = "" } else live = text }

    @Test
    fun sentencesAreEmittedOnce() {
        seg.partial("Well, that's exactly what a group")
        seg.partial("Well, that's exactly what a group of familiar faces are preparing to do next week. And it's")
        assertEquals(listOf("Well, that's exactly what a group of familiar faces are preparing to do next week."), finals)
        assertEquals("And it's", live)
    }

    @Test
    fun changedPunctuationDoesNotRepeatSentences() {
        seg.partial("Well, that's exactly what a group of familiar faces are preparing to do next week. And it's all")
        // The recognizer goes back and adds a comma inside the sentence that was already shown.
        seg.partial("Well, that's exactly what a group of familiar faces, are preparing to do next week. And it's all for a good cause")
        assertEquals(1, finals.size)
        assertEquals("And it's all for a good cause", live)
    }

    @Test
    fun longRunWithoutPunctuationIsCutAtAWord() {
        val words = (1..40).joinToString(" ") { "word$it" }
        seg.partial(words)
        assert(finals.isNotEmpty() && finals.all { it.length <= 95 }) { finals.toString() }
        // Nothing is lost or repeated, and no word is cut in two.
        assertEquals(words, (finals + live).joinToString(" "))
    }

    @Test
    fun pauseFinishesTheLineAndTheRestContinues() {
        seg.partial("I don't know him")
        seg.flush()
        seg.partial("I don't know him but he seems nice")
        assertEquals(listOf("I don't know him"), finals)
        assertEquals("but he seems nice", live)
    }

    @Test
    fun endOfUtteranceEmitsTheTailAndStartsOver() {
        seg.partial("This is the first sentence of it. And the tail")
        seg.finish("This is the first sentence of it. And the tail here")
        seg.partial("New one")
        assertEquals(listOf("This is the first sentence of it.", "And the tail here"), finals)
        assertEquals("New one", live)
    }
}

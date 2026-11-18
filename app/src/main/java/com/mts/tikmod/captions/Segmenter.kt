package com.mts.tikmod.captions

/**
 * Cuts a recognizer's ever-growing text into caption lines (pure JVM, unit-testable).
 *
 * A speech recognizer reports the whole utterance again and again while it grows ("which", "which will",
 * "which will then"…), sometimes for minutes without a break. Translating and showing all of it makes the
 * caption jump around. Here every finished sentence (or a long enough run of words) is emitted once as a
 * final line, and only the words after it stay "live".
 *
 * The recognizer also goes back and changes punctuation and capitals of what it already reported, so the
 * place where the emitted part ends is remembered as a count of letters, which those changes don't move.
 *
 * @param emit (text, final): final lines never change again; a live line replaces the previous live line.
 */
class Segmenter(private val emit: (String, Boolean) -> Unit) {

    private companion object {
        const val MAX = 120        // a line without punctuation is cut before it gets longer than this…
        const val SOFT = 95        // …at the last word that ends before this
        const val MIN_SENTENCE = 15
        const val ENDS = ".!?…"
        const val WIDE_ENDS = "。！？"
    }

    private var doneLetters = 0  // letters and digits of the current text that already went out as final lines
    private var last = ""        // the current text as last reported
    var live = ""; private set

    /** The utterance so far. */
    fun partial(text: String) = advance(text, end = false)

    /** The recognizer closed the utterance with this text; the next one starts from nothing. */
    fun finish(text: String) {
        advance(text, end = true)
        doneLetters = 0; last = ""; live = ""
    }

    /** The speaker paused: what is live becomes a final line. */
    fun flush() {
        if (live.isBlank()) return
        emit(live, true)
        doneLetters = letters(last)
        live = ""
    }

    private fun letters(s: String) = s.count { it.isLetterOrDigit() }

    /** Where the not-yet-emitted part of [text] starts. */
    private fun restStart(text: String): Int {
        if (doneLetters == 0) return 0
        var seen = 0
        var i = 0
        while (i < text.length && seen < doneLetters) { if (text[i].isLetterOrDigit()) seen++; i++ }
        if (seen < doneLetters) {
            // Shorter than what was emitted: a new utterance began without the old one being closed.
            if (seen < doneLetters / 2) { doneLetters = 0; return 0 }
            return text.length
        }
        // Words were changed and the count landed inside a word: that word was already shown.
        while (i < text.length && text[i].isLetterOrDigit()) i++
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        return i
    }

    private fun advance(text: String, end: Boolean) {
        last = text
        var rest = text.substring(restStart(text))
        while (true) {
            val trimmed = rest.trimStart()
            val cut = sentenceEnd(trimmed) ?: (if (trimmed.length > MAX) trimmed.lastIndexOf(' ', SOFT).takeIf { it > 30 } else null) ?: break
            emit(trimmed.substring(0, cut).trim(), true)
            val consumed = rest.length - trimmed.length + cut
            doneLetters += letters(rest.substring(0, consumed))
            rest = rest.substring(consumed)
        }
        val tail = rest.trim()
        if (end) { if (tail.any { it.isLetterOrDigit() }) emit(tail, true) }
        else { live = tail; if (tail.isNotEmpty()) emit(tail, false) }
    }

    /** Index right after the first sentence end that is followed by more text, or null. */
    private fun sentenceEnd(s: String): Int? {
        for (i in MIN_SENTENCE until s.length) {
            val c = s[i]
            if (c in WIDE_ENDS) return i + 1
            if (c in ENDS && i + 1 < s.length && s[i + 1] == ' ') return i + 1
        }
        return null
    }
}

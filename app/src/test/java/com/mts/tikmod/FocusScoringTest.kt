package com.mts.tikmod

import com.mts.tikmod.focus.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake TikTok objects: [com.mts.tikmod.utils.Reflect] reads them through their getters, like the real Aweme. */
class FocusScoringTest {
    class Stats(val diggCount: Long = 0, val playCount: Long = 0)
    class Video(val duration: Long)
    class Author(val uniqueId: String = "someone", val followStatus: Int = 0)
    class Post(
        val author: Author = Author(),
        val statistics: Stats = Stats(),
        val video: Video = Video(60_000),
        val desc: String = ""
    )

    @Test
    fun plainPostScoresTheBase() = assertEquals(15, Focus.attraction(Post()))

    @Test
    fun followedCreatorAddsEighteen() = assertEquals(33, Focus.attraction(Post(author = Author(followStatus = 1))))

    @Test
    fun shortClipsScoreHigher() {
        assertEquals(25, Focus.attraction(Post(video = Video(10_000))))
        assertEquals(20, Focus.attraction(Post(video = Video(25_000))))
    }

    @Test
    fun likesCountLogarithmicallyAndAreCapped() {
        assertEquals(15, Focus.attraction(Post(statistics = Stats(diggCount = 1_000))))       // log10 = 3 -> +0
        assertEquals(36, Focus.attraction(Post(statistics = Stats(diggCount = 1_000_000))))   // +21
        assertEquals(39, Focus.attraction(Post(statistics = Stats(diggCount = 5_000_000_000)))) // capped at +24.5
    }

    @Test
    fun scoreStaysWithinRange() {
        val top = Post(Author(followStatus = 2), Stats(diggCount = 9_000_000_000), Video(5_000))
        assertTrue(Focus.attraction(top) in 0..100)
    }

    @Test
    fun viralThresholds() {
        assertTrue(Focus.isViral(Post(statistics = Stats(diggCount = 300_000))))
        assertTrue(Focus.isViral(Post(statistics = Stats(playCount = 3_000_000))))
        assertFalse(Focus.isViral(Post(statistics = Stats(diggCount = 299_999, playCount = 2_999_999))))
    }

    @Test
    fun durationIsInSeconds() = assertEquals(42L, Focus.durationSec(Post(video = Video(42_500))))

    @Test
    fun hebrewPrefixesAreStripped() {
        val t = Focus.tokens("ולימוד המדע")
        assertTrue("ולימוד" in t)
        assertTrue("לימוד" in t)
        assertTrue("מדע" in t)
    }

    @Test
    fun topicMatching() {
        val text = "learning physics every day"
        val tokens = Focus.tokens(text)
        assertTrue(Focus.matches(text, tokens, "every day"))  // phrase
        assertTrue(Focus.matches(text, tokens, "physi"))      // 5+ letters: prefix
        assertFalse(Focus.matches(text, tokens, "phy"))       // short words: whole word only
        assertTrue(Focus.matches(text, tokens, "day"))
    }
}

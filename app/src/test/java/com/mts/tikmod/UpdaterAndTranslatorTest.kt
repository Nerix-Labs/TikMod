package com.mts.tikmod

import com.mts.tikmod.captions.Translator
import com.mts.tikmod.updates.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterAndTranslatorTest {

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updater.isNewer("4.8.0", "4.7.0"))
        assertTrue(Updater.isNewer("4.10.0", "4.9.9"))
        assertTrue(Updater.isNewer("v4.7.1", "4.7"))
        assertFalse(Updater.isNewer("4.7.0", "4.7.0"))
        assertFalse(Updater.isNewer("4.7", "4.7.0"))
        assertFalse(Updater.isNewer("4.6.9", "4.7.0"))
    }

    @Test
    fun googleLanguageCodes() {
        assertEquals("iw", Translator.code("he-IL"))
        assertEquals("iw", Translator.code("he"))
        assertEquals("zh-TW", Translator.code("cmn-Hant-TW"))
        assertEquals("zh-CN", Translator.code("cmn-Hans-CN"))
        assertEquals("en", Translator.code("en-US"))
    }
}

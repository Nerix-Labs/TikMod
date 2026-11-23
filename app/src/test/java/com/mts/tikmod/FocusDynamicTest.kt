package com.mts.tikmod

import com.mts.tikmod.focus.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Dynamic mode with the defaults (10 free minutes, full after 30 more). */
class FocusDynamicTest {
    private fun at(hour: Int) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0) }

    @Test
    fun freeAtFirstThenRisesToFull() {
        val day = at(15)
        assertEquals(0f, Focus.dynamicStrength(null, 0, day), 0.001f)
        assertEquals(0f, Focus.dynamicStrength(null, 9, day), 0.001f)
        val start = Focus.dynamicStrength(null, 10, day)
        val mid = Focus.dynamicStrength(null, 25, day)
        assertTrue("kicks in noticeably", start >= 0.15f)
        assertTrue("grows", mid > start)
        assertEquals(1f, Focus.dynamicStrength(null, 40, day), 0.001f)
        assertEquals(1f, Focus.dynamicStrength(null, 120, day), 0.001f)
    }

    @Test
    fun twiceAsFastLateAtNight() {
        val night = at(1)
        assertTrue(Focus.dynamicStrength(null, 5, night) > 0f)   // 5 free minutes at night instead of 10
        assertEquals(1f, Focus.dynamicStrength(null, 20, night), 0.001f)
        assertEquals(0f, Focus.dynamicStrength(null, 5, at(15)), 0.001f)
    }
}

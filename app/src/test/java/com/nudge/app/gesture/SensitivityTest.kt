package com.nudge.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTest {

    @Test
    fun `宽松档参数符合 spec`() {
        val p = Sensitivity.LOOSE.params
        assertEquals(500L, p.doubleTapWindowMs)
        assertEquals(150L, p.multiTouchSlopMs)
        assertEquals(350L, p.longPressMs)
        assertEquals(150L, p.holdBaseReadyMs)
        assertEquals(40f, p.moveToleranceDp, 0.01f)
    }

    @Test
    fun `标准档参数符合 spec`() {
        val p = Sensitivity.STANDARD.params
        assertEquals(350L, p.doubleTapWindowMs)
        assertEquals(100L, p.multiTouchSlopMs)
        assertEquals(500L, p.longPressMs)
        assertEquals(120L, p.holdBaseReadyMs)
        assertEquals(24f, p.moveToleranceDp, 0.01f)
    }

    @Test
    fun `严格档参数符合 spec`() {
        val p = Sensitivity.STRICT.params
        assertEquals(250L, p.doubleTapWindowMs)
        assertEquals(60L, p.multiTouchSlopMs)
        assertEquals(700L, p.longPressMs)
        assertEquals(180L, p.holdBaseReadyMs)
        assertEquals(12f, p.moveToleranceDp, 0.01f)
    }

    @Test
    fun `档位越严格 容差越小 长按越久`() {
        assertTrue(Sensitivity.LOOSE.params.doubleTapWindowMs > Sensitivity.STRICT.params.doubleTapWindowMs)
        assertTrue(Sensitivity.LOOSE.params.moveToleranceDp > Sensitivity.STRICT.params.moveToleranceDp)
        assertTrue(Sensitivity.LOOSE.params.longPressMs < Sensitivity.STRICT.params.longPressMs)
    }

    @Test
    fun `每档 holdBaseReadyMs 都不小于 multiTouchSlopMs`() {
        for (s in Sensitivity.values()) {
            assertTrue(
                "${s.displayName}: holdBaseReadyMs=${s.params.holdBaseReadyMs} " +
                    "must be >= multiTouchSlopMs=${s.params.multiTouchSlopMs}",
                s.params.holdBaseReadyMs >= s.params.multiTouchSlopMs
            )
        }
    }
}

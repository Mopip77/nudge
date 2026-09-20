package com.nudge.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手势识别测试。
 *
 * 辅助函数用「抬起手指数」的语义构造事件序列，避免手工计算 activePointerCount。
 * density 固定为 1f，因此 dp 与 px 数值相等，移动容差可直接用像素表达。
 */
class GestureRecognizerTest {

    private fun recognizer(s: Sensitivity = Sensitivity.STANDARD) =
        GestureRecognizer(s.params, densityDpi = 1f)

    /** 构造 n 指同时按下再同时抬起的一次「轻点」，返回识别结果（取最后一个非 null）。 */
    private fun GestureRecognizer.tap(
        fingers: Int,
        startTime: Long,
        downGapMs: Long = 10L,
        holdMs: Long = 50L,
        x: Float = 100f,
        y: Float = 100f,
    ): Gesture? {
        var result: Gesture? = null
        for (i in 0 until fingers) {
            val r = onTouchEvent(
                TouchEvent(TouchEventType.DOWN, i, x + i * 50, y, startTime + i * downGapMs, i + 1)
            )
            if (r != null) result = r
        }
        val upStart = startTime + fingers * downGapMs + holdMs
        for (i in 0 until fingers) {
            val r = onTouchEvent(
                TouchEvent(TouchEventType.UP, i, x + i * 50, y, upStart + i * downGapMs, fingers - i - 1)
            )
            if (r != null) result = r
        }
        return result
    }

    @Test
    fun `单指双击在窗口内触发`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
        assertEquals(Gesture.DOUBLE_TAP, r.tap(1, startTime = 200))
    }

    @Test
    fun `单指双击超时不触发`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
        // 标准档 doubleTapWindow=350ms，第二次点击在 500ms 后开始
        assertNull(r.tap(1, startTime = 600))
    }

    @Test
    fun `两指双击触发`() {
        val r = recognizer()
        assertNull(r.tap(2, startTime = 0))
        assertEquals(Gesture.TWO_FINGER_DOUBLE_TAP, r.tap(2, startTime = 200))
    }

    @Test
    fun `三指双击触发`() {
        val r = recognizer()
        assertNull(r.tap(3, startTime = 0))
        assertEquals(Gesture.THREE_FINGER_DOUBLE_TAP, r.tap(3, startTime = 250))
    }

    @Test
    fun `手指数不一致的两次点击不触发`() {
        val r = recognizer()
        assertNull(r.tap(2, startTime = 0))
        assertNull(r.tap(3, startTime = 200))
    }

    @Test
    fun `多指按下间隔超过同时性窗口则不算同时按下`() {
        val r = recognizer()
        // 标准档 multiTouchSlop=100ms，两指间隔 200ms
        assertNull(r.tap(2, startTime = 0, downGapMs = 200))
        assertNull(r.tap(2, startTime = 600, downGapMs = 200))
    }

    @Test
    fun `按下后移动超过容差则取消手势`() {
        val r = recognizer()
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1)))
        // 标准档 moveTolerance=24dp，density=1 故为 24px；移动 100px 远超容差
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.MOVE, 0, 200f, 100f, 20, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 200f, 100f, 40, 0)))
        // 第二次点击不应与被取消的第一次组成双击
        assertNull(r.tap(1, startTime = 100))
    }

    @Test
    fun `容差内的轻微抖动不取消手势`() {
        val r = recognizer()
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.MOVE, 0, 110f, 100f, 20, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 110f, 100f, 40, 0)))
        assertEquals(Gesture.DOUBLE_TAP, r.tap(1, startTime = 150))
    }

    @Test
    fun `单次点击不触发任何手势`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
    }

    @Test
    fun `CANCEL 事件重置状态`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.CANCEL, 0, 100f, 100f, 100, 0)))
        assertNull(r.tap(1, startTime = 200))
    }

    @Test
    fun `宽松档允许更长的双击间隔`() {
        val r = recognizer(Sensitivity.LOOSE)
        assertNull(r.tap(1, startTime = 0))
        // gap = 450-60 = 390ms，在宽松档(500ms)内，在标准档(350ms)外
        assertEquals(Gesture.DOUBLE_TAP, r.tap(1, startTime = 450))
    }

    @Test
    fun `严格档拒绝标准档能接受的间隔`() {
        val r = recognizer(Sensitivity.STRICT)
        assertNull(r.tap(1, startTime = 0))
        // 第一次tap在60ms抬起，gap = 400-60 = 340ms，在标准档(350ms)内、严格档(250ms)外
        assertNull(r.tap(1, startTime = 400))
    }

    @Test
    fun `长按单指后抬起不算双击的第一次点击`() {
        val r = recognizer()
        // 按住 800ms 远超长按阈值，不应计入双击序列
        assertNull(r.tap(1, startTime = 0, holdMs = 800))
        assertNull(r.tap(1, startTime = 1000))
    }
}

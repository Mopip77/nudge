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

    /** 构造「N 指按住 + 第 N+1 指单击」序列。 */
    private fun GestureRecognizer.holdAndTap(
        holdFingers: Int,
        holdStartMs: Long,
        tapAtMs: Long,
        tapDurationMs: Long = 50L,
    ): Gesture? {
        var result: Gesture? = null
        for (i in 0 until holdFingers) {
            onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, holdStartMs + i * 10, i + 1))
        }
        val tapId = holdFingers
        onTouchEvent(
            TouchEvent(TouchEventType.DOWN, tapId, 400f, 300f, tapAtMs, holdFingers + 1)
        )
        val r = onTouchEvent(
            TouchEvent(TouchEventType.UP, tapId, 400f, 300f, tapAtMs + tapDurationMs, holdFingers)
        )
        if (r != null) result = r
        return result
    }

    @Test
    fun `两指长按加一指单击触发`() {
        val r = recognizer()
        // 标准档 longPressMs=500，第三指在 600ms 时点击
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.holdAndTap(holdFingers = 2, holdStartMs = 0, tapAtMs = 600)
        )
    }

    @Test
    fun `三指长按加一指单击触发`() {
        val r = recognizer()
        assertEquals(
            Gesture.THREE_FINGER_HOLD_TAP,
            r.holdAndTap(holdFingers = 3, holdStartMs = 0, tapAtMs = 700)
        )
    }

    @Test
    fun `底座刚按下就立刻点击未达就位阈值则不触发`() {
        val r = recognizer()
        // 标准档 holdBaseReadyMs=120ms，底座 0ms 按下，第三指在 50ms 点击，未就位
        assertNull(r.holdAndTap(holdFingers = 2, holdStartMs = 0, tapAtMs = 50))
    }

    @Test
    fun `长按期间可连续单击多次触发`() {
        val r = recognizer()
        for (i in 0 until 2) {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, i * 10L, i + 1))
        }
        // 第一次单击
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2))
        )
        // 第二次单击（间隔超过 300ms 冷却期）
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 1000, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 1050, 2))
        )
    }

    @Test
    fun `冷却期内的重复单击被抑制`() {
        val r = recognizer()
        for (i in 0 until 2) {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, i * 10L, i + 1))
        }
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2))
        )
        // 紧接着再点（距上次触发仅 100ms，小于 300ms 冷却期）
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 700, 3))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 750, 2)))
    }

    /**
     * 冷却期跨批次生效是有意设计，不是疏漏。
     *
     * 全部手指离屏后重新放上再点，仍受 300ms 冷却期约束——因为冷却期防的就是
     * 连击误触发，而「快速抬手重来」同样属于连击场景，300ms 内触发两次动作
     * 大概率是抖动而非本意。
     */
    @Test
    fun `冷却期跨批次生效_全部抬起重来仍受抑制`() {
        val r = recognizer()
        // 第一次：两指底座 + 单击，正常触发
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 1, 150f, 100f, 10, 2))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 200, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 250, 2))
        )
        // 全部手指抬起，本批结束
        r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 100f, 100f, 260, 1))
        r.onTouchEvent(TouchEvent(TouchEventType.UP, 1, 150f, 100f, 270, 0))

        // 重新放上两指再点，但距上次触发仅 200ms（450-250），仍在冷却期内
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 280, 1))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 1, 150f, 100f, 290, 2))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 420, 3))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 450, 2)))

        // 超过冷却期后（距上次触发 600-250=350ms > 300ms）恢复正常
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 550, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 600, 2))
        )
    }

    @Test
    fun `长按的手指移动超容差则单击不触发`() {
        val r = recognizer()
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 1, 150f, 100f, 10, 2))
        // 长按手指滑动 100px，超过 24px 容差
        r.onTouchEvent(TouchEvent(TouchEventType.MOVE, 0, 300f, 100f, 300, 2))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2)))
    }

    @Test
    fun `四指长按加一指单击不触发任何手势`() {
        val r = recognizer()
        assertNull(r.holdAndTap(holdFingers = 4, holdStartMs = 0, tapAtMs = 700))
    }

    @Test
    fun `过早的单击尝试不应污染后续合法单击`() {
        val r = recognizer()
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 1, 150f, 100f, 5, 2))
        // 第三指点早了，底座才按 110ms 未达 120ms 就位阈值，这次不应触发
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 110, 3))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 115, 2)))
        // 手指未全部松开，再点一次。此时底座已充分就绪，应正常触发
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 300, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 350, 2))
        )
    }

    @Test
    fun `长按加单击后所有手指抬起不产生双击误判`() {
        val r = recognizer()
        for (i in 0 until 2) {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, i * 10L, i + 1))
        }
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2))
        // 长按的两指抬起，不应再产生手势
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 100f, 100f, 700, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 1, 150f, 100f, 710, 0)))
    }

    @Test
    fun `底座刚就位即可单击触发`() {
        val r = recognizer()
        // 标准档 holdBaseReadyMs=120ms，底座 0/10ms 按下，第三指在刚过 120ms 就位后点击
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.holdAndTap(holdFingers = 2, holdStartMs = 0, tapAtMs = 130)
        )
    }

    @Test
    fun `三指同时双击不被误判为底座加单击`() {
        val r = recognizer()
        // 标准档 multiTouchSlop=100ms、holdBaseReadyMs=120ms。
        // 三指按下间隔卡在同时性窗口边界（0/50/99ms，仍算“同时按下”），
        // 若 holdBaseReadyMs < multiTouchSlopMs（比如误设为 80），最后落下的第三指
        // 在其自身按下时刻（99ms）去看前两指（0ms/50ms 按下）会发现「已就位」
        // （99-0=99>=80），从而在它随后抬起时被 tryHoldTap 误判为「两指底座+单击」，
        // 而不是走三指双击的判定路径。当前实现 holdBaseReadyMs(120) >= slop(99) 时，
        // 就位条件不成立，不会分裂出这个误判分支。
        fun threeFingerTap(startTime: Long): Gesture? {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, startTime, 1))
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 1, 150f, 100f, startTime + 50, 2))
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 200f, 100f, startTime + 99, 3))
            var result: Gesture? = null
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 200f, 100f, startTime + 130, 2))
                ?.let { result = it }
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 1, 150f, 100f, startTime + 140, 1))
                ?.let { result = it }
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 100f, 100f, startTime + 150, 0))
                ?.let { result = it }
            return result
        }
        assertNull(threeFingerTap(startTime = 0))
        assertEquals(Gesture.THREE_FINGER_DOUBLE_TAP, threeFingerTap(startTime = 300))
    }
}

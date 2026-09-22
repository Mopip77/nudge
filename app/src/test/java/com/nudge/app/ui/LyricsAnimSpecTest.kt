package com.nudge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些断言守的是**梯度的方向性**，不是具体数值。
 *
 * 数值要在实验室里按观感调，随时会变；但「沿屏幕自上而下单调变硬」
 * 这条方向性是这版动画的前提——它一旦被写反或退化成对称，
 * 最上面那行就会重新带上不该有的拖尾（改这版之前正是如此），
 * 而这个缺陷在代码里看不出来，只能靠断言拦住。
 */
class LyricsAnimSpecTest {

    private val spec = LyricsAnimSpec.DEFAULT

    @Test
    fun `刚度沿屏幕自上而下单调不减`() {
        val values = (0..12).map { spec.stiffnessAt(it) }
        values.zipWithNext { a, b ->
            assertTrue("刚度在屏幕下方反而变小了：$a -> $b", b >= a)
        }
    }

    @Test
    fun `阻尼比沿屏幕自上而下单调不减`() {
        val values = (0..12).map { spec.dampingAt(it) }
        values.zipWithNext { a, b ->
            assertTrue("阻尼比在屏幕下方反而变小了：$a -> $b", b >= a)
        }
    }

    @Test
    fun `梯度不再对称：当前行上下同样距离处的刚度不同`() {
        // 这是与旧实现最本质的差别。旧实现用 abs(distance)，
        // 上下同距离的行必然拿到相同的刚度。
        val above = spec.stiffnessAt(spec.anchorRow - 2)
        val below = spec.stiffnessAt(spec.anchorRow + 2)
        assertTrue("上方应比下方软：above=$above below=$below", above < below)
    }

    @Test
    fun `屏幕顶部取顶部端点值`() {
        assertEquals(spec.stiffnessTop, spec.stiffnessAt(0), 0.001f)
        assertEquals(spec.dampingTop, spec.dampingAt(0), 0.001f)
    }

    @Test
    fun `超出梯度跨度后封顶在底部端点`() {
        val beyond = (spec.anchorRow + spec.gradientRampLines).toInt() + 5
        assertEquals(spec.stiffnessBottom, spec.stiffnessAt(beyond), 0.001f)
        assertEquals(spec.dampingBottom, spec.dampingAt(beyond), 0.001f)
    }

    @Test
    fun `容器顶边之外的行不再继续外推`() {
        // 负的屏幕行号已在裁切区外，再软也看不见，按顶部端点处理即可
        assertEquals(spec.stiffnessAt(0), spec.stiffnessAt(-4), 0.001f)
    }

    @Test
    fun `当前行不模糊`() {
        assertEquals(0f, spec.blurDpAt(0), 0.001f)
    }

    @Test
    fun `模糊随距离单调不减并封顶在峰值`() {
        val values = (0..20).map { spec.blurDpAt(it) }
        values.zipWithNext { a, b -> assertTrue("模糊反而变小了：$a -> $b", b >= a) }
        assertEquals(spec.maxBlurDp, spec.blurDpAt(30), 0.001f)
    }

    @Test
    fun `模糊第 1 行即起步`() {
        // 「紧邻行完全清晰」的豁免档是早先实现的特征，这版刻意去掉了
        assertTrue(spec.blurDpAt(1) > 0f)
    }

    @Test
    fun `默认参数下清晰度上下对称`() {
        // 默认 upperFadeScale = 1：位移梯度这次已改成有向的，
        // 若清晰度也同时改成有向，出了问题分不清是哪个变量在起作用。
        assertEquals(1f, spec.upperFadeScale, 0.001f)
        assertEquals(spec.blurDpAt(3), spec.blurDpAt(-3), 0.001f)
        assertEquals(spec.alphaAt(3, false), spec.alphaAt(-3, false), 0.001f)
    }

    @Test
    fun `上方跨度倍率小于 1 时上方糊得更快`() {
        val asymmetric = spec.copy(upperFadeScale = 0.5f)
        assertTrue(asymmetric.blurDpAt(-3) > asymmetric.blurDpAt(3))
    }

    @Test
    fun `当前行透明度为 1 且其余行不低于下限`() {
        assertEquals(1f, spec.alphaAt(0, isCurrent = true), 0.001f)
        (1..30).forEach {
            assertTrue(spec.alphaAt(it, false) >= spec.alphaFar - 0.001f)
        }
    }

    @Test
    fun `锚点默认在第 3 行`() {
        // 恒定居中是旧实现。焦点压到偏上的位置，预读区才比已唱过的区域大。
        assertEquals(2, spec.anchorRow)
    }
}

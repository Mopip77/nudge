package com.nudge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些断言守的是**梯度的方向性与结构**，不是具体数值。
 *
 * 数值要在实验室里按观感调，随时会变；但「拖尾沿屏幕自上而下递增」
 * 这条方向性是这版动画的前提——列表往上走，最上面那行走得最久、
 * 最先落定，越靠下越是被拖着走。
 *
 * 这个方向曾经被写反过一版（顶懒底干脆），代码上完全看不出问题，
 * 端点数字也「有梯度」，只有真机上肉眼看才发现最上面那行最晃。
 *
 * 另一条同样重要：**位移只逼近目标、永不越过**。早先用弹簧
 * （PID 式：快速拉到目标再震荡），观感上是「拱一下」；
 * 现在用单调的贝塞尔曲线，各行只是趋近的快慢不同。
 */
class LyricsAnimSpecTest {

    private val spec = LyricsAnimSpec.DEFAULT

    /**
     * 复算 `LyricsOverlay.easingFor` 那条曲线，用来在纯 JVM 下验证单调性。
     * 形状必须与那里一致：`cubic-bezier(ease, 0, 0.25, 1)`。
     */
    private fun progressAt(ease: Float, t: Float): Float {
        val x1 = ease.coerceIn(0f, 1f)
        val x2 = 0.25f
        fun bx(u: Float) = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u
        fun by(u: Float) = 3 * (1 - u) * u * u * 1f + u * u * u
        var lo = 0f
        var hi = 1f
        repeat(50) {
            val m = (lo + hi) / 2
            if (bx(m) < t) lo = m else hi = m
        }
        return by((lo + hi) / 2)
    }

    @Test
    fun `懒惰度沿屏幕自上而下单调不减`() {
        // 越靠下起步越慢 → 越像被拖着走 → 拖尾越明显
        val values = (0..12).map { spec.easeAt(it) }
        values.zipWithNext { a, b ->
            assertTrue("懒惰度在屏幕下方反而变小了：$a -> $b", b >= a)
        }
    }

    @Test
    fun `最上面那行最干脆`() {
        // 它是这趟位移的终点，该最先到位。写反方向时正是这一条先破。
        (1..8).forEach { row ->
            assertTrue("第 $row 行比屏幕顶还干脆", spec.easeAt(row) >= spec.easeAt(0))
        }
    }

    @Test
    fun `位移单调逼近目标，永不越过`() {
        // 这是与弹簧最本质的差别，也是「拱一下」的根治办法。
        // 弹簧会过冲再回弹；这条曲线的进度必须始终在 [0,1] 内单调不减。
        (0..10).forEach { row ->
            val e = spec.easeAt(row)
            var prev = 0f
            var t = 0f
            while (t <= 1f) {
                val p = progressAt(e, t)
                assertTrue("第 $row 行在 t=$t 处进度 $p 超出 [0,1]，会越过目标", p in -0.001f..1.001f)
                assertTrue("第 $row 行在 t=$t 处进度回退（$prev -> $p），说明有震荡", p >= prev - 0.001f)
                prev = p
                t += 0.02f
            }
        }
    }

    @Test
    fun `越靠下的行起步越慢`() {
        // 「被拖着走」的直接度量：同一时刻，下面的行走得更少。
        val quarter = (0..7).map { progressAt(spec.easeAt(it), 0.25f) }
        quarter.zipWithNext { a, b ->
            assertTrue("下方的行在 ¼ 时刻反而走得更多：$a -> $b", b <= a + 0.001f)
        }
    }

    @Test
    fun `相邻行的差要看得出来`() {
        // 差太小的话肉眼会把整列合成一个刚体，退化成「整列线性滚动」——
        // 这个缺陷栽过一次，端点数字看着「有梯度」但实际没有。
        // 取可视区首尾在 ¼ 时刻的进度差。
        val top = progressAt(spec.easeAt(0), 0.25f)
        val bottom = progressAt(spec.easeAt(spec.gradientRampLines.toInt()), 0.25f)
        assertTrue("首尾行在 ¼ 时刻只差 ${top - bottom}，错峰看不出来", top - bottom >= 0.2f)
    }

    @Test
    fun `梯度不对称：当前行上下同样距离处的曲线不同`() {
        // 最早的实现用 abs(distance)，上下同距离的行必然拿到相同的曲线，
        // 拖尾在两个方向同时出现。
        val above = spec.easeAt(spec.anchorRow - 2)
        val below = spec.easeAt(spec.anchorRow + 2)
        assertTrue("上方应比下方干脆：above=$above below=$below", above < below)
    }

    @Test
    fun `梯度与锚点解耦`() {
        // 梯度是纯粹的屏幕位置函数，挪动锚点不该改变任何一行的快慢。
        // 早先跨度写成 anchorRow + gradientRampLines，是残留的
        // 「以当前行为中心」的思路。
        val moved = spec.copy(anchorRow = spec.anchorRow + 2)
        (0..10).forEach { row ->
            assertEquals(spec.easeAt(row), moved.easeAt(row), 0.001f)
        }
    }

    @Test
    fun `屏幕顶部取顶部端点值`() {
        assertEquals(spec.easeTop, spec.easeAt(0), 0.001f)
    }

    @Test
    fun `超出梯度跨度后封顶在底部端点`() {
        val beyond = spec.gradientRampLines.toInt() + 5
        assertEquals(spec.easeBottom, spec.easeAt(beyond), 0.001f)
    }

    @Test
    fun `容器顶边之外的行不再继续外推`() {
        // 负的屏幕行号已滑出裁切区，按最干脆档处理即可
        assertEquals(spec.easeAt(0), spec.easeAt(-4), 0.001f)
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
    fun `锚点默认在第 4 行`() {
        // 恒定居中是旧实现。焦点压到偏上的位置，预读区才比已唱过的区域大。
        // 但也不能太靠上：取 2 那版实测焦点贴着容器顶边，上方那点上下文
        // 被边缘淡出吃掉大半。
        assertEquals(3, spec.anchorRow)
    }

    @Test
    fun `当前行起步不能太慢`() {
        // 当前行是阅读焦点，起步太慢会显得歌词滞后于演唱。
        // 它落在梯度中段，应该还算干脆。
        val p = progressAt(spec.easeAt(spec.anchorRow), 0.5f)
        assertTrue("当前行在半程只走了 ${p * 100}%，太拖", p >= 0.35f)
    }

    @Test
    fun `淡入淡出等位移基本走完才开始`() {
        // 「先滑到位、再换焦点」。delay 为 0 就是旧的「边移动边对焦」，
        // 两件事挤在一起显得急——这是用户直接反馈的观感问题。
        //
        // 阅读区走的是补间（见 settleTweenMs），所以这里比的是补间时长
        // 而不是弹簧落定的估算值。
        assertTrue("淡入淡出没有延迟，会边移动边对焦", spec.fadeDelayMs > 0)
        // 但也不能等到位移完全停住：完全排队会有个能察觉的停顿，
        // 稍有交叠才连贯。
        assertTrue(
            "淡入淡出延迟 ${spec.fadeDelayMs}ms 不短于位移 ${spec.settleTweenMs}ms，会出现停顿",
            spec.fadeDelayMs < spec.settleTweenMs,
        )
    }

    @Test
    fun `阅读区位移时长在 0_3 到 0_6 秒之间`() {
        // 太快是「瞬间切过去」，太慢则歌词滞后于演唱。
        assertTrue(
            "位移 ${spec.settleTweenMs}ms 不在 300~600ms 区间",
            spec.settleTweenMs in 300..600,
        )
    }
}

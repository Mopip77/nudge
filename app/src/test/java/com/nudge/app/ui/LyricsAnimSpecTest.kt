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
    fun `可视区内所有行同速——整列是刚体`() {
        // 这是「先快后慢的吸附感」的前提：可视区内一旦各行不同速，
        // 它们会互相错开，整列显得散，观感就是「急」。
        // 注意断言的是 easeFor（渲染侧真正用的），不是 easeAt——
        // 后者仍是那条逐行梯度，但现在只有 incoming 那档会取到它的端点。
        val visible = 8
        val first = spec.easeFor(0, visible)
        (0 until visible).forEach { row ->
            assertEquals(
                "第 $row 行与整列不同速，刚体假设被破坏",
                first, spec.easeFor(row, visible), 0.0001f,
            )
        }
    }

    @Test
    fun `整列用最干脆的那一档`() {
        // 懒惰度即贝塞尔第一个控制点的 x，越大起步越平。
        // 整列取 easeTop（最小）才有「先快后慢」的吸附感；
        // 取大的那端会变成「先拱一下再走」，正是用户反馈的急。
        assertEquals(spec.easeTop, spec.blockEase, 0.0001f)
        assertTrue(
            "整列的懒惰度应明显小于 incoming：block=${spec.blockEase} in=${spec.incomingEase}",
            spec.blockEase < spec.incomingEase,
        )
    }

    @Test
    fun `只有即将进入可视区的那一行错峰`() {
        val visible = 8
        assertTrue("可视区最后一行仍属整列", !spec.isIncoming(visible - 1, visible))
        assertTrue("可视区外第一行应是 incoming", spec.isIncoming(visible, visible))
        assertEquals(spec.incomingEase, spec.easeFor(visible, visible), 0.0001f)
    }

    @Test
    fun `incoming 那行必须带一个能看出来的延迟`() {
        // 只给它一条更懒的曲线是不够的：曲线再懒也是从第 0ms 就开始动，
        // 肉眼分不出「懒」和「慢」。要读出「它晚了一拍」必须有静止段。
        assertTrue("incomingDelayMs=${spec.incomingDelayMs} 太短，看不出来", spec.incomingDelayMs >= 60)
        // 但延迟 + 时长要留在一次换行的间隔内，否则快歌时它会被
        // 下一次换行打断，永远追不上。
        assertTrue(
            "延迟 ${spec.incomingDelayMs} + 时长 ${spec.settleTweenMs} 过长",
            spec.incomingDelayMs + spec.settleTweenMs <= 800,
        )
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

    /** 逐行渐进的旧模型，只有它才谈得上「截断距离」「梯度」。 */
    private val gradientSpec = LyricsAnimSpec(uniformBlurDp = -1f)

    @Test
    fun `默认一行都不挂 blur，层次纯靠透明度`() {
        // 这是最终形态：观察 Android 版 Apple Music，非当前行只是**淡**，
        // 并没有模糊——先前把「淡到看不清笔画」误读成了「糊」。
        //
        // 对流畅度是决定性的：每个挂 blur 的行都要一个全屏宽的离屏缓冲，
        // 17 行全挂时 24.6% 的帧掉帧；一行不挂降到 3.9%、90 分位 30ms→12ms。
        val blurred = (-8..8).count { spec.blurDpAt(it) > 0f }
        assertEquals("默认不该有任何行挂 blur", 0, blurred)
    }

    @Test
    fun `透明度独自承担层次，跨度要够大`() {
        // 去掉 blur 后，alpha 是建立焦点与远近的**唯一**手段，
        // 跨度必须比「配合 blur」的年代拉得更开，否则各行糊成一片。
        // 早先：0.55 起、地板 0.3、每行 0.035（跨度 0.25）。
        val near = spec.alphaAt(1, false)
        val far = spec.alphaFar
        assertTrue(
            "当前行与紧邻行的对比 ${1f - near} 太弱，焦点不明确",
            1f - near >= 0.4f,
        )
        assertTrue("alpha 跨度 ${near - far} 太窄，分不出远近", near - far >= 0.25f)
    }

    @Test
    fun `开启两档模糊后当前行与其余行有区分`() {
        // 机制保留，供实验室对比——真要开的话当前行必须更清晰
        val withBlur = LyricsAnimSpec(uniformBlurDp = 4f, currentBlurDp = 0f)
        assertTrue(
            "当前行不比其余行清晰，焦点就没了",
            withBlur.blurDpAt(0) < withBlur.blurDpAt(1),
        )
        // 截断之外仍不挂
        assertEquals(0f, withBlur.blurDpAt(withBlur.blurCutoffLines + 1), 0.001f)
    }

    @Test
    fun `开启两档模糊时半径要守住最远处仍认得出字`() {
        val withBlur = LyricsAnimSpec(uniformBlurDp = 4f)
        assertTrue("统一半径过大，远处会糊成色块", withBlur.uniformBlurDp <= 12f)
        assertTrue("统一半径过小，看不出景深", withBlur.uniformBlurDp >= 3f)
    }

    @Test
    fun `旧模型下模糊在截断距离内随距离单调不减并封顶在峰值`() {
        val values = (0..gradientSpec.blurCutoffLines).map { gradientSpec.blurDpAt(it) }
        values.zipWithNext { a, b -> assertTrue("模糊反而变小了：$a -> $b", b >= a) }

        // 不截断时才谈得上「远处封顶在峰值」
        val noCutoff = gradientSpec.copy(blurCutoffLines = 0)
        assertEquals(noCutoff.maxBlurDp, noCutoff.blurDpAt(30), 0.001f)
    }

    @Test
    fun `旧模型下超出截断距离的行不挂模糊`() {
        // 每个挂 blur 的行都要一个全屏宽的离屏缓冲，而渲染窗口有 17 行。
        val s = gradientSpec
        assertTrue("截断距离内应当有模糊", s.blurDpAt(s.blurCutoffLines) > 0f)
        assertEquals(
            "超出截断距离仍在挂 blur，离屏缓冲省不下来",
            0f,
            s.blurDpAt(s.blurCutoffLines + 1),
            0.001f,
        )
        // 上方同理，截断是按绝对距离算的
        assertEquals(0f, s.blurDpAt(-(s.blurCutoffLines + 1)), 0.001f)
    }

    @Test
    fun `截断可以关闭`() {
        // 设 0 表示全部挂 blur，供实验室对比用。
        // 前提是先开启模糊——默认是纯 alpha、一行都不挂。
        val noCutoff = spec.copy(uniformBlurDp = 4f, blurCutoffLines = 0)
        assertTrue(noCutoff.blurDpAt(50) > 0f)
    }

    @Test
    fun `默认不离散化半径`() {
        // 离散化曾被当作性能优化引入，真机实测反而更差
        // （Janky 7.6% → 12.2%，90 分位 12ms → 25ms）：
        // Skia 不会因为半径相同就复用（内容不同仍要各做一遍），
        // 而向上取整把半径抬大了，高斯核更大反而更慢。
        // 详见 blurSteps 的注释。
        assertEquals(0, spec.blurSteps)
    }

    @Test
    fun `开启离散化后半径只取有限档位`() {
        // 机制本身是对的，只是不划算。保留可开启，让那次实测结论可复现。
        val stepped = spec.copy(blurSteps = 3)
        val radii = (-12..12).map { stepped.blurDpAt(it) }.toSet()
        assertTrue(
            "半径档位有 ${radii.size} 种，离散化没生效",
            radii.size <= 3 + 1, // +1 是 0 那档
        )
    }

    @Test
    fun `离散化不改变单调性`() {
        // 吸附后仍必须随距离单调不减，否则会出现「远的反而更清晰」
        val stepped = spec.copy(blurSteps = 3)
        val values = (0..stepped.blurCutoffLines).map { stepped.blurDpAt(it) }
        values.zipWithNext { a, b -> assertTrue("模糊反而变小了：$a -> $b", b >= a) }
    }

    @Test
    fun `开启模糊时紧邻当前行不糊到看不清`() {
        // 紧邻的行承担「预读下一句」，糊到认不出字就失去意义了。
        // 默认是纯 alpha（不挂 blur），这条只在显式开启模糊时才有意义。
        val near = LyricsAnimSpec(uniformBlurDp = 4f).blurDpAt(1)
        assertTrue("紧邻行没有模糊，焦点层次会断", near > 0f)
        assertTrue(
            "紧邻行模糊 ${near}dp 过大，预读下一句会看不清",
            near <= 12f,
        )
    }

    @Test
    fun `开启模糊时第 1 行即起步`() {
        // 「紧邻行完全清晰」的豁免档是更早实现的特征，刻意去掉了。
        // 默认纯 alpha 不挂 blur，这条只在显式开启模糊时才有意义。
        assertTrue(LyricsAnimSpec(uniformBlurDp = 4f).blurDpAt(1) > 0f)
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
        // 这条只对逐行渐进的旧模型成立：两档模型下所有非当前行同一档，
        // 上下本来就没有差别（alpha 那边仍然有向）。
        val asymmetric = gradientSpec.copy(upperFadeScale = 0.5f)
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
    fun `开译文时锚点提一行`() {
        // 锚点是按标称高度累加出来的，而译文行约 82dp 而非 52dp。
        // 不提这一行，同样的三行上文会从 156dp 涨到 246dp。
        assertEquals(spec.anchorRow - 1, spec.anchorRowFor(true))
        assertEquals(spec.anchorRow, spec.anchorRowFor(false))
    }

    @Test
    fun `锚点高度不因译文开关而明显变化`() {
        // 这条才是真正要守的不变式——「提一行」只是手段，
        // 目的是让焦点在屏幕上的**像素位置**基本不动。
        // 光断言行号的话，将来改了行高或译文排版就会静默失效。
        val plain = LyricRowData("原文")
        val withTr = LyricRowData("原文", "译文")

        val plainTop = spec.anchorRowFor(false) * plain.nominalHeightDp()
        val trTop = spec.anchorRowFor(true) * withTr.nominalHeightDp()

        // 允许一个行高以内的浮动：译文行数普遍少于原文，上方那几行
        // 不一定都带译文，本来就做不到严格等高。
        val drift = kotlin.math.abs(trTop - plainTop)
        assertTrue(
            "锚点高度 $plainTop -> $trTop，漂移 ${drift}dp 超过一个行高",
            drift < LyricRowData.LINE_HEIGHT_DP,
        )
    }

    @Test
    fun `锚点不会被提成负数`() {
        // 实验室可以把 anchorRow 拖到 0（当前行贴顶边），
        // 再减一就是负数，anchorTopPx 的 `1..anchorRow` 会变成空区间
        // 而静默退化。这里钉住下界。
        val top = spec.copy(anchorRow = 0)
        assertEquals(0, top.anchorRowFor(true))
    }

    @Test
    fun `当前行起步不能太慢`() {
        // 当前行是阅读焦点，起步太慢会显得歌词滞后于演唱。
        // 它落在梯度中段，应该还算干脆。
        val p = progressAt(spec.easeAt(spec.anchorRow), 0.5f)
        assertTrue("当前行在半程只走了 ${p * 100}%，太拖", p >= 0.35f)
    }

    @Test
    fun `焦点与位移必须错开，不能同时开始`() {
        // focusLeadMs == 0 就是「边移动边对焦」，两件事挤在一起显得急。
        // 这一条与方向无关——无论谁先，都不该同时开始。
        assertTrue("焦点与位移同时开始，会显得急", spec.focusLeadMs != 0)
    }

    @Test
    fun `默认时序是焦点先行`() {
        // Apple Music 的时序：先高亮下一句，极短间隔后整列才滚动。
        // 早先的口径正相反（位移先行），改这个方向要连带改文档与注释，
        // 所以用测试把当前的选择钉住。
        assertTrue(
            "默认时序应为焦点先行（focusLeadMs > 0），实际 ${spec.focusLeadMs}",
            spec.focusLeadMs > 0,
        )
    }

    @Test
    fun `时序偏移要明显小于位移时长`() {
        // 错开量若接近甚至超过位移时长，两段就完全排队了，
        // 中间会有一个能察觉的停顿；稍有交叠才连贯。
        assertTrue(
            "时序偏移 ${spec.focusLeadMs}ms 不短于位移 ${spec.settleTweenMs}ms，会出现停顿",
            kotlin.math.abs(spec.focusLeadMs) < spec.settleTweenMs,
        )
    }

    @Test
    fun `延迟换算的两边恒不同时为正`() {
        // focusDelayMs / scrollDelayMs 是同一个有符号量的两个投影：
        // 谁落后谁延迟，另一边必须恒为 0。两边都延迟等于整体延后，
        // 那会让换行整体慢半拍，且是个没人会故意选的状态。
        for (lead in listOf(-500, -90, -1, 0, 1, 90, 400)) {
            val s = LyricsAnimSpec(focusLeadMs = lead)
            assertTrue(
                "focusLeadMs=$lead 时两边同时延迟了",
                s.focusDelayMs == 0 || s.scrollDelayMs == 0,
            )
            assertTrue("延迟不能为负", s.focusDelayMs >= 0 && s.scrollDelayMs >= 0)
            // 两边之差恒等于偏移量本身，保证换算无损
            assertEquals(lead, s.scrollDelayMs - s.focusDelayMs)
        }
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

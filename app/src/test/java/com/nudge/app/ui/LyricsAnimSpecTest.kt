package com.nudge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些断言守的是动画的**结构性约束**，不是具体数值。
 *
 * 数值要在实验室里按观感调，随时会变；但违反其中任何一条都会让动画退化，
 * 而这在代码里看不出来、端点数字也看不出来——这一块栽过的每一次都是这样。
 *
 * 现在守的主要是两组：
 *
 * 1. **位移只逼近目标、永不越过**。早先用弹簧（PID 式：快速拉到目标再震荡），
 *    观感上是「拱一下」；现在是单调的贝塞尔曲线。
 * 2. **高亮与位移的时序必须真的错开**。这是两个参数的比例关系，
 *    任何一个单独看都正常——栽过一次，见对应用例的注释。
 *
 * 逐行梯度那组断言已随模型一起删掉（整列现在共用一条曲线），
 * 它们记录的坑搬进了 `LyricsAnimSpec.easeTop` 的注释。
 */
class LyricsAnimSpecTest {

    private val spec = LyricsAnimSpec.DEFAULT

    /**
     * 复算 `LyricsOverlay.easingFor` 那条曲线，用来在纯 JVM 下验证单调性。
     * 四个控制点全部来自 spec，与渲染侧是同一条曲线——
     * 另算一个近似值的话，测试通过了也不代表屏幕上跑的那条是对的。
     */
    private fun progressAt(p: LyricsAnimSpec.CubicPoints, t: Float): Float {
        val x1 = p.x1.coerceIn(0f, 1f)
        val x2 = p.x2.coerceIn(0f, 1f)
        fun bx(u: Float) = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u
        fun by(u: Float) =
            3 * (1 - u) * (1 - u) * u * p.y1 + 3 * (1 - u) * u * u * p.y2 + u * u * u
        var lo = 0f
        var hi = 1f
        repeat(50) {
            val m = (lo + hi) / 2
            if (bx(m) < t) lo = m else hi = m
        }
        return by((lo + hi) / 2)
    }

    // 早先这里有六个测试断言「逐行梯度」：懒惰度自上而下单调不减、
    // 最上面那行最干脆、越靠下起步越慢、相邻行的差要看得出来、
    // 梯度上下不对称、以及逐行的单调逼近。
    //
    // 那套模型已经删掉（整列共用一条曲线，见 LyricsAnimSpec.blockEase），
    // 这些断言随之失去对象。**它们记录的坑仍然有效**，都搬进了
    // easeTop 的注释里：无向距离导致上下对称扩散、方向整个写反、
    // 两端差太小退化成线性滚动。将来若真要再做错峰，先读那段。
    //
    // 单调逼近那一条是唯一还成立的，保留在下面的
    // `位移单调逼近，不越过目标` 里——它现在只需验一条曲线。

    @Test
    fun `高亮必须先淡完，位移才开始`() {
        // 这是「先高亮 → 停一下 → 再移动」成立的**充要条件**，
        // 而它是两个参数的**比例**关系，任何一个单独看都正常。
        //
        // 栽过一次：焦点先行 90ms 而淡入 260ms，延迟本身分毫不差地生效了
        // （日志里 FOCUS→LAYOUT 恰好 90ms），但淡入要到 +278ms 才结束，
        // 而位移 +165ms 就开始——后一半淡入与整段位移完全重叠，
        // 观感就是「同时进行」。当时一直在查延迟有没有生效，方向就错了。
        assertTrue(
            "淡入 ${spec.fadeAnimMs}ms 不短于焦点先行 ${spec.focusLeadMs}ms，两段会重叠",
            spec.fadeAnimMs < spec.focusLeadMs,
        )
        // 还要留出一段**两者都不动**的空档，那个「停一下」才看得见。
        assertTrue(
            "空档只有 ${spec.focusLeadMs - spec.fadeAnimMs}ms，太短读不出停顿",
            spec.focusLeadMs - spec.fadeAnimMs >= 40,
        )
    }

    @Test
    fun `焦点先行不能长到把位移挤出换行间隔`() {
        // 先行 + 位移时长要能在一次换行内跑完，否则快歌时位移会被
        // 下一次换行打断，整列永远追不上。
        assertTrue(
            "先行 ${spec.focusLeadMs} + 位移 ${spec.settleTweenMs} 过长",
            spec.focusLeadMs + spec.settleTweenMs <= 800,
        )
    }

    @Test
    fun `整列共用一条曲线，取值不依赖行号`() {
        // blockEase 是个**不带参数**的值，这本身就是「整列同速」的保证——
        // 拿不到行号就不可能按行区分。这里试过两版错峰都实测无效后删掉了：
        // 逐行梯度让每行起步都慢；只让进场那行慢则因为歌词一直铺到屏幕
        // 底部、那一行根本看不见。签名里**不该再出现 screenRow**，
        // 这条测试拦着「将来又想加回第三版」。
        assertEquals(spec.easeX1, spec.blockEase.x1, 0.0001f)
        assertEquals(spec.easeY1, spec.blockEase.y1, 0.0001f)
        assertEquals(spec.easeX2, spec.blockEase.x2, 0.0001f)
        assertEquals(spec.easeY2, spec.blockEase.y2, 0.0001f)
    }

    @Test
    fun `默认是 ease-out 族：起步就带速度`() {
        // y1 决定起点处的速度。**y1 = 0 会让曲线起点是平的**（有一段零速度），
        // 那正是用户反馈「直愣愣」的那一版——它是逐行梯度时代为了做
        // 「起步慢的拖尾」留下的，错峰删掉后就只剩副作用了。
        assertTrue(
            "y₁=${spec.easeY1} 太小，起步没速度，吸附感出不来",
            spec.easeY1 >= 0.8f,
        )
        // 起步要快：¼ 时刻应当已经走掉一大半。
        val quarter = progressAt(spec.blockEase, 0.25f)
        assertTrue("¼ 时刻只走了 ${quarter * 100}%，起步太肉", quarter >= 0.5f)
    }

    // 刻意**没有**「后段衰减要够顺」那类断言。
    //
    // 写过一版（断言 ½→¾ 之间仍有 ≥3% 的推进），实算之后发现它把旧曲线
    // (0.1,0,0.25,1) 判为合格（14.9%）、把更激进的 easeOutExpo 判为不合格
    // （2.6%），与注释里写的意图正好相反——因为 ease-out 族本来就是
    // 前段吃掉绝大部分位移、后段只剩零头，「后段推进少」是它的**特征**
    // 而不是缺陷。
    //
    // 教训：「直愣愣」是个观感问题，落不到「某个时刻走了百分之多少」
    // 这种单一判据上（旧曲线的分布其实比新的更均匀）。这一档只能靠
    // 实验室肉眼比对，所以那里留了「旧（直愣愣）」预设做参照物。
    // 别再凭直觉补一条看起来合理的数值断言。

    @Test
    fun `位移单调逼近，不越过目标`() {
        // 整列现在共用这一条曲线，它必须是单调的——位移只趋近不越过，
        // 否则就是弹簧式的过冲，焦点行晃一下会被读成「歌词跳了」。
        var last = 0f
        (0..20).forEach { i ->
            val p = progressAt(spec.blockEase, i / 20f)
            assertTrue("t=${i / 20f} 处进度回退了：$last -> $p", p >= last - 0.0001f)
            assertTrue("进度越过了 1：$p", p <= 1.0001f)
            last = p
        }
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
    fun `整列起步不能太慢`() {
        // 起步太慢会显得歌词滞后于演唱，也就是用户反馈的「急」的反面——
        // 曲线在起点附近太平时，观感是「先拱一下再走」。
        // 整列共用一条曲线，所以这里验的就是那条曲线本身。
        val p = progressAt(spec.blockEase, 0.5f)
        assertTrue("半程只走了 ${p * 100}%，太拖", p >= 0.35f)
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

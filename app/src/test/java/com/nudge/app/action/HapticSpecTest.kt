package com.nudge.app.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 波形渲染的结构性约束。
 *
 * 振动本身没法自动化测（只能真机上手摸），所以这里断言的是**包络算得对不对**：
 * 形状的方向性、振幅地板、迸发的落差。这些在代码里都看不出问题
 * ——`startGapMs = 90, endGapMs = 18` 看着就是「有梯度」，但渲染时
 * 如果把 t 算反了，出来的就是减速列，而那在真机上只表现为「震得怪」。
 *
 * 与 `LyricsAnimSpecTest` 同一个思路：数值随手感调，但违反其中任何一条
 * 都会让效果退化成它的反面。
 */
class HapticSpecTest {

    @Test
    fun `单脉冲只产出一段振动`() {
        val w = HapticSpec(pulses = 1, pulseMs = 42, startAmp = 0.9f, endAmp = 0.9f).render()

        assertEquals(1, w.timings.size)
        assertEquals(42L, w.timings[0])
        assertTrue("单脉冲应接近满幅", w.amplitudes[0] > 200)
    }

    @Test
    fun `两个数组恒等长`() {
        HapticPalette.ALL.forEach { slot ->
            val w = slot.default.render()
            assertEquals(
                "${slot.label} 的 timings 与 amplitudes 必须一一对应",
                w.timings.size,
                w.amplitudes.size,
            )
        }
    }

    @Test
    fun `加速脉冲列的间隔单调收紧`() {
        val w = HapticSpec(
            pulses = 6,
            startGapMs = 90,
            endGapMs = 16,
            gapCurve = 1.9f,
        ).render()

        val gaps = w.extractGaps()
        assertTrue("应有 5 段间隔，实际 ${gaps.size}", gaps.size == 5)
        gaps.zipWithNext().forEach { (a, b) ->
            assertTrue("间隔必须单调收紧，出现 $a -> $b", b <= a)
        }
        assertTrue("首尾间隔要拉开足够差距", gaps.first() - gaps.last() > 50)
    }

    @Test
    fun `减速脉冲列的间隔单调拉开`() {
        val w = HapticSpec(
            pulses = 4,
            startGapMs = 60,
            endGapMs = 150,
        ).render()

        val gaps = w.extractGaps()
        gaps.zipWithNext().forEach { (a, b) ->
            assertTrue("间隔必须单调拉开，出现 $a -> $b", b >= a)
        }
    }

    @Test
    fun `渐强脉冲列的振幅单调递增`() {
        val w = HapticSpec(
            pulses = 7,
            startAmp = 0.28f,
            endAmp = 0.62f,
            minAmp = 0.2f,
        ).render()

        val amps = w.extractPulseAmplitudes()
        amps.zipWithNext().forEach { (a, b) ->
            assertTrue("振幅必须单调递增，出现 $a -> $b", b >= a)
        }
    }

    @Test
    fun `振幅地板挡住低于起振阈值的脉冲`() {
        // 起点 0.02 远低于马达起振阈值，若不夹紧，前几个脉冲完全摸不到，
        // 蓄力的前半段等于白做——真机上表现为「从中间突然开始震」。
        val floored = HapticSpec(
            pulses = 5,
            startAmp = 0.02f,
            endAmp = 0.9f,
            minAmp = 0.25f,
        ).render()

        val min = floored.extractPulseAmplitudes().min()
        assertTrue(
            "最弱脉冲应被地板抬到 0.25 附近（255*0.25≈64），实际 $min",
            min >= 60,
        )
    }

    @Test
    fun `地板设为零时不再夹紧`() {
        // 实验室里要能直接对比「有没有地板」的差别，所以地板必须可关。
        val raw = HapticSpec(
            pulses = 5,
            startAmp = 0.02f,
            endAmp = 0.9f,
            minAmp = 0f,
        ).render()

        assertTrue("关掉地板后最弱脉冲应很弱", raw.extractPulseAmplitudes().min() < 20)
    }

    @Test
    fun `迸发前有静默且振幅高于蓄力段`() {
        val w = HapticPalette.LIKED.render()

        // 最后一段是迸发，倒数第二段应是静默。
        val lastIdx = w.timings.size - 1
        assertEquals("迸发前必须留静默，否则与蓄力列黏成一团", 0, w.amplitudes[lastIdx - 1])
        assertTrue("迸发前的静默要有可感知的长度", w.timings[lastIdx - 1] >= 30)

        val burst = w.amplitudes[lastIdx]
        val peakOfRamp = w.extractPulseAmplitudes().dropLast(1).max()
        assertTrue(
            "迸发($burst)必须明显高于蓄力段峰值($peakOfRamp)，否则没有落差",
            burst - peakOfRamp > 50,
        )
    }

    @Test
    fun `蓄力段刻意不爬满给迸发留落差`() {
        val ramp = HapticPalette.LIKED.copy(burstMs = 0).render()
        assertTrue(
            "蓄力段峰值不该接近满幅，否则迸发失去冲击力",
            ramp.extractPulseAmplitudes().max() < 200,
        )
    }

    @Test
    fun `五种波形的形状两两不同`() {
        // 这是整个改动的目的：早先五种只有时长差别，盲操下分不出来。
        // 用「脉冲数 + 有无迸发 + 节奏走向」三元组做签名，任意两条不得相同。
        val signatures = HapticPalette.ALL.map { slot ->
            val s = slot.default
            Triple(
                s.pulses,
                s.burstMs > 0,
                when {
                    s.pulses < 2 -> "n/a"
                    s.startGapMs > s.endGapMs -> "加速"
                    s.startGapMs < s.endGapMs -> "减速"
                    else -> "匀速"
                },
            )
        }

        assertEquals(
            "五种波形的形状签名必须两两不同，实际 $signatures",
            signatures.size,
            signatures.toSet().size,
        )
    }

    @Test
    fun `高频反馈足够短低频反馈才允许长`() {
        // 切歌是最高频操作，连着用几次，长反馈会累。
        assertTrue(
            "切歌反馈不应超过 100ms",
            HapticPalette.NEXT.totalDurationMs() <= 100,
        )
        assertTrue(
            "已收藏反馈不应超过 150ms",
            HapticPalette.ALREADY_LIKED.totalDurationMs() <= 150,
        )
        // 收藏是低频且值得庆祝的动作，允许长，但也不能长到离谱。
        val liked = HapticPalette.LIKED.totalDurationMs()
        assertTrue("收藏反馈应有足够的蓄力长度，实际 ${liked}ms", liked in 300..800)
    }

    @Test
    fun `脉冲宽度有下限`() {
        // 马达有启动惯性，过短的脉冲在低振幅下根本没转起来就结束了。
        val w = HapticSpec(pulses = 3, pulseMs = 1).render()
        w.extractPulseTimings().forEach {
            assertTrue("脉冲宽度被夹到下限，实际 ${it}ms", it >= HapticSpec.MIN_PULSE_MS)
        }
    }

    @Test
    fun `振幅恒在合法区间`() {
        // createWaveform 要求 0..255，且 0 表示静默。非静默段不得为 0，
        // 否则那一下静默掉、脉冲数对不上。
        HapticPalette.ALL.forEach { slot ->
            slot.default.render().amplitudes.forEach {
                assertTrue("${slot.label} 振幅越界: $it", it in 0..HapticSpec.MAX_AMPLITUDE)
            }
            slot.default.render().extractPulseAmplitudes().forEach {
                assertTrue("${slot.label} 的脉冲不得为静默", it > 0)
            }
        }
    }

    @Test
    fun `曲率大于一时压缩集中在后段`() {
        val linear = HapticSpec(pulses = 5, startGapMs = 100, endGapMs = 20, gapCurve = 1f)
            .render().extractGaps()
        val curved = HapticSpec(pulses = 5, startGapMs = 100, endGapMs = 20, gapCurve = 2.5f)
            .render().extractGaps()

        // 曲率大时前段变化慢，所以中间那个间隔应当更接近起点（更大）。
        assertTrue(
            "gapCurve 越大，前段越平缓。linear=${linear[1]}, curved=${curved[1]}",
            curved[1] > linear[1],
        )
    }
}

/**
 * 从渲染结果里挑出脉冲段（非零振幅）与静默段（零振幅）。
 *
 * 渲染结果是交替的「脉冲/静默」序列，断言时几乎总要分开看，
 * 所以抽成扩展函数而不是在每个用例里手写下标。
 */
private fun HapticWaveform.extractGaps(): List<Long> =
    timings.filterIndexed { i, _ -> amplitudes[i] == 0 }

private fun HapticWaveform.extractPulseAmplitudes(): List<Int> =
    amplitudes.filter { it > 0 }

private fun HapticWaveform.extractPulseTimings(): List<Long> =
    timings.filterIndexed { i, _ -> amplitudes[i] > 0 }

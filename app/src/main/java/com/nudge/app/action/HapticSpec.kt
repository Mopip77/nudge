package com.nudge.app.action

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一段振动的**振幅包络**，描述为「一串脉冲 + 可选的收尾迸发」。
 *
 * **纯 Kotlin，不依赖任何 Android 类**——与 `GestureRecognizer`、`LyricsAnimSpec`
 * 同口径。渲染出来的 timings/amplitudes 对能在 JVM 上直接断言，
 * 而振动本身是没法自动化测的（只能真机上手摸），所以「波形算得对不对」
 * 这一层必须能单测，否则整块逻辑没有任何防回归手段。
 *
 * ## 为什么只能做振幅包络
 *
 * 真机取证（SM-G9810 / Android 13，`dumpsys vibrator_manager`）：
 *
 * ```
 * mCapabilities=[AMPLITUDE_CONTROL], mSupportedPrimitives=[],
 * mSupportedEffects=[], mCompositionSizeMax=0, mPwleSizeMax=0
 * ```
 *
 * 这意味着 Android 那套「好看」的触感 API 全部不可用：
 *
 * - `startComposition()` + `PRIMITIVE_QUICK_RISE` —— 官方做「蓄力→迸发」
 *   正是用它，但 `mSupportedPrimitives` 是空的，`mCompositionSizeMax=0`。
 * - `createPredefined(EFFECT_HEAVY_CLICK)` —— `mSupportedEffects` 空，
 *   会静默回退成通用的一震，毫无区分度。
 * - PWLE（频率曲线）—— `mPwleSizeMax=0`，没有频率控制。
 *
 * 唯一剩下的高表达力接口是 `createWaveform(timings, amplitudes, repeat)`：
 * 任意长的「时长 + 0..255 振幅」序列。所以本类做的事就是**把一条参数化的
 * 包络离散成那两个数组**。系统的 `mRampStepDurationMs=5` 说明 5ms 是
 * 有效分辨率，脉冲宽度取几十毫秒绰绰有余。
 *
 * ## 为什么是脉冲列而不是连续渐强
 *
 * 「火箭发射」那种蓄力感，直觉上该用一条从 0 平滑爬到满幅的连续曲线。
 * 但这台是弱马达且无频率控制，连续渐强的低振幅段人手几乎感知不到，
 * 实际体验会退化成「停一会儿然后震一下」——正是要修的「一段持续振动」
 * 的亲戚。
 *
 * 脉冲的**起停边沿**本身就是最强的触觉信号，所以「累积感」主要靠
 * **间隔逐步压缩**（节奏变密）来传递，振幅递增只是辅助。
 */
data class HapticSpec(
    /**
     * 脉冲个数。1 表示单击（此时间隔参数全部无意义）。
     *
     * 盲操下「数个数」只在 3 个以内可靠，所以多脉冲的形状不靠个数辨识，
     * 靠节奏走向（见 [startGapMs] / [endGapMs]）。
     */
    val pulses: Int = 1,

    /** 单个脉冲的宽度（毫秒）。见 [MIN_PULSE_MS] 对下限的说明。 */
    val pulseMs: Int = 25,

    /**
     * 脉冲间隔从 [startGapMs] 渐变到 [endGapMs]（毫秒）。
     *
     * **两者的大小关系就是这个波形的「形状」**，也是五种反馈之间的
     * 结构性差异而非数值微调：
     *
     * - `start > end` —— 节奏**加速**，「哒…哒…哒·哒·哒哒哒」，蓄力感。
     * - `start < end` —— 节奏**减速**，「哒—哒——哒———」，泄气感。
     * - 相等 —— 匀速，中性。
     *
     * 盲操下这种类别差异不需要对照就能认出来，而「振幅大一点小一点」
     * 没有对照根本分不出来。
     */
    val startGapMs: Int = 90,
    val endGapMs: Int = 18,

    /** 脉冲振幅从 [startAmp] 渐变到 [endAmp]，取值 0..1。 */
    val startAmp: Float = 0.25f,
    val endAmp: Float = 0.7f,

    /**
     * 间隔与振幅各自的变化曲率。1 = 线性，>1 = 后段变化更剧烈。
     *
     * 分成两个而不是共用一个：蓄力感主要来自节奏压缩，希望它后段急剧收紧
     * （gapCurve > 1），而振幅更适合平稳爬升——两条曲线绑在一起就没法
     * 单独试出「节奏很急但力度还留着余地」这种组合，而迸发的冲击力
     * 恰恰依赖前面留了余地。
     */
    val gapCurve: Float = 1.6f,
    val ampCurve: Float = 1f,

    /**
     * 收尾迸发的振幅与时长。[burstMs] 为 0 表示不要迸发。
     *
     * 迸发**前面有一段静默**（[burstGapMs]）：蓄力列刚把节奏压到最密，
     * 紧接着一记重击会跟最后几个脉冲黏成一团，反而没有「集中迸发」的
     * 断裂感。留一小段空白，冲击力全在那个落差上。
     */
    val burstAmp: Float = 1f,
    val burstMs: Int = 0,
    val burstGapMs: Int = 45,

    /**
     * 振幅地板：渲染时任何非零振幅不低于这个值（0..1）。
     *
     * LRA/ERM 马达有起振阈值，振幅太低时马达根本没转起来。若「渐强」的
     * 起点低于阈值，前几个脉冲完全摸不到，表现为「从中间突然开始震」——
     * 蓄力的前半段等于白做。
     *
     * 所以渐强的起点应当是「弱但摸得到」，而不是「几乎为零」。
     * 具体数值依赖机型，留给实验室调。
     */
    val minAmp: Float = 0.2f,
) {

    /**
     * 离散成 `VibrationEffect.createWaveform(timings, amplitudes, -1)` 的两个数组。
     *
     * 返回的两个数组**等长**，一一对应。首元素是一段 0 振幅的 0ms 占位，
     * 好让「第一个脉冲立刻开始」——`createWaveform` 的语义是
     * timings[i] 这段时间里保持 amplitudes[i] 的振幅，没有隐含的起始静默。
     */
    fun render(): HapticWaveform {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()

        val count = pulses.coerceAtLeast(1)
        // 间隔比脉冲少一个，所以它有**自己的**进度基准。
        // 早先两者共用 `i/(count-1)`，于是最后一段间隔只取到 (count-2)/(count-1)，
        // 永远到不了 endGapMs——节奏压缩在最该收紧的地方戛然而止，
        // 迸发前的蓄力少了最急的那一截。曲率越大这个缺口越明显
        // （gapCurve=1.9 时 0.8^1.9≈0.66，最后一段间隔差了四倍）。
        val gapCount = count - 1
        for (i in 0 until count) {
            // 单脉冲时 t 取 0，即完全落在「起点」档上；多脉冲时均匀铺满 0..1。
            val t = if (count == 1) 0f else i.toFloat() / (count - 1)

            timings += pulseMs.coerceAtLeast(MIN_PULSE_MS).toLong()
            amplitudes += toAmplitude(lerp(startAmp, endAmp, curve(t, ampCurve)))

            // 最后一个脉冲后面不接间隔：后面要么是迸发前的静默，要么就结束了。
            if (i < gapCount) {
                val gapT = if (gapCount == 1) 1f else i.toFloat() / (gapCount - 1)
                val gap = lerp(
                    startGapMs.toFloat(),
                    endGapMs.toFloat(),
                    curve(gapT, gapCurve),
                ).roundToInt().coerceAtLeast(0)
                if (gap > 0) {
                    timings += gap.toLong()
                    amplitudes += 0
                }
            }
        }

        if (burstMs > 0) {
            if (burstGapMs > 0) {
                timings += burstGapMs.toLong()
                amplitudes += 0
            }
            timings += burstMs.toLong()
            amplitudes += toAmplitude(burstAmp)
        }

        return HapticWaveform(timings.toLongArray(), amplitudes.toIntArray())
    }

    /** 整段振动的总时长（毫秒）。实验室用来显示，也用于断言不至于长得离谱。 */
    fun totalDurationMs(): Long = render().timings.sum()

    /**
     * 把 0..1 的振幅映射到 `createWaveform` 要的 1..255。
     *
     * 地板在这里生效而不是在参数上：参数保持「用户设了多少就是多少」，
     * 渲染时才夹紧，这样实验室里把 [minAmp] 调到 0 就能直接对比
     * 「有没有地板」的差别。
     */
    private fun toAmplitude(raw: Float): Int {
        val floored = raw.coerceAtLeast(minAmp).coerceIn(0f, 1f)
        return (floored * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE)
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /**
     * 把线性进度 [t] 按曲率 [c] 弯曲。
     *
     * c = 1 时原样返回；c > 1 时前段平缓、后段陡峭（即「后段变化更剧烈」）。
     * 用幂函数而非贝塞尔：这里只需要单调的单参数弯曲，幂函数足够且可直接
     * 在测试里复算。
     */
    private fun curve(t: Float, c: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        if (abs(c - 1f) < 1e-4f) return clamped
        return clamped.pow(c.coerceAtLeast(0.05f))
    }

    companion object {
        /** `createWaveform` 的振幅上限。 */
        const val MAX_AMPLITUDE = 255

        /**
         * 脉冲宽度下限（毫秒）。
         *
         * 马达有启动惯性，一个过短的脉冲在低振幅下可能根本没转起来就结束，
         * 表现为「前几下摸不到」。系统的 `mRampStepDurationMs=5` 是控制
         * 分辨率，但**可控 ≠ 摸得到**，所以这里的下限远高于 5ms。
         */
        const val MIN_PULSE_MS = 8
    }
}

/**
 * [HapticSpec.render] 的产物，直接喂给 `VibrationEffect.createWaveform`。
 *
 * 单独定义而不是返回 `Pair`：两个数组的语义（时长 / 振幅）靠位置区分
 * 太容易传反，而传反了在真机上只表现为「震得怪」，很难定位。
 */
data class HapticWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
) {
    // data class 对数组默认用引用比较，测试里断言相等会失败，故手写。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HapticWaveform) return false
        return timings.contentEquals(other.timings) &&
            amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int = 31 * timings.contentHashCode() + amplitudes.contentHashCode()
}

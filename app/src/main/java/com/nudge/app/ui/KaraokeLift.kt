package com.nudge.app.ui

import com.nudge.app.lyrics.LyricWord

/**
 * 逐字升起的几何参数。
 *
 * 从 [LyricsAnimSpec] 里摘出来单独成一个数据类，是为了让 [KaraokeLift]
 * 保持「只依赖它真正需要的四个值」——那边是纯函数，参数面越小越好测，
 * 也不必因为 [LyricsAnimSpec] 加了个不相干的字段就跟着变。
 */
data class KaraokeLiftSpec(
    /**
     * 升起相对扫光的**时间差**（毫秒）。
     *
     * 这是整个效果的因果所在：光先扫过这个词，隔这么久字才被「提起来」。
     * 为 0 时两件事同时发生，观感退化成「字自己在动」，
     * 「光把词提上来」的那层关系就读不出来了。
     */
    val delayMs: Int,

    /** 单个字符从基线升到保持高度所用的时长（毫秒）。 */
    val riseMs: Int,

    /**
     * 升起途中的**过冲峰值**（dp），必须 ≥ [holdDp]。
     *
     * 峰值与保持高度之间那道落差就是用户看到的「上下摆动一下」。
     * 两者相等即没有过冲，动效会退化成单调升到位，显得死板。
     */
    val peakDp: Float,

    /**
     * 升起完成后**保持**的高度（dp）。
     *
     * 刻意不回落到 0：已唱的字留在抬高的位置，被光提起来就留在上面。
     * 整行的落回交给渲染侧在**换行**时统一做（见 `LyricsOverlay` 的
     * liftScale），而不是每个字符自己落——后者会让一行里的字此起彼伏，
     * 像在抖。
     */
    val holdDp: Float,
)

/**
 * 某一帧的逐字状态：扫光揭示到哪了、各字符升起多少。
 *
 * 两者打包成一个 state 而不是各持一个，是为了让它们**同源**：
 * 各建一条 `withFrameNanos` 循环的话，两条循环的帧时间会有半帧错位，
 * 而「光先走、字后起」的那个时间差正是靠同源才精确的。
 */
data class KaraokeFrame(
    val reveal: KaraokeReveal,
    /** 字符下标 → 升起位移（dp）。越界返回 0，见 [KaraokeLift.liftAt]。 */
    val lift: (Int) -> Float,
) {
    companion object {
        val EMPTY = KaraokeFrame(KaraokeReveal(0, 0, 0, 0f), { 0f })
    }
}

/**
 * 逐字升起：光扫过之后，字符依次被「提」起来并留在高处。
 *
 * ## 为什么是纯时间函数
 *
 * 「这个字符被唱到之后过了多久」完全由 `positionMs` 与它所属词的
 * `startMs` 决定，不需要任何持久状态。于是暂停时天然定格、拖进度条
 * 直接跳到正确形态、换歌不必清理——与 [KaraokeProgress] 同一个口径。
 *
 * 早先设计时以为「留在高处」必须维护每个字符的状态（因为终值不为 0），
 * 那是错的：终值不为 0 只是把曲线的末端抬高，与有没有状态无关。
 *
 * ## 单位是词，相位差在词内
 *
 * 节奏锚在**词**上（与扫光完全一致，共用同一条时间轴），词内的字符
 * 再依次起跳。两个被否掉的极端：
 *
 * - **逐字符独立计时**：中文一个字比字母宽得多，同样的相位差在中文上
 *   是「一个字一个字蹦」而不是「一道波」。
 * - **整词一起升起**：没有「一个字母一个字母」那层细腻。
 *
 * 相位差**按词长自适应**（见 [staggerMsFor]），所以不需要额外参数。
 */
object KaraokeLift {

    /**
     * 整个词的起跳要在这个比例的词时长内铺完。
     *
     * 留出余量而不是铺满：铺满的话最后一个字符恰好在词唱完时才起跳，
     * 而那时光已经走到下一个词了，视觉上会掉队。
     */
    private const val STAGGER_SPAN_RATIO = 0.55f

    /** 单字符相位差的上限（毫秒）。短词不该把间隔拉得过大，否则像卡顿。 */
    private const val MAX_STAGGER_MS = 55f

    /**
     * [positionMs] 时刻各字符的升起位移（dp）。
     *
     * 返回 lambda 而不是数组：渲染侧按 `TextLayoutResult` 的字符数遍历，
     * 与 [words] 的字符总数**不一定相等**（折行、省略号），
     * 用下标查询才能安全地各取所需。越界一律返回 0。
     *
     * [words] 必须按 [LyricWord.startMs] 升序（`YrcParser` 保证）。
     */
    fun liftAt(
        words: List<LyricWord>,
        positionMs: Long,
        spec: KaraokeLiftSpec,
    ): (Int) -> Float {
        if (words.isEmpty() || spec.peakDp <= 0f && spec.holdDp <= 0f) return { 0f }

        // 预先把每个字符的**起跳时刻**摊平成一个数组：渲染侧每帧要对
        // 整行的字符各查一次，边查边找所属词是 O(字符数 × 词数)。
        // 这里一次遍历建表，查询降到 O(1)。
        val startTimes = LongArray(words.sumOf { it.text.length })
        var cursor = 0
        for (word in words) {
            val len = word.text.length
            if (len == 0) continue
            val stagger = staggerMsFor(word, len)
            for (i in 0 until len) {
                startTimes[cursor + i] = word.startMs + spec.delayMs + (stagger * i).toLong()
            }
            cursor += len
        }

        val rise = spec.riseMs.coerceAtLeast(1)
        return fun(index: Int): Float {
            if (index < 0 || index >= startTimes.size) return 0f
            val elapsed = positionMs - startTimes[index]
            if (elapsed <= 0L) return 0f
            val p = (elapsed.toFloat() / rise).coerceIn(0f, 1f)
            return heightAt(p, spec)
        }
    }

    /**
     * 单字符相位差。**按词长自适应**：词越长间隔越小，
     * 保证整个词的起跳都落在它自己的演唱时长内。
     *
     * 零时长的词真机上存在（实测 Stay 里的 `(21060,0,0), `），
     * 此时 span 为 0、间隔取 0，整词同时起跳——那是正确的降级：
     * 一个瞬间唱完的词本来就没有铺开相位差的余地。
     */
    private fun staggerMsFor(word: LyricWord, len: Int): Float {
        if (len <= 1) return 0f
        val span = word.durationMs * STAGGER_SPAN_RATIO
        return (span / (len - 1)).coerceIn(0f, MAX_STAGGER_MS)
    }

    /**
     * 升起曲线：0 → 过冲到 [KaraokeLiftSpec.peakDp] → 回落并停在
     * [KaraokeLiftSpec.holdDp]。
     *
     * 分两段而不是用一条带回弹的缓动：回弹类缓动（如 easeOutBack）的
     * 过冲量由曲线本身定死，改不了，而这里**峰值与保持高度是两个独立
     * 参数**——实验室要能单独调「抬多高」和「摆多大」。
     *
     * 前段用 easeOutCubic 冲上去（起步就带速度，像被光「拽」了一下），
     * 后段用平滑收敛回落到保持高度。分界点靠前，让上冲干脆、回落舒缓。
     */
    private fun heightAt(p: Float, spec: KaraokeLiftSpec): Float {
        val peak = maxOf(spec.peakDp, spec.holdDp)
        if (p >= 1f) return spec.holdDp
        return if (p < PEAK_AT) {
            // 上冲段：easeOutCubic，起步最快
            val t = p / PEAK_AT
            peak * (1f - (1f - t) * (1f - t) * (1f - t))
        } else {
            // 回落段：smoothstep 收敛到 holdDp，两端导数为 0，
            // 与上冲段在峰值处衔接时不会出现折角。
            val t = (p - PEAK_AT) / (1f - PEAK_AT)
            val s = t * t * (3f - 2f * t)
            peak + (spec.holdDp - peak) * s
        }
    }

    /**
     * 峰值出现在升起时长的哪个位置。
     *
     * 取 0.45：略早于中点。上冲要干脆（那是「被提起来」的瞬间），
     * 回落要有足够时间铺开，否则摆动看着像抽搐。
     */
    private const val PEAK_AT = 0.45f
}

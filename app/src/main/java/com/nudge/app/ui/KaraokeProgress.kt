package com.nudge.app.ui

import com.nudge.app.lyrics.LyricWord

/**
 * 逐字扫光的进度：**以词为单位**，正在唱的那个词内部再做一次快速揭示。
 *
 * [sungChars] 之前的字符已完全点亮；[activeStart]..[activeEnd] 是**正在唱**
 * 的那个词的字符区间，[activeProgress]（0..1）是它揭示到哪了。
 *
 * ## 为什么不是匀速逐像素推进
 *
 * 匀速推进与「词」这个语义单位脱节：一个词的读法是整体的，光在词中间
 * 停着不动（词长中位数 450ms）看着像卡住。而整词一次点亮又是一跳一跳的。
 * 折中是**节奏锚在词上、每次点亮本身连续**——光在词内快速扫过，
 * 扫完就停在词尾等下一个词，这正是 Apple Music 的做法。
 */
data class KaraokeReveal(
    /** 已完全唱过的字符数（不含正在唱的那个词）。 */
    val sungChars: Int,
    /** 正在唱的词的起始字符索引；无正在唱的词时与 [activeEnd] 相等。 */
    val activeStart: Int,
    /** 正在唱的词的结束字符索引（不含）。 */
    val activeEnd: Int,
    /** 正在唱的词揭示到哪了，0..1。 */
    val activeProgress: Float,
)

/**
 * 抽成纯函数是为了能 JVM 单测：扫光的正确性肉眼只能看出「大致跟得上」，
 * 而「词内揭示会不会回退」「间隙里会不会继续推进」这类要靠断言钉住。
 */
object KaraokeProgress {

    /**
     * [positionMs] 时刻的揭示状态。
     *
     * 语义：
     * - 行首之前 → 什么都没唱
     * - 行尾之后 → 整行唱满
     * - 正在唱某个词 → 该词进入 active，按 [revealFraction] 做词内揭示
     * - 两个词的**间隙**里 → 前一个词保持全亮，不继续推进
     *
     * 最后一条是刻意的：间隙（换气、拖腔）里继续推进会让光跑到还没唱的词上。
     *
     * [words] 必须按 [LyricWord.startMs] 升序（`YrcParser` 保证）。
     */
    fun revealAt(
        words: List<LyricWord>,
        positionMs: Long,
        revealFraction: Float = 0.45f,
    ): KaraokeReveal {
        if (words.isEmpty()) return KaraokeReveal(0, 0, 0, 0f)
        if (positionMs <= words.first().startMs) return KaraokeReveal(0, 0, 0, 0f)

        var chars = 0
        for (word in words) {
            val len = word.text.length
            when {
                // 还没唱到这个词：停在已累计的位置（间隙里也走这一支）
                positionMs < word.startMs -> return KaraokeReveal(chars, chars, chars, 0f)

                positionMs < word.endMs -> {
                    // 时长为 0 的词真机上存在（如 `(21060,0,0), `），
                    // 会让除法炸掉；此时该词瞬间唱完
                    if (word.durationMs <= 0) {
                        chars += len
                        continue
                    }
                    val elapsed = (positionMs - word.startMs).toFloat()
                    val revealMs = word.durationMs * revealFraction.coerceIn(0f, 1f)
                    val p = if (revealMs <= 0f) 1f else (elapsed / revealMs).coerceIn(0f, 1f)
                    // 揭示完成后该词并入已唱，active 清空——这样词内揭示
                    // 结束到下一个词开始之间，光稳稳停在词尾。
                    return if (p >= 1f) {
                        KaraokeReveal(chars + len, chars + len, chars + len, 0f)
                    } else {
                        KaraokeReveal(chars, chars, chars + len, p)
                    }
                }

                else -> chars += len
            }
        }
        return KaraokeReveal(chars, chars, chars, 0f)
    }
}

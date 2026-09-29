package com.nudge.app.lyrics

/**
 * 一行歌词。[timeMs] 是这行开始演唱的时刻（相对歌曲开头）。
 *
 * [translation] 是中文译文，**可空**：网易云的译文按行给，实测普遍比原文
 * 少一两行（纯语气词、重复副歌往往不译），所以「这一行没有译文」是常态
 * 而非异常。渲染侧据此决定行高（见 `LyricsOverlay` 的 Row 抽象），
 * null 时该行只有原文、高度自然变矮。
 *
 * 刻意不含 Android 依赖：解析与查找的边界条件多，必须能在 JVM 上单测。
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    /**
     * 字级时间表，**可空**：网易云只对约四成的歌提供逐字歌词（`yrc`），
     * 真机抽样 12 首里 5 首有。null 是常态而非异常，渲染侧据此退化为
     * 整行高亮（见 `LyricsOverlay` 的扫光）。口径同 [translation]。
     *
     * 非空时保证：按 [LyricWord.startMs] 升序、拼接起来等于 [text]。
     */
    val words: List<LyricWord>? = null,
)

/**
 * 逐字歌词里的一个「字」。中文是单字，英文是单词（往往带尾随空格）。
 *
 * [startMs] 与 [LyricLine.timeMs] 同一时间轴，都是相对歌曲开头的绝对毫秒。
 *
 * **绝不能把 `yrc` 的字级时间表挂到 `lrc` 解析出的行上**：真机实测两者
 * 不在同一条时间轴上，按文本匹配后的偏差非常数，单曲内范围可达
 * [-523, +2000]ms（详见 `YrcParser` 的注释）。混用的表现是扫光跑在
 * 字的前面或后面，而那看着像动画没调好，不会被认成数据缺陷。
 */
data class LyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
) {
    /** 这个字唱完的时刻。 */
    val endMs: Long get() = startMs + durationMs
}

/**
 * 当前时刻应高亮的行索引，无则 -1。
 *
 * 前奏期间（位置早于首行）返回 -1 而非 0，避免第一行在没唱之前就亮着。
 * 列表已按时间升序（[LrcParser] 保证），用二分查找而非线性扫描——
 * 这个函数在滚动时会被高频调用。
 */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    if (isEmpty() || positionMs < first().timeMs) return -1

    var low = 0
    var high = size - 1
    var result = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

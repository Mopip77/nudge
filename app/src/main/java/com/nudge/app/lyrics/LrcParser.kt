package com.nudge.app.lyrics

/**
 * LRC 歌词解析。
 *
 * 刻意不含 Android 依赖，可在 JVM 上单测——真实 LRC 的边界情况很多
 * （毫秒位数不一、一行多时间戳、元信息行、空停顿行），靠真机手测不现实。
 */
object LrcParser {

    /**
     * 时间戳 `[mm:ss.SSS]`。毫秒部分可选且位数不定：
     * 网易云实测均为 3 位，但 LRC 标准允许 2 位，接口非公开故保持宽容。
     * 分隔符除 `.` 外也接受 `:`，某些歌词源会这么写。
     */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        for (line in raw.lineSequence()) {
            val tags = TIME_TAG.findAll(line).toList()
            // 无时间戳的行是元信息（[by:xxx]）或垃圾，直接丢弃
            if (tags.isEmpty()) continue

            // 文本是最后一个时间戳之后的部分：一行可能挂多个时间戳，
            // 表示同一句在多处重复出现
            val text = line.substring(tags.last().range.last + 1).trim()

            // 丢弃只有时间戳没有文字的行。网易云用这种行标记间奏留白，
            // 但它在 UI 上会占满一个行高却没有字，且一旦成为「当前行」，
            // 看起来就是整屏没有任何一句被点亮（实测「有没有」的 2:22 就是这种行）。
            //
            // 丢弃后 indexAt 自然回落到上一句，间奏期间保持上一句高亮。
            // 这不会影响其余行的高亮时机：每行都带绝对 timeMs，二分查找按时间定位，
            // 删掉一项不改变任何其他行的时间。
            if (text.isEmpty()) continue

            for (tag in tags) {
                val timeMs = toMillis(tag) ?: continue
                result.add(LyricLine(timeMs, text))
            }
        }

        // 一行多时间戳展开后顺序是乱的，且个别歌词源本身不保证有序，
        // 而 indexAt 的二分查找依赖升序
        return result.sortedBy { it.timeMs }
    }

    private fun toMillis(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]

        // 两位是百分秒（.76 = 760ms），三位才是毫秒，按位数补零而非直接相加
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes * 60_000 + seconds * 1000 + millis
    }
}

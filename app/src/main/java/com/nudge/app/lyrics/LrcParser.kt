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

    /**
     * 解析原文并挂上译文。[tlyric] 为 null／空时退化为 [parse]。
     *
     * **按时间戳匹配，绝不能按下标 zip**：两边行数普遍不等。真机实测
     * 网易云同一首《Yesterday》的四个版本，原文/译文行数分别是
     * 18/17、20/18、27/26、17/18——译文多数时候少一两行（纯语气词、
     * 重复的副歌往往不译），**偶尔还会多**（末位 17/18 那个）。
     * 按下标对齐会从第一个缺口起整体错位，把译文挂到后面的句子上，
     * 而这在界面上看着「像是翻译得不太准」，不会被认成缺陷。
     *
     * 匹配用**精确相等**而非就近容差：两边同源于网易云同一份时间轴，
     * 实测严格对齐。引入容差反而会在间奏附近误匹配到相邻句——
     * 那里原文的空行已被 [parse] 丢弃，最近的时间戳可能隔着好几秒。
     *
     * 译文里匹配不到原文的行直接丢弃：它们没有原文可挂靠。
     */
    fun parseWithTranslation(lrc: String, tlyric: String?): List<LyricLine> {
        val lines = parse(lrc)
        if (tlyric.isNullOrBlank()) return lines

        // 一行多时间戳被 parse 展开成多行，同一 timeMs 理论上只有一条；
        // 真出现重复取最后一条即可（associateBy 的语义），不影响正确性。
        val translations = parse(tlyric).associate { it.timeMs to it.text }
        if (translations.isEmpty()) return lines

        return lines.map { line ->
            // 译文与原文完全相同时不挂：网易云对不需要翻译的行（如英文歌名、
            // 拟声词）有时会原样回填一份，显示出来就是同一句话印两遍。
            val translated = translations[line.timeMs]?.takeIf { it != line.text }
            if (translated == null) line else line.copy(translation = translated)
        }
    }

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

package com.nudge.app.lyrics

/**
 * 网易云 `yrc`（逐字歌词）解析。
 *
 * 格式（全是**绝对毫秒**，与 `lrc` 的 `[mm:ss.SSS]` 不同）：
 *
 * ```
 * [11460,2730](11460,210,0)I (11670,180,0)do (11850,120,0)the ...
 *  行起  行长   字起  字长 ?
 * ```
 *
 * 括号里第三个数字**恒为 0**（真机 51 行样本零违例），用途不明，直接丢弃。
 *
 * 校验过的结构不变式（同样本）：字级起点单调递增、末字终点恰好等于
 * 行起 + 行长（误差 0）。解析侧仍做排序与兜底，因为接口非公开。
 *
 * ## 为什么不能把字级时间表挂到 lrc 的行上
 *
 * 真机实测 `yrc` 与 `lrc` **不在同一条时间轴上**：按时间戳精确匹配的
 * 命中率近乎为零（5 首样本，命中 1~8 行 / 约 50 行）；按文本匹配后量到的
 * 偏差**非常数**，中位数 −2 ~ −243ms 不等，单曲内范围可达 [−523, +2000]ms，
 * 无法靠一个补偿量修正。
 *
 * 但接口同时给了 `ytlrc`——它与 `yrc` **精确同轴**（实测命中 35/35，
 * 而 `tlyric` 对 `yrc` 命中 0/35）。即接口提供的是 `(lrc, tlyric)` 与
 * `(yrc, ytlrc)` 两套各自自洽的数据，**跨轴混用必然错位**。
 * 故 `LyricsRepository` 按整首歌粒度二选一，绝不合并。
 *
 * 刻意不含 Android 依赖，可 JVM 单测——理由同 [LrcParser]。
 */
object YrcParser {

    /** 行头 `[行起,行长]`，两个都是绝对毫秒。 */
    private val LINE_TAG = Regex("""^\[(\d+),(\d+)]""")

    /** 字级块 `(字起,字长,0)`，其后紧跟该字的文本。 */
    private val WORD_TAG = Regex("""\((\d+),(\d+),(\d+)\)""")

    /**
     * 解析原文并挂上译文。[ytlrc] 必须是 **`ytlrc`**（逐字版的译文），
     * 不能传 `tlyric`——后者与 `yrc` 不同轴，挂上去会整体错位。
     *
     * 译文本身是行级的普通 LRC 格式，故复用 [LrcParser.parse] 解析，
     * 再按时间戳**精确相等**匹配，口径同 [LrcParser.parseWithTranslation]。
     */
    fun parseWithTranslation(yrc: String, ytlrc: String?): List<LyricLine> {
        val lines = parse(yrc)
        if (ytlrc.isNullOrBlank()) return lines

        val translations = LrcParser.parse(ytlrc).associate { it.timeMs to it.text }
        if (translations.isEmpty()) return lines

        return lines.map { line ->
            // 译文与原文相同时不挂，理由同 LrcParser：接口有时会对不需要
            // 翻译的行原样回填一份，显示出来就是同一句话印两遍。
            val translated = translations[line.timeMs]?.takeIf { it != line.text }
            if (translated == null) line else line.copy(translation = translated)
        }
    }

    /** 解析失败、无逐字数据、只有元信息行时一律返回空列表，由调用方回落到 `lrc`。 */
    fun parse(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            val head = LINE_TAG.find(line) ?: continue
            val startMs = head.groupValues[1].toLongOrNull() ?: continue

            val body = line.substring(head.range.last + 1)
            val words = parseWords(body)

            // **单块的行是元信息**（`作词 : 唐恬`），不是歌词。
            //
            // 不能只按「时长为 0」判断：实测富士山下（4877111）的元信息行
            // 带的是 [0,1000]，时长非零。也不能按「冒号前是已知标签」判断：
            // 歌词里本来就可能出现冒号。
            //
            // 真实歌词行必然被逐字切成多块，而元信息整行是一块——这是
            // 最稳的判据，且不依赖任何文案约定（接口改版也不会变）。
            if (words.size < 2) continue

            val text = words.joinToString("") { it.text }
            // 只有时间戳没有文字的行要丢弃，理由同 LrcParser：它在 UI 上
            // 会占满一个行高却没有字，一旦成为当前行就是整屏没有一句被点亮。
            if (text.isBlank()) continue

            result.add(LyricLine(timeMs = startMs, text = text, words = words))
        }

        // 个别歌词源不保证有序，而 indexAt 的二分查找依赖升序
        return result.sortedBy { it.timeMs }
    }

    /**
     * 把 `(起,长,0)文本` 的序列切成字级列表。
     *
     * 末尾若还有不带标签的残余文本会被丢弃——它没有时间信息，
     * 扫光无从定位；实测不存在这种情况，这里只是不让它悄悄混进 [LyricLine.text]
     * （混进去会让拼接结果与字级时间表对不上，扫光的字符区间随之错位）。
     */
    private fun parseWords(body: String): List<LyricWord> {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()

        val words = mutableListOf<LyricWord>()
        for ((i, tag) in tags.withIndex()) {
            val start = tag.groupValues[1].toLongOrNull() ?: continue
            val duration = tag.groupValues[2].toLongOrNull() ?: continue
            // 文本是本标签之后、下一个标签之前的部分
            val from = tag.range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            if (from > to) continue
            words.add(LyricWord(start, duration, body.substring(from, to)))
        }

        // 起点单调是扫光按时间二分定位的前提，乱序会让光倒退
        return words.sortedBy { it.startMs }
    }
}

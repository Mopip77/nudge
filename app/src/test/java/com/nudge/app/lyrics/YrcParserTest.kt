package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `yrc` 逐字歌词解析的测试。
 *
 * 样本全部摘自真机实测的接口返回（歌曲 id 见各用例），不是构造的理想数据——
 * 这一格式没有公开文档，凭想象造的样本测不出真实的边界。
 */
class YrcParserTest {

    /** 摘自 Stay（1859245776），典型的英文逐字行。 */
    private val STAY_LINE =
        "[11460,2730](11460,210,0)I (11670,180,0)do (11850,120,0)the (11970,540,0)same " +
            "(12510,270,0)thing (12780,90,0)I (12870,270,0)told (13140,150,0)you " +
            "(13290,210,0)that (13500,90,0)I (13590,360,0)never (13950,240,0)would"

    @Test
    fun `解析出行的起止与字级时间表`() {
        val lines = YrcParser.parse(STAY_LINE)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(11460L, line.timeMs)
        assertEquals("I do the same thing I told you that I never would", line.text)

        val words = requireNotNull(line.words)
        assertEquals(12, words.size)
        assertEquals(LyricWord(11460L, 210L, "I "), words[0])
        assertEquals(LyricWord(13950L, 240L, "would"), words.last())
    }

    /**
     * 字级起点必须单调递增——扫光进度按它二分定位，乱序会让光倒退。
     * 真机 51 行样本里零违例，但接口非公开，解析侧仍要保证输出有序。
     */
    @Test
    fun `字级起点单调递增`() {
        val words = requireNotNull(YrcParser.parse(STAY_LINE)[0].words)
        words.zipWithNext { a, b ->
            assertTrue("字级起点必须单调：${a.startMs} -> ${b.startMs}", b.startMs >= a.startMs)
        }
    }

    /**
     * 末字终点应等于行起 + 行长。这条真机实测误差为 0（51 行样本），
     * 是扫光「唱完正好铺满整行」的前提。
     */
    @Test
    fun `末字终点与行时长一致`() {
        val line = YrcParser.parse(STAY_LINE)[0]
        val words = requireNotNull(line.words)
        val last = words.last()
        assertEquals(11460L + 2730L, last.startMs + last.durationMs)
    }

    /**
     * 行首的 `[0,0](0,0,0) 作词 : XXX` 是元信息，不是歌词。
     * 口径同 `LrcParser` 丢弃无时间戳行——留着会让第一行显示成制作人名单。
     *
     * 样本摘自孤勇者（1901371647），它开头有整整十行这种。
     */
    @Test
    fun `丢弃零时长的元信息行`() {
        val raw = """
            [0,0](0,0,0) 作词 : 唐恬
            [0,0](0,0,0) 作曲 : 钱雷
            $STAY_LINE
        """.trimIndent()

        val lines = YrcParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals(11460L, lines[0].timeMs)
    }

    /**
     * 富士山下（4877111）的元信息行带的是 `[0,1000]` 而非 `[0,0]`——
     * 时长非零，所以不能只按时长判断。靠「冒号前是已知的元信息标签」也不行
     * （歌词里本来就可能有冒号）。判据是**这一行只有一个字级块**：
     * 真实歌词行必然被切成多个块，而元信息整行是一块。
     */
    @Test
    fun `丢弃单块的元信息行_即便时长非零`() {
        val raw = """
            [0,1000](0,1000,0) 作词 : 林夕
            [1000,1000](1000,1000,0) 作曲 : 陈辉阳
            $STAY_LINE
        """.trimIndent()

        val lines = YrcParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals("I do the same thing I told you that I never would", lines[0].text)
    }

    /** 中文逐字：一个字一块，且不带空格。摘自程艾影（1974443815）。 */
    @Test
    fun `解析中文逐字行`() {
        val raw = "[41900,1680](41900,380,0)伍(42280,480,0)岚(42760,820,0)正"

        val line = YrcParser.parse(raw)[0]

        assertEquals("伍岚正", line.text)
        val words = requireNotNull(line.words)
        assertEquals(3, words.size)
        assertEquals("伍", words[0].text)
        assertEquals(41900L, words[0].startMs)
        assertEquals(380L, words[0].durationMs)
    }

    /** 多行按时间升序输出，口径同 `LrcParser`（indexAt 的二分依赖有序）。 */
    @Test
    fun `多行按时间升序`() {
        val raw = """
            [20000,500](20000,250,0)второй (20250,250,0)line
            [10000,500](10000,250,0)first (10250,250,0)line
        """.trimIndent()

        val lines = YrcParser.parse(raw)

        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].timeMs)
        assertEquals(20000L, lines[1].timeMs)
    }

    /** 空串、纯垃圾、只有元信息行——一律返回空列表而非抛异常。 */
    @Test
    fun `畸形输入返回空列表`() {
        assertTrue(YrcParser.parse("").isEmpty())
        assertTrue(YrcParser.parse("这不是 yrc").isEmpty())
        assertTrue(YrcParser.parse("[00:12.34]这是普通 LRC").isEmpty())
        assertTrue(YrcParser.parse("[0,0](0,0,0) 作词 : 某人").isEmpty())
    }

    /**
     * 整首歌只有元信息行时（纯音乐的 yrc 可能如此），要能被上层识别为
     * 「没有逐字数据」而回落到 lrc，而不是显示一屏制作人名单。
     */
    @Test
    fun `只有元信息时解析为空_便于上层回落`() {
        val raw = """
            [0,0](0,0,0) 作词 : A
            [0,0](0,0,0) 作曲 : B
        """.trimIndent()

        assertTrue(YrcParser.parse(raw).isEmpty())
    }

    /**
     * 译文按时间戳挂载，且**必须与 yrc 同轴**。
     *
     * 真机实测 ytlrc 对 yrc 命中 35/35，而 tlyric 对 yrc 命中 0/35——
     * 上层负责不把 tlyric 传进来，这里只验证同轴时能挂上。
     */
    @Test
    fun `按时间戳挂载同轴译文`() {
        val lines = YrcParser.parseWithTranslation(
            STAY_LINE,
            "[00:11.460]我曾说我有所不为 如今却出尔反尔",
        )

        assertEquals("我曾说我有所不为 如今却出尔反尔", lines[0].translation)
        // 挂译文不能破坏字级时间表
        assertEquals(12, requireNotNull(lines[0].words).size)
    }

    /** 译文时间戳对不上就不挂，而不是就近匹配——口径同 `LrcParser`。 */
    @Test
    fun `时间戳对不上的译文不挂载`() {
        val lines = YrcParser.parseWithTranslation(
            STAY_LINE,
            "[00:11.000]差了 460ms 的译文",
        )

        assertNull(lines[0].translation)
    }
}

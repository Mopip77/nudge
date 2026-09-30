package com.nudge.app.ui

import com.nudge.app.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字升起的位移。
 *
 * 同 `KaraokeProgressTest` 的口径，断言的是**结构性约束**而非具体数值：
 * 数值随观感调，但违反其中任何一条都会让动效退化成肉眼可见的错误
 * （字掉到基线以下、唱完的字高低不齐、长词最后一个字母迟到半秒）。
 */
class KaraokeLiftTest {

    /** "I do stay"：三个词，字符数 2 + 3 + 4 = 9。与扫光测试同一份样本。 */
    private val words = listOf(
        LyricWord(1000L, 200L, "I "),
        LyricWord(1200L, 300L, "do "),
        LyricWord(1500L, 500L, "stay"),
    )

    private val totalChars = words.sumOf { it.text.length }

    private val spec = KaraokeLiftSpec(
        delayMs = 90,
        riseMs = 260,
        peakDp = 5f,
        holdDp = 3f,
    )

    private fun liftAt(ms: Long) = KaraokeLift.liftAt(words, ms, spec)

    @Test
    fun `行首之前全部停在基线`() {
        val lift = liftAt(900L)
        for (i in 0 until totalChars) {
            assertEquals("字符 $i 不该提前起跳", 0f, lift(i), 0.0001f)
        }
    }

    /**
     * **升起必须滞后于扫光**，这是整个效果的因果：光先扫过，字才被提起来。
     * 时间差为 0 时两件事同时发生，「被光提起来」的观感就没有了。
     */
    @Test
    fun `升起滞后于词的起始时刻`() {
        // 第一个词起于 1000，延迟 90ms，所以 1000..1089 之间它还没动
        for (ms in 1000L..1089L step 10) {
            assertEquals("在 ${ms}ms 第一个字符不该已起跳", 0f, liftAt(ms)(0), 0.0001f)
        }
        assertTrue("过了延迟应开始升起", liftAt(1120L)(0) > 0f)
    }

    /**
     * **词内字符带相位差**：同一个词里，靠后的字符起跳更晚。
     * 没有相位差就是整词一起蹦，那是被否掉的形态之一。
     */
    @Test
    fun `词内靠后的字符起跳更晚`() {
        // "stay" 起于 1500，四个字符索引 5..8
        val lift = liftAt(1500L + spec.delayMs + 30L)
        for (i in 5 until 8) {
            assertTrue(
                "字符 $i 的升起应不小于字符 ${i + 1}（相位差方向写反会让波倒着走）",
                lift(i) >= lift(i + 1) - 0.0001f,
            )
        }
        assertTrue("首尾应当真的有差别，否则等于整词一起蹦", lift(5) > lift(8))
    }

    /**
     * **相位差按词长自适应**：长词不能让最后一个字母迟到太久，
     * 否则整个词的升起拖过了它自己的演唱时长，观感上光早走了字还在慢慢起。
     */
    @Test
    fun `长词的整体起跳仍在词时长内走完`() {
        val longWord = listOf(LyricWord(0L, 400L, "extraordinary"))
        val lift = KaraokeLift.liftAt(longWord, 400L + spec.delayMs.toLong(), spec)
        val last = "extraordinary".length - 1
        assertTrue(
            "词唱完时最后一个字符也该已经起跳（当前 ${lift(last)}）",
            lift(last) > 0f,
        )
    }

    /**
     * **终值恒等于 holdDp**：升起后留在高处（不是回落到基线）。
     * 这是 B 方案的核心——光把词提上来，它就留在那里，直到换行整行落回。
     */
    @Test
    fun `升起完成后停在保持高度`() {
        // 整行唱完很久之后，所有字符都该稳定在 holdDp
        for (ms in longArrayOf(3000L, 5000L, 99999L)) {
            val lift = liftAt(ms)
            for (i in 0 until totalChars) {
                assertEquals(
                    "在 ${ms}ms 字符 $i 应停在保持高度",
                    spec.holdDp,
                    lift(i),
                    0.0001f,
                )
            }
        }
    }

    /**
     * **全程不低于基线**：字只能往上走，不能往下掉。
     * 过冲曲线若把下界算错，字会先沉下去一下，看着像行距在抖。
     */
    @Test
    fun `全程不低于基线`() {
        for (ms in 900L..3000L step 3) {
            val lift = liftAt(ms)
            for (i in 0 until totalChars) {
                assertTrue("在 ${ms}ms 字符 $i 掉到基线以下：${lift(i)}", lift(i) >= -0.0001f)
            }
        }
    }

    /**
     * **峰值出现在中段**，且真的超过保持高度——这就是那个「摆动一下」。
     * 没有过冲就是单调升到位，动效会显得死板。
     */
    @Test
    fun `升起过程中有一次高过保持高度的过冲`() {
        var maxSeen = 0f
        // 第一个字符：起跳于 1000 + 90，升起 260ms
        for (ms in 1090L..1360L) {
            maxSeen = maxOf(maxSeen, liftAt(ms)(0))
        }
        assertTrue("峰值 $maxSeen 应超过保持高度 ${spec.holdDp}", maxSeen > spec.holdDp + 0.2f)
        assertTrue("峰值不该超过 peakDp", maxSeen <= spec.peakDp + 0.0001f)
    }

    /**
     * 时长为 0 的词真机上存在（实测 Stay 里的 `(21060,0,0), `），
     * 相位差按词长自适应时这里有个除法，不能炸成 NaN。
     */
    @Test
    fun `零时长的词不产生 NaN`() {
        val withZero = listOf(
            LyricWord(1000L, 200L, "stay"),
            LyricWord(1200L, 0L, ", "),
            LyricWord(1200L, 300L, "hey"),
        )
        for (ms in 900L..1800L step 7) {
            val lift = KaraokeLift.liftAt(withZero, ms, spec)
            for (i in 0 until 9) {
                assertTrue("在 ${ms}ms 字符 $i 非有限值", lift(i).isFinite())
            }
        }
    }

    @Test
    fun `默认曲线起步柔和且相邻中文字上升重叠`() {
        val defaults = LyricsAnimSpec.DEFAULT.karaokeLift
        val chinese = listOf(
            LyricWord(1000L, 300L, "你"),
            LyricWord(1300L, 300L, "好"),
        )
        val start = 1000L + defaults.delayMs
        fun height(ms: Long, index: Int) = KaraokeLift.liftAt(chinese, ms, defaults)(index)

        // 第一帧不应猛冲：16ms 内的位移远小于中段一帧。
        val firstFrame = height(start + 16, 0)
        val middleFrame = height(start + 216, 0) - height(start + 200, 0)
        assertTrue("起步不能像弹跳一样突然冲出", firstFrame < middleFrame * 0.1f)

        // 第二个字已经移动时，第一个字仍在上升，而非先落定再轮到下一个。
        val overlap = start + 380
        for (index in 0..1) {
            assertTrue("相邻中文字应同时向上移动", height(overlap + 16, index) > height(overlap, index))
        }
        var maximum = 0f
        for (elapsed in 0..defaults.riseMs) {
            maximum = maxOf(maximum, height(start + elapsed, 0))
        }
        assertTrue("默认回弹不应超过保持高度的 15%", maximum <= defaults.holdDp * 1.15f)
    }

    @Test
    fun `空时间表恒为零`() {
        val lift = KaraokeLift.liftAt(emptyList(), 1234L, spec)
        assertEquals(0f, lift(0), 0.0001f)
        assertEquals(0f, lift(5), 0.0001f)
    }

    /** 越界索引不能抛异常：渲染侧按 TextLayoutResult 的字符数遍历，两者可能不等长。 */
    @Test
    fun `越界索引返回零而不抛异常`() {
        val lift = liftAt(2000L)
        assertEquals(0f, lift(-1), 0.0001f)
        assertEquals(0f, lift(totalChars + 10), 0.0001f)
    }

    /**
     * **关掉时恒为 0**：peakDp 与 holdDp 都为 0 应当完全没有位移，
     * 让实验室能做「开/关」对照，也让渲染侧可以据此跳过逐字符绘制。
     */
    @Test
    fun `高度全为零时恒不位移`() {
        val off = spec.copy(peakDp = 0f, holdDp = 0f)
        for (ms in 900L..3000L step 11) {
            val lift = KaraokeLift.liftAt(words, ms, off)
            for (i in 0 until totalChars) {
                assertEquals(0f, lift(i), 0.0001f)
            }
        }
    }
}

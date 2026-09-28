package com.nudge.app.ui

import com.nudge.app.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫光揭示状态。
 *
 * 断言的是**结构性约束**（单调、不越界、间隙里不前进、词内揭示能走完），
 * 而不是具体数值——数值随字级时间表变，但违反其中任何一条都会让扫光
 * 出现肉眼可见的错误（光倒退、光跑到还没唱的词上、词点亮不全）。
 */
class KaraokeProgressTest {

    /** "I do stay"：三个词，字符数 2 + 3 + 4 = 9。 */
    private val words = listOf(
        LyricWord(1000L, 200L, "I "),
        LyricWord(1200L, 300L, "do "),
        LyricWord(1500L, 500L, "stay"),
    )

    private val totalChars = words.sumOf { it.text.length }

    /** 已点亮的字符数：已唱满的 + 正在揭示的那部分。用于单调性断言。 */
    private fun litAt(ms: Long): Float {
        val r = KaraokeProgress.revealAt(words, ms)
        return r.sungChars + (r.activeEnd - r.activeStart) * r.activeProgress
    }

    @Test
    fun `行首之前什么都没唱`() {
        val r = KaraokeProgress.revealAt(words, 999L)
        assertEquals(0, r.sungChars)
        assertEquals(0f, r.activeProgress, 0.001f)
    }

    @Test
    fun `行尾之后整行唱满`() {
        assertEquals(totalChars, KaraokeProgress.revealAt(words, 2000L).sungChars)
        assertEquals(totalChars, KaraokeProgress.revealAt(words, 99999L).sungChars)
    }

    /**
     * **词内揭示**：进入一个词时 active 指向它的字符区间，
     * 进度从 0 推到 1，而不是整词一次点亮。
     */
    @Test
    fun `正在唱的词处于揭示中`() {
        // 第三个词 "stay" 起于 1500，时长 500，揭示占 45% 即 225ms
        val r = KaraokeProgress.revealAt(words, 1500L + 100L)
        assertEquals("已唱满前两个词", 5, r.sungChars)
        assertEquals("active 指向 stay", 5, r.activeStart)
        assertEquals(9, r.activeEnd)
        assertTrue("揭示进行中：${r.activeProgress}", r.activeProgress > 0f && r.activeProgress < 1f)
    }

    /**
     * 揭示走完后该词并入已唱、active 清空——于是词内揭示结束到下一个词
     * 开始之间，光**稳稳停在词尾**，而不是继续匀速爬。这正是「按词」
     * 与「逐像素匀速」的分界。
     */
    @Test
    fun `揭示走完后停在词尾`() {
        // 揭示段是 1500..1725，之后到 2000 都该是停住的
        for (ms in 1750L..1990L step 20) {
            val r = KaraokeProgress.revealAt(words, ms)
            assertEquals("在 ${ms}ms 应已并入已唱", totalChars, r.sungChars)
            assertEquals("不该还有 active", r.activeStart, r.activeEnd)
        }
    }

    /**
     * **间隙里不前进**。换气、拖腔会留下数百毫秒的空档，
     * 此时继续推进会让光跑到还没开始唱的词上。
     */
    @Test
    fun `词间隙内停在前一个词的末尾`() {
        val gapped = listOf(
            LyricWord(1000L, 200L, "I "),
            // 1200~2000 是间隙
            LyricWord(2000L, 300L, "do "),
        )

        for (ms in longArrayOf(1200L, 1600L, 1999L)) {
            val r = KaraokeProgress.revealAt(gapped, ms)
            assertEquals("在 ${ms}ms 应停在 2", 2, r.sungChars)
            assertEquals("间隙里不该有 active", r.activeStart, r.activeEnd)
        }
    }

    /** 全程单调不回退——光倒退是最刺眼的缺陷。 */
    @Test
    fun `全程单调不回退`() {
        var prev = -1f
        for (ms in 900L..2100L) {
            val now = litAt(ms)
            assertTrue("在 ${ms}ms 处回退：$prev -> $now", now >= prev - 0.0001f)
            prev = now
        }
    }

    @Test
    fun `全程不越界`() {
        for (ms in 0L..3000L step 7) {
            val r = KaraokeProgress.revealAt(words, ms)
            assertTrue(r.sungChars in 0..totalChars)
            assertTrue(r.activeStart in 0..totalChars)
            assertTrue(r.activeEnd in 0..totalChars)
            assertTrue(r.activeEnd >= r.activeStart)
            assertTrue(r.activeProgress in 0f..1f)
        }
    }

    /** 每个词都必须**完整**点亮，不能有词被跳过（跳词在屏幕上一眼可见）。 */
    @Test
    fun `每个词最终都会被完整点亮`() {
        for (w in words) {
            val r = KaraokeProgress.revealAt(words, w.endMs)
            val expected = words.takeWhile { it.startMs <= w.startMs }.sumOf { it.text.length }
            assertEquals("词 ${w.text.trim()} 唱完时应已并入已唱", expected, r.sungChars)
        }
    }

    /**
     * 时长为 0 的词真机上存在（实测 Stay 里的 `(21060,0,0), `），
     * 不能让它把除法炸成 NaN，也不能让它卡住后面的词。
     */
    @Test
    fun `零时长的词不产生 NaN 也不卡住后续`() {
        val withZero = listOf(
            LyricWord(1000L, 200L, "stay"),
            LyricWord(1200L, 0L, ", "),
            LyricWord(1200L, 300L, "hey"),
        )

        for (ms in 900L..1600L) {
            val r = KaraokeProgress.revealAt(withZero, ms)
            assertTrue("在 ${ms}ms 处非有限值", r.activeProgress.isFinite())
        }
        assertEquals(9, KaraokeProgress.revealAt(withZero, 1600L).sungChars)
    }

    @Test
    fun `空时间表恒为零`() {
        val r = KaraokeProgress.revealAt(emptyList(), 1234L)
        assertEquals(0, r.sungChars)
        assertEquals(0f, r.activeProgress, 0.001f)
    }
}

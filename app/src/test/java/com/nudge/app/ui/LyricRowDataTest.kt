package com.nudge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 的标称高度。锚点与窗口补偿都按它累加，**算错就会让整列在换行时
 * 先跳一帧再被动画拖回来**。
 *
 * 这个缺陷在代码里看不出来（"一行译文就算两行高"听着很合理），
 * 肉眼也几乎看不出（跳变只持续一帧，紧接着就是正常的 400ms 平滑位移），
 * 真机上是逐帧互相关量出来的：单帧跳变 82~84px，而行高 136px、
 * 标准是不超过 10%。所以抽成纯函数钉住。
 */
class LyricRowDataTest {

    @Test
    fun `无译文的行就是一个行高`() {
        val row = LyricRowData("original line")
        assertEquals(
            LyricRowData.LINE_HEIGHT_DP,
            row.nominalHeightDp(),
            0.01f,
        )
    }

    @Test
    fun `带译文的行不是整两倍行高`() {
        // 这是整个缺陷的核心：按 2 倍算会高估约半个行高。
        val row = LyricRowData("original line", "译文")
        val twoLines = LyricRowData.LINE_HEIGHT_DP * 2

        assertTrue(
            "带译文的行被算成了整两倍行高，锚点会偏高，换行时整列会跳一帧",
            row.nominalHeightDp() < twoLines,
        )
    }

    @Test
    fun `带译文的行等于原文加间距加译文`() {
        val row = LyricRowData("original line", "译文")
        assertEquals(
            LyricRowData.LINE_HEIGHT_DP +
                LyricRowData.TRANSLATION_GAP_DP +
                LyricRowData.TRANSLATION_LINE_DP,
            row.nominalHeightDp(),
            0.01f,
        )
    }

    @Test
    fun `带译文的行高于不带的`() {
        val plain = LyricRowData("original line")
        val translated = LyricRowData("original line", "译文")
        assertTrue(translated.nominalHeightDp() > plain.nominalHeightDp())
    }

    @Test
    fun `译文为空串时按无译文处理`() {
        // 空串不该撑开一个空的行高。解析侧已保证空行被丢弃成 null，
        // 这里是第二道防线。
        val row = LyricRowData("original line", null)
        assertEquals(LyricRowData.LINE_HEIGHT_DP, row.nominalHeightDp(), 0.01f)
    }

    @Test
    fun `像素换算按 density 缩放`() {
        val row = LyricRowData("original line")
        assertEquals(
            LyricRowData.LINE_HEIGHT_DP * 2.625f,
            row.nominalHeightPx(2.625f),
            0.01f,
        )
    }

    @Test
    fun `译文高度不能大于原文行高`() {
        // 译文字号比原文小一档，它占的高度理应也更小。
        // 若将来有人把译文字号调大到超过原文，这条会拦住——
        // 那会让译文喧宾夺主，与「译文从属于原文」的设计相悖。
        assertTrue(
            "译文占的高度不应超过原文行高",
            LyricRowData.TRANSLATION_LINE_DP < LyricRowData.LINE_HEIGHT_DP,
        )
    }

    @Test
    fun `一整列的累计高度随带译文的行数增长`() {
        // 锚点就是这么累加的。混合列（部分行有译文）是真实形态——
        // 网易云的译文普遍比原文少一两行。
        val mixed = listOf(
            LyricRowData("a", "甲"),
            LyricRowData("b"),
            LyricRowData("c", "丙"),
        )
        val total = mixed.sumOf { it.nominalHeightDp().toDouble() }.toFloat()
        val allPlain = LyricRowData.LINE_HEIGHT_DP * 3
        val allTranslated = mixed.size *
            (LyricRowData.LINE_HEIGHT_DP + LyricRowData.TRANSLATION_GAP_DP +
                LyricRowData.TRANSLATION_LINE_DP)

        assertTrue("混合列应高于全无译文", total > allPlain)
        assertTrue("混合列应低于全有译文", total < allTranslated)
    }
}

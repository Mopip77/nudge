package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 译文挂载（[LrcParser.parseWithTranslation]）的边界。
 *
 * 这些条件在真机上极难复现——要专门找一首「译文比原文多一行」的歌，
 * 而缺陷的表现（译文整体错位一行）看着只像是「翻译得不准」，
 * 不会被认成缺陷。所以全部收敛到纯函数单测。
 */
class LrcTranslationTest {

    @Test
    fun `译文按时间戳挂到对应行`() {
        val lrc = "[00:10.000]first line\n[00:20.000]second line"
        val tlyric = "[00:10.000]第一行\n[00:20.000]第二行"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertEquals(2, result.size)
        assertEquals("第一行", result[0].translation)
        assertEquals("第二行", result[1].translation)
    }

    @Test
    fun `译文缺行时该行译文为 null 且其余行不错位`() {
        // 真实形态：译文比原文少一行。按下标 zip 会让第 2 行之后全部错位，
        // 把「第三行」挂到 second line 上。
        val lrc = "[00:10.000]first\n[00:20.000]second\n[00:30.000]third"
        val tlyric = "[00:10.000]第一行\n[00:30.000]第三行"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertEquals(3, result.size)
        assertEquals("第一行", result[0].translation)
        assertNull(result[1].translation)
        assertEquals("第三行", result[2].translation)
    }

    @Test
    fun `译文多出的行被丢弃不影响原文行数`() {
        // 实测确实存在译文行数 > 原文的情况（Yesterday 某版本 17/18）。
        // 多出来的译文没有原文可挂靠，直接丢弃。
        val lrc = "[00:10.000]only line"
        val tlyric = "[00:10.000]唯一一行\n[00:25.000]多出来的译文"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertEquals(1, result.size)
        assertEquals("唯一一行", result[0].translation)
    }

    @Test
    fun `时间戳对不上时不做就近匹配`() {
        // 差 500ms 也不挂：两边同源于同一份时间轴，实测严格对齐。
        // 引入容差会在间奏附近误匹配到相邻句。
        val lrc = "[00:10.000]line"
        val tlyric = "[00:10.500]译文"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertNull(result[0].translation)
    }

    @Test
    fun `译文与原文相同时不挂载`() {
        // 网易云对不需要翻译的行有时原样回填，挂上去就是同一句印两遍
        val lrc = "[00:10.000]Hello\n[00:20.000]World"
        val tlyric = "[00:10.000]Hello\n[00:20.000]世界"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertNull(result[0].translation)
        assertEquals("世界", result[1].translation)
    }

    @Test
    fun `无译文时退化为纯原文解析`() {
        val lrc = "[00:10.000]line one\n[00:20.000]line two"

        for (empty in listOf(null, "", "   ")) {
            val result = LrcParser.parseWithTranslation(lrc, empty)
            assertEquals(2, result.size)
            assertEquals(listOf(null, null), result.map { it.translation })
        }
    }

    @Test
    fun `译文只有元信息行时退化为纯原文`() {
        // tlyric 非空但解析后没有任何有效行（version=0 的歌常是这种）
        val lrc = "[00:10.000]line"
        val result = LrcParser.parseWithTranslation(lrc, "[by:someone]")

        assertEquals(1, result.size)
        assertNull(result[0].translation)
    }

    @Test
    fun `译文的空行被丢弃不会挂成空字符串`() {
        // 空行在 parse 阶段就被丢弃，所以这里拿到的是 null 而非 ""。
        // 挂成空串会让渲染侧以为「有译文」而撑开一个空的行高。
        val lrc = "[00:10.000]line one\n[00:20.000]line two"
        val tlyric = "[00:10.000]\n[00:20.000]第二行"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertNull(result[0].translation)
        assertEquals("第二行", result[1].translation)
    }

    @Test
    fun `原文一行多时间戳时各处都挂上译文`() {
        // 重复副歌：原文一行挂两个时间戳，parse 展开成两行。
        // 译文若同样展开，两处都应挂上。
        val lrc = "[00:10.000][01:00.000]chorus"
        val tlyric = "[00:10.000][01:00.000]副歌"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertEquals(2, result.size)
        assertEquals(listOf("副歌", "副歌"), result.map { it.translation })
    }

    @Test
    fun `结果仍按时间升序`() {
        val lrc = "[00:30.000]third\n[00:10.000]first\n[00:20.000]second"
        val tlyric = "[00:20.000]第二\n[00:10.000]第一"

        val result = LrcParser.parseWithTranslation(lrc, tlyric)

        assertEquals(listOf(10_000L, 20_000L, 30_000L), result.map { it.timeMs })
        assertEquals(listOf("第一", "第二", null), result.map { it.translation })
    }

    @Test
    fun `畸形译文不抛异常`() {
        val lrc = "[00:10.000]line"
        for (bad in listOf("[00:10.000 缺右括号", "没有时间戳", "[aa:bb.ccc]非数字")) {
            val result = LrcParser.parseWithTranslation(lrc, bad)
            assertEquals(1, result.size)
            assertNull(result[0].translation)
        }
    }
}

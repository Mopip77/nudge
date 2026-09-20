package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `解析标准三位毫秒`() {
        val result = LrcParser.parse("[00:21.762]素胚勾勒出青花笔锋浓转淡")
        assertEquals(1, result.size)
        assertEquals(21762L, result[0].timeMs)
        assertEquals("素胚勾勒出青花笔锋浓转淡", result[0].text)
    }

    @Test
    fun `解析两位毫秒按百分秒换算`() {
        val result = LrcParser.parse("[00:21.76]测试")
        assertEquals(21760L, result[0].timeMs)
    }

    @Test
    fun `解析无毫秒部分`() {
        val result = LrcParser.parse("[01:05]测试")
        assertEquals(65_000L, result[0].timeMs)
    }

    @Test
    fun `分钟数正确换算`() {
        val result = LrcParser.parse("[02:03.500]测试")
        assertEquals(123_500L, result[0].timeMs)
    }

    @Test
    fun `元信息行被忽略`() {
        val raw = """
            [by:Lvemiwxq]
            [00:10.000]真正的歌词
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(1, result.size)
        assertEquals("真正的歌词", result[0].text)
    }

    @Test
    fun `一行多时间戳展开为多行`() {
        val result = LrcParser.parse("[00:10.000][01:20.000]重复的副歌")
        assertEquals(2, result.size)
        assertEquals(10_000L, result[0].timeMs)
        assertEquals(80_000L, result[1].timeMs)
        assertTrue(result.all { it.text == "重复的副歌" })
    }

    @Test
    fun `空文本时间戳行保留`() {
        // 空行代表停顿留白，丢弃会让后续行高亮时机错位
        val raw = """
            [00:03.000]
            [00:10.000]歌词
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(2, result.size)
        assertEquals("", result[0].text)
    }

    @Test
    fun `结果按时间升序排列`() {
        val raw = """
            [00:30.000]第三
            [00:10.000]第一
            [00:20.000]第二
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), result.map { it.timeMs })
        assertEquals(listOf("第一", "第二", "第三"), result.map { it.text })
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   \n  \n ").isEmpty())
    }

    @Test
    fun `纯元信息无歌词返回空列表`() {
        assertTrue(LrcParser.parse("[by:someone]\n[ar:artist]").isEmpty())
    }

    @Test
    fun `畸形输入不抛异常`() {
        assertTrue(LrcParser.parse("[00:10.000 缺右括号").isEmpty())
        assertTrue(LrcParser.parse("[aa:bb.ccc]非数字").isEmpty())
        assertTrue(LrcParser.parse("没有任何时间戳的纯文本").isEmpty())
    }

    @Test
    fun `歌词文本两端空白被裁剪`() {
        val result = LrcParser.parse("[00:10.000]  有空格的歌词  ")
        assertEquals("有空格的歌词", result[0].text)
    }

    @Test
    fun `真实网易云片段完整解析`() {
        // 取自真机实测 id=185811《发如雪》的实际返回
        val raw = """
            [00:00.000] 作词 : 方文山
            [00:03.000]
            [00:21.762]素胚勾勒出青花笔锋浓转淡
            [00:26.224]瓶身描绘的牡丹一如你初妆
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(4, result.size)
        assertEquals("作词 : 方文山", result[0].text)
        assertEquals("", result[1].text)
        assertEquals(21762L, result[2].timeMs)
    }
}

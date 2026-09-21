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
    fun `空文本时间戳行被丢弃`() {
        // 空行在 UI 上会占一个行高却没有字，且它一旦成为「当前行」，
        // 看起来就是"整屏没有任何一句被点亮"。丢弃后 indexAt 自然回落到
        // 上一句，间奏期间保持上一句高亮。
        //
        // 丢弃不会让后续行的高亮时机错位：每行都带绝对 timeMs，
        // indexAt 按时间二分，删掉一项不影响其余行的时间。
        val raw = """
            [00:03.000]
            [00:10.000]歌词
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(1, result.size)
        assertEquals("歌词", result[0].text)
    }

    @Test
    fun `只有空白字符的行也被丢弃`() {
        val result = LrcParser.parse("[00:03.000]   \n[00:10.000]歌词")
        assertEquals(1, result.size)
        assertEquals("歌词", result[0].text)
    }

    @Test
    fun `间奏空行丢弃后高亮回落到上一句`() {
        // 真实回归用例：网易云「有没有」(id=160023) 的 2:22 是一个空行，
        // 夹在 2:18 和 2:27 两句之间。修复前播放到 2:24 会高亮这个空行，
        // 表现为歌词区空出一片且没有任何一句被点亮。
        val raw = """
            [02:18.000]真的当 是误会一场
            [02:22.000]
            [02:27.000]你 有没有爱过我 有没有想过我
        """.trimIndent()
        val lines = LrcParser.parse(raw)

        assertEquals(2, lines.size)
        // 2:24 落在空行原本的位置，应仍然高亮 2:18 那句
        assertEquals("真的当 是误会一场", lines[lines.indexAt(144_000)].text)
        // 到 2:27 才切到下一句
        assertEquals("你 有没有爱过我 有没有想过我", lines[lines.indexAt(147_000)].text)
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
        // [00:03.000] 是空行，已被丢弃，只剩 3 行
        assertEquals(3, result.size)
        assertEquals("作词 : 方文山", result[0].text)
        assertEquals(21762L, result[1].timeMs)
        assertEquals("素胚勾勒出青花笔锋浓转淡", result[1].text)
    }
}

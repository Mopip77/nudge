package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricLineTest {

    private val lines = listOf(
        LyricLine(1000, "第一行"),
        LyricLine(3000, "第二行"),
        LyricLine(5000, "第三行"),
    )

    @Test
    fun `位置早于首行返回 -1`() {
        assertEquals(-1, lines.indexAt(0))
        assertEquals(-1, lines.indexAt(999))
    }

    @Test
    fun `恰好等于时间戳命中该行`() {
        assertEquals(0, lines.indexAt(1000))
        assertEquals(1, lines.indexAt(3000))
        assertEquals(2, lines.indexAt(5000))
    }

    @Test
    fun `位置在两行之间命中前一行`() {
        assertEquals(0, lines.indexAt(2999))
        assertEquals(1, lines.indexAt(4999))
    }

    @Test
    fun `位置晚于末行命中末行`() {
        assertEquals(2, lines.indexAt(999_999))
    }

    @Test
    fun `空列表返回 -1`() {
        assertEquals(-1, emptyList<LyricLine>().indexAt(1000))
    }

    @Test
    fun `负数位置返回 -1`() {
        assertEquals(-1, lines.indexAt(-500))
    }
}

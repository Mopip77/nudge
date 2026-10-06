package com.nudge.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LikeCountTest {

    @Test
    fun `千以下原样显示`() {
        assertEquals("0", formatLikeCount(0))
        assertEquals("7", formatLikeCount(7))
        assertEquals("999", formatLikeCount(999))
    }

    @Test
    fun `负数按 0 显示`() {
        assertEquals("0", formatLikeCount(-5))
    }

    @Test
    fun `千到万之间用 K`() {
        assertEquals("1K", formatLikeCount(1_000))
        assertEquals("1.2K", formatLikeCount(1_234))
        assertEquals("1.2K", formatLikeCount(1_299))
    }

    @Test
    fun `截断而非四舍五入，不越过区间上界`() {
        // 四舍五入会得到「10.0K」，既越界又比真实值大
        assertEquals("9.9K", formatLikeCount(9_999))
        assertEquals("9.9W", formatLikeCount(99_999))
        assertEquals("99.9W", formatLikeCount(999_999))
    }

    @Test
    fun `万以上用 W`() {
        assertEquals("1W", formatLikeCount(10_000))
        assertEquals("1.2W", formatLikeCount(12_345))
        assertEquals("23.1W", formatLikeCount(231_523))
    }

    @Test
    fun `小数位为 0 时省略`() {
        assertEquals("10W", formatLikeCount(100_000))
        assertEquals("2K", formatLikeCount(2_050))
    }

    @Test
    fun `百万以上只留整数，免得角标过宽`() {
        assertEquals("100W", formatLikeCount(1_000_000))
        assertEquals("162W", formatLikeCount(1_625_017))
        assertEquals("2252W", formatLikeCount(22_522_262))
    }

    @Test
    fun `非纯数字 mediaId 直接返回 null 不发请求`() {
        listOf("dQw4w9WgXcQ", "", "   ", "spotify:track:abc123", "12345abc").forEach {
            assertNull("「$it」不该走到网络请求", LikeCountFetcher.fetch(it))
        }
    }
}

package com.nudge.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `千到万之间用 K 且只留整数`() {
        assertEquals("1K", formatLikeCount(1_000))
        assertEquals("1K", formatLikeCount(1_999))
        assertEquals("2K", formatLikeCount(2_050))
    }

    @Test
    fun `万以上用 W 且只留整数`() {
        assertEquals("1W", formatLikeCount(10_000))
        assertEquals("1W", formatLikeCount(12_345))
        assertEquals("60W", formatLikeCount(602_000))
        assertEquals("23W", formatLikeCount(231_523))
        assertEquals("162W", formatLikeCount(1_625_017))
        assertEquals("2252W", formatLikeCount(22_522_262))
    }

    @Test
    fun `向下取整而非四舍五入，不越过区间上界`() {
        // 四舍五入会得到「10K」，既越界又比真实值大
        assertEquals("9K", formatLikeCount(9_999))
        assertEquals("9W", formatLikeCount(99_999))
        assertEquals("99W", formatLikeCount(999_999))
    }

    @Test
    fun `换成 K 或 W 之后永远不出现小数点`() {
        // 覆盖各个量级的边界附近，任何一个带小数点都算回归
        listOf(1_000L, 1_234, 9_999, 10_000, 12_345, 99_999, 602_000, 999_999, 1_625_017)
            .forEach { assertFalse("$it → ${formatLikeCount(it)}", formatLikeCount(it).contains('.')) }
    }

    @Test
    fun `非纯数字 mediaId 直接返回 null 不发请求`() {
        listOf("dQw4w9WgXcQ", "", "   ", "spotify:track:abc123", "12345abc").forEach {
            assertNull("「$it」不该走到网络请求", LikeCountFetcher.fetch(it))
        }
    }
}

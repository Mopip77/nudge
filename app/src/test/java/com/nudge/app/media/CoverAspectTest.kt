package com.nudge.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 封面几何的两条纯逻辑：请求边长 [squareEdgeFor] 与排版高度 [coverHeightRatio]。
 *
 * 后者是**本次改动的核心不变式**所在：低清那张 363 方图与高清那张必须算出
 * 同一个排版高度，否则高清图到位替换的那一刻整块封面会 zoom 一下。
 * 这条约束在代码里看不出来，真机上也只有切歌那一瞬间才暴露。
 */
class CoverAspectTest {

    @Test
    fun `不超限时按目标宽度取`() {
        assertEquals(1080, squareEdgeFor(sourceShortEdge = 3000, targetWidth = 1080))
    }

    @Test
    fun `超限时降到源图短边`() {
        // 实测见过的小原图：553、348。请求再大也只会拿回原图本身。
        assertEquals(553, squareEdgeFor(sourceShortEdge = 553, targetWidth = 1080))
        assertEquals(348, squareEdgeFor(sourceShortEdge = 348, targetWidth = 1080))
    }

    @Test
    fun `请求边长恒不超过源图短边`() {
        // 原图不一定是方的（实测歌曲 28643004 的原图是 852×1136），
        // 能裁出的最大方块由短边决定。
        listOf(348, 553, 640, 800, 852, 1500, 3000, 6000).forEach { edge ->
            val got = squareEdgeFor(sourceShortEdge = edge, targetWidth = 1080)
            assertTrue("源图 $edge 上请求了 $got，超了", got <= edge)
        }
    }

    @Test
    fun `源图极小时边长不为 0`() {
        // 边长 0 的请求参数接口不接受
        assertTrue(squareEdgeFor(sourceShortEdge = 1, targetWidth = 1080) >= 1)
        assertTrue(squareEdgeFor(sourceShortEdge = 0, targetWidth = 1080) >= 1)
        assertTrue(squareEdgeFor(sourceShortEdge = 800, targetWidth = 0) >= 1)
    }

    /**
     * **核心不变式**：排版高度只取决于比例，与图自身尺寸无关。
     *
     * 早先是按图自身宽高比算的，那时高清图是向接口要的 4:5——
     * 低清方图得 1.0、高清得 1.25，替换时几何跳变。
     */
    @Test
    fun `排版高度与图自身尺寸无关`() {
        CoverAspect.entries.forEach { aspect ->
            val ratio = coverHeightRatio(aspect, 1080f, 2400f)
            // 无论传进来的是 363 方图、1080 方图还是 852×1136 的非方原图，
            // 这个函数都不看它们——签名里根本没有 bitmap 尺寸，
            // 这条断言保的是「将来也别把它加回去」。
            assertTrue("$aspect 高度比例应为正", ratio > 0f)
        }
    }

    @Test
    fun `排版高度按比例递增`() {
        val w = 1080f
        val h = 2400f
        val square = coverHeightRatio(CoverAspect.SQUARE, w, h)
        val p45 = coverHeightRatio(CoverAspect.PORTRAIT_4_5, w, h)
        val p916 = coverHeightRatio(CoverAspect.PORTRAIT_9_16, w, h)
        assertTrue("越竖的比例该占更多屏高", square < p45)
        assertTrue("越竖的比例该占更多屏高", p45 < p916)
    }

    @Test
    fun `方形在 1080x2400 上占约 45% 屏高`() {
        // 这个数字是「方图偏空、才改用竖图」那个判断的依据，钉住它
        val r = coverHeightRatio(CoverAspect.SQUARE, 1080f, 2400f)
        assertTrue("方图应占约 0.45 屏高，实际 $r", abs(r - 0.45f) < 0.01f)
    }

    @Test
    fun `四比五在 1080x2400 上占约 56% 屏高`() {
        val r = coverHeightRatio(CoverAspect.PORTRAIT_4_5, 1080f, 2400f)
        assertTrue("4:5 应占约 0.56 屏高，实际 $r", abs(r - 0.5625f) < 0.01f)
    }

    @Test
    fun `屏幕高度为 0 时不除零`() {
        assertEquals(0f, coverHeightRatio(CoverAspect.PORTRAIT_4_5, 1080f, 0f), 1e-6f)
    }

    @Test
    fun `每档的高宽比与它的名字一致`() {
        CoverAspect.entries.forEach { aspect ->
            val want = aspect.hRatio.toFloat() / aspect.wRatio
            assertEquals("$aspect", want, aspect.heightOverWidth, 1e-6f)
        }
    }
}

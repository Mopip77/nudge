package com.nudge.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [paramFor] 的降级逻辑。
 *
 * 这是整条高清封面链路里唯一能单测的部分，也正是最容易算错的部分：
 * 长边超过源图时接口**静默回落成方图**，界面上看不出来，只能靠这里拦着。
 */
class CoverAspectTest {

    @Test
    fun `不超限时按目标宽度取`() {
        val p = paramFor(CoverAspect.PORTRAIT_2_3, sourceEdge = 3000, targetWidth = 1080)
        assertEquals(1080, p.width)
        assertEquals(1620, p.height)
    }

    @Test
    fun `方形不超限时宽高相同`() {
        val p = paramFor(CoverAspect.SQUARE, sourceEdge = 1274, targetWidth = 1080)
        assertEquals(1080, p.width)
        assertEquals(1080, p.height)
    }

    @Test
    fun `长边超限时等比降级到源图边长`() {
        // 真机实测的那组：1274 的源图上请求 1080y1620 会静默回落成方图
        val p = paramFor(CoverAspect.PORTRAIT_2_3, sourceEdge = 1274, targetWidth = 1080)
        assertEquals(1274, maxOf(p.width, p.height))
        assertTrue("长边不得超过源图", maxOf(p.width, p.height) <= 1274)
    }

    @Test
    fun `降级后比例保持`() {
        // 源图边长按歌不同，每一档都要维持住自己的比例
        listOf(800, 1274, 1841, 2000, 3000).forEach { edge ->
            CoverAspect.entries.forEach { aspect ->
                val p = paramFor(aspect, sourceEdge = edge, targetWidth = 1080)
                val want = aspect.hRatio.toDouble() / aspect.wRatio
                val got = p.height.toDouble() / p.width
                assertTrue(
                    "$aspect @ $edge 比例跑偏：${p.width}x${p.height}",
                    abs(got - want) < 0.01,
                )
                assertTrue(
                    "$aspect @ $edge 长边超限：${p.width}x${p.height}",
                    maxOf(p.width, p.height) <= edge,
                )
            }
        }
    }

    @Test
    fun `横向比例按宽度降级`() {
        // 3:2 的长边是宽度，降级要夹的是宽而不是高
        val p = paramFor(CoverAspect.LANDSCAPE_3_2, sourceEdge = 900, targetWidth = 1080)
        assertEquals(900, p.width)
        assertEquals(600, p.height)
    }

    @Test
    fun `源图极小时边长不为 0`() {
        // 边长 0 的请求参数接口不接受，不能让比例计算把某一边算没
        CoverAspect.entries.forEach { aspect ->
            val p = paramFor(aspect, sourceEdge = 1, targetWidth = 1080)
            assertTrue("$aspect 宽为 0", p.width >= 1)
            assertTrue("$aspect 高为 0", p.height >= 1)
        }
    }

    @Test
    fun `请求串是 WyH 形式`() {
        assertEquals("810y1080", CoverParam(810, 1080).query)
    }
}

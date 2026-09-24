package com.nudge.app.wallpaper

import com.nudge.app.media.CoverAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 几何的结构性约束。断言的不是具体数值（数值随观感调），而是那些
 * **在代码里看不出来、真机上才暴露**的不变式——违反其中任何一条，
 * 壁纸要么与主界面长得不一样，要么直接崩。
 */
class BackdropGeometryTest {

    private val W = 1080f
    private val H = 2400f

    // ---- 核心不变式：排版高度与图自身尺寸无关 ----

    @Test
    fun `排版只取决于比例，与图是方是竖无关`() {
        // 这条就是「低清换高清不跳变」。低清那张 363 是方图、高清可能是
        // 852×1136，两者算出的排版必须**完全相同**，否则替换那一刻 zoom 一下。
        // 函数签名里没有 bitmap 尺寸，这里能做的是钉住「同参数同结果」。
        val a = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H)
        val b = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H)
        assertEquals(a, b)
    }

    @Test
    fun `比例越竖，封面占屏越高`() {
        val square = BackdropGeometry.layout(CoverAspect.SQUARE, W, H).heightRatio
        val p45 = BackdropGeometry.layout(CoverAspect.PORTRAIT_4_5, W, H).heightRatio
        val p34 = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H).heightRatio
        assertTrue("4:5 应比方图高", p45 > square)
        assertTrue("3:4 应比 4:5 高", p34 > p45)
    }

    @Test
    fun `清晰区以 centerY 为中心上下对称展开`() {
        val centerY = 0.40f
        val l = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H, centerY)
        assertEquals(centerY, l.topRatio + l.heightRatio / 2f, 1e-5f)
    }

    // ---- 清晰区半径 ----

    @Test
    fun `清晰区半径必须明显小于封面半高，给渐变留余量`() {
        // 按半高取值的话过渡段被挤成 0，方形封面的上下沿直接露出两道硬横边。
        val h = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H).heightRatio
        val reach = BackdropGeometry.reachFor(h, layer = 0)
        assertTrue("半径 $reach 应明显小于半高 ${h / 2}", reach < h / 2f * 0.5f)
    }

    @Test
    fun `层号越大越糊，不透明半径越窄`() {
        // 清晰的那层（layer 0）铺得最宽、压在最上面；越糊的层不透明区越窄，
        // 于是从中心往外依次**露出**更糊的层。反过来层次就没有了。
        val h = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H).heightRatio
        val r = BackdropGeometry.BLUR_STEPS_DP.indices.map { BackdropGeometry.reachFor(h, it) }
        r.zipWithNext { a, b -> assertTrue("半径应随层号递减，实际 $r", b < a) }
    }

    @Test
    fun `所有层的不透明半径都为正`() {
        // 取到 0 或负数的话该层完全不显示，层次直接塌掉。
        val h = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H).heightRatio
        BackdropGeometry.BLUR_STEPS_DP.indices.forEach {
            assertTrue("第 $it 层半径不为正", BackdropGeometry.reachFor(h, it) > 0f)
        }
    }

    // ---- 蒙版色标：崩过的那条 ----

    @Test
    fun `色标严格递增——普通情形`() {
        val l = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H)
        val reach = BackdropGeometry.reachFor(l.heightRatio, 0)
        val stops = BackdropGeometry.maskStops(
            fadeFrom = BackdropGeometry.DEFAULT_CENTER_Y - reach,
            fadeTo = BackdropGeometry.DEFAULT_CENTER_Y + reach,
            topRatio = l.topRatio,
            heightRatio = l.heightRatio,
        )
        stops.zipWithNext { a, b -> assertTrue("色标必须递增，实际 $stops", b > a) }
    }

    @Test
    fun `色标严格递增——清晰区贴着屏幕上下边缘时也不能撞`() {
        // 这是真机上崩过的那条：几个色标夹回 [0,1] 后撞到一起，
        // LinearGradient 直接抛。极端 centerY 正是触发条件。
        listOf(0.0f, 0.02f, 0.5f, 0.98f, 1.0f).forEach { centerY ->
            CoverAspect.entries.forEach { aspect ->
                val l = BackdropGeometry.layout(aspect, W, H, centerY)
                BackdropGeometry.BLUR_STEPS_DP.indices.forEach { layer ->
                    val reach = BackdropGeometry.reachFor(l.heightRatio, layer)
                    val stops = BackdropGeometry.maskStops(
                        centerY - reach, centerY + reach, l.topRatio, l.heightRatio,
                    )
                    stops.zipWithNext { a, b ->
                        assertTrue("centerY=$centerY aspect=$aspect layer=$layer 撞标: $stops", b > a)
                    }
                }
            }
        }
    }

    @Test
    fun `色标全部落在 0 到 1 之间`() {
        val l = BackdropGeometry.layout(CoverAspect.PORTRAIT_3_4, W, H)
        val reach = BackdropGeometry.reachFor(l.heightRatio, 0)
        BackdropGeometry.maskStops(
            BackdropGeometry.DEFAULT_CENTER_Y - reach,
            BackdropGeometry.DEFAULT_CENTER_Y + reach,
            l.topRatio, l.heightRatio,
        ).forEach { assertTrue("色标 $it 越界", it in 0f..1f) }
    }

    @Test
    fun `高度为零时不抛异常`() {
        // 防御性：配置极端值或屏幕尺寸未就绪时不能崩。
        val stops = BackdropGeometry.maskStops(0.5f, 0.5f, 0f, 0f)
        stops.zipWithNext { a, b -> assertTrue(b > a) }
    }

    // ---- 与 AlbumBackdrop 的一致性 ----

    @Test
    fun `模糊档位与缩放一一对应`() {
        // 两个列表长度必须相同，否则烘焙时会漏层或越界。
        assertEquals(BackdropGeometry.BLUR_STEPS_DP.size, BackdropGeometry.BLUR_SCALES.size)
    }

    @Test
    fun `模糊档位递增且从零起步`() {
        assertEquals(0f, BackdropGeometry.BLUR_STEPS_DP.first(), 1e-6f)
        BackdropGeometry.BLUR_STEPS_DP.zipWithNext { a, b -> assertTrue(b > a) }
    }

    @Test
    fun `放大倍数随模糊递增`() {
        // 模糊让边缘向内收，半径越大收得越多，不补放大会透出发虚的暗边。
        assertEquals(1.0f, BackdropGeometry.BLUR_SCALES.first(), 1e-6f)
        BackdropGeometry.BLUR_SCALES.zipWithNext { a, b -> assertTrue(b > a) }
    }
}

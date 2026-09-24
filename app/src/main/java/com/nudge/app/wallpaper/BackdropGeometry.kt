package com.nudge.app.wallpaper

import com.nudge.app.media.CoverAspect
import com.nudge.app.media.coverHeightRatio

/**
 * 封面分层背景的**几何**，纯 Kotlin，无 Android 依赖。
 *
 * 抽出来是因为同一套几何有**两个**渲染实现：
 *
 * - `ui/AlbumBackdrop.kt`——实时 composable，用 `Modifier.blur()`（GPU）
 * - `wallpaper/BackdropBaker.kt`——离屏烘焙成一张 bitmap 写进锁屏壁纸
 *
 * 后者不能复用前者：`Modifier.blur()` 是 `RenderEffect`，只在活的组合里
 * 成立，**拿不到 bitmap**。两条渲染路径各写一份几何的话，改了一处忘了
 * 另一处，壁纸与主界面就会长得不一样——而这在代码里看不出来。
 *
 * CLAUDE.md 「专辑封面模式」一节里那些真机踩出来的约束
 * （高度只跟目标比例走、清晰区半径必须明显小于半高、蒙版色标严格递增）
 * 在烘焙侧**一条都不会自动成立**，所以全部收敛到这里，由单测钉住。
 */
object BackdropGeometry {

    /**
     * 模糊半径档位（dp）。与 `AlbumBackdrop.BLUR_STEPS` 必须一致。
     *
     * 半径不等距（0 → 6 → 18 → 44）：模糊的视觉强度大致按半径的平方根走，
     * 等距取值时后几层看着差不多，层次全挤在前半段。
     */
    val BLUR_STEPS_DP = listOf(0f, 6f, 18f, 44f)

    /**
     * 各层放大倍数。与 `AlbumBackdrop.BLUR_SCALES` 必须一致。
     *
     * 模糊会让图像边缘向内收（采样超出边界的部分没有内容），半径越大收得
     * 越多，不逐层补放大的话，糊得厉害的那几层四周会透出一圈发虚的暗边。
     */
    val BLUR_SCALES = listOf(1.0f, 1.04f, 1.10f, 1.22f)

    /** 清晰区中心的默认位置，同 `AlbumBackdrop.SHARP_CENTER`。 */
    const val DEFAULT_CENTER_Y = 0.52f

    /**
     * 第 [layer] 层「完全不透明」的竖向半径，占**屏幕高度**的比例。
     *
     * 系数 `0.46 - 0.13 * layer` 与 `AlbumBackdrop` 里那行同源：
     * 越清晰的层越窄，于是从中心往外依次露出更糊的层。
     *
     * **必须明显小于封面自身的半高**（最宽只取到 0.46 倍），剩下的才是
     * 渐变过渡的余量。早先按半高取值，过渡段被挤成 0，方形封面的上下沿
     * 直接露出两道硬横边——正是这套方案要消灭的东西。
     *
     * @param coverHeightRatio 封面占屏高的比例
     * @param layer 层号，0 为最清晰
     * @param sharpScale 清晰区大小的用户调节量，1.0 为默认
     */
    fun reachFor(coverHeightRatio: Float, layer: Int, sharpScale: Float = 1f): Float =
        coverHeightRatio / 2f * (0.46f - 0.13f * layer) * sharpScale

    /**
     * 封面在屏幕上的排版框：上沿与高度，均为占屏高的比例。
     *
     * 高度**只取决于比例，与图自身宽高比无关**——这是「低清换高清不跳变」
     * 的核心不变式（见 [coverHeightRatio] 的注释）。函数签名里因此
     * **没有 bitmap 尺寸**，有测试拦着「将来别把它加回去」。
     */
    fun layout(
        aspect: CoverAspect,
        screenWidth: Float,
        screenHeight: Float,
        centerY: Float = DEFAULT_CENTER_Y,
    ): Layout {
        val h = coverHeightRatio(aspect, screenWidth, screenHeight)
        return Layout(topRatio = centerY - h / 2f, heightRatio = h)
    }

    /** 本层的排版框，占屏高比例。 */
    data class Layout(val topRatio: Float, val heightRatio: Float)

    /**
     * 算出竖向渐变蒙版的四个色标（占**本层高度**的比例，严格递增）。
     *
     * 两件事必须在这里做对，都是真机上崩过或露过边的：
     *
     * 1. **坐标换算**。[fadeFrom] / [fadeTo] 按占**屏幕**高度传入，而绘制
     *    坐标是**本层自己**的（封面层只有屏幕的一部分高）。不换算的话
     *    蒙版会整体偏上、且跨度被放大。
     * 2. **色标严格递增**。清晰区贴着屏幕边缘时，几个色标夹回 [0,1] 后会
     *    撞到一起，`Brush`/`LinearGradient` 直接抛。
     *    **别写成 `coerceIn(last + eps, 上界)`**——`last` 顶到上界时下界会
     *    超过上界，`coerceIn` 抛「Cannot coerce value to an empty range」，
     *    真机上就是一进封面模式立刻崩。正确写法是先各自夹好、再逐个抬到
     *    比前一个大一点。
     *
     * 过渡段一直铺到本层的上下边缘（[span] 取「从不透明区边界到本层边缘」
     * 的全部距离）：只要在边缘处还没衰减到全透明，方形封面的边就会露出来。
     *
     * @return 四个递增的色标；配合 `[透明, 不透明, 不透明, 透明]` 使用
     */
    fun maskStops(fadeFrom: Float, fadeTo: Float, topRatio: Float, heightRatio: Float): List<Float> {
        if (heightRatio <= 0f) return listOf(0f, EPS, 2 * EPS, 3 * EPS)
        val from = (fadeFrom - topRatio) / heightRatio
        val to = (fadeTo - topRatio) / heightRatio
        val span = maxOf(from, 1f - to).coerceAtLeast(0.02f)

        val raw = listOf(from - span, from, to, to + span)
        val stops = ArrayList<Float>(4)
        var last = 0f
        raw.forEach { v ->
            last = maxOf(v.coerceIn(0f, 1f), last + EPS)
            stops += last
        }
        return stops
    }

    private const val EPS = 1e-4f
}

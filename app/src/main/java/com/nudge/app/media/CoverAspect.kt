package com.nudge.app.media

/**
 * 封面的**渲染裁切比例**。
 *
 * 注意这**不是请求比例**——网络层恒请求方图（见 [squareEdgeFor]），
 * 比例完全在渲染侧裁（见 `AlbumBackdrop`）。
 *
 * ## 为什么自己裁而不是找接口要
 *
 * 网易云的 `?param=WxH` 做的是**纯居中裁切**，没有任何针对竖图的构图
 * 优化。18 首歌逐像素比对确认：把方图正中间那几列裁出来，与直接请求
 * 4:5 拿到的图**八首 RMSE 恰好 0.000（逐字节相同）**，其余 0.03~0.08
 * 即 JPEG 噪声——而「整体压扁」假设是 17~86，差三个数量级。
 * 所以自己裁得到的就是同一张图，找接口要没有任何额外收益。
 *
 * 而自己裁能换来两件接口给不了的事：
 *
 * 1. **低清与高清的几何恒等**。MediaSession 那张 363 是方的，若向接口
 *    要 4:5，两张图的宽高比不同，替换那一刻整块封面会 zoom 一下。
 *    统一成「方图进、目标框出」之后，切换时只有清晰度变化。
 * 2. **绕开静默回落**。请求长边超过原图时接口会**连比例一起丢掉**，
 *    直接返回原图方图（实测 553 的原图上请求 `640y800` 拿回 553×553），
 *    界面上看不出来。方图请求永远不会触发它——超限也只是拿到原图本身。
 *
 * 档位摆这么多是给封面实验室逐档试的：裁多少好看没法推理，
 * 4:5 可能正好去掉留白，9:16 可能把人脸裁没，取决于封面本身的构图。
 */
enum class CoverAspect(val displayName: String, val wRatio: Int, val hRatio: Int) {
    SQUARE("1:1 方形", 1, 1),
    PORTRAIT_6_7("6:7 微竖", 6, 7),
    PORTRAIT_5_6("5:6 竖", 5, 6),
    PORTRAIT_4_5("4:5 竖", 4, 5),
    PORTRAIT_3_4("3:4 竖", 3, 4),
    PORTRAIT_5_7("5:7 高竖", 5, 7),
    PORTRAIT_2_3("2:3 高竖", 2, 3),
    PORTRAIT_9_16("9:16 超竖", 9, 16),
    LANDSCAPE_3_2("3:2 横", 3, 2);

    /** 供实验室显示用的「W:H」。 */
    val ratioLabel: String get() = "$wRatio:$hRatio"

    /** 高宽比，渲染侧按它定框的形状。 */
    val heightOverWidth: Float get() = hRatio.toFloat() / wRatio
}

/**
 * 默认比例。
 *
 * 取 **3:4 竖**而不是方形：方图的清晰区只占屏高 45%，上下两大片全是模糊
 * 延伸，竖屏上偏空；3:4 占 60%，观感明显更满。
 *
 * 这是**用户逐档看过之后拍板的取舍**：已知越竖左右切得越多
 * （3:4 吃掉横向 25%，含封面上贴边的标题字——实测陈奕迅
 * 「梦想天空分外蓝」左侧一整列字缺了左半边），仍然选「满」。
 * 要换回完整就改成 [CoverAspect.SQUARE]，排版会自动跟上。
 *
 * 换档是**纯渲染侧**的事，不再受「请求超限会回落成方图」的限制——
 * 9:16 这类极竖的档位以前选了也没用（长边先顶到原图上限），现在都真的生效。
 */
val DEFAULT_COVER_ASPECT = CoverAspect.PORTRAIT_3_4

/**
 * 算出要向网易云请求的**方图边长**。
 *
 * 恒为方图，所以这里只有一件事：别超过原图，超了白费——接口从不上采样，
 * 请求再大也只会拿回原图本身。
 *
 * 取 `min(目标宽, 源图短边)`。**按短边而不是长边**：原图不一定是方的
 * （实测歌曲 28643004 的原图是 852×1136），求方图时能裁出的最大方块
 * 由短边决定，按长边算会超出去。
 *
 * 与早先那版 `paramFor` 的关键区别：那时要为非方请求做「等比降级且保住
 * 比例」，是整条链路里最容易算错、界面上又看不出来的一环。现在方图不需要
 * 保比例，算错也只是拿到一张小一点的图，没有几何后果。
 *
 * @param sourceShortEdge 源图短边。探测失败时调用方传保守值。
 * @param targetWidth 渲染需要的宽度，通常是屏幕宽度
 */
fun squareEdgeFor(sourceShortEdge: Int, targetWidth: Int): Int =
    // 边长 0 的请求参数接口不接受，两端都兜住 1。
    minOf(targetWidth.coerceAtLeast(1), sourceShortEdge.coerceAtLeast(1))

/**
 * 封面在屏幕上占的高度比例：宽度恒铺满，高度由**目标比例**定。
 *
 * **本次改动的核心不变式就在这个函数里**：它的结果与传入的图是方是竖
 * 无关，只取决于 [aspect]。低清那张 363 方图与高清那张，算出来必须是
 * 同一个值——否则高清图到位替换的那一刻，整块封面会 zoom 一下。
 *
 * 早先是按「图自身宽高比」算的（`bitmap.height / bitmap.width`），
 * 于是低清方图得 1.0、高清 4:5 图得 1.25，几何不同，跳变就是这么来的。
 *
 * 抽成纯函数是为了能 JVM 单测——这条约束在代码里看不出来，
 * 真机上也只有切歌那一瞬间才暴露。
 *
 * @param screenWidth 屏幕宽（px 或 dp 都可，只要与 [screenHeight] 同单位）
 * @param screenHeight 屏幕高
 */
fun coverHeightRatio(aspect: CoverAspect, screenWidth: Float, screenHeight: Float): Float {
    if (screenHeight <= 0f) return 0f
    return screenWidth / screenHeight * aspect.heightOverWidth
}

package com.nudge.app.media

import kotlin.math.roundToInt

/**
 * 高清封面的请求比例。
 *
 * 网易云的图片服务支持 `?param=WxH`，而真机逐像素比对确认它做的是
 * **居中裁切而非拉伸**（与居中裁切假设的平均通道差 5.6，与纯拉伸假设 34.3，
 * 相差 6 倍）。所以非方比例是可用的——裁掉的是封面的上下（或左右），
 * 而不是把人脸压扁。
 *
 * 但**哪个比例好看要肉眼判断**：方图最完整却上下留白最多，9:16 铺满屏幕
 * 却可能把主体裁没。所以这里只把档位摆出来，选哪个交给封面实验室去试。
 */
enum class CoverAspect(val displayName: String, val wRatio: Int, val hRatio: Int) {
    SQUARE("1:1 方形", 1, 1),
    PORTRAIT_4_5("4:5 竖", 4, 5),
    PORTRAIT_2_3("2:3 竖", 2, 3),
    PORTRAIT_9_16("9:16 竖", 9, 16),
    LANDSCAPE_3_2("3:2 横", 3, 2);

    /** 供实验室显示用的「W:H」。 */
    val ratioLabel: String get() = "$wRatio:$hRatio"
}

/**
 * 默认比例。先取方形：现有渲染（[com.nudge.app.ui.AlbumBackdrop]）的清晰档
 * 按方形摆放，换比例要连带改排版，而本次只先把「更清晰」这个确定的收益拿到。
 */
val DEFAULT_COVER_ASPECT = CoverAspect.SQUARE

/**
 * 请求参数的计算结果。带上实际尺寸是给实验室显示用的——
 * 界面上要如实标注「2:3 → 810×1080」，而不是只把档位名摆出来。
 */
data class CoverParam(
    val width: Int,
    val height: Int,
) {
    /** 拼成 `?param=` 的值，如 `810y1080`。 */
    val query: String get() = "${width}y$height"
}

/**
 * 算出不超过源图的请求尺寸。**本文件里唯一值得单测的逻辑**。
 *
 * ## 为什么必须按源图长边降级
 *
 * 真机实测：请求 `1080y1620` 而源图只有 1274 时，接口**静默回落成
 * 1274×1274 的方图**——既不是请求的比例，也没有任何错误提示。
 * 同一台机器上 `810y1080` 则正常返回。所以超限不是「返回小一点的图」，
 * 而是**连比例都丢掉**，界面上看不出来。
 *
 * 而源图边长**按歌不同**（实测 800 / 1274 / 1841 / 2000 / 3000），
 * 写死一个安全值等于对多数歌放弃清晰度。只能运行时按拿到的源图边长算。
 *
 * @param sourceEdge 源图长边（网易云的原图恒为方形，故宽高相同）
 * @param targetWidth 渲染需要的宽度，通常是屏幕宽度
 */
fun paramFor(aspect: CoverAspect, sourceEdge: Int, targetWidth: Int): CoverParam {
    val w0 = targetWidth.coerceAtLeast(1)
    val h0 = (w0.toLong() * aspect.hRatio / aspect.wRatio).toInt().coerceAtLeast(1)

    val longEdge = maxOf(w0, h0)
    val limit = sourceEdge.coerceAtLeast(1)
    if (longEdge <= limit) return CoverParam(w0, h0)

    // 等比缩到长边正好贴着源图。用 Double 再取整，避免先整除丢掉精度
    // 导致比例偏移（如 9:16 在小尺寸下被算成 9:15）。
    val k = limit.toDouble() / longEdge
    return CoverParam(
        // 源图极小时（理论上 sourceEdge=1）按比例算会得到 0，
        // 而边长为 0 的请求参数接口不接受，故兜住 1。
        width = (w0 * k).roundToInt().coerceAtLeast(1),
        height = (h0 * k).roundToInt().coerceAtLeast(1),
    )
}

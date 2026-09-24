package com.nudge.app.media

import kotlin.math.roundToInt

/**
 * 高清封面的请求比例。
 *
 * 网易云的图片服务支持 `?param=WxH`，而真机逐像素比对确认它做的是
 * **居中裁切而非拉伸**（与居中裁切假设的平均通道差 5.6，与纯拉伸假设 34.3，
 * 相差 6 倍）。所以非方比例是可用的，不会把人脸压扁。
 *
 * **原图是方的，所以求竖图裁的是左右、不是上下。** 这条容易想反：
 * 1080×1350 比 1080×1080 还高，方图不可能靠切上下变高——实际是放大到
 * 够高再切两侧。逐像素验证过（1500 方图求 `1080y1350`，缩回同高是
 * 864×1080；与「裁左右」假设的 RMSE 0.0057，与「不裁」0.164，差 29 倍）。
 *
 * 但**哪个比例好看要肉眼判断**：方图最完整却上下留白最多，越竖越满
 * 却越容易把贴边的标题字切掉。所以这里只把档位摆出来，
 * 选哪个交给封面实验室去试。
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
 * 默认比例。
 *
 * 取 **4:5 竖**而不是方形：方图的清晰区只占屏高 45%，上下两大片全是模糊
 * 延伸，竖屏上偏空；4:5 占 56%，观感明显更满。
 *
 * 这是**用户拍板的取舍**：已知它会切掉原图横向 20%（含封面上贴边的
 * 标题字，实测陈奕迅「梦想天空分外蓝」左侧一整列字缺了左半边），
 * 仍然选「满」。要换回完整就把这里改成 [CoverAspect.SQUARE]。
 *
 * 不取更竖的 2:3 / 9:16：它们的长边先顶到原图上限，**超限就整个回落成
 * 方图**（1500 的原图上请求 `1080y1620` 拿回来的是 1500×1500），
 * 等于选了也没用。只有 4:5 在常见的 1000~1500 原图上还能真的生效。
 *
 * 渲染侧的清晰区高度跟随图自身比例（见 `AlbumBackdrop`），
 * 所以改这个值排版会自动跟上，不必两处同时改。
 */
val DEFAULT_COVER_ASPECT = CoverAspect.PORTRAIT_4_5

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

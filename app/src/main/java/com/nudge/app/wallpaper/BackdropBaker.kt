package com.nudge.app.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager

/**
 * 把封面**离屏**烘焙成一张全屏图，供写入锁屏壁纸。
 *
 * ## 为什么不能复用 `AlbumBackdrop`
 *
 * 那边用的是 `Modifier.blur()`，本质是 GPU 的 `RenderEffect`，**只在活的
 * 组合里成立，拿不到 bitmap**。壁纸要的是静态图，所以必须另走一条
 * `android.graphics` 的离屏路径。
 *
 * 几何**全部**取自 [BackdropGeometry]，与 `AlbumBackdrop` 共用同一份，
 * 由 `BackdropGeometryTest` 钉住——两边各写一份的话，改了一处忘了另一处，
 * 壁纸与主界面就会长得不一样，而这在代码里看不出来。
 *
 * ## 模糊的实现与降级
 *
 * API 31+ 用 `RenderNode` + `RenderEffect.createBlurEffect` 硬件离屏渲染。
 * minSdk 是 26，低版本没有这个 API，回落到 [downscaleBlur]——
 * 缩小再放大的廉价模糊。观感不如前者，但延伸区本来就是糊的，可接受。
 *
 * 刻意**不引 RenderScript**：它在 API 31 已废弃，为一个降级路径引一整套
 * 依赖不划算。
 */
object BackdropBaker {

    /**
     * 壁纸要铺满**整块屏幕**的尺寸。
     *
     * **不能用 `resources.displayMetrics`**：那是应用窗口的大小，扣掉了
     * 状态栏与导航栏。真机实测 1080×2400 的屏上它只给 1080×**2277**，
     * 少的 123px 会被系统拉伸补上，整张壁纸纵向变形。
     *
     * 也不取物理分辨率（`wm size` 的 Physical 1440×3200）：本机开了
     * 分辨率缩放，实际渲染是 Override 的 1080×2400，按物理尺寸烘焙
     * 要多花 1.7 倍内存又被缩回去。
     *
     * `maximumWindowMetrics` 给的正是「整块屏幕」（实测与
     * `mMaxBounds=Rect(0,0-1080,2400)` 一致）。
     */
    fun screenSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /**
     * 烘焙。**阻塞且吃内存，必须在 IO 线程调用。**
     *
     * @param cover 封面原图，方图或任意比例都可（框的形状由 [cfg] 定，图居中裁进去）
     * @param screenWidth 目标宽（px），取**有效分辨率**而非物理分辨率
     * @param screenHeight 目标高（px）
     * @param density 屏幕密度，用于把模糊半径的 dp 换算成 px
     */
    fun bake(
        cover: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        cfg: LockWallpaperConfig,
    ): Bitmap {
        val scale = cfg.renderScale.coerceIn(LockWallpaperConfig.RENDER_SCALE_RANGE)
        val w = (screenWidth * scale).toInt().coerceAtLeast(1)
        val h = (screenHeight * scale).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // 兜底底色：万一各层都没盖住（极端配置），黑底也好过透明。
        canvas.drawColor(Color.BLACK)

        val layout = BackdropGeometry.layout(
            cfg.aspect, w.toFloat(), h.toFloat(), cfg.centerY,
        )
        // 模糊半径按用户配置的峰值等比缩放各档，保持档间的相对关系
        // （不等距那套的用意见 BackdropGeometry.BLUR_STEPS_DP）。
        val blurScale = cfg.maxBlurDp / BackdropGeometry.BLUR_STEPS_DP.last()
        val blursPx = BackdropGeometry.BLUR_STEPS_DP.map { it * blurScale * density * scale }

        // 最糊的那一档打底，**纵向铺满全屏**——它只提供延伸的色块与纹理，
        // 看不清细节，拉伸不影响观感，却能保证上下最远端不露出兜底黑色。
        drawLayer(
            canvas = canvas,
            cover = cover,
            dst = RectF(0f, 0f, w.toFloat(), h.toFloat()),
            blurPx = blursPx.last(),
            layerScale = BackdropGeometry.BLUR_SCALES.last(),
            maskStops = null,
        )

        // 其余各档由糊到清依次叠上去，每层带一个「离清晰区越远越透明」的蒙版。
        // 倒序：清晰的要压在模糊的上面。
        for (i in BackdropGeometry.BLUR_STEPS_DP.lastIndex - 1 downTo 0) {
            val reach = BackdropGeometry.reachFor(layout.heightRatio, i, cfg.sharpScale)
            val top = layout.topRatio * h
            val height = layout.heightRatio * h
            drawLayer(
                canvas = canvas,
                cover = cover,
                dst = RectF(0f, top, w.toFloat(), top + height),
                blurPx = blursPx[i],
                layerScale = BackdropGeometry.BLUR_SCALES[i],
                maskStops = BackdropGeometry.maskStops(
                    fadeFrom = cfg.centerY - reach,
                    fadeTo = cfg.centerY + reach,
                    topRatio = layout.topRatio,
                    heightRatio = layout.heightRatio,
                ),
            )
        }

        // 压暗层。恒为黑：锁屏上系统画的是白字，压暗是让它读得清的唯一手段。
        val scrim = (cfg.scrimAlpha.coerceIn(LockWallpaperConfig.SCRIM_RANGE) * 255).toInt()
        if (scrim > 0) canvas.drawColor(Color.argb(scrim, 0, 0, 0))

        return out
    }

    /**
     * 画一层：居中裁切进 [dst]、按 [layerScale] 放大、模糊、再按蒙版擦除。
     *
     * [maskStops] 为 null 表示不擦（兜底层）。
     */
    private fun drawLayer(
        canvas: Canvas,
        cover: Bitmap,
        dst: RectF,
        blurPx: Float,
        layerScale: Float,
        maskStops: List<Float>?,
    ) {
        val lw = dst.width().toInt().coerceAtLeast(1)
        val lh = dst.height().toInt().coerceAtLeast(1)

        // 先把这一层单独画进一张图，才能整层做模糊与蒙版擦除——
        // 直接画到主画布上的话，DST_IN 会把底下已经画好的各层一起擦掉。
        val layer = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)

        // 居中裁切（等价于 ContentScale.Crop）：框的形状由目标比例定死，
        // 图居中裁进去——这与网易云 `?param` 做的那次居中裁逐像素等价
        // （见 CoverAspect 的注释）。
        //
        // 不能用 FillBounds：框由 aspect 定死，方图会被**压成** 4:5，那是真变形。
        // 况且原图本来就不一定是方的（实测 852×1136）。
        val src = centerCropSrc(cover.width, cover.height, lw, lh, layerScale)
        lc.drawBitmap(cover, src, RectF(0f, 0f, lw.toFloat(), lh.toFloat()), FILTER_PAINT)

        val blurred = if (blurPx > 0.5f) blur(layer, blurPx) else layer

        if (maskStops != null) {
            applyMask(blurred, maskStops)
        }

        canvas.drawBitmap(blurred, null, dst, FILTER_PAINT)

        // 中间产物立刻放掉：全屏 ARGB_8888 一张就是 10MB 量级，
        // 四层同时持有会直接顶到堆上限。
        if (blurred !== layer) blurred.recycle()
        layer.recycle()
    }

    /**
     * 算居中裁切的源矩形。
     *
     * [layerScale] > 1 时**取更小的源区域**（放大效果）：模糊会让图像边缘
     * 向内收（采样超出边界的部分没有内容），半径越大收得越多，
     * 不逐层补放大的话，糊得厉害的那几层四周会透出一圈发虚的暗边。
     */
    private fun centerCropSrc(
        srcW: Int, srcH: Int, dstW: Int, dstH: Int, layerScale: Float,
    ): Rect {
        val dstRatio = dstW.toFloat() / dstH
        val srcRatio = srcW.toFloat() / srcH
        // 先取「铺满目标框」的最大内接区域
        var cw: Float
        var ch: Float
        if (srcRatio > dstRatio) {
            ch = srcH.toFloat()
            cw = ch * dstRatio
        } else {
            cw = srcW.toFloat()
            ch = cw / dstRatio
        }
        // 再按放大倍数收窄
        cw /= layerScale
        ch /= layerScale
        val left = (srcW - cw) / 2f
        val top = (srcH - ch) / 2f
        return Rect(
            left.toInt().coerceAtLeast(0),
            top.toInt().coerceAtLeast(0),
            (left + cw).toInt().coerceAtMost(srcW),
            (top + ch).toInt().coerceAtMost(srcH),
        )
    }

    /** 按竖向渐变擦除：两端透明、中间不透明。色标由 [BackdropGeometry.maskStops] 保证严格递增。 */
    private fun applyMask(bmp: Bitmap, stops: List<Float>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                0f, 0f, 0f, bmp.height.toFloat(),
                intArrayOf(TRANSPARENT, OPAQUE, OPAQUE, TRANSPARENT),
                stops.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
    }

    /**
     * 模糊一张图：按半径缩小、再双线性放大回去。
     *
     * 缩小本身就是一次低通滤波，放大时的双线性插值又抹掉了台阶，
     * 合起来非常接近高斯模糊——而代价只有真高斯的几十分之一
     * （半径 44 时只需处理 1/8 边长的图）。这正是壁纸场景想要的：
     * 延伸区要的是「色块与纹理」，不是精确的高斯。
     *
     * **刻意不用 `RenderEffect`**（哪怕 API 31+ 有）：`drawRenderNode` 只在
     * **硬件画布**上可用，而这里是离屏的 `Canvas(bitmap)`（软件画布），
     * 用不了；真要用得搭 `HardwareRenderer` + `ImageReader` 一整套，
     * 为一个「本来就要糊掉」的背景层不划算。
     *
     * 也不用 `RenderScript`：API 31 已废弃。
     */
    private fun blur(src: Bitmap, radiusPx: Float): Bitmap {
        // 经验换算：缩到 1/(radius/2) 左右，再放大回去的观感与该半径的高斯接近。
        val factor = (radiusPx / 2f).coerceAtLeast(1f)
        val sw = (src.width / factor).toInt().coerceAtLeast(1)
        val sh = (src.height / factor).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val back = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small !== back) small.recycle()
        return back
    }

    private const val OPAQUE = Color.BLACK          // DST_IN 只看 alpha
    private const val TRANSPARENT = Color.TRANSPARENT
    private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}

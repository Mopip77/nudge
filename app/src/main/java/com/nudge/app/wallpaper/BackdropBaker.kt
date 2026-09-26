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
     * 模糊一张图：**分级**缩小、最小档上迭代 box blur、再**分级**放大回去。
     *
     * ## 为什么不能一次缩到底再一次放大回来
     *
     * 那是最初的写法，真机上表现为**锁屏壁纸像打了马赛克**——能看到边长
     * 六七十像素的规则方格，而主界面（`Modifier.blur()` 走 GPU 高斯）是平滑的。
     * 两个原因叠加，都只在**大倍率**时才发作：
     *
     * 1. `createScaledBitmap` 大比例缩小时是**稀疏采样而非盒式平均**。
     *    缩 60 倍时它不会把 60×60 个像素求平均，只会取其中少数几个点——
     *    信息不是被低通掉的，是被**丢掉**的。
     * 2. **双线性放大的核是三角形，只覆盖相邻一格**。放大 60 倍后，
     *    每个源像素摊成一个 60px 见方的斜面，格子边界处曲率突变，
     *    就是肉眼看到的马赛克。
     *
     * 原注释说的「缩小是低通、双线性放大抹掉台阶」在 2~4 倍时成立，
     * 60 倍时完全不成立。**这条路本身在大半径下必然出方块，不是参数问题。**
     *
     * ## 现在的做法
     *
     * - **每步只缩一半 / 放一倍**：每一步的双线性都在其有效范围内
     *   （2×2 邻域），等价于反复卷积一个小核。多次卷积按中心极限趋于高斯。
     *   **这一步就足以消灭马赛克**（真机逐图比对过）。
     * - **最小档上做 [BOX_PASSES] 次 box blur**：三次 box 卷积是高斯的标准
     *   逼近，再抹一道缩小阶段残留的采样噪点。放在最小档上做，
     *   代价很小（半径 44 那档只有几十像素见方）。
     *
     * ## 真机实测的代价（S24 Ultra，1080×2400，四层，稳态）
     *
     * | 写法 | 烘焙耗时 | 马赛克 |
     * |---|---|---|
     * | 旧：一次缩到底 | ~120ms | **肉眼可见的方格** |
     * | 仅分级缩放 | ~180ms | 无 |
     * | 分级 + 3×box（现状） | ~220ms | 无，过渡更细腻 |
     *
     * 多出来的 ~100ms 放在整条链路里可以忽略：单次写入总耗时约 1.5 秒，
     * 其中**系统那一步（PNG 无损编码 + 落盘 + 锁屏重绘）就占了 700ms 以上**，
     * 烘焙从来不是瓶颈（见 CLAUDE.md「写入很贵」一节）。
     *
     * 保留 box 那一道而不是只用分级缩放：两者在实测的几张封面上差别很小
     * （分级缩放后仍留一点极淡的结构），但 37ms 换「细节更硬的封面上也不会
     * 露馅」是划算的——这条路径一年也未必再看一次。
     *
     * **测耗时要取稳态**：冷进程第一次烘焙受 JIT 影响会报到 1600ms，
     * 连测三四次后才落到真实值。拿第一次的数字会把代价高估近一个数量级。
     *
     * **仍然刻意不用 `RenderEffect`**（哪怕 API 31+ 有）：`drawRenderNode`
     * 只在**硬件画布**上可用，而这里是离屏的 `Canvas(bitmap)`（软件画布），
     * 用不了；真要用得搭 `HardwareRenderer` + `ImageReader` 一整套。
     * 分级缩放已经够好，那套不划算。也不用 `RenderScript`：API 31 已废弃。
     */
    private fun blur(src: Bitmap, radiusPx: Float): Bitmap {
        // 经验换算：缩到 1/(radius/2) 左右，再放大回去的观感与该半径的高斯接近。
        val factor = (radiusPx / 2f).coerceAtLeast(1f)
        val targetW = (src.width / factor).toInt().coerceAtLeast(1)
        val targetH = (src.height / factor).toInt().coerceAtLeast(1)

        // ---- 分级缩小：每次最多减半 ----
        var cur = src
        var w = src.width
        var h = src.height
        while (w / 2 > targetW && h / 2 > targetH) {
            w /= 2
            h /= 2
            val next = Bitmap.createScaledBitmap(cur, w, h, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (w != targetW || h != targetH) {
            val next = Bitmap.createScaledBitmap(cur, targetW, targetH, true)
            if (cur !== src) cur.recycle()
            cur = next
            w = targetW
            h = targetH
        }

        // ---- 最小档上迭代 box blur，抹掉缩小阶段残留的采样噪点 ----
        repeat(BOX_PASSES) {
            val next = boxBlur(cur)
            if (cur !== src) cur.recycle()
            cur = next
        }

        // ---- 分级放大：每次最多翻倍 ----
        while (w * 2 < src.width && h * 2 < src.height) {
            w *= 2
            h *= 2
            val next = Bitmap.createScaledBitmap(cur, w, h, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (w != src.width || h != src.height) {
            val next = Bitmap.createScaledBitmap(cur, src.width, src.height, true)
            if (cur !== src) cur.recycle()
            cur = next
        }

        // 半径极小时上面各步可能一次都没跑，此时返回的还是 src 本身。
        // 调用方按 `blurred !== layer` 判断要不要 recycle，返回 src 是安全的。
        return cur
    }

    /**
     * 3×3 box blur（可分离，横纵各扫一遍）。
     *
     * 只在**缩到最小的那一档**上调用，所以尺寸很小、代价可忽略。
     * 边界按 clamp 取值，不然四周会因为采样到空白而发暗。
     */
    private fun boxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return src.copy(Bitmap.Config.ARGB_8888, false) ?: src

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)

        // 横向
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (dx in -1..1) {
                    val p = px[row + (x + dx).coerceIn(0, w - 1)]
                    a += (p ushr 24) and 0xff
                    r += (p ushr 16) and 0xff
                    g += (p ushr 8) and 0xff
                    b += p and 0xff
                }
                tmp[row + x] = ((a / 3) shl 24) or ((r / 3) shl 16) or ((g / 3) shl 8) or (b / 3)
            }
        }
        // 纵向
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (dy in -1..1) {
                    val p = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                    a += (p ushr 24) and 0xff
                    r += (p ushr 16) and 0xff
                    g += (p ushr 8) and 0xff
                    b += p and 0xff
                }
                px[y * w + x] = ((a / 3) shl 24) or ((r / 3) shl 16) or ((g / 3) shl 8) or (b / 3)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** box blur 的迭代次数。三次是高斯的标准逼近（中心极限），再多收益不明显。 */
    private const val BOX_PASSES = 3

    private const val OPAQUE = Color.BLACK          // DST_IN 只看 alpha
    private const val TRANSPARENT = Color.TRANSPARENT
    private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}

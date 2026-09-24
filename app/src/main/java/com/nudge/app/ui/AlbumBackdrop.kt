package com.nudge.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap

/**
 * 梯度模糊的分层。每层都是**同一张图、同一套排版**，只是模糊半径递增，
 * 再用竖向渐变蒙版把各层按屏幕位置揉在一起。
 *
 * 半径不等距（0 → 6 → 18 → 44）：模糊的视觉强度大致按半径的平方根走，
 * 等距取值时后几层看着差不多，层次全挤在前半段。
 */
private val BLUR_STEPS = listOf(0.dp, 6.dp, 18.dp, 44.dp)

/**
 * 各层的放大倍数，也随模糊递增。
 *
 * 模糊会让图像边缘向内收（采样超出边界的部分没有内容），半径越大收得越多，
 * 不逐层补放大的话，糊得厉害的那几层四周会透出一圈发虚的暗边。
 *
 * 顺带让远处的层略微「推近」，上下延伸区的纹理因此比中心稍大，
 * 视觉上像是同一张图往外铺开而不是叠了好几张。
 */
private val BLUR_SCALES = listOf(1.0f, 1.04f, 1.10f, 1.22f)

/**
 * 封面中心落在屏幕高度的哪个位置。
 *
 * 略高于正中：上方要减去顶栏占掉的那一条，取正中的话封面看着会偏低。
 * 各档的清晰区都以这里为中心向上下展开。
 */
private const val SHARP_CENTER = 0.46f

/**
 * 压暗遮罩的透明度。封面模式恒为暗底白字（见 [PlayerHeader] 的 onDark），
 * 这两个值就是那个「暗底」的来源。
 *
 * 歌词态压得更狠：背景此时只是氛围层，不该和歌词抢注意力。
 * 封面态压得轻，封面本身就是主角。
 */
private const val SCRIM_ALPHA_ALBUM = 0.30f
private const val SCRIM_ALPHA_LYRICS = 0.52f

/**
 * 顶栏处额外叠的一道竖向渐变，自上而下由浓转无。
 *
 * 只靠全屏均匀的压暗层不够：**封面可能是浅色的**（实测「迟到千年」那张
 * 几乎全白），白字在上面直接糊掉。而把全屏遮罩加浓到浅色封面也能读，
 * 深色封面就会被压成一团黑，封面模式的意义没了。
 *
 * 所以按区域给：文字**只出现在顶栏**那一条，就只在那里额外压暗，
 * 中间的封面主体保持通透。这比「采样封面亮度动态决定黑字白字」稳——
 * 后者在封面上下亮度不均时仍会有局部读不清，且会出现同一首歌
 * 文字颜色跳变。
 */
private const val HEADER_SCRIM_ALPHA = 0.45f

/** 模式切换的时长。短到不像转场，长到不像硬切。 */
private const val MODE_CROSSFADE_MS = 300

/**
 * 专辑封面模式的背景：**一张整屏封面，清晰度自中心向上下连续衰减**。
 *
 * ## 为什么不是「方形封面 + 外围模糊背景」
 *
 * 早先那版是两张图：中间一张原比例的方形清晰封面，底下一张模糊铺底，
 * 交界处靠羽化融合。**实测看着仍然割裂**——因为那是两张不同尺度的图
 * （一张原比例、一张放大裁切），羽化只能让边界变软，变不掉「里面是
 * 一张方形图片、外面是另一层背景」这个事实：交界处两侧的纹理对不上，
 * 人眼对这种不连续极其敏感。
 *
 * Apple Music 的做法里根本没有方形：**整屏就是这一张图**，只是越往上下
 * 越糊。衣服、头发这些纹理是顺着延伸下去的，没有任何一处能指出
 * 「边界在这」。
 *
 * 所以这里改成：[BLUR_STEPS] 每一档都是**同一张图、同一套排版**，
 * 只有模糊半径不同，再用竖向渐变蒙版按屏幕位置把它们揉在一起。
 * 各层像素一一对应，过渡处只是同一个像素在「清晰版」和「模糊版」之间
 * 插值，因此绝不可能出现纹理错位，也就没有边界可言。
 *
 * 代价是要画 4 层全屏图像。都是 GPU 的 `RenderEffect`，封面不变时
 * 各层的模糊结果会被复用，实测不掉帧。
 *
 * ## 关于清晰度
 *
 * 经 MediaSession 拿到的封面只有 **363×363**，铺满 1080px 宽是 3 倍上采样。
 * 现已绕开它，用歌曲 id 另查网易云拿原图（见 [com.nudge.app.media.ArtworkFetcher]，
 * 实测 1274~3000 见方），调用方传进来的通常已是那张高清图。
 *
 * 但本组件**对图源无所谓**：拉不到高清（无网络、非网易云播放器）时传进来的
 * 仍是 363 那张，排版一行不用改。梯度模糊在低清图下反而帮了忙——
 * 上下本来就要糊，只有中间一条需要顶着上采样。
 *
 * **纯展示，不接触摸**：本组件不加任何 pointer 修饰符，与 [LyricsOverlay] 同一口径。
 *
 * @param lyricsMode 歌词是否正在显示。为 true 时清晰层整体淡出、遮罩加深，
 *   整屏退化成统一的模糊氛围层。切换走 crossfade（见 [MODE_CROSSFADE_MS]）。
 * @param headerHeight 顶栏占的高度，顶栏的压暗渐变按它铺。
 */
@Composable
fun AlbumBackdrop(
    artwork: Bitmap?,
    lyricsMode: Boolean,
    headerHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // 没封面时只铺一层纯黑：此时没有任何图像可延伸，硬凑一个主色块
    // 还得先采样 bitmap，而「未检测到播放」本就是个短暂的过渡态。
    if (artwork == null) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }

    val bitmap = artwork.asImageBitmap()
    // 歌词态下清晰档整体淡出，只留最糊的那一层当氛围底。
    // 不动 blur 半径：半径每变一次都要重新生成模糊，全屏尺寸下每帧重算
    // 会和歌词的逐行动画、手势识别抢同一帧的预算。
    val sharpness by animateFloatAsState(
        targetValue = if (lyricsMode) 0f else 1f,
        animationSpec = tween(MODE_CROSSFADE_MS),
        label = "sharpness",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (lyricsMode) SCRIM_ALPHA_LYRICS else SCRIM_ALPHA_ALBUM,
        animationSpec = tween(MODE_CROSSFADE_MS),
        label = "scrimAlpha",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 所有层共用**同一套排版**：宽度铺满、高度等于宽度（即原比例的方形），
        // 竖直方向按 SHARP_CENTER 摆放。这是「看不出边界」的前提——
        // 各层像素一一对应，过渡处只是同一个像素在不同模糊程度之间插值。
        //
        // 不能用 ContentScale.Crop 铺满整个竖屏：363×363 的方图填满 1080×2400
        // 要放大 6.6 倍，只能看到中间一条竖缝（实测就是「一张脸加半个肩膀」），
        // 整张专辑封面根本看不全。Apple Music 那种观感里，清晰的那一段
        // **是完整的封面**，往外才是它自己的延伸。
        val coverHeightRatio = maxWidth / maxHeight
        val coverTop = SHARP_CENTER - coverHeightRatio / 2f

        // 最糊的那一档打底，且**纵向放大到铺满全屏**——它只提供延伸的色块与
        // 纹理，看不清细节，拉伸不影响观感，却能保证上下最远端不露出背景色。
        BlurLayer(
            bitmap = bitmap,
            blur = BLUR_STEPS.last(),
            scale = BLUR_SCALES.last(),
            fillScreen = true,
        )

        // 其余各档由糊到清依次叠上去，每层带一个「离清晰区越远越透明」的蒙版。
        // 倒序是因为清晰的要压在模糊的上面。
        for (i in BLUR_STEPS.lastIndex - 1 downTo 0) {
            // 这一层「完全不透明」的竖向半径，越清晰的层越窄，
            // 于是从中心往外依次露出更糊的层。
            //
            // 必须**明显小于**封面自身的半高（这里最宽只取到 0.46 倍），
            // 剩下的才是渐变过渡的余量。早先按半高取值，过渡段被挤成 0，
            // 方形封面的上下沿直接露出两道硬横边——正是这次要消灭的东西。
            val reach = coverHeightRatio / 2f * (0.46f - 0.13f * i)
            BlurLayer(
                bitmap = bitmap,
                blur = BLUR_STEPS[i],
                scale = BLUR_SCALES[i],
                // 歌词态下**除兜底层外全部淡出**，只剩最糊的那一层当氛围底。
                //
                // 不能只淡出第 0 档：中间那两档（6dp / 18dp）仍然保留着
                // 可辨认的结构，实测歌词压在一张认得出五官的脸上，
                // 背景在跟文字抢注意力。歌词态的口径是「只看到色彩氛围，
                // 认不出封面细节」，那就得让这几档一起退场。
                alpha = sharpness,
                topRatio = coverTop,
                heightRatio = coverHeightRatio,
                fadeFrom = SHARP_CENTER - reach,
                fadeTo = SHARP_CENTER + reach,
            )
        }

        // 压暗层。恒为黑色而不跟随主题：封面模式的底色完全由封面决定，
        // 跟着明暗主题走没有意义，而「暗底白字」是唯一不用做亮度分析
        // 就在任何封面下都稳定可读的组合。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
        )

        // 顶栏专用的额外压暗，自上而下渐隐。浅色封面下白字全靠这一层才读得出来，
        // 见 HEADER_SCRIM_ALPHA 的注释。高度取顶栏的 1.35 倍，让渐变的尾巴
        // 落在顶栏下沿之外——正好在下沿收干净的话，会显出一条能看见的横边。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight * 1.35f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = HEADER_SCRIM_ALPHA),
                        1f to Color.Transparent,
                    )
                )
        )
    }
}

/**
 * 一档模糊。可选一个「中间实、两端虚」的竖向蒙版。
 *
 * [fadeFrom] / [fadeTo] 是**占屏幕高度的比例**（不是本层高度），
 * 之间为完全不透明，之外渐变到本层边缘；传 null 表示整层不带蒙版。
 *
 * 各档排版完全相同，所以同一个像素在各层里落在同一个位置——
 * 过渡处只是它在不同模糊程度之间插值，不会有纹理错位。
 */
@Composable
private fun BoxWithConstraintsScope.BlurLayer(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    blur: Dp,
    scale: Float,
    alpha: Float = 1f,
    /** true 表示纵向拉满整屏（兜底层用），此时忽略 [topRatio] / [heightRatio]。 */
    fillScreen: Boolean = false,
    topRatio: Float = 0f,
    heightRatio: Float = 1f,
    fadeFrom: Float? = null,
    fadeTo: Float? = null,
) {
    if (alpha <= 0f) return
    val placement = if (fillScreen) {
        Modifier.fillMaxSize()
    } else {
        // 用 offset + 固定高度显式摆放，不靠 align：后者会把子项按
        // wrap-content 测量，尺寸修饰符拿到的是被夹过的约束。
        Modifier
            .fillMaxWidth()
            .height(maxHeight * heightRatio)
            .offset(y = maxHeight * topRatio)
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        // 方形图放进等宽等高的框里，FillBounds 与 Crop 等价，
        // 但前者不依赖「源图恰好是正方形」这个假设：真出现非方封面时
        // 宁可轻微拉伸，也好过把两侧裁掉。
        contentScale = if (fillScreen) ContentScale.Crop else ContentScale.FillBounds,
        modifier = placement
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                // DstIn 要先把内容画进独立图层才能按蒙版擦除，
                // 直接画会把底下各层一起擦掉。
                if (fadeFrom != null) {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            }
            // blur 必须排在 graphicsLayer 之后（更内层）：BlurNode 恒 clip=true
            // 且裁到自己那层的排版矩形，排在放大之前的话，放大出去的那部分
            // 会先被裁掉，补边缘的意义就没了。
            .then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)
            .then(
                if (fadeFrom != null && fadeTo != null) {
                    Modifier.drawWithContent {
                        drawContent()
                        // fadeFrom / fadeTo 是**占屏幕高度**的比例，而这里的
                        // 绘制坐标是**本层自己**的（方形层只有屏幕的一部分高），
                        // 不换算的话蒙版会整体偏上、且跨度被放大。
                        val from = (fadeFrom - topRatio) / heightRatio
                        val to = (fadeTo - topRatio) / heightRatio
                        // 过渡段一直铺到本层的上下边缘：只要在边缘处还没衰减到
                        // 全透明，方形封面的边就会露出来。取「从不透明区边界
                        // 到本层边缘」的全部距离，而不是一个固定跨度。
                        val span = maxOf(from, 1f - to).coerceAtLeast(0.02f)
                        // 色标必须**严格递增**，否则 Brush 会抛。各段都夹回 [0,1]
                        // 之后相邻两个可能撞到一起（清晰区贴着屏幕边缘时），
                        // 故逐个往后推一个极小量保证顺序。
                        // 色标必须**严格递增**否则 Brush 会抛。清晰区贴着屏幕
                        // 边缘时，四个内部色标夹回 [0,1] 后会撞到一起，
                        // 故先各自夹好、再逐个抬到比前一个大一点。
                        // （别写成 coerceIn(last + eps, 上界)：last 顶到上界时
                        //  下界会超过上界，coerceIn 直接抛「empty range」。）
                        val eps = 1e-4f
                        val raw = listOf(from - span, from, to, to + span)
                        val stops = ArrayList<Float>(4)
                        var last = 0f
                        raw.forEach { v ->
                            last = maxOf(v.coerceIn(0f, 1f), last + eps)
                            stops += last
                        }
                        // 末尾固定 1f，前面最多顶到 1-eps，保证不撞
                        val tail = maxOf(1f, last + eps)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                stops[0] to Color.Transparent,
                                stops[1] to Color.Black,
                                stops[2] to Color.Black,
                                stops[3] to Color.Transparent,
                                tail to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                } else Modifier
            ),
    )
}

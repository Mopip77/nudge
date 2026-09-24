package com.nudge.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
 * 铺底层的模糊半径。两个模式共用这一层，只靠 alpha 和遮罩切换观感，
 * **不对半径本身做动画**：blur 半径每变一次都要重新生成模糊，
 * 全屏尺寸下每帧重算会和歌词的逐行动画、手势识别抢同一帧的预算。
 */
private val BACKDROP_BLUR = 48.dp

/**
 * 铺底层的放大倍数。模糊会让图像边缘向内收（采样超出边界的部分没有内容），
 * 不放大的话四条边会透出一圈发虚的暗边。
 */
private const val BACKDROP_SCALE = 1.18f

/** 清晰封面上下羽化区占封面自身高度的比例。约等于封面的四分之一。 */
private const val FEATHER_RATIO = 0.22f

/**
 * 压暗遮罩的透明度。封面模式恒为暗底白字（见 [PlayerHeader] 的 onDark），
 * 这两个值就是那个「暗底」的来源。
 *
 * 歌词态压得更狠：背景此时只是氛围层，不该和歌词抢注意力。
 * 封面态压得轻，清晰封面本身就是主角。
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
 * 专辑封面模式的背景。三层自下而上叠：
 *
 * 1. **铺底层**：封面 Crop 铺满全屏 + 强模糊 + 放大，提供上下的延伸色块
 * 2. **清晰层**：原比例的方形封面居中，上下边缘用渐变蒙版羽化掉，融进铺底层
 * 3. **压暗层**：保证任何封面下白字都有对比度
 *
 * 「中间清晰、上下模糊」没法靠单个 blur 修饰符做到——`BlurNode` 恒 clip=true
 * 且裁到自己那层的排版矩形，一张图不可能只糊一部分。所以是两张图叠加，
 * 交界处靠羽化而不是靠裁切，这样才不会出现「这里是一张正方形图片」的硬边。
 *
 * **纯展示，不接触摸**：本组件不加任何 pointer 修饰符，与 [LyricsOverlay] 同一口径。
 *
 * @param lyricsMode 歌词是否正在显示。为 true 时清晰层淡出、遮罩加深，
 *   整屏退化成统一的模糊氛围层。切换走 crossfade（见 [MODE_CROSSFADE_MS]）。
 * @param headerHeight 顶栏占的高度。背景要铺满整个窗口（含顶栏背后，
 *   否则顶上会留一条突兀的背景色），但**清晰封面要在顶栏以下的那块区域里
 *   居中**——直接按整屏居中的话，封面会被顶栏挤得偏下，上方空出一大片。
 *   顶栏的压暗渐变也按这个高度铺。
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
    // 两个值一起过渡，构成整个模式切换动画：清晰层淡出的同时遮罩加深，
    // 观感上就是「封面越来越糊、越来越暗」，不必真的去动 blur 半径。
    val coverAlpha by animateFloatAsState(
        targetValue = if (lyricsMode) 0f else 1f,
        animationSpec = tween(MODE_CROSSFADE_MS),
        label = "coverAlpha",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (lyricsMode) SCRIM_ALPHA_LYRICS else SCRIM_ALPHA_ALBUM,
        animationSpec = tween(MODE_CROSSFADE_MS),
        label = "scrimAlpha",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 铺底层。放大用 graphicsLayer 的 scale 而不是更大的尺寸约束：
        // 前者走绘制阶段，不触发重测量。
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = BACKDROP_SCALE
                    scaleY = BACKDROP_SCALE
                }
                .blur(BACKDROP_BLUR),
        )

        // 清晰层。宽度铺满、高度按 1:1 取方形——封面几乎都是正方形，
        // 按容器宽度定边长能让它在竖屏上占到中间最大的一块。
        //
        // 垂直位置按「顶栏以下那块区域」居中，而不是整屏居中：
        // 背景铺满整个窗口（含顶栏背后），整屏居中会让封面被顶栏挤得偏下，
        // 进度条与封面之间空出一大片。往下半个顶栏高度即是那块区域的中心。
        if (coverAlpha > 0f) {
            // 边长取容器宽度，做成正方形——封面几乎都是 1:1。
            val coverSize = maxWidth
            Box(
                modifier = Modifier
                    // 尺寸必须排在 align / offset **之前**：排在后面时
                    // 这两个修饰符已经把约束改成了 wrap-content，
                    // width(coverSize) 拿到的是被夹过的约束，实测只铺到七成宽，
                    // 两侧露出硬边（而上下的羽化照常生效，故很容易误判成
                    // 「只有横向羽化没写对」）。
                    .size(coverSize)
                    .align(Alignment.Center)
                    .offset(y = headerHeight / 2)
                    .graphicsLayer {
                        alpha = coverAlpha
                        // DstIn 要先把内容画进独立图层才能按蒙版擦除，
                        // 直接画会把底下的铺底层一起擦掉。
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // 上下羽化。与 LyricsScroller 的边缘淡出是同一套手法：
                        // 用竖向渐变做 alpha 蒙版，让清晰层的上下边缘渐隐，
                        // 交界处就看不出「一张方形图片贴在另一层背景上」。
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                FEATHER_RATIO to Color.Black,
                                1f - FEATHER_RATIO to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                        // 左右也要羽化，且**必须单独画一道**。
                        //
                        // 封面铺满整个容器宽度，左右边缘正好落在屏幕两侧，
                        // 乍看不需要羽化。但清晰层与底下的模糊层在边缘处
                        // 内容并不连续（一个是原图，一个是放大 1.18 倍的裁切），
                        // 不羽化就会在屏幕两侧显出一条竖直的「接缝」。
                        //
                        // 跨度取上下的一半：两侧只要把接缝抹掉即可，
                        // 糊太宽会把封面主体也吃掉一截。
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                FEATHER_RATIO / 2 to Color.Black,
                                1f - FEATHER_RATIO / 2 to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // 图片放在一个已经定死尺寸的 Box 里再 fillMaxSize，而不是直接给
                // Image 挂 align + 尺寸修饰符：Image 会参考 painter 的固有尺寸
                // 参与测量，在 align() 带来的 wrap-content 约束下算出来的是
                // 图片自己的尺寸而非容器宽度，实测只铺到六成、两侧露出硬边。
                Image(
                    bitmap = bitmap,
                    contentDescription = "专辑封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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

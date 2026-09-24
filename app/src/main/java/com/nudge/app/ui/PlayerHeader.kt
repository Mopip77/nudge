package com.nudge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.os.SystemClock
import com.nudge.app.media.TrackInfo
import com.nudge.app.media.formatDuration
import kotlinx.coroutines.delay

/** 进度条重组间隔。500ms 让进度看起来连续，又不至于频繁重组。 */
private const val PROGRESS_TICK_MS = 500L

/** 封面模糊铺底的高斯半径，够糊到不干扰文字又能透出主色调。 */
private val BACKDROP_BLUR = 28.dp
private val LIKE_RED = Color(0xFFE04B5A)

/**
 * 顶部信息区：小封面 + 标题 + 歌手 + 收藏态 + 设置入口 + 进度条。
 *
 * **两个显示模式共用同一个组件**，位置、尺寸、间距完全一致——
 * 切模式时这块必须纹丝不动，否则会像跳转到了另一个页面而不是
 * 同一个播放器换了内容。差异全部收敛到 [onDark] 一个参数上。
 *
 * @param onDark 文字与图标是否强制走「暗底白字」。专辑封面模式传 true：
 *   那里的底色完全由封面决定，跟着明暗主题走没有意义，而白字配上
 *   [AlbumBackdrop] 的压暗层是唯一不用做亮度分析就稳定可读的组合。
 *   简洁模式传 false，仍跟随主题。
 * @param drawBackdrop 是否自己画模糊封面铺底。简洁模式需要（它没有别的背景），
 *   封面模式传 false——整屏已经是封面了，再叠一层只会让顶栏多出一道暗边。
 */
@Composable
fun PlayerHeader(
    track: TrackInfo?,
    onDark: Boolean,
    drawBackdrop: Boolean,
    onOpenSettings: () -> Unit,
) {
    // 封面模式下所有文字恒为白色；简洁模式下跟随主题。
    // 两个模式的层级关系（主文字 > 次文字 > 图标）保持一致，只是基色不同。
    val baseColor = if (onDark) Color.White else MaterialTheme.colorScheme.onBackground

    Box(modifier = Modifier.fillMaxWidth()) {
        val artwork = track?.artwork
        // 封面模糊铺底。blur 需要 API 31+，低版本自动降级为不模糊，
        // 那样整块会变成一张清晰大图盖住文字，故低版本直接不画。
        if (drawBackdrop && artwork != null && android.os.Build.VERSION.SDK_INT >= 31) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(BACKDROP_BLUR)
                    .alpha(0.45f),
            )
            // 压暗一层，保证任何封面下文字都有对比度
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            )
        }

        Column(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(
                    artwork = artwork,
                    // track 为 null（未检测到播放）时不算暂停，避免一进应用就顶着暂停图标
                    isPaused = track != null && !track.isPlaying,
                    onDark = onDark,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    MarqueeText(
                        text = track?.title ?: "未检测到播放",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = baseColor,
                    )
                    if (track != null && track.artist.isNotEmpty()) {
                        MarqueeText(
                            // 网易云用 "/" 分隔多位歌手，换成中点更像常规排版
                            text = track.artist.replace("/", " · "),
                            fontSize = 13.sp,
                            color = baseColor.copy(alpha = 0.6f),
                        )
                    }
                }

                if (track != null) {
                    Icon(
                        imageVector = if (track.isLiked) Icons.Filled.Favorite
                                      else Icons.Filled.FavoriteBorder,
                        contentDescription = if (track.isLiked) "已收藏" else "未收藏",
                        // 收藏红在任何背景下都要保持红色——它是状态指示而非装饰，
                        // 跟着 onDark 变白会让「已收藏」和「未收藏」失去区分。
                        tint = if (track.isLiked) LIKE_RED else baseColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = baseColor.copy(alpha = 0.6f),
                    )
                }
            }

            if (track != null && track.durationMs > 0) {
                ProgressRow(
                    track = track,
                    baseColor = baseColor,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                )
            }
        }
    }
}

/** 跑马灯每秒滚过的距离。慢到能读清，又不至于长标题绕一圈要等太久。 */
private val MARQUEE_SPEED_PER_SEC = 30.dp
/** 两遍文本之间的空隙，避免首尾相接看不出断点。 */
private val MARQUEE_GAP = 48.dp
/** 开始滚动前的停顿，让用户先看清开头。整圈滚完回到原点后同样停顿。 */
private const val MARQUEE_DELAY_MS = 1500L

/**
 * 超长时循环滚动的单行文本，不超长则静态居左显示。
 *
 * 没用 Compose 自带的 `basicMarquee`：它要求较新的 foundation 版本且早期为实验 API，
 * 这里自己滚更可控（停顿时长、间隙宽度）。
 *
 * 滚动方式是"跑两遍 + 中间留空隙"的无缝循环：画两份文本，位移走完
 * 「一份宽度 + 间隙」后瞬间归零，视觉上等价于首尾相接地无限滚动。
 */
@Composable
private fun MarqueeText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    fontWeight: FontWeight? = null,
) {
    val style = LocalTextStyle.current.merge(
        TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = color)
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // clipToBounds 必须加在容器上而非内部滚动的 Row 上：Row 自身宽度是两份文本，
    // 裁到它的边界等于没裁，滚出去的字会画到相邻控件（收藏图标、设置按钮）上。
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        // 必须显式左对齐：Row 用 requiredWidth 超出了容器，默认居中会让它往左溢出半截
        contentAlignment = Alignment.CenterStart,
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        // 无约束测量，拿到文本的真实宽度；直接用 Text 的 onTextLayout 只能拿到被截断后的宽度
        val textWidthPx = remember(text, style, measurer) {
            measurer.measure(text = text, style = style, maxLines = 1).size.width.toFloat()
        }
        val overflowing = textWidthPx > containerWidthPx

        if (!overflowing) {
            Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Clip)
            return@BoxWithConstraints
        }

        val gapPx = with(density) { MARQUEE_GAP.toPx() }
        val cyclePx = textWidthPx + gapPx
        val offsetX = remember(text) { Animatable(0f) }

        LaunchedEffect(text, cyclePx) {
            val speedPxPerSec = with(density) { MARQUEE_SPEED_PER_SEC.toPx() }
            val durationMs = (cyclePx / speedPxPerSec * 1000f).toInt().coerceAtLeast(1)
            while (true) {
                delay(MARQUEE_DELAY_MS)
                offsetX.animateTo(-cyclePx, tween(durationMs, easing = LinearEasing))
                offsetX.snapTo(0f)
            }
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.toInt(), 0) }
                // requiredWidth 而非 width：后者会被父级约束夹回容器宽度，
                // 两份文本挤不下，第二份就没了。
                .requiredWidth(with(density) { (cyclePx * 2).toDp() })
        ) {
            Text(text = text, style = style, maxLines = 1, softWrap = false)
            Spacer(modifier = Modifier.width(MARQUEE_GAP))
            Text(text = text, style = style, maxLines = 1, softWrap = false)
        }
    }
}

/** 暂停时封面的模糊半径。够看出「蒙上一层」，又不至于认不出是哪张封面。 */
private val PAUSED_COVER_BLUR = 6.dp

/**
 * 专辑封面。暂停时**模糊 + 叠一个暂停图标**，取代早先那行「已暂停」小字——
 * 文字混在时长旁边，盲操抬眼一瞥根本分不出来，而封面是视线本来就会落到的地方。
 *
 * @param isPaused 播放器处于暂停态。无封面时同样生效（图标叠在占位音符上）。
 */
@Composable
private fun AlbumArt(artwork: Bitmap?, isPaused: Boolean, onDark: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    // 切歌/暂停时不要硬切，跟随状态渐变一下更顺眼
    val blurRadius by animateDpAsState(
        targetValue = if (isPaused) PAUSED_COVER_BLUR else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "coverBlur",
    )
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            // 无封面时的占位底色。封面模式下 surface 是浅色（白天主题）会在
            // 暗背景上戳出一块白，故那里用半透明白，与整屏的暗底一致。
            .background(
                if (onDark) Color.White.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = "专辑封面",
                contentScale = ContentScale.Crop,
                // blur 恒 clip=true 且裁到自己那层的排版矩形，半径为 0 时也照样裁。
                // 放在 fillMaxSize 之后、外层 clip 之内，光晕才有整块封面可以铺开。
                modifier = Modifier.fillMaxSize().blur(blurRadius),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (onDark) Color.White.copy(alpha = 0.5f)
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(24.dp),
            )
        }

        if (isPaused) {
            // 压暗一层再放图标：浅色封面下白图标本身对比度不够
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
            Icon(
                imageVector = Icons.Filled.Pause,
                contentDescription = "已暂停",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 进度条 + 时间。
 *
 * 播放器只在状态变化时回推 position，故进度由 TrackInfo 依据采样时刻推算，
 * 这里靠每秒自增的 tick 驱动重组，让进度条平滑前进而非跟着 1 秒轮询跳动。
 */
@Composable
private fun ProgressRow(track: TrackInfo, baseColor: Color, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.mediaId, track.isPlaying) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(PROGRESS_TICK_MS)
        }
    }

    val position = track.currentPositionMs(nowMs)
    Column(modifier = modifier) {
        // 手绘而非 LinearProgressIndicator：后者的参数在 material3 各版本间
        // 有差异（progress 由 Float 改为 lambda），自己画一条圆角进度更稳定。
        val fraction = track.progress(nowMs)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = baseColor.copy(alpha = 0.12f),
                cornerRadius = radius,
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = baseColor.copy(alpha = 0.7f),
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(position),
                fontSize = 11.sp,
                color = baseColor.copy(alpha = 0.45f),
            )
            // 暂停态改由封面上的图标表达，这里只留总时长——
            // 两处都说同一件事反而让时长这个信息被稀释
            Text(
                text = formatDuration(track.durationMs),
                fontSize = 11.sp,
                color = baseColor.copy(alpha = 0.45f),
            )
        }
    }
}

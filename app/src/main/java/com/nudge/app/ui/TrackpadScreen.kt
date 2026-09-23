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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import android.view.MotionEvent
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.GestureRecognizer
import com.nudge.app.gesture.TouchEvent
import com.nudge.app.gesture.TouchEventType
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.media.TrackInfo
import com.nudge.app.media.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 进度条重组间隔。500ms 让进度看起来连续，又不至于频繁重组。 */
private const val PROGRESS_TICK_MS = 500L

@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
fun TrackpadScreen(
    track: TrackInfo?,
    config: NudgeConfig,
    hasPermission: Boolean,
    lyricsState: LyricsState,
    onGesture: (Gesture) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val density = LocalDensity.current.density
    // 灵敏度变化时重建识别器
    val recognizer = remember(config.sensitivity, density) {
        GestureRecognizer(config.sensitivity.params, density)
    }
    var touchPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // 手势触发时整屏闪一下：把 alpha 置 1 后动画归零
    val flashAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 沉浸式下窗口铺到了屏幕物理边缘，不避开刘海／挖孔的话顶栏会被盖住。
            // 用 safeDrawing 而非 statusBars：系统栏此时是隐藏的，真正要避的是切口。
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TopBar(track = track, onOpenSettings = onOpenSettings)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    touchPoints.forEach { point ->
                        drawCircle(
                            color = Color.Gray.copy(alpha = 0.35f),
                            radius = 48f,
                            center = point,
                        )
                    }
                    if (flashAlpha.value > 0f) {
                        drawRect(color = Color.White.copy(alpha = flashAlpha.value * 0.5f))
                    }
                }
                .pointerInteropFilter { motionEvent ->
                    handleMotionEvent(motionEvent, recognizer) { gesture ->
                        scope.launch {
                            flashAlpha.snapTo(1f)
                            flashAlpha.animateTo(0f, tween(durationMillis = 250))
                        }
                        onGesture(gesture)
                    }
                    touchPoints = currentPoints(motionEvent)
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            // 歌词画在最底层，触摸事件由外层 Box 的 pointerInteropFilter 接收，
            // 本层不加任何 pointer 修饰符，故不影响手势识别
            if (config.lyricsEnabled) {
                LyricsOverlay(
                    state = lyricsState,
                    track = track,
                    alignment = config.lyricsAlignment,
                    // debug 包里跟随实验室的实时调参，release 恒为默认值。
                    // 见 LyricsAnimOverride：不落盘，杀进程即回默认。
                    spec = LyricsAnimOverride.current,
                )
            }

            if (!hasPermission) {
                Text(
                    text = "需要通知使用权才能控制播放\n点击右上角设置授予",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }

            // 正在跑实验室参数时的角标。没有它就分不清「刚才那下观感变化
            // 是参数生效了，还是这首歌本来就长这样」，而调参全靠肉眼比对。
            // release 恒 false（见 LyricsAnimOverride.isActive），角标不存在。
            if (LyricsAnimOverride.isActive) {
                Text(
                    text = "LAB",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

/** 封面模糊铺底的高斯半径，够糊到不干扰文字又能透出主色调。 */
private val BACKDROP_BLUR = 28.dp
private val LIKE_RED = Color(0xFFE04B5A)

@Composable
private fun TopBar(track: TrackInfo?, onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // 封面模糊铺底。blur 需要 API 31+，低版本自动降级为不模糊，
        // 那样整块会变成一张清晰大图盖住文字，故低版本直接不画。
        val artwork = track?.artwork
        if (artwork != null && android.os.Build.VERSION.SDK_INT >= 31) {
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
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (track != null && track.artist.isNotEmpty()) {
                        MarqueeText(
                            // 网易云用 "/" 分隔多位歌手，换成中点更像常规排版
                            text = track.artist.replace("/", " · "),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }

                if (track != null) {
                    Icon(
                        imageVector = if (track.isLiked) Icons.Filled.Favorite
                                      else Icons.Filled.FavoriteBorder,
                        contentDescription = if (track.isLiked) "已收藏" else "未收藏",
                        tint = if (track.isLiked) LIKE_RED
                               else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }

            if (track != null && track.durationMs > 0) {
                ProgressRow(
                    track = track,
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
private fun AlbumArt(artwork: Bitmap?, isPaused: Boolean) {
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
            .background(MaterialTheme.colorScheme.surface),
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
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
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
private fun ProgressRow(track: TrackInfo, modifier: Modifier = Modifier) {
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
        val barColor = MaterialTheme.colorScheme.onBackground
        val fraction = track.progress(nowMs)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = barColor.copy(alpha = 0.12f),
                cornerRadius = radius,
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = barColor.copy(alpha = 0.7f),
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
            // 暂停态改由封面上的图标表达，这里只留总时长——
            // 两处都说同一件事反而让时长这个信息被稀释
            Text(
                text = formatDuration(track.durationMs),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
        }
    }
}

/** 把 MotionEvent 翻译成与 Android 解耦的 TouchEvent 喂给识别器。 */
private fun handleMotionEvent(
    event: MotionEvent,
    recognizer: GestureRecognizer,
    onGesture: (Gesture) -> Unit,
) {
    val index = event.actionIndex
    val type = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> TouchEventType.DOWN
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> TouchEventType.UP
        MotionEvent.ACTION_MOVE -> TouchEventType.MOVE
        MotionEvent.ACTION_CANCEL -> TouchEventType.CANCEL
        else -> return
    }

    if (type == TouchEventType.MOVE) {
        // MOVE 事件携带所有手指的位置，逐个上报
        for (i in 0 until event.pointerCount) {
            recognizer.onTouchEvent(
                TouchEvent(
                    type = TouchEventType.MOVE,
                    pointerId = event.getPointerId(i),
                    x = event.getX(i),
                    y = event.getY(i),
                    timeMs = event.eventTime,
                    activePointerCount = event.pointerCount,
                )
            )?.let(onGesture)
        }
        return
    }

    // UP 类事件中，抬起的这根手指仍计入 pointerCount，故需减一
    val activeCount = if (type == TouchEventType.UP) event.pointerCount - 1 else event.pointerCount
    recognizer.onTouchEvent(
        TouchEvent(
            type = type,
            pointerId = event.getPointerId(index),
            x = event.getX(index),
            y = event.getY(index),
            timeMs = event.eventTime,
            activePointerCount = activeCount,
        )
    )?.let(onGesture)
}

/** 当前屏幕上所有手指的位置，用于绘制涟漪。 */
private fun currentPoints(event: MotionEvent): List<Offset> {
    if (event.actionMasked == MotionEvent.ACTION_UP ||
        event.actionMasked == MotionEvent.ACTION_CANCEL
    ) return emptyList()

    val liftedIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
        event.actionIndex
    } else -1

    return (0 until event.pointerCount)
        .filter { it != liftedIndex }
        .map { Offset(event.getX(it), event.getY(it)) }
}

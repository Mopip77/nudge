package com.nudge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.MotionEvent
import com.nudge.app.action.HapticOverride
import com.nudge.app.config.DisplayMode
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.GestureRecognizer
import com.nudge.app.gesture.TouchEvent
import com.nudge.app.gesture.TouchEventType
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.media.TrackInfo
import kotlinx.coroutines.launch

/**
 * 播放主界面。两个显示模式（见 [DisplayMode]）共用同一套状态、同一个顶栏、
 * 同一个手势识别器，差异只在下方内容区的呈现。
 *
 * **手势区范围两个模式完全一致**：恒为顶栏**以下**那块。封面模式下虽然
 * 视觉上没有边界了，但触摸区并没有扩张到整屏——顶栏要留给设置按钮，
 * 整屏接管会让落在按钮上的那根手指被识别器吞掉半个多指手势。
 */
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

    val albumMode = config.displayMode == DisplayMode.ALBUM
    // 封面模式恒为暗底白字：底色完全由封面决定，跟着明暗主题走没有意义。
    // 歌词、提示文字、顶栏都走这一个基色，保证同一块屏幕上口径统一。
    val contentColor = if (albumMode) Color.White else MaterialTheme.colorScheme.onSurface

    // 触摸区的修饰符。两个模式共用，保证手势行为逐字节一致——
    // 分别写两份的话，改了一处忘了另一处就会出现「换个模式手势就不灵」。
    val touchModifier = Modifier
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
        }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 封面背景铺在**窗口的物理边缘**，不受 safeDrawing 内缩——
        // 沉浸式下封面要一直铺到刘海和屏幕底边，留一圈背景色会把
        // 「整屏都是这张封面」的观感破坏掉。文字层仍然避开切口（见下）。
        //
        // 顶栏实测高度（含它上方被 safeDrawing 让出的切口高度）。
        // 背景要铺满整个窗口，但清晰封面得在顶栏**以下**那块区域里居中，
        // 顶栏的压暗渐变也按这个高度铺，所以要把它量出来传给背景层。
        var headerHeight by remember { mutableStateOf(0.dp) }
        val localDensity = LocalDensity.current
        // safeDrawing 在顶部让出的高度（刘海／挖孔）。顶栏的实测高度不含它，
        // 而背景层是从窗口物理顶边起算的，两者要对齐才行。
        val safeTopPadding = WindowInsets.safeDrawing.asPaddingValues()
            .calculateTopPadding()

        if (albumMode) {
            AlbumBackdrop(
                // 高清那张拉到了就用它，没拉到（无网络、非网易云、还在拉）
                // 就继续用 MediaSession 那张 363 的当占位。不等高清图到位再显示——
                // 切歌瞬间封面空一下比糊一点更难看。
                //
                // 两张图都是方的，裁进同一个 aspect 框，所以这次替换
                // **不改变任何几何**，只是变清晰（再叠一层淡入，见 artworkKey）。
                artwork = track?.hiResArtwork ?: track?.artwork,
                aspect = CoverOverride.current,
                // 同一首歌内换图才淡入；换歌直接换。
                artworkKey = track?.mediaId.orEmpty(),
                // 歌词开着时退化成统一的模糊氛围层，清晰封面淡出
                lyricsMode = config.lyricsEnabled,
                headerHeight = headerHeight,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 沉浸式下窗口铺到了屏幕物理边缘，不避开刘海／挖孔的话顶栏会被盖住。
                // 用 safeDrawing 而非 statusBars：系统栏此时是隐藏的，真正要避的是切口。
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Box(
                modifier = Modifier.onSizeChanged {
                    // 加上被 safeDrawing 让出的那段：背景是从窗口物理顶边起算的，
                    // 顶栏却从切口下方才开始，不补这一段会让封面整体偏上。
                    headerHeight = with(localDensity) {
                        it.height.toDp()
                    } + safeTopPadding
                }
            ) {
                PlayerHeader(
                    track = track,
                    onDark = albumMode,
                    // 封面模式下整屏已经是封面了，顶栏再自己铺一层模糊封面
                    // 会在信息区下沿戳出一道能看见的暗边
                    drawBackdrop = !albumMode,
                    onOpenSettings = onOpenSettings,
                )
            }

            if (albumMode) {
                // 封面模式：内容区没有卡片、没有背景，直接浮在整屏封面上。
                // 触摸修饰符照挂，手势范围与简洁模式一致。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(touchModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    ContentLayer(
                        track = track,
                        config = config,
                        hasPermission = hasPermission,
                        lyricsState = lyricsState,
                        contentColor = contentColor,
                    )
                }
            } else {
                // 简洁模式：早先唯一的形态，一块圆角卡片。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .then(touchModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    ContentLayer(
                        track = track,
                        config = config,
                        hasPermission = hasPermission,
                        lyricsState = lyricsState,
                        contentColor = contentColor,
                    )
                }
            }
        }
    }
}

/**
 * 内容区里的各层：歌词、无权限提示、实验室角标。
 *
 * **不加任何 pointer 修饰符**，触摸事件全部由外层 Box 的
 * pointerInteropFilter 接收，故不影响手势识别。两个模式共用，
 * 只有配色随 [contentColor] 变。
 */
@Composable
private fun BoxScope.ContentLayer(
    track: TrackInfo?,
    config: NudgeConfig,
    hasPermission: Boolean,
    lyricsState: LyricsState,
    contentColor: Color,
) {
    if (config.lyricsEnabled) {
        LyricsOverlay(
            state = lyricsState,
            track = track,
            alignment = config.lyricsAlignment,
            // debug 包里跟随实验室的实时调参，release 恒为默认值。
            // 见 LyricsAnimOverride：不落盘，杀进程即回默认。
            spec = LyricsAnimOverride.current,
            textColor = contentColor,
        )
    }

    if (!hasPermission) {
        Text(
            text = "需要通知使用权才能控制播放\n点击右上角设置授予",
            color = contentColor.copy(alpha = 0.5f),
            fontSize = 14.sp,
        )
    }

    // 正在跑实验室参数时的角标。没有它就分不清「刚才那下观感变化
    // 是参数生效了，还是这首歌本来就长这样」，而调参全靠肉眼比对。
    // release 恒 false（见 LyricsAnimOverride.isActive），角标不存在。
    //
    // 三个实验室共用**一个**角标：它要回答的是「现在跑的是不是实验室
    // 调出来的参数」，而这个问题对三者是同一个。拆成三个角标反而要
    // 用户先分辨是哪一个亮着，而角标本身只是个消歧提示。
    if (LyricsAnimOverride.isActive || HapticOverride.isActive || CoverOverride.isActive) {
        Text(
            text = "LAB",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )
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

package com.nudge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.MotionEvent
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.GestureRecognizer
import com.nudge.app.gesture.TouchEvent
import com.nudge.app.gesture.TouchEventType
import com.nudge.app.media.TrackInfo
import kotlinx.coroutines.launch

@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
fun TrackpadScreen(
    track: TrackInfo?,
    config: NudgeConfig,
    hasPermission: Boolean,
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
            if (!hasPermission) {
                Text(
                    text = "需要通知使用权才能控制播放\n点击右上角设置授予",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
        }

        BindingHint(config = config)
    }
}

@Composable
private fun TopBar(track: TrackInfo?, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track?.title ?: "未检测到播放",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (track != null && track.artist.isNotEmpty()) {
                Text(
                    text = track.artist,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
        if (track != null) {
            Icon(
                imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (track.isLiked) "已收藏" else "未收藏",
                tint = if (track.isLiked) Color(0xFFE04B5A)
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
}

@Composable
private fun BindingHint(config: NudgeConfig) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionType.entries.forEach { action ->
            val gesture = config.bindings[action]
            if (gesture != null) {
                Text(
                    text = "${gesture.displayName} → ${action.displayName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                )
            }
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

package com.nudge.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.lyrics.indexAt
import com.nudge.app.media.TrackInfo
import kotlinx.coroutines.delay

/**
 * 歌词滚动的刷新间隔。
 *
 * 比进度条的 500ms 密，否则换行会明显滞后于演唱。这块区域同时是触摸板，
 * 高频刷新有拖慢手势响应的风险，故刷新只更新本组件内部的 tick 状态
 * （不提升到 TrackpadScreen），且当前行用 derivedStateOf 记忆化——
 * 实际重组只发生在行号变化时，一行歌词通常持续数秒。
 */
private const val LYRIC_TICK_MS = 100L

/** 一行歌词占的高度，决定滚动步长。 */
private val LINE_HEIGHT = 34.dp

/**
 * 窗口边界的兜底值，仅用于容器高度尚未测量出来的首帧。
 * 真正的窗口大小按容器高度算（见 [LyricsOverlay]），写死常数会让歌词
 * 填不满区域——这块区域能放约 20 行，固定取 3 会上下空出一大片。
 */
private const val FALLBACK_NEIGHBORS = 3

/** 换行动画时长，"跟得上换行"与"看得出动效"的折中。 */
private const val SCROLL_ANIM_MS = 350

/**
 * 触摸板底下的歌词背景层。
 *
 * **纯展示，绝不参与触摸**：本组件不添加任何 pointer 修饰符，
 * 触摸层浮在其上完整接收手势。盲操场景下可交互的歌词会与手势语义冲突
 * （一次滑动到底是"跳转歌词"还是"切歌手势"？），故只读是长期设计。
 */
@Composable
fun LyricsOverlay(
    state: LyricsState,
    track: TrackInfo?,
    modifier: Modifier = Modifier,
) {
    val lines = (state as? LyricsState.Loaded)?.lines
    // 没歌词就什么都不画：加载中、纯音乐、网络失败表现一致，不打扰用户
    if (lines.isNullOrEmpty() || track == null) return

    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.mediaId, track.isPlaying) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(LYRIC_TICK_MS)
        }
    }

    // derivedStateOf 让下游只在行号真正变化时重组，而不是每个 tick 都重组。
    // key 必须含 track：它是普通参数不是 State，只 key lines 会让
    // lambda 一直捕获旧 track，切歌后进度推算仍按上一首算。
    val currentIndex by remember(lines, track) {
        derivedStateOf { lines.indexAt(track.currentPositionMs(nowMs)) }
    }

    // 前奏期间 indexAt 返回 -1，此时把第一行当作"即将唱的行"对齐到中央
    val anchorIndex = if (currentIndex < 0) 0 else currentIndex

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // 窗口大小按容器实际高度算：能放几行就渲染几行，让歌词填满整块区域。
        // 多渲一行做缓冲，避免滚动动画途中上下边缘出现空档。
        val neighbors = if (maxHeight > 0.dp) {
            (maxHeight / LINE_HEIGHT / 2).toInt() + 1
        } else {
            FALLBACK_NEIGHBORS
        }

        // 只渲染当前行附近的窗口，避免长歌词把上千个 Text 都组合出来。
        // 窗口本身已随 anchorIndex 移动，所以列不需要再按绝对行号位移——
        // 只需补上窗口在列表两端被截断时的缺口，否则当前行会偏离中央。
        val windowStart = (anchorIndex - neighbors).coerceAtLeast(0)
        val windowEnd = (anchorIndex + neighbors).coerceAtMost(lines.lastIndex)

        val lineHeightPx = with(LocalDensity.current) { LINE_HEIGHT.toPx() }
        // 开头几行时窗口上方不足 neighbors 行，向下补相应高度，
        // 使当前行始终落在容器垂直中央
        val offsetY by animateFloatAsState(
            targetValue = (anchorIndex - windowStart - neighbors) * -lineHeightPx,
            animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
            label = "lyricScroll",
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // graphicsLayer 的位移走绘制阶段，不触发重组
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationY = offsetY }
        ) {
            for (index in windowStart..windowEnd) {
                LyricRow(
                    text = lines[index].text,
                    isCurrent = index == currentIndex,
                    distance = kotlin.math.abs(index - anchorIndex),
                )
            }
        }
    }
}

@Composable
private fun LyricRow(text: String, isCurrent: Boolean, distance: Int) {
    // 随距离连续衰减而非分档：窗口现在有二十来行，只分"相邻/其余"两档
    // 会让远处一大片亮度一样，失去向外淡出的层次。0.06 是下限，
    // 再淡就完全看不见了，边缘行会显得凭空消失。
    val alpha = if (isCurrent) 0.85f else (0.34f - (distance - 1) * 0.05f).coerceAtLeast(0.06f)
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )

    // 行高固定：滚动位移按 LINE_HEIGHT 的整数倍算，
    // 若行高随文字换行而变，当前行就会逐渐偏离容器中央
    Box(
        modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            fontSize = if (isCurrent) 17.sp else 15.sp,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .graphicsLayer { this.alpha = animatedAlpha },
        )
    }
}

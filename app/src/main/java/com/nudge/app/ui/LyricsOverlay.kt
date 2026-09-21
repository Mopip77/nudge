package com.nudge.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import com.nudge.app.R
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

/** 一行歌词占的高度，决定滚动步长。必须随字号一起调，否则大字会被上下行挤掉。 */
private val LINE_HEIGHT = 52.dp

/** 当前行 / 其余行的字号。参考 Apple Music：当前行明显大一档，形成阅读焦点。 */
private val CURRENT_FONT_SIZE = 26.sp
private val OTHER_FONT_SIZE = 22.sp

/**
 * 当前行相对其余行的放大倍率。
 *
 * 文本统一按 OTHER_FONT_SIZE 排版，当前行靠 scale 放大到这个倍率（见 [LyricRow]），
 * 这样换行时大小变化能做成动画，而不是字号突变。
 */
private val CURRENT_SCALE = CURRENT_FONT_SIZE.value / OTHER_FONT_SIZE.value

/** 歌词左右边距。放大的行按倍率反向收窄排版宽度，使放大后仍落在这个边距上。 */
private val SIDE_PADDING = 20.dp

/**
 * 非当前行的最大模糊半径，模拟 Apple Music 的景深效果。
 *
 * 半径随距离递增（见 [LyricRow]），离当前行越远越糊。
 *
 * 上限取 12.dp 是比着 Apple Music 实机观感调的：那里远处几行是彻底化开的色块，
 * 完全不追求可读——只有紧邻当前行的一两行需要能"预读下一句"，再远的行存在意义
 * 只是提供景深和位置感。早先取 3.dp 是想保住"还能认出是歌词"的边界感，
 * 但那样远近层次拉不开，整片歌词看着是平的。
 */
private val MAX_BLUR = 12.dp

/**
 * 每远离一行增加的模糊半径。
 *
 * 2.6dp/行配合 12dp 上限，意味着第 5 行开外才触顶：近处三四行仍保有层次，
 * 不会一步糊到底。
 */
private val BLUR_PER_LINE = 2.6.dp

/** 上下边缘淡出区占容器高度的比例，约两行的量级。 */
private const val EDGE_FADE_RATIO = 0.12f

/**
 * 歌词专用字体：思源黑体简体（Noto Sans SC，SIL OFL 1.1，可随 APK 分发）。
 *
 * 不用系统 SansSerif 的原因：各厂商默认中文字体观感差异很大（三星 One UI
 * 的中文字重偏轻、字面偏小），歌词是本应用唯一的大字排版场景，交给系统
 * 会导致同一版本在不同机型上精致程度不一。Noto Sans SC 的字面率和
 * 笔画粗细接近 PingFang，是 Apple Music 观感在可自由分发字体里的最近似。
 *
 * 字体已按 GB2312 全集 + 拉丁 + 假名 + 标点子集化（7565 字形，单档 1.7MB）。
 * 完整 CJK 单档 8MB，两档会让 APK 翻倍，绝大部分字形歌词永远用不到。
 * **子集之外的字会渲染成豆腐块**，若将来要支持繁体或日文歌词，
 * 必须回到 tools 里重新生成子集，不能只改这里。
 */
private val LyricFont = FontFamily(
    // Medium 档目前没有文本在用（歌词统一用 Bold），保留是为了留一个比 Bold 轻的
    // 备选档：删掉就得连带删字体文件，将来想调轻得回 tools 重新生成子集。
    Font(R.font.noto_sans_sc_medium, FontWeight.Medium),
    Font(R.font.noto_sans_sc_bold, FontWeight.Bold),
)

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
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // 上下边缘淡出，替代硬裁切：否则边界处总会露出半截被切开的字。
            // 必须配 compositingStrategy=Offscreen，DstIn 要先把内容画进
            // 独立图层才能按蒙版擦除，直接画会把底下的界面一起擦掉。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        EDGE_FADE_RATIO to Color.Black,
                        1f - EDGE_FADE_RATIO to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        // 顶部对齐而非居中：列高会随窗口在列表两端被截断而变化，
        // 居中对齐时 Compose 会把"变短的列"重新居中，导致当前行跟着漂移
        // （歌快放完时尤其明显）。改为从顶部起算、由 offsetY 显式把
        // 当前行推到中央，位置就只取决于行号，与列高无关。
        contentAlignment = Alignment.TopCenter,
    ) {
        // 窗口大小按容器实际高度算：能放几行就渲染几行，让歌词填满整块区域。
        // 多渲两行做缓冲：一行给滚动动画途中的边缘空档，另一行保证
        // 上下边缘总有行被裁切位置之外的内容顶上，不会露出半截字。
        val neighbors = if (maxHeight > 0.dp) {
            (maxHeight / LINE_HEIGHT / 2).toInt() + 2
        } else {
            FALLBACK_NEIGHBORS
        }

        // 只渲染当前行附近的窗口，避免长歌词把上千个 Text 都组合出来
        val windowStart = (anchorIndex - neighbors).coerceAtLeast(0)
        val windowEnd = (anchorIndex + neighbors).coerceAtMost(lines.lastIndex)

        val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        // 各行实测高度，key 为绝对行号。长歌词会折行，行高不再统一，
        // 位移必须按实测值累加而不是 LINE_HEIGHT 的整数倍——
        // 否则一旦出现折行，当前行就会逐行累积偏移、越滚越偏离中央。
        val rowHeights = remember(lines) { mutableStateMapOf<Int, Int>() }

        // 当前行之前所有行的实高之和，即当前行在列内的顶边位置。
        // 未测量到的行按 LINE_HEIGHT 估算：仅发生在首帧，测量完成即自校正。
        val fallbackPx = with(LocalDensity.current) { LINE_HEIGHT.toPx() }
        val currentRowHeight = (rowHeights[anchorIndex] ?: fallbackPx.toInt()).toFloat()
        val topOffsetPx = (windowStart until anchorIndex)
            .sumOf { rowHeights[it] ?: fallbackPx.toInt() }
            .toFloat()

        // 把当前行的中心推到容器中心
        val offsetY by animateFloatAsState(
            targetValue = containerHeightPx / 2f - currentRowHeight / 2f - topOffsetPx,
            animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
            label = "lyricScroll",
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                // 必须解除父容器的高度上限：Column 默认被 BoxWithConstraints 的
                // maxHeight 卡住，撑满后剩下的行会被压成 0 高度——表现为当前行
                // 下方空出一片，行其实渲染了但没有尺寸。窗口整体本来就比容器高
                // （要靠 translationY 滚动），这里让它按内容自然展开，
                // 超出的部分由外层 clipToBounds 裁掉。
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                // graphicsLayer 的位移走绘制阶段，不触发重组
                .graphicsLayer { translationY = offsetY }
        ) {
            for (index in windowStart..windowEnd) {
                // key 必须绑到绝对行号：窗口滑动时 Compose 默认按位置复用
                // composable，onHeightMeasured 的 lambda 会继续捕获旧 index，
                // 把实测高度写进错误的 key，导致 rowHeights 永远读不到有效值、
                // 位移退化成 LINE_HEIGHT 估算，折行歌词下方因此空出一片。
                key(index) {
                    LyricRow(
                        text = lines[index].text,
                        isCurrent = index == currentIndex,
                        distance = kotlin.math.abs(index - anchorIndex),
                        onHeightMeasured = { rowHeights[index] = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    isCurrent: Boolean,
    distance: Int,
    onHeightMeasured: (Int) -> Unit,
) {
    // 随距离连续衰减而非分档，保留向外淡出的层次。
    // 起点 0.62 而不是更低：深色底上中间调的灰会发闷，紧邻当前行的
    // 一两行需要足够亮才能"预读下一句"——这是盲操之外唯一的实际用途。
    //
    // 衰减放缓到 0.03/行、下限提到 0.34：加大模糊后，去强调主要靠"糊"而不是"暗"
    // （Apple Music 就是这个路子，远处行并不特别暗，但已经完全化开）。
    // 若仍按原来衰减到 0.14，叠上 12dp 模糊会让远处几行直接消失，
    // 失去景深要的那种"下面还有内容"的体量感。
    val alpha = if (isCurrent) 1f else (0.62f - (distance - 1) * 0.03f).coerceAtLeast(0.34f)
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )

    // 模糊半径随距离递增，当前行保持全清晰。
    // blur 需要 API 31+，低版本自动降级为只靠 alpha 分层——
    // 那里没有景深，但仍然可读，不影响盲操主功能。
    val blurRadius = when {
        isCurrent || android.os.Build.VERSION.SDK_INT < 31 -> 0.dp
        else -> (BLUR_PER_LINE * (distance - 1).coerceAtLeast(0)).coerceAtMost(MAX_BLUR)
    }
    // 模糊也要过渡：换行时从 12dp 直接跳到 0 是整个"生硬感"里最刺眼的一跳
    val animatedBlur by animateDpAsState(
        targetValue = blurRadius,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricBlur",
    )

    // 当前行放大用 scale 而不是动画 fontSize：动 fontSize 会每帧重新测量排版，
    // 进而每帧触发 onSizeChanged，把抖动的行高写进 rowHeights——而外层的滚动位移
    // 正是按 rowHeights 累加算的，会跟着抖。scale 只作用于绘制阶段，测量高度不变。
    //
    // 代价是放大时字形是被拉伸的而非按字号重新排版，26/22 这个倍率下肉眼看不出来。
    val targetScale = if (isCurrent) CURRENT_SCALE else 1f
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricScale",
    )

    // 排版宽度要随放大倍率收窄，否则放大后左右溢出、首尾字被裁。
    // 解 (w - 2p) * scale = w - 2 * SIDE_PADDING 得 p = (w - (w - 2*SIDE_PADDING)/scale) / 2。
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val sidePadding = (screenWidth - (screenWidth - SIDE_PADDING * 2) / targetScale) / 2
    val animatedSidePadding by animateDpAsState(
        targetValue = sidePadding,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricSidePadding",
    )

    // 高度由内容决定而非写死：长句折行后要撑开，截断成省略号会让歌词直接读不成句。
    // 折行带来的行高不一由外层按实测值累加吸收（见 rowHeights）。
    // heightIn 保证短句仍占满一个标准行高，避免行距忽大忽小。
    //
    // 当前行额外留出放大后多占的高度：文本按 OTHER_FONT_SIZE 测量、靠 scale 放大，
    // 测得的高度是放大前的。不补这一块，折行的当前行放大后会向上下溢出自己的行，
    // 视觉上贴住相邻行。按最小行高补足即可——放大是绕中心的，两侧各溢出一半。
    val minHeight = if (isCurrent) LINE_HEIGHT * CURRENT_SCALE else LINE_HEIGHT
    val animatedMinHeight by animateDpAsState(
        targetValue = minHeight,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricMinHeight",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = animatedMinHeight)
            .onSizeChanged { onHeightMeasured(it.height) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            // 字号恒定，大小差异由 animatedScale 在绘制阶段表达（见上）
            fontSize = OTHER_FONT_SIZE,
            fontFamily = LyricFont,
            // 当前行和其余行都用 Bold：字重整体加粗更接近 Apple Music 的观感。
            // 两档都落在真实字体文件上（只随包了 Medium 和 Bold 两个档），
            // 不会触发系统的合成伪粗体——伪粗体在中文上会把笔画糊成一团。
            // 当前行与其余行的区分改由字号、透明度、模糊三者承担，已经足够。
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            // 折行上限 3 行：绝大多数歌词两行够用，留第三行兜底超长句；
            // 再多就会把上下文行全挤出屏幕，反而看不出唱到哪了
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = OTHER_FONT_SIZE * 1.3f,
            modifier = Modifier
                .fillMaxWidth()
                // 放大的行要按倍率收窄排版宽度。文本在缩放前排版，放大是绕中心的，
                // 若仍按 20dp 排版，放大后左右各溢出 (scale-1)/2 的宽度——
                // 实测表现为当前行首尾字被裁掉（「Dancing with my phone」的 D 和 e 都没了）。
                // 收窄后排版宽度 × scale 正好回到 20dp 边距。
                .padding(horizontal = animatedSidePadding, vertical = 6.dp)
                // scale 必须在 blur 之前：blur 默认的 BlurredEdgeTreatment.Rectangle
                // 会 clip=true 硬裁到排版矩形（见 Compose BlurNode: `clip = maskShape != null`）。
                // 放在 blur 之后，裁切发生在放大前的窄矩形上，放大的只是已经被切掉首尾的结果——
                // 表现为当前行里恰好排满整行的那一折行左右各少一个字，而没排满的折行完好。
                // 上面的 sidePadding 补偿只管在屏幕上留出放大后的位置，管不了这一刀。
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                // 零半径时不要挂 blur：BlurNode 无论半径多少都照样 clip=true，
                // 当前行半径恒为 0，挂着只会白白引入一个裁切边界。
                .then(
                    if (animatedBlur > 0.dp) Modifier.blur(animatedBlur) else Modifier
                )
                // alpha 必须在 blur 之后（即更内层）：模糊作用于已绘制内容，
                // 反过来会先被 alpha 压暗再模糊，远处行几乎看不见
                .graphicsLayer { this.alpha = animatedAlpha },
        )
    }
}

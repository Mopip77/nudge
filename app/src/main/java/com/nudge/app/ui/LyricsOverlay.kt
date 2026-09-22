package com.nudge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import com.nudge.app.config.LyricsAlignment
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

/**
 * 歌词字号。**当前行与其余行一致**，不做大小区分。
 *
 * 早先当前行 26sp、其余 22sp，靠 scale 放大表达。改为统一是对齐 Apple Music：
 * 那里非逐字模式下所有行同字号，焦点完全由清晰度（透明度 + 模糊）建立。
 * 统一字号顺带消掉了一串因放大衍生的补偿逻辑——排版宽度反向收窄、
 * 当前行行高补足、scale 与 blur 的顺序约束，都不再需要。
 */
private val FONT_SIZE = 24.sp

/** 歌词左右边距。 */
private val SIDE_PADDING = 20.dp

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
 * 可视行数的兜底值，仅用于容器高度尚未测量出来的首帧。
 * 真正的行数按容器高度算（见 [LyricsScroller]），写死常数会让歌词
 * 填不满区域——这块区域实际能放约 12 行。
 */
private const val FALLBACK_VISIBLE_ROWS = 6

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
    alignment: LyricsAlignment = LyricsAlignment.CENTER,
    spec: LyricsAnimSpec = LyricsAnimSpec.DEFAULT,
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

    LyricsScroller(
        lines = lines.map { it.text },
        currentIndex = currentIndex,
        alignment = alignment,
        spec = spec,
        modifier = modifier,
    )
}

/**
 * 歌词滚动的渲染与动画本体，与数据来源解耦。
 *
 * 从 [LyricsOverlay] 里拆出来是为了让实验室能用假数据驱动同一套动画——
 * 若实验室另写一份，调出来的参数在真实播放下未必是同样的观感，
 * 取景器就失去了意义。
 *
 * [currentIndex] 为 -1 表示前奏期（尚未唱到第一行）。
 */
@Composable
internal fun LyricsScroller(
    lines: List<String>,
    currentIndex: Int,
    alignment: LyricsAlignment,
    spec: LyricsAnimSpec,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return

    // 前奏期间把第一行当作"即将唱的行"摆到锚点位置
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
        // 当前行推到锚点行，位置就只取决于行号，与列高无关。
        contentAlignment = Alignment.TopCenter,
    ) {
        // 容器能放下的行数，决定窗口要往下渲染多远。
        val visibleRows = if (maxHeight > 0.dp) {
            (maxHeight / LINE_HEIGHT).toInt()
        } else {
            FALLBACK_VISIBLE_ROWS
        }

        // 窗口上下**不再对称**：当前行固定在第 anchorRow 行之后，
        // 它上方只需要 anchorRow 行（再往上就在容器外了），
        // 而下方要铺满剩下的全部可视区。早先对称取 neighbors 是因为
        // 当前行恒定居中，锚点上移后再对称会一头渲染过量、一头不够，
        // 表现为当前行下方空出一片（窗口已经到底但屏幕还没满）。
        //
        // 两端各多渲两行做缓冲：一行给滚动动画途中的边缘空档，
        // 另一行保证裁切边界外总有内容顶上，不会露出半截字。
        val rowsAbove = spec.anchorRow + 2
        val rowsBelow = (visibleRows - spec.anchorRow).coerceAtLeast(1) + 2

        // 只渲染当前行附近的窗口，避免长歌词把上千个 Text 都组合出来
        val windowStart = (anchorIndex - rowsAbove).coerceAtLeast(0)
        val windowEnd = (anchorIndex + rowsBelow).coerceAtMost(lines.lastIndex)

        // 各行实测高度，key 为绝对行号。长歌词会折行，行高不再统一，
        // 位移必须按实测值累加而不是 LINE_HEIGHT 的整数倍——
        // 否则一旦出现折行，当前行就会逐行累积偏移、越滚越偏离锚点。
        val rowHeights = remember(lines) { mutableStateMapOf<Int, Int>() }

        // 当前行之前所有行的实高之和，即当前行在列内的顶边位置。
        // 未测量到的行按 LINE_HEIGHT 估算：仅发生在首帧，测量完成即自校正。
        val fallbackPx = with(LocalDensity.current) { LINE_HEIGHT.toPx() }
        val topOffsetPx = (windowStart until anchorIndex)
            .sumOf { rowHeights[it] ?: fallbackPx.toInt() }
            .toFloat()

        // 把当前行的顶边推到屏幕第 anchorRow 行的位置。
        //
        // 锚点用 LINE_HEIGHT 的整数倍而不是实测高度累加：这里要的是
        // 「当前行稳定地停在屏幕上的某个固定位置」，若按上方各行的实测高度算，
        // 一旦上方出现折行（两行高），当前行就会被顶下去半行——盲操下
        // 焦点位置飘忽比精确对齐更糟。下方各行仍按实测高度自然排布，
        // 折行只影响它们之间的间距，不影响焦点。
        //
        // 这里**不做动画**：整列共用一条动画曲线正是"所有行同时同速平移"的根源。
        // 它是静态目标，由每行各自的弹簧去追（见 LyricRow），
        // 行与行之间的相位差就是错峰效果。
        val anchorTopPx = fallbackPx * spec.anchorRow
        val targetOffsetY = anchorTopPx - topOffsetPx

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
        ) {
            for (index in windowStart..windowEnd) {
                // key 必须绑到绝对行号：窗口滑动时 Compose 默认按位置复用
                // composable，onHeightMeasured 的 lambda 会继续捕获旧 index，
                // 把实测高度写进错误的 key，导致 rowHeights 永远读不到有效值、
                // 位移退化成 LINE_HEIGHT 估算，折行歌词下方因此空出一片。
                //
                // 换到逐行弹簧后 key 还多担一层作用：它同时决定了每行那个
                // Animatable 的身份。绑绝对行号，某一行的弹簧状态才会随它
                // 一起在窗口里平移，而不是被下一行接手（那会让位移从别人的
                // 当前值继续跑，表现为换行时随机抽搐）。
                key(index) {
                    // offset 是**有向**的（负数表示在当前行上方），screenRow 是
                    // 这行动画结束后会落在屏幕上的第几行。前者管清晰度，
                    // 后者管弹簧——两套梯度的基准不同，不能合成一个参数。
                    val offset = index - anchorIndex
                    LyricRow(
                        text = lines[index],
                        isCurrent = index == currentIndex,
                        offset = offset,
                        screenRow = offset + spec.anchorRow,
                        targetOffsetY = targetOffsetY,
                        alignment = alignment,
                        spec = spec,
                        onHeightMeasured = { rowHeights[index] = it },
                    )
                }
            }
        }
    }
}

/**
 * [offset]：相对当前行的**有向**距离，负数表示在当前行上方，管清晰度。
 * [screenRow]：动画结束后落在屏幕上的第几行（0 为顶边），管弹簧。
 */
@Composable
private fun LyricRow(
    text: String,
    isCurrent: Boolean,
    offset: Int,
    screenRow: Int,
    targetOffsetY: Float,
    alignment: LyricsAlignment,
    spec: LyricsAnimSpec,
    onHeightMeasured: (Int) -> Unit,
) {
    // 每行各自追 targetOffsetY，刚度与阻尼按**屏幕位置**插值：
    // 越靠屏幕上方越软越弹，越靠下方越硬越稳。相位差（错峰）与过冲（阻尼）
    // 都由此产生，不需要额外的 delay 或第二套动画。
    //
    // 基准从「距当前行的无向距离」换成屏幕位置，是这版的核心修正：
    // 前者让当前行上下两侧对称地软，于是最上面那行——它明明是最先该
    // 到位、被后面的行推着走的——反而带着和新进场的行一样的拖尾。
    // 整列实际只往上走一个方向，阻尼梯度就该沿这个方向单调排布。
    val stiffness = spec.stiffnessAt(screenRow)
    val damping = spec.dampingAt(screenRow)

    val offsetAnim = remember { Animatable(targetOffsetY) }
    // 首帧（容器尚未测量，targetOffsetY 还是基于估算值）不该看到弹簧从 0 弹到位，
    // 那会让歌词每次出现都先抖一下。snapTo 只在这一帧生效，之后都走 animateTo。
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(targetOffsetY, stiffness, damping) {
        if (!settled) {
            offsetAnim.snapTo(targetOffsetY)
            settled = true
        } else {
            offsetAnim.animateTo(
                targetValue = targetOffsetY,
                animationSpec = spring(dampingRatio = damping, stiffness = stiffness),
            )
        }
    }

    // 统一字号后，当前行与其余行的区分**全部**由这里的透明度和下面的模糊承担。
    val animatedAlpha by animateFloatAsState(
        targetValue = spec.alphaAt(offset, isCurrent),
        animationSpec = tween(spec.fadeAnimMs, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )

    // 模糊在整个区间内缓步加深，**第 1 行即起步**：下一行就带可见模糊，
    // 但因为有封顶，最远处仍认得出字。
    //
    // blur 需要 API 31+，低版本自动降级为只靠 alpha 分层——
    // 那里没有景深，但仍然可读，不影响盲操主功能。
    val blurRadius = if (android.os.Build.VERSION.SDK_INT < 31) {
        0.dp
    } else {
        spec.blurDpAt(offset).dp
    }
    // 模糊也要过渡：换行时直接跳到 0 是整个"生硬感"里最刺眼的一跳
    val animatedBlur by animateDpAsState(
        targetValue = blurRadius,
        animationSpec = tween(spec.fadeAnimMs, easing = FastOutSlowInEasing),
        label = "lyricBlur",
    )

    // 高度由内容决定而非写死：长句折行后要撑开，截断成省略号会让歌词直接读不成句。
    // 折行带来的行高不一由外层按实测值累加吸收（见 rowHeights）。
    // heightIn 保证短句仍占满一个标准行高，避免行距忽大忽小。
    //
    // 统一字号后这里不再需要为当前行补放大溢出的高度——所有行等高，
    // 测量值就是实际占位，rowHeights 的累加天然准确。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LINE_HEIGHT)
            .onSizeChanged { onHeightMeasured(it.height) }
            // 逐行位移放在行容器上而不是 Text 上：Text 外面还有 padding，
            // 挂在内层会让位移与模糊的裁切边界相互作用，远处行回弹时边缘发虚。
            // graphicsLayer 的位移走绘制阶段，不触发重组或重测量。
            .graphicsLayer { translationY = offsetAnim.value },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // 折行的句子里，第二行也要跟着靠左，所以对齐要落在 textAlign 上
            // 而不是 Box 的 contentAlignment——后者只摆放整个文本块的位置，
            // 块内各折行仍会按 textAlign 居中。
            textAlign = when (alignment) {
                LyricsAlignment.CENTER -> TextAlign.Center
                LyricsAlignment.START -> TextAlign.Start
            },
            fontSize = FONT_SIZE,
            fontFamily = LyricFont,
            // 当前行和其余行都用 Bold：字重整体加粗更接近 Apple Music 的观感。
            // 两档都落在真实字体文件上（只随包了 Medium 和 Bold 两个档），
            // 不会触发系统的合成伪粗体——伪粗体在中文上会把笔画糊成一团。
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            // 折行上限 3 行：绝大多数歌词两行够用，留第三行兜底超长句；
            // 再多就会把上下文行全挤出屏幕，反而看不出唱到哪了
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = FONT_SIZE * 1.3f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SIDE_PADDING, vertical = 6.dp)
                // 零半径时不要挂 blur：BlurNode 无论半径多少都 clip=true
                // （见 Compose BlurNode: `clip = maskShape != null`），
                // 当前行半径恒为 0，挂着只会白白引入一个裁切边界，
                // 把恰好排满整行的那一折行首尾字切掉。
                .then(
                    if (animatedBlur > 0.dp) Modifier.blur(animatedBlur) else Modifier
                )
                // alpha 必须在 blur 之后（即更内层）：模糊作用于已绘制内容，
                // 反过来会先被 alpha 压暗再模糊，远处行几乎看不见
                .graphicsLayer { this.alpha = animatedAlpha },
        )
    }
}

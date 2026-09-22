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

/**
 * 最远处行的模糊半径。
 *
 * 9dp 是在两个约束之间取的：既要有足够的景深（5.5dp 那版实测偏弱，
 * 远近层次拉不开），又要守住「最远处仍认得出字」——这条是这版的前提，
 * 早先 12dp 是第 5 行开外就化成色块，层次其实止步于前四行。
 *
 * 配合 [BLUR_RAMP_LINES] 看：真正决定观感的是曲线的斜率而不只是峰值，
 * 峰值抬高的同时把跨度也拉长，近处几行才不会跟着一起变糊。
 */
private val MAX_BLUR = 9.dp

/**
 * 模糊达到 [MAX_BLUR] 所需的距离（行）。
 *
 * 曲线在这个跨度上铺开，**第 1 行即起步**（不再有"紧邻行完全清晰"的豁免档），
 * 所以下一行就已带可见模糊——这是 Apple Music 与早先实现最直观的差别。
 *
 * 跨度随 [MAX_BLUR] 一起抬到 10：两者要配着调。只抬峰值不拉跨度，
 * 斜率会变陡，紧邻当前行的一两行跟着糊掉，"预读下一句"就没了。
 */
private const val BLUR_RAMP_LINES = 10f

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

/**
 * 淡入淡出类属性（透明度、模糊）的过渡时长。
 *
 * 位移不走时长而走弹簧（见下），但透明度和模糊没有"惯性"的物理含义，
 * 用固定时长的补间更稳，也避免弹簧过冲把 alpha 顶过 1。
 *
 * 取 260ms 而不是与位移相当的时长：清晰度要**先于**位移落定。
 * 实测 400ms 那版，换行途中新的当前行还在往上走、模糊却没退干净，
 * 看着像"字在移动中才慢慢对上焦"；缩短后焦点先建立、位移再收尾，
 * 反倒更接近 Apple Music 那种"一步到位又有余韵"的观感。
 */
private const val FADE_ANIM_MS = 260

/**
 * 逐行弹簧的刚度区间：当前行最硬，越远越软。
 *
 * **错峰与阻尼是同一套机制的两个侧面**，所以不设独立的 delay 参数。
 * 刚度随距离递减会同时产生两个效果：
 *
 * - 靠近当前行的行先到位、远处行后到位 → 相位差，即"下一行先顶上来，
 *   后面几行被依次拖拽"的链条感
 * - 远处行的阻尼比更低 → 过冲回弹更明显，即从裁切边界外进来的行
 *   那种"被拽进来又晃一下"的阻尼感
 *
 * 两个端点值真机实测调出来。第一版取 600→90 看着仍是"整体平移"：
 * 跨度不够，相邻行的相位差小到肉眼合成了一个刚体。拉到 900→28 之后
 * 链条感才出来——当前行几乎立刻就位（它是阅读焦点，拖泥带水会让人
 * 觉得歌词滞后于演唱），最远处行明显落后半拍被"拖"上来。
 *
 * 中间按距离线性插值，跨度 [SPRING_RAMP_LINES] 行之后不再变软——
 * 否则窗口边缘那些行会软到永远追不上，快歌连续换行时累积错位。
 */
private const val STIFFNESS_NEAR = 900f
private const val STIFFNESS_FAR = 28f

/**
 * 刚度衰减铺开的行数，超出后统一取 [STIFFNESS_FAR]。
 *
 * 取 4 而不是更大：衰减铺得越开，相邻行之间的差越小，链条感反而越弱。
 * 4 行之内跑完整个区间，拖尾正好落在视觉能分辨的 3–5 行。
 */
private const val SPRING_RAMP_LINES = 4f

/**
 * 逐行弹簧的阻尼比区间。
 *
 * 当前行 1f（临界阻尼，不过冲）：焦点行来回晃会很廉价。
 * 远处行 0.62f，有可见的一次回弹，这就是用户要的"阻尼拖拉"。
 * 低于 0.6 会晃两下以上，看着像故障而不是物理感。
 */
private const val DAMPING_NEAR = 1f
private const val DAMPING_FAR = 0.62f

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

        // 把当前行的中心推到容器中心。
        //
        // 这里**不再做动画**：整列共用一条动画曲线正是"所有行同时同速平移"的根源，
        // 也就是用户说的"直接往上顶、其他顺序变化"的生硬感。改为把它当作静态目标，
        // 由每行各自的弹簧去追（见 LyricRow 的 springOffset），行与行之间的
        // 相位差就是错峰效果。
        val targetOffsetY = containerHeightPx / 2f - currentRowHeight / 2f - topOffsetPx

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
                    LyricRow(
                        text = lines[index].text,
                        isCurrent = index == currentIndex,
                        distance = kotlin.math.abs(index - anchorIndex),
                        targetOffsetY = targetOffsetY,
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
    targetOffsetY: Float,
    onHeightMeasured: (Int) -> Unit,
) {
    // 每行各自追 targetOffsetY，刚度与阻尼按距离插值：近处硬而稳、远处软而弹。
    // 相位差（错峰）与过冲（阻尼）都由此产生，不需要额外的 delay 或第二套动画。
    //
    // 插值因子在 SPRING_RAMP_LINES 处封顶，窗口边缘的行不会软到追不上。
    val t = (distance / SPRING_RAMP_LINES).coerceIn(0f, 1f)
    val stiffness = STIFFNESS_NEAR + (STIFFNESS_FAR - STIFFNESS_NEAR) * t
    val damping = DAMPING_NEAR + (DAMPING_FAR - DAMPING_NEAR) * t

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
    // 当前行 1f，其余行从 0.55 起步缓降到 0.3：跨度比早先略大，
    // 因为没有字号差之后，只靠模糊撑不起足够的焦点。
    val alpha = if (isCurrent) 1f else (0.55f - (distance - 1) * 0.035f).coerceAtLeast(0.3f)
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(FADE_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )

    // 模糊在整个区间内缓步加深，**第 1 行即起步**：下一行就带可见模糊，
    // 但因为封顶只有 MAX_BLUR，最远处仍认得出字。这条曲线是本次改动的核心，
    // 早先"近处几行清晰、远处一步糊到底"的区分度正是要改掉的。
    //
    // blur 需要 API 31+，低版本自动降级为只靠 alpha 分层——
    // 那里没有景深，但仍然可读，不影响盲操主功能。
    val blurRadius = when {
        isCurrent || android.os.Build.VERSION.SDK_INT < 31 -> 0.dp
        else -> MAX_BLUR * (distance / BLUR_RAMP_LINES).coerceIn(0f, 1f)
    }
    // 模糊也要过渡：换行时直接跳到 0 是整个"生硬感"里最刺眼的一跳
    val animatedBlur by animateDpAsState(
        targetValue = blurRadius,
        animationSpec = tween(FADE_ANIM_MS, easing = FastOutSlowInEasing),
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
            textAlign = TextAlign.Center,
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

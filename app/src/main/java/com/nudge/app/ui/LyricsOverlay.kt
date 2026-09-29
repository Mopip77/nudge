package com.nudge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextLayoutResult
import com.nudge.app.lyrics.LyricWord
import android.os.SystemClock
import com.nudge.app.R
import com.nudge.app.config.LyricsAlignment
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.lyrics.indexAt
import com.nudge.app.media.TrackInfo
import kotlinx.coroutines.delay

/**
 * 造整列位移那条缓动曲线，四个控制点全部由参数给（见 [LyricsAnimSpec.easeX1]）。
 *
 * 早先这里只接一个「懒惰度」，另外三个点写死成 `(_, 0, 0.25, 1)`。
 * 那是逐行梯度时代的形状，错峰删掉后反而成了「直愣愣」的根源——
 * y1=0 让起步有一段零速度，x2=0.25 让曲线在四分之一处就逼近终点、
 * 后面拖一条又长又平的尾巴。理由详见 [LyricsAnimSpec.easeX1] 的注释。
 *
 * **曲线应当单调不减，位移只逼近目标、永不越过**。这是与弹簧最本质的
 * 差别：弹簧是 PID 式的，快速拉到目标再来回震荡。震荡在盲操场景里尤其糟
 * ——焦点行晃一下会被读成「歌词跳了」。
 *
 * 用 remember 缓存：CubicBezierEasing 会在内部做二分求解，
 * 每帧新建一个既浪费也让 Compose 误判参数变化。
 */
@Composable
private fun easingFor(p: LyricsAnimSpec.CubicPoints): Easing = remember(p) {
    // x 必须夹在 [0,1]（贝塞尔的定义域），y 不夹：y 超过 1 就是过冲，
    // 那是实验室里有意义的一档（虽然默认不用，见 easeY1 的注释）。
    CubicBezierEasing(p.x1.coerceIn(0f, 1f), p.y1, p.x2.coerceIn(0f, 1f), p.y2)
}

/**
 * 歌词滚动的刷新间隔。
 *
 * 比进度条的 500ms 密，否则换行会明显滞后于演唱。这块区域同时是触摸板，
 * 高频刷新有拖慢手势响应的风险，故刷新只更新本组件内部的 tick 状态
 * （不提升到 TrackpadScreen），且当前行用 derivedStateOf 记忆化——
 * 实际重组只发生在行号变化时，一行歌词通常持续数秒。
 */
private const val LYRIC_TICK_MS = 100L

/**
 * 一行歌词占的高度，决定滚动步长。必须随字号一起调，否则大字会被上下行挤掉。
 *
 * 从 [LyricRowData] 取值而不是各写一份：锚点计算按标称高度累加，
 * 两处不一致会让锚点算歪——而那表现为「换行时整列先跳一帧再被拖回来」，
 * 肉眼几乎看不出，只有逐帧互相关才量得到。
 */
private val LINE_HEIGHT = LyricRowData.LINE_HEIGHT_DP.dp

/**
 * 歌词字号。**当前行与其余行一致**，不做大小区分。
 *
 * 早先当前行 26sp、其余 22sp，靠 scale 放大表达。改为统一是对齐 Apple Music：
 * 那里非逐字模式下所有行同字号，焦点完全由清晰度（透明度 + 模糊）建立。
 * 统一字号顺带消掉了一串因放大衍生的补偿逻辑——排版宽度反向收窄、
 * 当前行行高补足、scale 与 blur 的顺序约束，都不再需要。
 */
private val FONT_SIZE = 27.sp

/**
 * 译文字号，比原文小一档。
 *
 * 小一号是为了让译文明确从属于原文而不与之争焦点（对齐 Apple Music）。
 * 不另降透明度：Row 是动画的原子单位，原文与译文共享同一个 alpha，
 * 再单独压暗译文会让远处的行叠乘到几乎看不见，而那时原文还读得清。
 *
 * 原文放大到 27sp 时**这里刻意不跟着放大**：比值从 0.71 收到 0.63，
 * 才落进 Apple Music 的 0.6~0.65。早先两者太接近，「从属」只是写在
 * 注释里的意图，视觉上并没有真的做到。
 */
private val TRANSLATION_FONT_SIZE = 17.sp

/** 原文与译文之间的间距。与锚点计算共用同一个值，理由同 [LINE_HEIGHT]。 */
private val TRANSLATION_GAP = LyricRowData.TRANSLATION_GAP_DP.dp

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
 * 当前行的逐字扫光时钟。
 *
 * 返回一个 lambda，读它得到「已唱到第几个字符」。**刻意返回 lambda 而不是
 * Float**：这样进度只在 `drawWithContent` 里被读到，Compose 据此把它的变化
 * 降级成**只重绘**，不触发重组或重测量——与项目里 `graphicsLayer` 位移
 * 「走绘制阶段」是同一个口径。直接返回 Float 会让整行每帧重组。
 *
 * ## 为什么不把 LYRIC_TICK_MS 提到 16ms
 *
 * 那会让 [LyricsOverlay] 每帧重组一次：`indexAt` 的二分、`rows` 的 remember
 * 校验、整个窗口的重组判断全跟着跑。而这块区域**同时是触摸板**，
 * 会与手势识别抢同一帧的预算。所以扫光自己在绘制阶段跑一条 60fps 的时钟，
 * 全局的 100ms tick 原封不动。
 *
 * ## 时基
 *
 * 起点用一次 [TrackInfo.currentPositionMs] 对齐，之后靠帧时间自增——
 * **每行只采样一次，不是每帧一次**。该时钟本身已是
 * `positionMs + elapsed × playbackSpeed` 的外推值，在播放器两次回推之间
 * 连续且单调，正适合做扫光的时基。
 *
 * 暂停时（`isPlaying == false`）循环挂起，扫光停在原地。
 */
@Composable
private fun rememberKaraokeReveal(
    words: List<LyricWord>?,
    track: TrackInfo?,
    revealFraction: Float,
    liftSpec: KaraokeLiftSpec,
): (() -> KaraokeFrame)? {
    if (words.isNullOrEmpty() || track == null) return null

    val state = remember { mutableStateOf(KaraokeFrame.EMPTY) }

    // **key 必须是「这是哪一行」的稳定标识，不能是 words 这个 List 本身。**
    //
    // `rows` 会因为译文开关、歌词重新加载等原因重建，于是每次都是一个新的
    // List 实例；而 `track` 更是**每秒被轮询重建一次**。任何一个进 key 都会
    // 让这条 effect 在**同一行唱到一半时重启**——重启时 baseMs 重新采样、
    // startNanos 归零，扫光就从这一行的开头再走一遍。
    //
    // 真机实测：12 秒内重启两次，两次都是 `词数=13`（同一行），
    // baseMs 从 9629 跳到 18459——正是用户看到的「四个词走完，整行又来一遍」。
    //
    // 取首词的起始时刻作标识：它在一首歌里唯一且稳定，换行/换歌必变，
    // 而 List 重建时不变。mediaId 仍要带上（不同歌可能撞同一个时间戳）。
    val lineKey = words?.firstOrNull()?.startMs

    // effect 不再 key 在 words 上，直接捕获它就可能读到上一次组合的实例。
    // 用 rememberUpdatedState 让循环里始终读到最新的一份——内容相同时
    // 这不改变任何行为，只是消掉「同一行但 List 被重建」时的陈旧引用。
    val latestWords by rememberUpdatedState(words)

    // isPlaying 进 key 是必要的：暂停时要退出循环，恢复时重新对齐
    // （暂停期间播放器的 positionUpdateTimeMs 不再推进，继续自增会跑飞）。
    // 升起与扫光**共用这一条时钟**，同一个 positionMs 同时算出两者。
    // 各建一条的话，两条 withFrameNanos 循环的帧时间会有半帧的错位，
    // 而「光先走、字后起」的那个时间差正是靠它们同源才精确的。
    LaunchedEffect(lineKey, track.isPlaying, track.mediaId, revealFraction, liftSpec) {
        fun frameAt(positionMs: Long) = KaraokeFrame(
            reveal = KaraokeProgress.revealAt(latestWords.orEmpty(), positionMs, revealFraction),
            lift = KaraokeLift.liftAt(latestWords.orEmpty(), positionMs, liftSpec),
        )

        if (!track.isPlaying) {
            // 暂停：按当前位置定格一次，不再推进。升起也跟着定格——
            // 它是纯时间函数，定格自然成立，不必额外处理。
            state.value = frameAt(track.currentPositionMs(SystemClock.elapsedRealtime()))
            return@LaunchedEffect
        }

        // 对齐一次，之后靠帧时间自增
        val baseMs = track.currentPositionMs(SystemClock.elapsedRealtime())
        var startNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (startNanos == 0L) startNanos = frameNanos
                val elapsedMs = (frameNanos - startNanos) / 1_000_000L
                state.value = frameAt(baseMs + elapsedMs)
            }
        }
    }

    return { state.value }
}

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
    showTranslation: Boolean = true,
    textColor: Color? = null,
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

    // 译文的显隐在**渲染侧**决定：字节已经跟原文一起拿回来了，
    // 切开关不需要重新联网。关掉时整列退回单行形态，行高随之变矮。
    val rows = remember(lines, showTranslation) {
        lines.map { line ->
            LyricRowData(
                text = line.text,
                translation = line.translation.takeIf { showTranslation },
                words = line.words,
            )
        }
    }

    LyricsScroller(
        rows = rows,
        currentIndex = currentIndex,
        alignment = alignment,
        spec = spec,
        textColor = textColor,
        track = track,
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
    rows: List<LyricRowData>,
    currentIndex: Int,
    alignment: LyricsAlignment,
    spec: LyricsAnimSpec,
    textColor: Color? = null,
    /**
     * 逐字扫光的时基来源。null 表示不做扫光（实验室用假数据时可不传，
     * 那里另有自己的时钟）——此时即便有字级时间表也只做整行高亮。
     */
    track: TrackInfo? = null,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return

    // null 表示跟随主题（简洁模式与实验室）。专辑封面模式必须显式传白色：
    // 那里的底色由封面决定，白天主题的深色 onSurface 会糊在暗背景上读不出来。
    val lyricColor = textColor ?: MaterialTheme.colorScheme.onSurface

    // 前奏期间把第一行当作"即将唱的行"摆到锚点位置
    val focusIndex = if (currentIndex < 0) 0 else currentIndex

    // **排版用的行号滞后于焦点用的行号**，这是「焦点先行」得以成立的关键。
    //
    // 早先 focusLeadMs 只作用在 offsetAnim 的 delayMillis 上，而那条路径
    // **只在歌曲开头**（windowStart 还钉在 0 时）才驱动位移。唱过头几行后
    // targetOffsetY 冻结成常数，位移全部由 shiftAnim 完成，而它按设计
    // 不带延迟（回收的是已发生的排版跳变，等不得）——于是真实播放的绝大
    // 部分时间里，位移都是立刻开始的，无论 focusLeadMs 调到多大。
    // 用户把滑块拖到 400ms 仍觉得「高亮和滚动同时进行」，就是这个原因。
    //
    // 根因是**排版跳变在组合期就已发生**：windowStart 跟着当前行走，
    // 换行当帧窗口就滑动、整列重新排版，pendingShift 只是把它补回来。
    // 既然跳变本身躲不掉，就不能靠延迟「回收」来实现焦点先行——
    // 那只会让跳变裸露在屏幕上（见 shiftSpec 的注释，那是 6e184ae 修过的坑）。
    //
    // 所以改为**让跳变本身晚发生**：排版侧继续按旧行号渲染，等焦点切完
    // 再整体推进。此时位移与回收天然一起延后，两条路径不必再各自处理延迟。
    var layoutIndex by remember { mutableIntStateOf(focusIndex) }
    LaunchedEffect(focusIndex, spec.scrollDelayMs) {
        // 延迟为 0（focusLeadMs <= 0）时不能走 delay：那会白白多等一帧，
        // 把「位移先行」这一侧的时序也弄脏。
        if (spec.scrollDelayMs > 0) delay(spec.scrollDelayMs.toLong())
        layoutIndex = focusIndex
    }
    // 切歌时必须**立刻**对齐，不能等延迟：换歌是换内容不是换行，
    // 让排版停在上一首的行号上会露出一整屏不相干的歌词。
    LaunchedEffect(rows) { layoutIndex = focusIndex }

    val anchorIndex = layoutIndex.coerceIn(0, rows.lastIndex)

    // 扫光时钟**只为当前行建一个**，而不是每行各建一个：非当前行不扫光，
    // 给它们各挂一条 withFrameNanos 循环纯属浪费（窗口里有十几行）。
    // currentIndex 为 -1（前奏期）时 words 取到 null，时钟自然不启动。
    val karaokeWords = rows.getOrNull(currentIndex)?.words
    val karaokeReveal = rememberKaraokeReveal(
        words = karaokeWords,
        track = track,
        revealFraction = spec.karaokeRevealFraction,
        liftSpec = spec.karaokeLift,
    )

    // 锚点行号按**这首歌实际有没有译文**定，而不是看译文开关。
    // 开关开着但这首歌没有译文时（网易云的纯中文歌常态），行高仍是 52dp，
    // 此时提锚点会让焦点凭空上移一行。以数据为准就自动覆盖了这种情形，
    // 也让实验室那份混合样本（有译文/无译文各半）走同一条判断。
    //
    // 只看整首歌有没有译文、而不是逐行判断上方那几行：后者会让锚点
    // 随歌曲推进在两个值之间反复跳，焦点位置晃动，比现在的小幅浮动更糟。
    val anchorRow = remember(rows, spec.anchorRow) {
        spec.anchorRowFor(rows.any { it.translation != null })
    }

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
        val rowsAbove = anchorRow + 2
        val rowsBelow = (visibleRows - anchorRow).coerceAtLeast(1) + 2

        // 只渲染当前行附近的窗口，避免长歌词把上千个 Text 都组合出来
        val windowStart = (anchorIndex - rowsAbove).coerceAtLeast(0)
        val windowEnd = (anchorIndex + rowsBelow).coerceAtMost(rows.lastIndex)

        // 各行实测高度，key 为绝对行号。长歌词会折行，行高不再统一，
        // 位移必须按实测值累加而不是 LINE_HEIGHT 的整数倍——
        // 否则一旦出现折行，当前行就会逐行累积偏移、越滚越偏离锚点。
        val rowHeights = remember(rows) { mutableStateMapOf<Int, Int>() }

        val density = LocalDensity.current.density

        // 当前行之前所有行的实高之和，即当前行在列内的顶边位置。
        // 未测量到的行按**各自的标称高度**估算（带译文的行更高），
        // 而不是一律按 LINE_HEIGHT——仅发生在首帧，但一律按单行算会让
        // 首帧的位移偏一大截，表现为歌词刚出现时抖一下。
        //
        // **从 0 数起而不是从 windowStart 数起**，这是消掉排版跳变的关键。
        //
        // 早先从 windowStart 数：窗口每前进一行，这个和就**少掉一整行的高度**，
        // 于是 targetOffsetY 出现一个阶跃。那个阶跃就是「排版跳变」——
        // 整列瞬间位移一行，再靠 pendingShift 补一个反向偏移抵消掉、
        // 然后把偏移动画回 0 冒充滚动。
        //
        // 那套机制有个躲不掉的缺陷：补偿在**组合期**加上，而把补偿降回 0 的
        // 动画要等 LaunchedEffect（组合与布局提交**之后**才跑）。于是必然有
        // 几帧是「补偿已加、动画还没开始」，整列停在被推下去的位置上。
        // 真机量到 44~52ms 的静止，紧接着突然启动——用户说的
        // 「从 y1 直接蹦到 y2，不是移动过去」就是它。
        //
        // 改成绝对累加之后，换行只让这个和**增加一行的高度**（连续变化），
        // targetOffsetY 随之平滑变化，由每行的动画自然追过去。
        // **不再有阶跃，也就不需要补偿**，那整套 pendingShift / shiftEpoch /
        // carried 的机制连同它的时序缺陷一起消失了。
        //
        // 代价是要对 0 until anchorIndex 求和，长歌词末尾是几百次加法。
        // 用 remember 按 (anchorIndex, rows) 缓存，只在换行时重算一次；
        // 窗口外那些行没有实测高度，用标称高度——它们在屏幕外，
        // 折行与否不影响观感，而标称值是确定性的（组合期就能算）。
        val topOffsetPx = remember(rows, anchorIndex, rowHeights.size) {
            (0 until anchorIndex)
                .sumOf { (rowHeights[it] ?: rows[it].nominalHeightPx(density).toInt()).toDouble() }
                .toFloat()
        }

        // 把当前行的顶边推到屏幕第 anchorRow 行的位置。
        //
        // 锚点按**标称高度**累加，而不是实测高度、也不再是 LINE_HEIGHT 的
        // 整数倍。三者的区别是这块的关键：
        //
        // - **实测高度**（被否掉的）：一旦上方出现折行，当前行就会被顶下去
        //   半行。折行高度取决于句子长短与屏幕宽度，不可预测，
        //   盲操下焦点位置飘忽比精确对齐更糟。
        // - **LINE_HEIGHT 整数倍**（加译文前的写法）：假设每行等高。
        //   带译文的行有两倍高，这个假设不再成立，上方只要有一行带译文，
        //   当前行就会被顶下去一整个译文的高度。
        // - **标称高度累加**（现在）：每行按「有没有译文」记 1 或 2 个行高
        //   （见 LyricRowData.nominalLines）。它只取决于**数据**，
        //   组合期就能算出来、不依赖测量，所以仍然是确定性的。
        //
        // 折行导致的偏差仍像以前一样被排除在锚点之外——下方各行按实测高度
        // 自然排布，折行只影响它们之间的间距，不影响焦点位置。
        //
        // 这里**不做动画**：整列共用一条动画曲线正是"所有行同时同速平移"的根源。
        // 它是静态目标，由每行各自的动画去追（见 LyricRow），
        // 行与行之间的相位差就是错峰效果。
        //
        // 这个量在歌曲开头（windowStart 恒为 0）随换行单调减小，
        // 但窗口一旦开始滑动它就变成常数 —— 见 LyricRow 里对
        // pendingShift 的处理，位移不能只靠它驱动。
        // 锚点位置 = 当前行**上方 anchorRow 个 Row** 的标称高度之和。
        // 逐个 Row 往上累加真实的标称高度，而不是「行数 × LINE_HEIGHT」：
        // 带译文的行不是整两倍高（见 LyricRowData.nominalHeightDp）。
        //
        // 行数本身也随译文而变（见上面 anchorRow 的推导）：正因为这里按
        // 标称高度累加，「第几行」与「多高」才会脱钩，要靠少数一行补回来。
        // 数不满 anchorRow 个（歌曲开头）时就只算到第一行为止，
        // 当前行自然贴近顶边——与改动前一致。
        val anchorTopPx = (1..anchorRow)
            .map { anchorIndex - it }
            .takeWhile { it >= 0 }
            .sumOf { rows[it].nominalHeightPx(density).toDouble() }
            .toFloat()
        // 各行要追的目标，**纯绝对量**（与 windowStart 无关），所以连续。
        val targetOffsetY = anchorTopPx - topOffsetPx

        // Column 自己的原点补偿：windowStart 之前那些行的总高度。
        //
        // 这是整套方案的关键一步，配合绝对的 targetOffsetY 把窗口消掉。
        // 推导（设第 i 行、窗口起点 w、sum(a..b) 为行高之和）：
        //
        //   第 i 行在 Column 内的**布局位置** = sum(w..i)   ← 含 w，会跳
        //   本项平移                          = sum(0..w)
        //   该行自己的动画位移                = anchorTop - sum(0..i)
        //   ----------------------------------------------------------
        //   屏幕位置 = sum(w..i) + sum(0..w) + anchorTop - sum(0..i)
        //            = sum(0..i) + anchorTop - sum(0..i)
        //            = anchorTop                        ← w 被完全消掉
        //
        // 也就是说：窗口滑动在**布局**里造成的阶跃，与本项在同一帧里
        // 精确抵消，屏幕位置恒等于锚点。**全程没有动画参与**，
        // 因此不存在「补偿已加、把补偿降回 0 的动画还没开始」那个中间态
        // ——那正是旧方案（pendingShift + carried）躲不掉的缺陷，
        // 真机量到换行后 44~52ms 的静止，观感是「蹦一下再开始动」。
        //
        // 与旧方案的本质差别：旧方案是「先让它跳，再用动画拉回来」，
        // 这里是「让它压根不跳」。
        val windowTopPx = remember(rows, windowStart, rowHeights.size) {
            (0 until windowStart)
                .sumOf { (rowHeights[it] ?: rows[it].nominalHeightPx(density).toInt()).toDouble() }
                .toFloat()
        }

        // 整套 pendingShift / shiftEpoch 的补偿机制已经删掉。
        //
        // 它存在的唯一理由是修补「windowStart 前进导致 targetOffsetY 阶跃」，
        // 而那个阶跃现在被 windowTopPx 在布局里静态抵消了（见上面的推导）。
        // 没有阶跃就不需要补偿，也就没有那个躲不掉的时序缺陷：
        // 补偿在组合期加上，把它降回 0 的动画却要等 LaunchedEffect，
        // 中间几帧整列停在被推下去的位置上（真机 44~52ms）。

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
                // 把 Column 从「以 windowStart 为原点」搬回绝对坐标系。
                // 与各行绝对的 targetOffsetY 配合，windowStart 在最终屏幕
                // 位置里被完全消掉（推导见上面 windowTopPx 的注释）。
                // 这是**静态**平移、不参与任何动画——正因如此，
                // 窗口滑动那一帧的排版阶跃在同一帧里就被抵消了。
                .graphicsLayer { translationY = windowTopPx }
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
                    // offset 是**有向**的（负数表示在当前行上方），管清晰度。
                    //
                    // 它基于 focusIndex（立即变）而不是 anchorIndex
                    // （滞后 scrollDelayMs）——这正是焦点先行的实现方式：
                    // 焦点切了，排版还没动。把 offset 也改成基于 anchorIndex
                    // 的话，高亮会跟着一起延后，整个延迟就白设了。
                    //
                    // 位移那边不再需要「这行落在屏幕第几行」：整列共用一条
                    // 缓动曲线，快慢与屏幕位置无关了。
                    val offset = index - focusIndex
                    LyricRow(
                        row = rows[index],
                        // 用 currentIndex 而非 focusIndex：前奏期
                        // （currentIndex == -1）第一行只是被摆到锚点位置
                        // 占位，还没唱到，不该高亮。focusIndex 那里的
                        // `< 0 -> 0` 是给排版用的，不是给焦点用的。
                        isCurrent = index == currentIndex,
                        offset = offset,
                        targetOffsetY = targetOffsetY,
                        alignment = alignment,
                        spec = spec,
                        textColor = lyricColor,
                        // 扫光只给当前行：其余行是整行同亮度的上下文，
                        // 给它们也扫光会让「唱到哪」这个信号出现十几份。
                        karaokeReveal = if (index == currentIndex) karaokeReveal else null,
                        onHeightMeasured = { rowHeights[index] = it },
                    )
                }
            }
        }

    }
}

/**
 * 逐字高亮：已唱的词保持原色，未唱的压暗，**边界落在词与词之间**。
 *
 * ## 单位是词，不是像素
 *
 * 第一版是匀速逐像素推进的扫光，真机实测「一卡一卡」，两个原因：
 *
 * 1. **与语义单位脱节**：词长中位数 450ms（真机量 Remedy 一曲），
 *    光会长时间停在某个词的中间，看着像卡住。
 * 2. **每帧最多 4 次 drawContent + 4 个 saveLayer**（压暗底一次、
 *    逐折行重画已唱区各一次）。每个 saveLayer 都要分配并合成一个离屏
 *    缓冲，全宽文本节点上开销很大。
 *
 * 现在恒为 **1 次 drawContent + 若干纯色矩形**：整行画一遍原色，
 * 再在未唱区叠 `DstOut` 的压暗矩形。矩形是最便宜的绘制，
 * 且已唱区什么都不叠，于是天然保持文字原色——不能靠叠白色提亮，
 * 那会把字往白拉，浅色主题下会变成灰。
 *
 * 词内仍有一次快速揭示（见 [LyricsAnimSpec.karaokeRevealFraction]），
 * 否则整词一次点亮在 450ms 的节奏下仍是一跳一跳的。
 *
 * ## 折行必须逐折行处理
 *
 * 朴素做法是整块套一个横向渐变。那在**不折行**时是对的，一旦折行就会
 * 同时切两个折行——唱到一半时上行右半暗、下行右半也暗。那不是「不够
 * 精细」，而是看着像渲染 bug。而折行在这里是常态（`maxLines = 3`、
 * 字号 27sp 偏大），实验室的假歌词里本就专门放了必然折行的样本。
 *
 * 所以用 [layout] 按**字符索引**查实际像素位置（`getHorizontalPosition`）：
 * 居中对齐时每一折行的起点都不同，按整行宽度线性猜必然错。
 *
 * ## 兜底
 *
 * [layout] 尚未测量（首帧）时整行按已唱处理，即维持原有的整行高亮。
 * 宁可不高亮，也不要闪一下——逐字是增强，不该让基础功能退化。
 */
private fun Modifier.karaokeSweep(
    frame: () -> KaraokeFrame,
    layout: TextLayoutResult?,
    unsungAlpha: Float,
    /**
     * 整行升起的缩放，0..1。换行时由 1 动到 0，让抬起的字符平滑落回基线。
     * 非当前行恒为 0，逐字符绘制整条路径都不会走。
     */
    liftScale: () -> Float,
): Modifier = this.drawWithContent {
    if (layout == null) {
        // 还没测量出来：整行按已唱画，退化为原有的整行高亮。
        // 宁可不扫光，也不要闪一下——扫光是增强，不该让基础功能退化。
        drawContent()
        return@drawWithContent
    }

    val state = frame().reveal
    val total = layout.layoutInput.text.length

    // 逐字符绘制只在真的有位移时才走。没位移时回到改动前那条
    // 「1 次 drawContent + 压暗矩形」的路径——它便宜得多，
    // 而这块区域同时是触摸板，省下的帧预算直接关系到手势响应。
    val scale = liftScale().coerceIn(0f, 1f)
    val lift = if (scale > 0.001f) frame().lift else null
    if (lift != null) {
        drawLiftedChars(layout, lift, scale, state, unsungAlpha)
        return@drawWithContent
    }

    val dim = (1f - unsungAlpha).coerceIn(0f, 1f)
    if (dim <= 0f) {
        drawContent()
        return@drawWithContent
    }

    // **整行只画一次文本**，然后在「未唱」的区域上叠一层压暗的遮罩。
    //
    // 早先是反过来的：先按未唱亮度画一遍底，再逐折行 clip + saveLayer
    // 重画已唱的部分。那样一帧最多要 **4 次 drawContent + 4 个 saveLayer**
    // （底 1 次 + 每折行 1 次，maxLines=3），每个 saveLayer 都是一次离屏
    // 缓冲的分配与合成，全宽文本节点上开销很大——真机观感就是「一卡一卡」。
    // 现在恒为 1 次 drawContent + 若干个纯色矩形，矩形是最便宜的绘制。
    drawContent()

    // **揭示边界**：已点亮到的字符位置（含正在揭示的词的那一部分）。
    // 未唱区从这里一直到行尾，**只压暗一次**。
    //
    // 早先是分两段画的：先从 `sungChars`（正在唱的词的**起点**）压暗到
    // 行尾，再单独压暗该词尚未揭示的尾巴——两个矩形在该词的尾巴上**重叠**，
    // `DstOut` 叠两次是相乘：未唱 0.3 会变成 0.7×0.7 → **0.09**。
    // 观感就是「词一进入高亮先突然变得比未唱还暗，扫一遍后再跳到全亮」。
    // 现在只有一条边界、一次压暗，该词的尾巴与后面的词是同一个亮度。
    val anchorChar = state.sungChars.coerceIn(0, total)
    if (anchorChar >= total && state.activeEnd <= state.activeStart) return@drawWithContent

    val startLine = layout.getLineForOffset(anchorChar.coerceAtMost(total - 1))
    // 揭示边界的像素位置。getHorizontalPosition 给的是**实际**位置——
    // 居中对齐时每一折行的起点都不同，按整行宽度线性猜必然错（折行是常态）。
    //
    // 在**像素**上插值而不是在字符索引上：后者会让边界一个字母一个字母地跳，
    // 英文单词只有三四个字母时就退化成阶梯，词内揭示的「连续」就没有了。
    val edgeX = karaokeEdgeX(layout, state, total, startLine)

    for (lineIndex in startLine until layout.lineCount) {
        val top = layout.getLineTop(lineIndex)
        val bottom = layout.getLineBottom(lineIndex)
        val lineRight = layout.getLineRight(lineIndex)
        // 边界所在的折行从边界起压暗，再往下的折行整条都没唱
        val left = if (lineIndex == startLine) {
            edgeX.coerceIn(layout.getLineLeft(lineIndex), lineRight)
        } else {
            layout.getLineLeft(lineIndex)
        }
        if (lineRight <= left) continue

        drawRect(
            color = Color.Black,
            alpha = dim,
            topLeft = Offset(left, top),
            size = Size(lineRight - left, bottom - top),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * 逐字符绘制：每个字符按各自的升起量上移，已唱/未唱各自压暗。
 *
 * ## 为什么是「整块重画 + 裁到一个字符」而不是逐字形绘制
 *
 * Compose 没有「画 TextLayoutResult 的第 i 个字符」这个 API。可行的做法
 * 只有把整块 layout 重画一遍、用 [clipRect] 裁到该字符的像素区间，
 * 再 [translate] 到它该在的位置。**排版完全交给已经算好的
 * [TextLayoutResult]**——字形、字距、折行、中英混排的断行规则全部沿用，
 * 我们只是把它切开、各自挪一下。
 *
 * 自己按字形绘制（取 Paint 逐字 drawText）看着更"直接"，但要重新处理
 * 字距调整、连字、双向文本，而这些正是文本排版最容易出偏差的地方——
 * 中文标点的压缩、英文的 kerning 一旦自己算就必然与未升起时对不齐，
 * 换行落回的那一刻会看到整行字微微错位。
 *
 * ## 成本
 *
 * 一行 N 个字符就是 N 次 drawText，每次都被裁到一个字符宽。裁切本身是
 * 便宜的（纯 clip，不开离屏缓冲），真正的成本是 N 次绘制调用。
 * **只有当前行走这条路径**，其余行仍是一次 drawContent，
 * 所以整屏十几行里只有一行付这个代价。
 *
 * [liftScale] 为 0 时调用方根本不会进来（见 [karaokeSweep]），
 * 于是"关掉升起"与改动前的成本完全一致。
 */
private fun DrawScope.drawLiftedChars(
    layout: TextLayoutResult,
    lift: (Int) -> Float,
    liftScale: Float,
    state: KaraokeReveal,
    unsungAlpha: Float,
) {
    val total = layout.layoutInput.text.length
    if (total <= 0) return

    // 亮暗分界仍然只有**一条**，口径与 karaokeSweep 完全一致：
    // 边界左边全亮、右边按 unsungAlpha 压暗。这里把它落到每个字符上——
    // 字符整体落在边界左边就全亮，右边就压暗，跨越边界的那一个
    // （正在揭示的词里的某个字）按边界位置再裁一刀（见下）。
    val edgeLine = layout.getLineForOffset(
        state.sungChars.coerceIn(0, (total - 1).coerceAtLeast(0))
    )
    val edgeX = karaokeEdgeX(layout, state, total, edgeLine)

    for (i in 0 until total) {
        val line = layout.getLineForOffset(i)
        // 字符的左右边界。getHorizontalPosition 给的是**实际**位置，
        // 折行与居中对齐时每一折行的起点都不同，按整行宽度线性猜必然错。
        val left = layout.getHorizontalPosition(i, usePrimaryDirection = true)
        val right = if (i + 1 <= total && layout.getLineForOffset((i + 1).coerceAtMost(total - 1)) == line) {
            layout.getHorizontalPosition(i + 1, usePrimaryDirection = true)
        } else {
            layout.getLineRight(line)
        }
        if (right <= left) continue

        val dy = -lift(i) * liftScale * density
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)

        // **裁切框严格等于这个字符的单元格**（上边只放宽它自己实际的升起量）。
        //
        // 这里栽过一次，真机截图 + 逐列量亮度才看出来：早先上下各放宽一个
        // 固定的 64px「余量」，本意是别把升起的字顶切掉。但 drawText 画的是
        // **整块 layout**（三个折行都在里面），裁切框一旦越过本折行的上下沿，
        // 相邻折行的字就从这道缝里漏进来、被重复画一遍。
        // 压暗矩形同样跨了折行，`DstOut` 是相乘的，于是**越靠前的已唱文字
        // 被压得越暗**——实测第 1 折行 151、第 2 折行已唱区 205、
        // 正在升起的词 255，整整三档，正是 CLAUDE.md 里记的
        // 「一个词要闪三段」那类缺陷的同源版本。
        //
        // 所以纵向只放宽 `-dy`（正好够这个字自己升起的量，恒不越过
        // 上一折行的基线），横向严格卡死。
        val slack = if (dy < 0f) -dy else 0f
        clipRect(left = left, top = top - slack, right = right, bottom = bottom) {
            translate(top = dy) {
                drawText(layout)
            }
            // 压暗矩形画在**同一个 clip 之内**，于是它与裁切框同界，
            // 绝不可能盖到邻字或邻折行上。每个字符因此只被压暗一次。
            val dim = charDim(i, left, right, edgeX, line, edgeLine, unsungAlpha)
            if (dim > 0f) {
                drawRect(
                    color = Color.Black,
                    alpha = dim,
                    topLeft = Offset(left, top - slack),
                    size = Size(right - left, (bottom - top) + slack),
                    blendMode = BlendMode.DstOut,
                )
            }
        }
    }
}

/**
 * 第 [index] 个字符该压暗多少（0 = 全亮）。
 *
 * 分界的像素位置是 [edgeX]（在第 [edgeLine] 折行上）。三种情形：
 * 整体在分界之前 → 全亮；在分界之后 → 全压暗；
 * **恰好跨越分界** → 按分界在这个字符内的位置按比例压——
 * 这保住了「边界在像素上平滑推进」这条，否则边界会一个字一个字地跳，
 * 英文单词只有三四个字母时就退化成阶梯。
 */
private fun charDim(
    index: Int,
    left: Float,
    right: Float,
    edgeX: Float,
    line: Int,
    edgeLine: Int,
    unsungAlpha: Float,
): Float {
    val dim = (1f - unsungAlpha).coerceIn(0f, 1f)
    if (dim <= 0f) return 0f
    // 分界所在折行之前的折行整条都唱过了，之后的整条都没唱
    if (line < edgeLine) return 0f
    if (line > edgeLine) return dim
    if (right <= edgeX) return 0f
    if (left >= edgeX) return dim
    // 跨越分界：按分界切进这个字符的比例插值
    val unsungPart = (right - edgeX) / (right - left)
    return dim * unsungPart.coerceIn(0f, 1f)
}

/**
 * 亮暗分界的像素位置。
 *
 * 这是**唯一**的分界：左边全亮、右边全暗，没有第三档。正在揭示的词
 * 只是让它在该词的像素区间内平滑推进——把「已唱」和「正在唱的词」
 * 分成两个矩形去画，就会在重叠处把亮度乘两次（见 [karaokeSweep]）。
 *
 * 只处理边界所在的那一折行：词跨折行极少（词内不断行），真出现时
 * 取本折行右端，下一折行由下一帧接手。
 */
private fun karaokeEdgeX(
    layout: TextLayoutResult,
    state: KaraokeReveal,
    total: Int,
    line: Int,
): Float {
    val lineLeft = layout.getLineLeft(line)
    val lineRight = layout.getLineRight(line)
    val anchor = state.sungChars.coerceIn(0, total)
    val anchorX = layout
        .getHorizontalPosition(anchor, usePrimaryDirection = true)
        .coerceIn(lineLeft, lineRight)

    // 没有正在揭示的词：边界就停在已唱的末尾（词与词之间的停顿）
    if (state.activeEnd <= state.activeStart) return anchorX

    // 正在揭示：在该词的像素区间内按进度插值
    val wordEnd = state.activeEnd.coerceIn(0, total)
    val wordEndX = if (layout.getLineForOffset((wordEnd - 1).coerceAtLeast(0)) == line) {
        layout.getHorizontalPosition(wordEnd, usePrimaryDirection = true)
            .coerceIn(lineLeft, lineRight)
    } else {
        lineRight
    }
    if (wordEndX <= anchorX) return anchorX
    return anchorX + (wordEndX - anchorX) * state.activeProgress.coerceIn(0f, 1f)
}

/**
 * [offset]：相对当前行的**有向**距离，负数表示在当前行上方，管清晰度。
 *
 * 不再有 screenRow / visibleRows：整列共用一条缓动曲线之后，行的快慢
 * 与它在屏幕上的位置无关了（见 [LyricsAnimSpec.blockEase]）。
 */
@Composable
private fun LyricRow(
    row: LyricRowData,
    isCurrent: Boolean,
    offset: Int,
    targetOffsetY: Float,
    alignment: LyricsAlignment,
    spec: LyricsAnimSpec,
    textColor: Color,
    /**
     * 逐字状态（扫光 + 升起）的读取器，null 表示这一行不做逐字效果
     * （非当前行、或这首歌没有逐字数据）。传 lambda 而非值是为了让它
     * 只在绘制阶段被读到——见 [rememberKaraokeReveal]。
     */
    karaokeReveal: (() -> KaraokeFrame)?,
    onHeightMeasured: (Int) -> Unit,
) {
    // 每行各自追 targetOffsetY，**时长相同、缓动曲线不同**：
    // 越靠屏幕上方起步越干脆，越靠下方起步越慢、后段才赶上来。
    // 滑动途中行距会先拉开再收拢，这就是「被拖着走」的观感来源。
    //
    // 方向的依据：整列往上走，最上面那行是这趟位移里走得最久、最先该
    // 落定的；越靠下的行越是被前面的行拖着走。方向写反过一版（顶懒底干脆），
    // 表现是最上面那行最晃，而它恰恰应该最稳。
    //
    // 时长对所有行相同是硬约束：若下面的行时长也更长，快歌连续换行时
    // 它们会追不上，位移累积起来越滚越偏。
    // **所有行共用同一条曲线**，整列作为刚体同速上移。
    // 试过两版错峰（逐行梯度、只让进场那行慢），都实测无效后删掉了，
    // 理由见 LyricsAnimSpec.blockEase 的注释。
    val rowEasing = easingFor(spec.blockEase)

    // **两条位移路径共用同一个 spec，且都不带延迟。**
    //
    // 这里曾经是两个 spec：scrollSpec 带 delayMillis = scrollDelayMs
    // 用来实现焦点先行，shiftSpec 不带延迟因为它回收的是「已经发生的
    // 排版跳变」，等不得（延迟它会让跳变裸露在屏幕上 3~4 帧，
    // 真机逐帧量到第 1 帧跳 76px 后静止 3~4 帧，就是「一卡一卡」）。
    //
    // 但那个方案只在**歌曲开头**成立：windowStart 一旦跟着当前行走，
    // targetOffsetY 就冻结成常数，位移全部由 shiftAnim 完成，
    // 而它恰恰是不带延迟的那条——于是真实播放的绝大部分时间里
    // 焦点先行根本没生效，滑块拖到 400ms 也还是「高亮与滚动同时进行」。
    //
    // 现在延迟上移到了排版侧（见 LyricsScroller 的 layoutIndex）：
    // 排版跳变本身就晚发生，两条路径于是天然一起延后，各自都不必再等。
    // 这也消掉了「两条路径对延迟需求相反」这个本来就很别扭的分叉。
    //
    val scrollSpec = tween<Float>(
        durationMillis = spec.settleTweenMs,
        easing = rowEasing,
    )

    // 这里曾有一整套窗口补偿机制（pendingShift / shiftEpoch / carried），
    // 已连同它的时序缺陷一起删掉。
    //
    // 它要修的是「windowStart 前进导致 targetOffsetY 阶跃」，做法是
    // 「先让整列跳一行，再用动画把它拉回来」。缺陷在于：补偿在组合期加上，
    // 而把补偿降回 0 的动画要等 LaunchedEffect（组合与布局提交**之后**
    // 才跑），于是必然有几帧是「补偿已加、动画还没开始」，整列停在被推下去
    // 的位置上——真机量到 44~52ms 的静止，观感就是用户说的
    // 「从 y1 直接蹦到 y2，不是移动过去」。换成 withFrameNanos 自驱动也
    // 只能从 52ms 压到 44ms，因为 effect 晚于布局提交这个事实没变。
    //
    // 现在的做法是**让它压根不跳**：targetOffsetY 改成绝对量（与
    // windowStart 无关），Column 自己挂一个 windowTopPx 的静态平移，
    // 两者在同一帧的布局里精确抵消（推导见 LyricsScroller 里 windowTopPx
    // 的注释）。没有阶跃就不需要补偿，也就没有那个中间态。

    val offsetAnim = remember { Animatable(targetOffsetY) }
    // 首帧（容器尚未测量，targetOffsetY 还是基于估算值）不该看到位移从 0 走到位，
    // 那会让歌词每次出现都先抖一下。snapTo 只在这一帧生效，之后都走 animateTo。
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(targetOffsetY, scrollSpec) {
        if (!settled) {
            offsetAnim.snapTo(targetOffsetY)
            settled = true
        } else {
            // **现在这是唯一的位移路径**，歌曲开头与中段走同一条，
            // 不再有「唱到第 6 行手感突然变一下」的问题——
            // 那正是早先两条路径（offsetAnim / shiftAnim）并存的代价。
            //
            // 不带延迟：targetOffsetY 本身就是从滞后的 anchorIndex 算出来的，
            // 它变化的那一刻焦点已经先行过了。
            offsetAnim.animateTo(targetOffsetY, animationSpec = scrollSpec)
        }
    }

    // 统一字号后，当前行与其余行的区分**全部**由这里的透明度和下面的模糊承担。
    //
    // delayMillis 让清晰度的变化**等位移基本走完再开始**：两件事同时进行时
    // 是「一边往上滚一边对焦」，挤在一起显得急。延迟略小于位移落定时间，
    // 两段稍有交叠而不是完全排队——完全排队会有个能察觉的停顿。
    val animatedAlpha by animateFloatAsState(
        targetValue = spec.alphaAt(offset, isCurrent),
        animationSpec = tween(
            durationMillis = spec.fadeAnimMs,
            delayMillis = spec.focusDelayMs,
            easing = FastOutSlowInEasing,
        ),
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
    // 模糊也要过渡：换行时直接跳到 0 是整个"生硬感"里最刺眼的一跳。
    // 延迟与 alpha 完全一致——两者是同一件事（建立焦点）的两个侧面，
    // 错开会让字先变清晰再去掉模糊，像对焦对了两次。
    //
    // 曾为性能把这里改成「不做动画、直接跳档」，配合半径离散化。
    // 那套实测是净损失（见 LyricsAnimSpec.blurSteps），已一并退回。
    // 真正有效的省法是**减少挂 blur 的行数**（blurCutoffLines）。
    val animatedBlur by animateDpAsState(
        targetValue = blurRadius,
        animationSpec = tween(
            durationMillis = spec.fadeAnimMs,
            delayMillis = spec.focusDelayMs,
            easing = FastOutSlowInEasing,
        ),
        label = "lyricBlur",
    )

    // 整行的升起缩放：当前行为 1，失焦后动到 0，让抬起的字符**平滑落回基线**。
    //
    // 换行时整行一起落，而不是每个字符自己落回去：后者会让一行里的字
    // 此起彼伏，像在抖。而硬切回 0 更糟——换行本来就是整列在滚动的时刻，
    // 多一个跳变很容易被读成滚动出了问题。
    //
    // 时序复用 fadeAnimMs / focusDelayMs，不引入新参数：落回与淡出、
    // 去模糊是同一件事（焦点移开）的三个侧面，各给一套时序只会让它们错开。
    // 用非委托形式接住 State：委托（by）会在**组合期**读取，
    // 于是整行每帧重组；持有 State 本身、在 drawWithContent 里才 .value，
    // Compose 据此把它的变化降级成只重绘。
    val liftScale = animateFloatAsState(
        targetValue = if (isCurrent && spec.karaokeLiftEnabled) 1f else 0f,
        animationSpec = tween(
            durationMillis = spec.fadeAnimMs,
            delayMillis = spec.focusDelayMs,
            easing = FastOutSlowInEasing,
        ),
        label = "lyricLift",
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
            //
            // **只剩一个来源**。早先是三段相加（offsetAnim + shiftAnim +
            // pendingShift），那是为了补窗口滑动的排版阶跃；现在阶跃由
            // Column 的 windowTopPx 静态抵消，位移就只有这一条路径了。
            .graphicsLayer { translationY = offsetAnim.value },
        contentAlignment = Alignment.Center,
    ) {
        val textAlign = when (alignment) {
            LyricsAlignment.CENTER -> TextAlign.Center
            LyricsAlignment.START -> TextAlign.Start
        }

        // 模糊与透明度挂在**整个 Row**上，而不是各自挂给原文和译文。
        //
        // 这是「Row 是动画的原子单位」在渲染上的落点：原文与译文共享
        // 同一个 alpha / blur，作为一个整体淡入淡出、一起对焦——对齐
        // Apple Music，译文在视觉上是这一句的一部分，不是一个跟它
        // 抢焦点的独立行。分别挂的话，远处行的译文会被叠乘到几乎看不见，
        // 而那时原文还读得清，看着像译文加载失败。
        //
        // 顺序与改动前一致，两条都是实测踩出来的：
        // - blur 必须排在 padding **之前**（更外层、作用于整行宽度）。
        //   BlurNode 恒 clip=true，裁切边界就是它自己那一层的排版矩形；
        //   排在 padding 之后时那个矩形已被 SIDE_PADDING 内缩过，模糊光晕
        //   会在距边缘 SIDE_PADDING 处被硬切一刀——居中对齐时短句离边界远
        //   看不出来，靠左对齐时每行行首都贴着这条边界，远处那些糊得厉害的
        //   行左边就像被竖着裁掉一块。
        // - alpha 必须在 blur **之后**（更内层）：模糊作用于已绘制内容，
        //   反过来会先被 alpha 压暗再模糊，远处行几乎看不见。
        Column(
            horizontalAlignment = when (alignment) {
                LyricsAlignment.CENTER -> Alignment.CenterHorizontally
                LyricsAlignment.START -> Alignment.Start
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 零半径时不要挂 blur：半径 0 也照样裁，挂着只会白白
                    // 引入一个裁切边界。当前行半径恒为 0。
                    if (animatedBlur > 0.dp) Modifier.blur(animatedBlur) else Modifier
                )
                .padding(horizontal = SIDE_PADDING, vertical = 6.dp)
                .graphicsLayer { this.alpha = animatedAlpha },
        ) {
            // 逐字扫光只作用于当前行，且只在这首歌有字级时间表时才启用。
            // 拿不到时 karaokeChars 恒为 null，下面退化为整行同亮度——
            // 与加逐字支持之前完全一致（约六成的歌走这条路）。
            var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

            Text(
                text = row.text,
                // 折行的句子里，第二行也要跟着靠左，所以对齐要落在 textAlign 上
                // 而不是容器的 alignment——后者只摆放整个文本块的位置，
                // 块内各折行仍会按 textAlign 居中。
                textAlign = textAlign,
                fontSize = FONT_SIZE,
                fontFamily = LyricFont,
                // 当前行和其余行都用 Bold：字重整体加粗更接近 Apple Music 的观感。
                // 两档都落在真实字体文件上（只随包了 Medium 和 Bold 两个档），
                // 不会触发系统的合成伪粗体——伪粗体在中文上会把笔画糊成一团。
                fontWeight = FontWeight.Bold,
                color = textColor,
                // 折行上限 3 行：绝大多数歌词两行够用，留第三行兜底超长句；
                // 再多就会把上下文行全挤出屏幕，反而看不出唱到哪了
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = FONT_SIZE * 1.3f,
                // 扫光要按**字符索引**查实际像素位置，所以必须接住布局结果。
                // 折行时每一折行的起止 x 都不同（尤其居中对齐），
                // 没有它就只能按整行宽度线性猜，那在折行时必然错。
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (karaokeReveal != null) {
                            Modifier.karaokeSweep(
                                frame = karaokeReveal,
                                layout = textLayout,
                                unsungAlpha = spec.karaokeUnsungAlpha,
                                // 传 lambda 而非值：liftScale 每帧都在变，
                                // 直接传值会让整行每帧重组，而这块区域
                                // 同时是触摸板。读在 drawWithContent 里
                                // Compose 就只重绘——口径同 karaokeReveal。
                                liftScale = { liftScale.value },
                            )
                        } else {
                            Modifier
                        }
                    ),
            )

            row.translation?.let { translation ->
                Spacer(Modifier.height(TRANSLATION_GAP))
                Text(
                    text = translation,
                    textAlign = textAlign,
                    // 小一号字表达从属关系，不再额外压暗——整个 Row 共用
                    // 一个 alpha，见上面那段。
                    fontSize = TRANSLATION_FONT_SIZE,
                    fontFamily = LyricFont,
                    // 译文用 Medium 而非 Bold：与原文同字重时，即便字号小一档
                    // 两行仍会互相争夺注意力。这也正是随包 Medium 档的用处。
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    // 译文比原文更容易超长（中文译文往往比英文原文字数多），
                    // 但仍限 2 行：译文占掉三行会把上下文行全挤出屏幕。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = TRANSLATION_FONT_SIZE * 1.3f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

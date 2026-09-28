package com.nudge.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.config.LyricsAlignment
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 实验室里循环播放的假歌词。
 *
 * 刻意不取真实歌曲：调参需要**可复现**的素材，真实播放要等切歌、
 * 要等副歌，试一组参数就得等半分钟。这里几行短句配一行长到必然折行的，
 * 是因为折行是动画里最容易露馅的场景（行高不一，位移累加一旦算错就飘）。
 */
/**
 * 假歌词样本（自造文本，非任何真实歌曲）。
 *
 * 刻意混着有译文和没译文的行：真实的网易云译文普遍比原文少一两行
 * （纯语气词、重复副歌往往不译），而**行高不统一**正是锚点计算最容易
 * 出错的地方。全都有译文的样本测不出这条。
 *
 * 其中一句故意写得很长，用来验证折行；另有一句的译文很长，
 * 因为中文译文往往比英文原文字数多，译文自己折行是真实场景。
 */
private val SAMPLE_ROWS = listOf(
    LyricRowData("夜色渐浓 风穿过窗", "Night thickens, wind through the window"),
    LyricRowData("我把心事折进纸飞机", "I fold my thoughts into a paper plane"),
    LyricRowData("你说过的话还留在原地"),
    LyricRowData(
        "这一句故意写得很长很长 好让它折成两行 看看换行时位移会不会算歪",
        "这一句的译文同样写得很长很长 用来验证译文自己折行时行高还准不准",
    ),
    LyricRowData("街灯一盏一盏亮起来", "Street lamps light up one by one"),
    LyricRowData("像谁在数着回家的路"),
    LyricRowData("别回头", "Don't look back"),
    LyricRowData("有些告别不必说出口"),
    LyricRowData("时间会替我们收好", "Time will keep them for us"),
    LyricRowData("所有来不及讲完的以后", "All the afters we never finished saying"),
)

/** 换行间隔的可调范围（毫秒）。下限 800ms 模拟快歌的连续换行。 */
private const val MIN_INTERVAL_MS = 800f
private const val MAX_INTERVAL_MS = 5000f

/**
 * 歌词动画实验室，**仅 debug 包可见**（入口在设置页，包在 `BuildConfig.DEBUG` 里）。
 *
 * 上半屏用假歌词跑真实的 [LyricsScroller]——与真实播放共用同一个 composable，
 * 若这里另写一份，调出来的参数在真实场景下未必是同样的观感。
 * 下半屏是参数滑块，改一下立刻生效。
 *
 * 改动同时写进 [LyricsAnimOverride]，**返回主界面后真实歌词也跟着变**。
 * 这是必要的：预览框只有 340dp 高，能看到的行数远少于真实全屏，
 * 而梯度的观感与可见行数强相关，只在框里调容易看走眼。
 *
 * 参数**只存在内存里**，杀进程即回默认，不落盘也不进 `NudgeConfig`。
 * 歌词动画的好坏没有「因人而异」的成分，做成用户可持久化的配置只会让
 * 线上出现一堆没人能复现的观感问题。调好之后要把值手写回
 * [LyricsAnimSpec] 的默认值——界面底部的「打印当前参数」会把一整段
 * 可直接粘贴的构造调用打到界面上，省得逐个滑块抄数字。
 */
@Composable
fun LyricsLabScreen(onBack: () -> Unit) {
    // 初值读回上次调的值而不是恒取 DEFAULT：否则来回切主界面／实验室
    // 每次都从默认重来，刚调好的一组白丢。
    var spec by remember { mutableStateOf(LyricsAnimOverride.peek() ?: LyricsAnimSpec.DEFAULT) }

    // 是否已经动过参数。只是「进来看一眼」不该点亮主界面的角标——
    // 角标要回答的是「现在跑的是不是实验室调出来的参数」，
    // 没动过就还是代码里的默认值，点亮就成了假信号。
    //
    // 初值跟随 peek()：之前调过、这次只是再进来看看，那覆盖本就还生效着。
    var touched by remember { mutableStateOf(LyricsAnimOverride.peek() != null) }

    // 改动同步给主界面。放在一个 LaunchedEffect 而不是每个滑块的 onChange 里：
    // 滑块有十几个，逐个加调用漏一个就会出现「这一项调了主界面不动」的
    // 静默不一致，而这种不一致在调参时极难察觉——会被当成参数本身没效果。
    //
    // touched 同时作为 key：「恢复默认」会把它置回 false 并 clear()，
    // 若只 key spec，那次 clear 会被本 effect 立刻重新 set 回去，角标灭不掉。
    LaunchedEffect(spec, touched) {
        if (touched) LyricsAnimOverride.set(spec)
    }

    // 所有参数滑块都走这一个入口，而不是各自 `spec = spec.copy(...)`：
    // touched 只在这里置位，滑块再多也漏不掉。
    val updateSpec: ((LyricsAnimSpec) -> LyricsAnimSpec) -> Unit = { transform ->
        spec = transform(spec)
        touched = true
    }

    var intervalMs by remember { mutableStateOf(2400f) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var alignment by remember { mutableStateOf(LyricsAlignment.CENTER) }
    // 译文显隐在实验室里是本地状态，**不写回 NudgeConfig**：这里是取景器，
    // 调的是观感参数，不该顺手改用户的真实配置。真实开关在设置页。
    var showTranslation by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<String?>(null) }

    // 自动换行。key 带 intervalMs，拖动滑块会重启循环，新节奏立刻生效
    // 而不是等当前这一轮 delay 走完。
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs.toLong())
            currentIndex = (currentIndex + 1) % SAMPLE_ROWS.size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "歌词动画实验室",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // 预览区固定高度而非按比例：比例会随机型变，而行数（视野里能看到几行）
        // 直接决定梯度的观感，换台机器调出来的参数就对不上了。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            LyricsScroller(
                rows = if (showTranslation) SAMPLE_ROWS else SAMPLE_ROWS.map {
                    it.copy(translation = null)
                },
                currentIndex = currentIndex,
                alignment = alignment,
                spec = spec,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SectionTitle("节奏")
            LabSlider(
                label = "换行间隔",
                value = intervalMs,
                range = MIN_INTERVAL_MS..MAX_INTERVAL_MS,
                display = "${intervalMs.roundToInt()}ms",
                hint = "仅影响实验室的假数据，不是真实参数",
                onChange = { intervalMs = it },
            )
            LabSlider(
                label = "整列位移时长",
                value = spec.settleTweenMs.toFloat(),
                range = 150f..900f,
                display = "${spec.settleTweenMs}ms",
                hint = "所有行共用，同时开始同时结束。错峰靠各行不同的缓动曲线，" +
                    "不靠时长差——时长也差的话快歌连续换行时下面的行会追不上",
                onChange = { v -> updateSpec { it.copy(settleTweenMs = v.roundToInt()) } },
            )
            LabSlider(
                label = "焦点时序偏移",
                value = spec.focusLeadMs.toFloat(),
                // 负到正贯通，让整个时序空间能连着扫过来——这块栽过四次的
                // 教训就是端点数字看着都没问题，只有连续对比才分得出来。
                range = -500f..400f,
                display = when {
                    spec.focusLeadMs > 0 -> "焦点先行 ${spec.focusLeadMs}ms"
                    spec.focusLeadMs < 0 -> "位移先行 ${-spec.focusLeadMs}ms"
                    else -> "同时开始"
                },
                hint = "正数＝先高亮再滚动（Apple Music 的时序，默认）；" +
                    "负数＝先滚动再对焦（早先的口径）；0＝边移动边对焦，实测最急",
                onChange = { v -> updateSpec { it.copy(focusLeadMs = v.roundToInt()) } },
            )
            LabSlider(
                label = "淡入淡出时长",
                value = spec.fadeAnimMs.toFloat(),
                range = 80f..800f,
                display = "${spec.fadeAnimMs}ms",
                hint = "延迟结束后这段渐变本身有多长",
                onChange = { v -> updateSpec { it.copy(fadeAnimMs = v.roundToInt()) } },
            )

            SectionTitle("锚点")
            LabSlider(
                label = "当前行固定在第几行",
                value = spec.anchorRow.toFloat(),
                range = 0f..6f,
                steps = 5,
                display = "第 ${spec.anchorRow + 1} 行",
                // 点明这是不带译文时的基准：预览用的 SAMPLE_ROWS 混了带译文的行，
                // 实际锚点会自动提一行（见 LyricsAnimSpec.anchorRowFor），
                // 不说明的话会以为滑块调了没生效。
                hint = "上方留 ${spec.anchorRow} 行已唱过的做上下文，其余是预读区。" +
                    "这是无译文时的基准，有译文时自动提到第 ${spec.anchorRowFor(true) + 1} 行",
                onChange = { v -> updateSpec { it.copy(anchorRow = v.roundToInt()) } },
            )

            // 只剩一个滑块：整列共用一条曲线，逐行梯度已经删掉了
            // （两版错峰都实测无效，理由见 LyricsAnimSpec.blockEase）。
            // 早先这里有「底部懒惰度」「梯度跨度」两个滑块和一张逐行梯度表，
            // 它们现在都驱动不了任何东西——摆着只会让人以为调了有用，
            // 与「灵敏度只在 debug 包可调」那条同一个口径：
            // 没有效果的控件比没有控件更糟。
            SectionTitle("缓动曲线（整列同速）")
            LabSlider(
                label = "懒惰度",
                value = spec.easeTop,
                range = 0f..1f,
                display = fmt(spec.easeTop),
                hint = "贝塞尔第一个控制点的 x。0 = 起步就全速（最干脆的吸附感）；" +
                    "越大起步越平、后段越赶，大到一定程度就是「先拱一下再走」的急",
                onChange = { v -> updateSpec { it.copy(easeTop = v) } },
            )
            CurveTable(spec)

            SectionTitle("清晰度")
            LabSlider(
                label = "统一模糊（两档模型）",
                value = spec.uniformBlurDp,
                // 下限取 -1 表示切回逐行渐进的旧模型，便于 A/B
                range = -1f..14f,
                display = if (spec.uniformBlurDp < 0f) {
                    "关（用逐行渐进）"
                } else {
                    "${fmt(spec.uniformBlurDp)}dp"
                },
                hint = "非当前行统一这一档，与距离无关（Apple Music 的形态）。" +
                    "**流畅度的主要杠杆**：半径只有一种，Skia 才谈得上复用。" +
                    "拖到最左切回旧的逐行渐进模型对比",
                onChange = { v -> updateSpec { it.copy(uniformBlurDp = v) } },
            )
            LabSlider(
                label = "当前行模糊",
                value = spec.currentBlurDp,
                range = 0f..3f,
                display = "${fmt(spec.currentBlurDp)}dp",
                hint = "Apple Music 的当前行并非纯锐利，边缘带一点柔光。" +
                    "0 最省（少一个离屏缓冲）",
                onChange = { v -> updateSpec { it.copy(currentBlurDp = v) } },
            )
            LabSlider(
                label = "最大模糊（仅旧模型）",
                value = spec.maxBlurDp,
                range = 0f..20f,
                display = "${fmt(spec.maxBlurDp)}dp",
                hint = "峰值要守住「最远处仍认得出字」。统一模糊开启时本项不生效",
                onChange = { v -> updateSpec { it.copy(maxBlurDp = v) } },
            )
            LabSlider(
                label = "模糊跨度",
                value = spec.blurRampLines,
                range = 1f..20f,
                display = "${fmt(spec.blurRampLines)} 行",
                hint = "与峰值配着调，决定观感的是斜率不只是峰值",
                onChange = { v -> updateSpec { it.copy(blurRampLines = v) } },
            )
            LabSlider(
                label = "模糊行数上限",
                value = spec.blurCutoffLines.toFloat(),
                range = 0f..17f,
                display = if (spec.blurCutoffLines <= 0) {
                    "不限（全部挂）"
                } else {
                    "${spec.blurCutoffLines} 行"
                },
                hint = "性能杠杆：每个挂 blur 的行都要一个全屏离屏缓冲。" +
                    "拖到 0 看观感上界，往小拖看流畅度换来多少",
                onChange = { v -> updateSpec { it.copy(blurCutoffLines = v.roundToInt()) } },
            )
            LabSlider(
                label = "上方跨度倍率",
                value = spec.upperFadeScale,
                range = 0.3f..2f,
                display = fmt(spec.upperFadeScale),
                hint = "1 为上下对称；小于 1 则已唱过的行糊得更快",
                onChange = { v -> updateSpec { it.copy(upperFadeScale = v) } },
            )
            LabSlider(
                label = "近处透明度",
                value = spec.alphaNear,
                range = 0.2f..1f,
                display = fmt(spec.alphaNear),
                hint = null,
                onChange = { v -> updateSpec { it.copy(alphaNear = v) } },
            )
            LabSlider(
                label = "最远透明度",
                value = spec.alphaFar,
                range = 0.05f..0.8f,
                display = fmt(spec.alphaFar),
                hint = null,
                onChange = { v -> updateSpec { it.copy(alphaFar = v) } },
            )
            LabSlider(
                label = "每行衰减",
                value = spec.alphaStep,
                range = 0f..0.15f,
                display = fmt(spec.alphaStep),
                hint = null,
                onChange = { v -> updateSpec { it.copy(alphaStep = v) } },
            )

            SectionTitle("其他")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        alignment = when (alignment) {
                            LyricsAlignment.CENTER -> LyricsAlignment.START
                            LyricsAlignment.START -> LyricsAlignment.CENTER
                        }
                    }
                ) {
                    Text("对齐：${alignment.displayName}")
                }
                // 译文这一项只切预览，不进 spec：它不是动画参数，
                // 所以也不该点亮 LAB 角标（角标的语义是「跑着实验室的动画参数」）。
                TextButton(onClick = { showTranslation = !showTranslation }) {
                    Text("译文：${if (showTranslation) "开" else "关"}")
                }
                // 不走 updateSpec：这里要的恰恰相反，是把 touched 清掉。
                // clear() 与 touched=false 必须成对——前者让主界面回到默认值，
                // 后者既熄灭角标，又阻止上面那个 effect 立刻把覆盖 set 回去。
                TextButton(
                    onClick = {
                        spec = LyricsAnimSpec.DEFAULT
                        touched = false
                        LyricsAnimOverride.clear()
                    }
                ) {
                    Text("恢复默认")
                }
                TextButton(onClick = { snapshot = spec.toSourceSnippet() }) {
                    Text("打印当前参数")
                }
            }

            if (snapshot != null) {
                Text(
                    text = snapshot!!,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * 把整列那条缓动曲线的进度采样打出来。
 *
 * 早先这是一张**逐行**梯度表，因为那时每行的曲线都不同，在这上面栽过几次，
 * 每次都是「看端点数字觉得没问题」：有一组看着有梯度、实际相邻行差太小，
 * 肉眼合成刚体成了线性滚动；另一组方向整个写反，最上面那行最晃而它本该
 * 最先落定。所以当初要把逐行的值摊开看。
 *
 * 现在整列共用一条曲线（见 LyricsAnimSpec.blockEase），逐行摊开已无意义，
 * 但**进度采样仍然有用**：「¼ 时刻走了多少」比端点数字更能反映
 * 「先快后慢」到底有多快——吸附感强不强全看前段那一截。
 */
@Composable
private fun CurveTable(spec: LyricsAnimSpec) {
    val e = spec.blockEase
    // 直接采样渲染侧真正在用的那条曲线，不另算近似值，
    // 否则表里的数字和屏幕上跑的不是一回事，调参就是瞎调。
    val easing = CubicBezierEasing(e.coerceIn(0f, 1f), 0f, 0.25f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = "时刻    ¼      ½      ¾",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        Text(
            text = "走了  %5.0f%%  %5.0f%%  %5.0f%%".format(
                easing.transform(0.25f) * 100f,
                easing.transform(0.5f) * 100f,
                easing.transform(0.75f) * 100f,
            ),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Text(
            text = "整列同速，${spec.settleTweenMs}ms 走完。¼ 时刻走得越多，吸附感越强",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 一条带标签与读数的滑块。
 *
 * 读数必须显示：盲拖滑块调不出可复现的参数，最后要抄回代码里的是数字而不是手感。
 */
@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    hint: String?,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = display,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

private fun fmt(v: Float): String =
    if (v >= 10f) v.roundToInt().toString() else String.format("%.3f", v).trimEnd('0').trimEnd('.')

/**
 * 把当前参数拼成可直接粘贴回 [LyricsAnimSpec] 默认值的源码片段。
 *
 * 不用剪贴板：真机上调参时手边未必有键盘，且剪贴板在分屏／后台限制下
 * 时灵时不灵；直接把文本显示出来，截图或照抄都行。
 */
private fun LyricsAnimSpec.toSourceSnippet(): String = buildString {
    appendLine("anchorRow = $anchorRow,")
    appendLine("easeTop = ${fmt(easeTop)}f,")
    appendLine("maxBlurDp = ${fmt(maxBlurDp)}f,")
    appendLine("uniformBlurDp = ${fmt(uniformBlurDp)}f,")
    appendLine("currentBlurDp = ${fmt(currentBlurDp)}f,")
    appendLine("blurRampLines = ${fmt(blurRampLines)}f,")
    appendLine("blurCutoffLines = $blurCutoffLines,")
    appendLine("upperFadeScale = ${fmt(upperFadeScale)}f,")
    appendLine("alphaNear = ${fmt(alphaNear)}f,")
    appendLine("alphaFar = ${fmt(alphaFar)}f,")
    appendLine("alphaStep = ${fmt(alphaStep)}f,")
    appendLine("settleTweenMs = $settleTweenMs,")
    appendLine("fadeAnimMs = $fadeAnimMs,")
    append("focusLeadMs = $focusLeadMs,")
}

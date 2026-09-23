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
private val SAMPLE_LINES = listOf(
    "夜色渐浓 风穿过窗",
    "我把心事折进纸飞机",
    "你说过的话还留在原地",
    "这一句故意写得很长很长 好让它折成两行 看看换行时位移会不会算歪",
    "街灯一盏一盏亮起来",
    "像谁在数着回家的路",
    "别回头",
    "有些告别不必说出口",
    "时间会替我们收好",
    "所有来不及讲完的以后",
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
    var snapshot by remember { mutableStateOf<String?>(null) }

    // 自动换行。key 带 intervalMs，拖动滑块会重启循环，新节奏立刻生效
    // 而不是等当前这一轮 delay 走完。
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs.toLong())
            currentIndex = (currentIndex + 1) % SAMPLE_LINES.size
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
                lines = SAMPLE_LINES,
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
                label = "淡入淡出延迟",
                value = spec.fadeDelayMs.toFloat(),
                range = 0f..600f,
                display = "${spec.fadeDelayMs}ms",
                hint = "等位移基本走完再开始对焦。设 0 就是「边移动边对焦」，" +
                    "两件事挤在一起会显得急。略小于当前行落定时间最顺",
                onChange = { v -> updateSpec { it.copy(fadeDelayMs = v.roundToInt()) } },
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
                hint = "上方留 ${spec.anchorRow} 行已唱过的做上下文，其余是预读区",
                onChange = { v -> updateSpec { it.copy(anchorRow = v.roundToInt()) } },
            )

            SectionTitle("缓动梯度（拖尾自上而下递增）")
            LabSlider(
                label = "顶部懒惰度（第 1 行）",
                value = spec.easeTop,
                range = 0f..1f,
                display = fmt(spec.easeTop),
                hint = "0 = 起步就全速（最干脆）。最上面那行是这趟位移的终点，" +
                    "该最先到位，所以取小值",
                onChange = { v -> updateSpec { it.copy(easeTop = v) } },
            )
            LabSlider(
                label = "底部懒惰度（最下一行）",
                value = spec.easeBottom,
                range = 0f..1f,
                display = fmt(spec.easeBottom),
                hint = "越大起步越慢、后段越赶，「被拖着走」越明显。" +
                    "与顶部的差要够大，否则相邻行差太小，肉眼会合成一个刚体",
                onChange = { v -> updateSpec { it.copy(easeBottom = v) } },
            )
            LabSlider(
                label = "梯度跨度",
                value = spec.gradientRampLines,
                range = 1f..14f,
                display = "${fmt(spec.gradientRampLines)} 行",
                hint = "从屏幕顶边往下数，超出后统一取底部档（最懒）。" +
                    "太窄则梯度早早跑完、下面一坨一起动；太开则相邻行差太小",
                onChange = { v -> updateSpec { it.copy(gradientRampLines = v) } },
            )
            GradientTable(spec)

            SectionTitle("清晰度")
            LabSlider(
                label = "最大模糊",
                value = spec.maxBlurDp,
                range = 0f..20f,
                display = "${fmt(spec.maxBlurDp)}dp",
                hint = "峰值要守住「最远处仍认得出字」",
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
 * 逐行列出缓动参数与进度采样。
 *
 * 加这张表是因为在这上面栽过几次，每次都是「看端点数字觉得没问题」：
 *
 * - 40→260 / 0.58→1 那组看着「有梯度」，实际上整个阅读区的阻尼都近临界，
 *   刚度相邻只差 27（肉眼合成刚体），于是整列就是线性滚动。
 * - 之后那组方向整个写反了：最上面那行最软最晃，而它本该是最先落定的。
 * - 再之后是弹簧本身的问题——速度峰值在极早期，观感是「往上拱一下」。
 *
 * 所以要把逐行的值摊开，一眼能看出**梯度朝哪个方向**、**相邻行差得够不够**。
 * 进度采样（25%/50% 时刻走了多少）比端点数字更能反映实际观感。
 */
@Composable
private fun GradientTable(spec: LyricsAnimSpec) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = "屏幕行  懒惰度   ¼时走了  ½时走了",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        (0..8).forEach { row ->
            val e = spec.easeAt(row)
            // 直接采样这一行实际会用的那条曲线，而不是另算一个近似值——
            // 表里的数字必须和屏幕上跑的是同一条曲线，否则调参就是瞎调。
            val easing = CubicBezierEasing(e.coerceIn(0f, 1f), 0f, 0.25f, 1f)
            val isAnchor = row == spec.anchorRow
            Text(
                text = "%4d  %7.2f  %6.0f%%  %6.0f%%%s".format(
                    row, e,
                    easing.transform(0.25f) * 100f,
                    easing.transform(0.5f) * 100f,
                    if (isAnchor) "  ← 当前行" else "",
                ),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isAnchor) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                },
            )
        }
        Text(
            text = "所有行 ${spec.settleTweenMs}ms 同时结束；越往下越晚发力",
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
    appendLine("easeBottom = ${fmt(easeBottom)}f,")
    appendLine("gradientRampLines = ${fmt(gradientRampLines)}f,")
    appendLine("maxBlurDp = ${fmt(maxBlurDp)}f,")
    appendLine("blurRampLines = ${fmt(blurRampLines)}f,")
    appendLine("upperFadeScale = ${fmt(upperFadeScale)}f,")
    appendLine("alphaNear = ${fmt(alphaNear)}f,")
    appendLine("alphaFar = ${fmt(alphaFar)}f,")
    appendLine("alphaStep = ${fmt(alphaStep)}f,")
    appendLine("settleTweenMs = $settleTweenMs,")
    appendLine("fadeAnimMs = $fadeAnimMs,")
    append("fadeDelayMs = $fadeDelayMs,")
}

package com.nudge.app.ui

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
 * 参数**只存在内存里**，退出即丢。这是刻意的：歌词动画的好坏没有「因人而异」
 * 的成分，做成用户可持久化的配置只会让线上出现一堆没人能复现的观感问题。
 * 调好之后把值手写回 [LyricsAnimSpec] 的默认值——界面底部的「打印当前参数」
 * 会把一整段可直接粘贴的构造调用打到界面上，省得逐个滑块抄数字。
 */
@Composable
fun LyricsLabScreen(onBack: () -> Unit) {
    var spec by remember { mutableStateOf(LyricsAnimSpec.DEFAULT) }
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
                label = "淡入淡出时长",
                value = spec.fadeAnimMs.toFloat(),
                range = 80f..800f,
                display = "${spec.fadeAnimMs}ms",
                hint = "要短于位移落定的时间，否则会「边移动边对焦」",
                onChange = { spec = spec.copy(fadeAnimMs = it.roundToInt()) },
            )

            SectionTitle("锚点")
            LabSlider(
                label = "当前行固定在第几行",
                value = spec.anchorRow.toFloat(),
                range = 0f..6f,
                steps = 5,
                display = "第 ${spec.anchorRow + 1} 行",
                hint = "上方留 ${spec.anchorRow} 行已唱过的做上下文，其余是预读区",
                onChange = { spec = spec.copy(anchorRow = it.roundToInt()) },
            )

            SectionTitle("弹簧梯度（沿屏幕自上而下）")
            LabSlider(
                label = "顶部刚度",
                value = spec.stiffnessTop,
                range = 10f..400f,
                display = fmt(spec.stiffnessTop),
                hint = "越小越软、拖尾越明显",
                onChange = { spec = spec.copy(stiffnessTop = it) },
            )
            LabSlider(
                label = "底部刚度",
                value = spec.stiffnessBottom,
                range = 40f..1200f,
                display = fmt(spec.stiffnessBottom),
                hint = "越大越干脆；新进场的行走这一档",
                onChange = { spec = spec.copy(stiffnessBottom = it) },
            )
            LabSlider(
                label = "顶部阻尼比",
                value = spec.dampingTop,
                range = 0.35f..1f,
                display = fmt(spec.dampingTop),
                hint = "低于 0.5 会晃两下以上，看着像故障",
                onChange = { spec = spec.copy(dampingTop = it) },
            )
            LabSlider(
                label = "底部阻尼比",
                value = spec.dampingBottom,
                range = 0.35f..1f,
                display = fmt(spec.dampingBottom),
                hint = "1 为临界阻尼，不过冲",
                onChange = { spec = spec.copy(dampingBottom = it) },
            )
            LabSlider(
                label = "梯度跨度",
                value = spec.gradientRampLines,
                range = 1f..14f,
                display = "${fmt(spec.gradientRampLines)} 行",
                hint = "从锚点往下数，超出后统一取底部档",
                onChange = { spec = spec.copy(gradientRampLines = it) },
            )

            SectionTitle("清晰度")
            LabSlider(
                label = "最大模糊",
                value = spec.maxBlurDp,
                range = 0f..20f,
                display = "${fmt(spec.maxBlurDp)}dp",
                hint = "峰值要守住「最远处仍认得出字」",
                onChange = { spec = spec.copy(maxBlurDp = it) },
            )
            LabSlider(
                label = "模糊跨度",
                value = spec.blurRampLines,
                range = 1f..20f,
                display = "${fmt(spec.blurRampLines)} 行",
                hint = "与峰值配着调，决定观感的是斜率不只是峰值",
                onChange = { spec = spec.copy(blurRampLines = it) },
            )
            LabSlider(
                label = "上方跨度倍率",
                value = spec.upperFadeScale,
                range = 0.3f..2f,
                display = fmt(spec.upperFadeScale),
                hint = "1 为上下对称；小于 1 则已唱过的行糊得更快",
                onChange = { spec = spec.copy(upperFadeScale = it) },
            )
            LabSlider(
                label = "近处透明度",
                value = spec.alphaNear,
                range = 0.2f..1f,
                display = fmt(spec.alphaNear),
                hint = null,
                onChange = { spec = spec.copy(alphaNear = it) },
            )
            LabSlider(
                label = "最远透明度",
                value = spec.alphaFar,
                range = 0.05f..0.8f,
                display = fmt(spec.alphaFar),
                hint = null,
                onChange = { spec = spec.copy(alphaFar = it) },
            )
            LabSlider(
                label = "每行衰减",
                value = spec.alphaStep,
                range = 0f..0.15f,
                display = fmt(spec.alphaStep),
                hint = null,
                onChange = { spec = spec.copy(alphaStep = it) },
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
                TextButton(onClick = { spec = LyricsAnimSpec.DEFAULT }) {
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
    appendLine("stiffnessTop = ${fmt(stiffnessTop)}f,")
    appendLine("stiffnessBottom = ${fmt(stiffnessBottom)}f,")
    appendLine("dampingTop = ${fmt(dampingTop)}f,")
    appendLine("dampingBottom = ${fmt(dampingBottom)}f,")
    appendLine("gradientRampLines = ${fmt(gradientRampLines)}f,")
    appendLine("maxBlurDp = ${fmt(maxBlurDp)}f,")
    appendLine("blurRampLines = ${fmt(blurRampLines)}f,")
    appendLine("upperFadeScale = ${fmt(upperFadeScale)}f,")
    appendLine("alphaNear = ${fmt(alphaNear)}f,")
    appendLine("alphaFar = ${fmt(alphaFar)}f,")
    appendLine("alphaStep = ${fmt(alphaStep)}f,")
    append("fadeAnimMs = $fadeAnimMs,")
}

package com.nudge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.action.HapticId
import com.nudge.app.action.HapticOverride
import com.nudge.app.action.HapticPalette
import com.nudge.app.action.HapticPlayer
import com.nudge.app.action.HapticSpec
import kotlin.math.roundToInt

/**
 * 振动实验室，**仅 debug 包可见**（入口在设置页，包在 `BuildConfig.DEBUG` 里）。
 *
 * 五种反馈各占一块，每块自带「试一下」按钮、包络示意图和参数滑块。
 * 结构上与歌词实验室的「一套参数 + 一个预览」不同，因为这里是
 * **五条形状互不相同的波形**，不是一套参数的五个取值——用通用编辑器的话，
 * 「单记重击」这种形状会摊上一堆无意义的参数（间隔曲线之于单脉冲）。
 *
 * ## 为什么要画包络图
 *
 * 振动是纯触觉的，改完参数只能靠手去试，而手的记忆很短——试到第三块时
 * 已经想不起第一块是什么手感了。包络图让「这条波形长什么样」在按下去之前
 * 就是可见的，调参时先看图排除明显不对的组合，再用手确认细节。
 *
 * 这与歌词实验室里那张逐行梯度表是同一个用途：把只能靠感觉判断的东西
 * 变成看得见的量，否则调参就是瞎调。
 *
 * 参数**只存内存**，杀进程即回默认（同 `LyricsAnimOverride`，
 * 理由见 [HapticOverride]）。调好之后点「打印当前参数」抄回 [HapticPalette]。
 */
@Composable
fun HapticLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { HapticPlayer(context) }

    // 离开这一页时掐掉正在跑的振动。收藏那条有 500ms 长，
    // 点完立刻返回的话它会继续在主界面上震，看着像误触发了手势。
    DisposableEffect(Unit) {
        onDispose { player.cancel() }
    }

    // 五块各自的当前参数。初值读回上次调的值而非恒取默认，
    // 否则来回切主界面／实验室时刚调好的一组白丢。
    var specs by remember {
        mutableStateOf(
            HapticPalette.ALL.associate { it.id to (HapticOverride.peek(it.id) ?: it.default) }
        )
    }
    var snapshot by remember { mutableStateOf<String?>(null) }

    // 所有滑块都走这一个入口：写回 HapticOverride 的动作只在这里发生，
    // 滑块再多也漏不掉。歌词实验室在这点上栽过——逐个 onChange 里各自
    // 同步，漏一个就出现「这一项调了主界面不动」的静默不一致。
    val updateSpec: (HapticId, (HapticSpec) -> HapticSpec) -> Unit = { id, transform ->
        val next = transform(specs.getValue(id))
        specs = specs + (id to next)
        HapticOverride.set(id, next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
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
                text = "振动实验室",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Text(
            text = "这台机器只有振幅控制（无 primitive、无频率），所以形状全靠" +
                "「脉冲间隔 + 振幅」的包络。改完立刻生效，返回后真实手势反馈也跟着变。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        HapticPalette.ALL.forEach { slot ->
            val spec = specs.getValue(slot.id)
            HapticBlock(
                label = slot.label,
                hint = slot.hint,
                spec = spec,
                onPlay = { player.play(spec) },
                onChange = { transform -> updateSpec(slot.id, transform) },
            )
        }

        SectionTitle("其他")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    specs = HapticPalette.ALL.associate { it.id to it.default }
                    HapticOverride.clear()
                    snapshot = null
                }
            ) {
                Text("恢复默认")
            }
            TextButton(
                onClick = {
                    snapshot = HapticPalette.ALL.joinToString("\n\n") { slot ->
                        "// ${slot.label}\n" + specs.getValue(slot.id).toSourceSnippet()
                    }
                }
            ) {
                Text("打印当前参数")
            }
        }

        if (snapshot != null) {
            Text(
                text = snapshot!!,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        Text(
            text = "",
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

/**
 * 一种反馈的完整调参区：标题、包络图、试听按钮、该形状用得上的滑块。
 *
 * 滑块按 `pulses` 决定显示哪些：单脉冲时间隔和曲率毫无意义，摆出来只会
 * 让人以为调了有用。这是「五块独立」而非「一个通用编辑器」的直接好处。
 */
@Composable
private fun HapticBlock(
    label: String,
    hint: String,
    spec: HapticSpec,
    onPlay: () -> Unit,
    onChange: ((HapticSpec) -> HapticSpec) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            Button(onClick = onPlay) { Text("试一下") }
        }

        EnvelopeChart(
            spec = spec,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Text(
            text = "总时长 ${spec.totalDurationMs()}ms",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        LabSlider2(
            label = "脉冲个数",
            value = spec.pulses.toFloat(),
            range = 1f..12f,
            steps = 10,
            display = "${spec.pulses} 记",
            hint = "1 记即单击。盲操下数个数只在 3 记以内可靠，" +
                "更多记时靠的是节奏走向而不是个数",
            onChange = { v -> onChange { it.copy(pulses = v.roundToInt()) } },
        )
        LabSlider2(
            label = "脉冲宽度",
            value = spec.pulseMs.toFloat(),
            range = 8f..80f,
            display = "${spec.pulseMs}ms",
            hint = "太窄时马达还没转起来就结束了，低振幅下尤其明显",
            onChange = { v -> onChange { it.copy(pulseMs = v.roundToInt()) } },
        )

        if (spec.pulses > 1) {
            LabSlider2(
                label = "起始间隔",
                value = spec.startGapMs.toFloat(),
                range = 0f..200f,
                display = "${spec.startGapMs}ms",
                hint = "起始 > 结束即加速（蓄力），< 即减速（泄气）。" +
                    "这个大小关系就是波形的「形状」",
                onChange = { v -> onChange { it.copy(startGapMs = v.roundToInt()) } },
            )
            LabSlider2(
                label = "结束间隔",
                value = spec.endGapMs.toFloat(),
                range = 0f..200f,
                display = "${spec.endGapMs}ms",
                hint = null,
                onChange = { v -> onChange { it.copy(endGapMs = v.roundToInt()) } },
            )
            LabSlider2(
                label = "间隔曲率",
                value = spec.gapCurve,
                range = 0.3f..3f,
                display = fmt2(spec.gapCurve),
                hint = "1 = 线性。大于 1 则压缩集中在后段，「越到后面越急」",
                onChange = { v -> onChange { it.copy(gapCurve = v) } },
            )
        }

        LabSlider2(
            label = "起始振幅",
            value = spec.startAmp,
            range = 0f..1f,
            display = fmt2(spec.startAmp),
            hint = null,
            onChange = { v -> onChange { it.copy(startAmp = v) } },
        )
        LabSlider2(
            label = "结束振幅",
            value = spec.endAmp,
            range = 0f..1f,
            display = fmt2(spec.endAmp),
            hint = if (spec.burstMs > 0) {
                "刻意别爬满——顶上那截要留给迸发，否则迸发没有落差"
            } else {
                null
            },
            onChange = { v -> onChange { it.copy(endAmp = v) } },
        )
        if (spec.pulses > 1) {
            LabSlider2(
                label = "振幅曲率",
                value = spec.ampCurve,
                range = 0.3f..3f,
                display = fmt2(spec.ampCurve),
                hint = null,
                onChange = { v -> onChange { it.copy(ampCurve = v) } },
            )
        }

        LabSlider2(
            label = "振幅地板",
            value = spec.minAmp,
            range = 0f..0.5f,
            display = fmt2(spec.minAmp),
            hint = "马达的起振阈值。设 0 可直接对比「没有地板」时前几记是不是摸不到",
            onChange = { v -> onChange { it.copy(minAmp = v) } },
        )

        LabSlider2(
            label = "迸发时长",
            value = spec.burstMs.toFloat(),
            range = 0f..150f,
            display = if (spec.burstMs > 0) "${spec.burstMs}ms" else "无迸发",
            hint = "0 即没有收尾迸发",
            onChange = { v -> onChange { it.copy(burstMs = v.roundToInt()) } },
        )
        if (spec.burstMs > 0) {
            LabSlider2(
                label = "迸发前静默",
                value = spec.burstGapMs.toFloat(),
                range = 0f..150f,
                display = "${spec.burstGapMs}ms",
                hint = "冲击力全在这段落差上。设 0 则迸发与蓄力列黏成一团",
                onChange = { v -> onChange { it.copy(burstGapMs = v.roundToInt()) } },
            )
            LabSlider2(
                label = "迸发振幅",
                value = spec.burstAmp,
                range = 0f..1f,
                display = fmt2(spec.burstAmp),
                hint = null,
                onChange = { v -> onChange { it.copy(burstAmp = v) } },
            )
        }
    }
}

/**
 * 把渲染出来的包络画成柱状图：横轴是时间，柱高是振幅，空白是静默。
 *
 * 直接画 [HapticSpec.render] 的产物而不是另算一条近似曲线——
 * 图上看到的必须和马达上跑的是同一份数据，否则看图调参就是瞎调
 * （歌词实验室的梯度表也是这个口径）。
 */
@Composable
private fun EnvelopeChart(spec: HapticSpec, modifier: Modifier = Modifier) {
    val waveform = spec.render()
    // 横轴至少铺到 200ms，短波形才不会被拉满整个宽度。
    //
    // 单脉冲是这里的退化情形：它占自己总时长的 100%，按比例画就是一整块
    // 实心蓝，既看不出「只有一记」也看不出「很短」——而那恰恰是切歌这条
    // 波形的全部特征。给横轴一个下限之后，短波形在图上就真的显得短，
    // 五块之间的宽度差也成了可比的量。
    val total = waveform.timings.sum().coerceAtLeast(MIN_CHART_SPAN_MS)
    val pulseColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)

    Canvas(modifier = modifier) {
        // 底线：让「静默」这段也有视觉存在感，否则迸发前的空白看着像图画完了。
        drawRect(
            color = baseColor,
            topLeft = Offset(0f, size.height - 1.dp.toPx()),
            size = Size(size.width, 1.dp.toPx()),
        )

        var elapsed = 0L
        waveform.timings.forEachIndexed { i, duration ->
            val amp = waveform.amplitudes[i]
            if (amp > 0) {
                val x = size.width * (elapsed.toFloat() / total)
                val w = size.width * (duration.toFloat() / total)
                val h = size.height * (amp / HapticSpec.MAX_AMPLITUDE.toFloat())
                drawRect(
                    color = pulseColor,
                    topLeft = Offset(x, size.height - h),
                    // 极窄的脉冲在图上会细到看不见，给个最小宽度。
                    // 这只影响观感，不影响真机上实际跑的时长。
                    size = Size(w.coerceAtLeast(2.dp.toPx()), h),
                )
            }
            elapsed += duration
        }
    }
}

@Composable
private fun LabSlider2(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    hint: String?,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = display,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 10.sp,
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

/**
 * 包络图横轴的最小跨度（毫秒）。
 *
 * 取 200ms 略高于「已收藏」「播放/暂停」的总时长（130ms 左右），
 * 让那几条短波形在图上留出空白，与占满整宽的「收藏」形成对比。
 */
private const val MIN_CHART_SPAN_MS = 200L

private fun fmt2(v: Float): String = String.format("%.2f", v)

/** 拼成可直接粘贴回 [HapticPalette] 的构造调用。同歌词实验室，不用剪贴板。 */
private fun HapticSpec.toSourceSnippet(): String = buildString {
    appendLine("HapticSpec(")
    appendLine("    pulses = $pulses,")
    appendLine("    pulseMs = $pulseMs,")
    if (pulses > 1) {
        appendLine("    startGapMs = $startGapMs,")
        appendLine("    endGapMs = $endGapMs,")
        appendLine("    gapCurve = ${fmt2(gapCurve)}f,")
    }
    appendLine("    startAmp = ${fmt2(startAmp)}f,")
    appendLine("    endAmp = ${fmt2(endAmp)}f,")
    if (pulses > 1) appendLine("    ampCurve = ${fmt2(ampCurve)}f,")
    appendLine("    minAmp = ${fmt2(minAmp)}f,")
    if (burstMs > 0) {
        appendLine("    burstAmp = ${fmt2(burstAmp)}f,")
        appendLine("    burstMs = $burstMs,")
        appendLine("    burstGapMs = $burstGapMs,")
    } else {
        appendLine("    burstMs = 0,")
    }
    append(")")
}

package com.nudge.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.overlay.BubbleEdge
import com.nudge.app.overlay.OverlayConfig

/**
 * 快速跳转的设置页。
 *
 * 权限状态摆在最前且**开关被权限挡住时禁用**：两项权限都要跳系统设置手动开，
 * 若让用户先打开开关、再发现没有权限什么都没发生，他没有任何线索知道
 * 缺的是哪一项——而这两个授权页长得完全不一样。
 */
@Composable
fun QuickJumpScreen(
    config: OverlayConfig,
    canDrawOverlay: Boolean,
    canReadUsage: Boolean,
    /**
     * 用户已经打开了开关，但权限还没授全，正等着授权。
     *
     * 这个状态**只存内存**，杀进程即失效：持久化会让「几天后某次偶然授权」
     * 悄悄把功能开起来，而用户早就忘了自己点过。
     */
    pendingEnable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdgeChange: (BubbleEdge) -> Unit,
    onYRatioChange: (Float) -> Unit,
    onSizeChange: (Int) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = canDrawOverlay && canReadUsage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("快速跳转", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Text(
            text = "网易云音乐在前台时，在屏幕边缘显示一个悬浮按钮，点一下回到 nudge。" +
                "按钮可以拖动，松手后吸附到最近的一侧。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        SectionTitle("权限")
        PermissionRow(
            label = "显示在其他应用上方",
            granted = canDrawOverlay,
            hint = "画悬浮按钮需要它",
            onRequest = onRequestOverlay,
        )
        PermissionRow(
            label = "使用情况访问",
            granted = canReadUsage,
            // 这个授权页不接受 package Uri，只能落到列表页，所以要说清找哪一项。
            hint = "判断网易云是否在前台。在打开的列表里找到「nudge」并允许",
            onRequest = onRequestUsage,
        )

        SectionTitle("开关")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启用快速跳转", fontSize = 15.sp)
                Text(
                    text = when {
                        // 等授权时要说清「已经记下了」，否则用户从授权页返回
                        // 看到开关是开的却没气泡，会以为坏了。
                        pendingEnable -> "已记下，授予权限后自动开启"
                        config.enabled -> "网易云音乐在前台时显示，通知栏常驻一条"
                        ready -> "开启后通知栏会常驻一条"
                        else -> "打开后会引导你授予所需权限"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            // **开关始终可点**，不因缺权限而禁用。禁用态只能传达「现在不能开」，
            // 传达不了「为什么」和「怎么才能开」——用户看到一个灰着的开关，
            // 得自己把它和上面的权限行联系起来。打开时直接跳授权页更直接。
            Switch(
                checked = config.enabled || pendingEnable,
                onCheckedChange = onEnabledChange,
            )
        }

        SectionTitle("外观")
        BubbleEdge.entries.forEach { e ->
            OptionRow(
                label = e.displayName,
                hint = null,
                selected = config.edge == e,
                onClick = { onEdgeChange(e) },
            )
        }

        LabeledSlider(
            label = "竖向位置",
            value = "${(config.yRatio * 100).toInt()}%",
            sliderValue = config.yRatio,
            range = OverlayConfig.Y_RATIO_RANGE,
            onChange = onYRatioChange,
        )
        LabeledSlider(
            label = "大小",
            value = "${config.sizeDp}dp",
            sliderValue = config.sizeDp.toFloat(),
            range = OverlayConfig.SIZE_RANGE.first.toFloat()..OverlayConfig.SIZE_RANGE.last.toFloat(),
            onChange = { onSizeChange(it.toInt()) },
        )
        LabeledSlider(
            label = "不透明度",
            value = "${(config.alpha * 100).toInt()}%",
            sliderValue = config.alpha,
            range = OverlayConfig.ALPHA_RANGE,
            onChange = onAlphaChange,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    hint: String,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$label：${if (granted) "已授予" else "未授予"}",
                fontSize = 14.sp,
            )
            if (!granted) {
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
        if (!granted) {
            Button(onClick = onRequest) { Text("前往") }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text("$label：$value", fontSize = 14.sp)
        Slider(
            value = sliderValue,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

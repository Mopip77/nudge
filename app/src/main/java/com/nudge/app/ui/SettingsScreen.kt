package com.nudge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.config.ThemeMode
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity

@Composable
fun SettingsScreen(
    config: NudgeConfig,
    hasPermission: Boolean,
    onBindingChange: (ActionType, Gesture) -> Unit,
    onSensitivityChange: (Sensitivity) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                text = "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SectionTitle("权限")
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = if (hasPermission) "通知使用权：已授予" else "通知使用权：未授予",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!hasPermission) {
                Text(
                    text = "控制播放与读取歌曲信息需要此权限",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("前往授予")
                }
            }
        }

        // 每个动作绑定一个手势，手势互斥由 ConfigStore.setBinding 保证
        ActionType.entries.forEach { action ->
            SectionTitle("${action.displayName} 的手势")
            Gesture.entries.forEach { gesture ->
                val occupiedBy = config.gestureToAction(gesture)
                val selected = config.bindings[action] == gesture
                val occupiedByOther = occupiedBy != null && occupiedBy != action
                OptionRow(
                    label = gesture.displayName,
                    hint = if (occupiedByOther) "已绑定「${occupiedBy?.displayName}」" else null,
                    selected = selected,
                    onClick = { onBindingChange(action, gesture) },
                )
            }
        }

        SectionTitle("灵敏度")
        Sensitivity.entries.forEach { s ->
            OptionRow(
                label = s.displayName,
                hint = "双击间隔 ${s.params.doubleTapWindowMs}ms，长按 ${s.params.longPressMs}ms",
                selected = config.sensitivity == s,
                onClick = { onSensitivityChange(s) },
            )
        }

        SectionTitle("主题")
        ThemeMode.entries.forEach { mode ->
            OptionRow(
                label = mode.displayName,
                hint = null,
                selected = config.themeMode == mode,
                onClick = { onThemeChange(mode) },
            )
        }

        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "收藏功能仅对网易云音乐有效，且只会点亮红心，不会取消已有收藏。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun OptionRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }
    }
}

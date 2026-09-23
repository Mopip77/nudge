package com.nudge.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture

/**
 * 单个动作的手势绑定页（设置页的二级菜单）。
 *
 * 一个动作可绑多个手势（多选），反向的互斥——一个手势只属于一个动作——
 * 由 ConfigStore.addBinding 的抢占保证，勾给新动作时会从原动作自动移除。
 * 抢占语义沿用平铺版：被别的动作占用的手势照样可勾，只是多一行提示说明会被夺走。
 */
@Composable
fun GestureBindingScreen(
    action: ActionType,
    config: NudgeConfig,
    onBindingAdd: (ActionType, Gesture) -> Unit,
    onBindingRemove: (ActionType, Gesture) -> Unit,
    onBack: () -> Unit,
) {
    val bound = config.bindings[action].orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 沉浸式下窗口铺到物理边缘，二级页同样要避开刘海／挖孔
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
                text = action.displayName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Text(
            text = if (bound.isEmpty()) "尚未绑定手势，勾选下方任意一项即可启用"
                   else "已绑定 ${bound.size} 个手势",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )

        SectionTitle("点击类")
        Gesture.entries.filter { !it.isSwipe }.forEach { gesture ->
            GestureRow(gesture, action, config, bound, onBindingAdd, onBindingRemove)
        }

        // 滑动类单独分组：它与点击类的触发方式完全不同（要划够距离而非点住不动），
        // 混在一起列会让用户以为是同一类操作的变体。
        SectionTitle("滑动类")
        Gesture.entries.filter { it.isSwipe }.forEach { gesture ->
            GestureRow(gesture, action, config, bound, onBindingAdd, onBindingRemove)
        }
    }
}

@Composable
private fun GestureRow(
    gesture: Gesture,
    action: ActionType,
    config: NudgeConfig,
    bound: Set<Gesture>,
    onBindingAdd: (ActionType, Gesture) -> Unit,
    onBindingRemove: (ActionType, Gesture) -> Unit,
) {
    val occupiedBy = config.gestureToAction(gesture)
    val selected = gesture in bound
    val occupiedByOther = occupiedBy != null && occupiedBy != action
    OptionRow(
        label = gesture.displayName,
        hint = if (occupiedByOther) "当前属于「${occupiedBy?.displayName}」，勾选将移交"
               else null,
        selected = selected,
        multiSelect = true,
        onClick = {
            if (selected) onBindingRemove(action, gesture)
            else onBindingAdd(action, gesture)
        },
    )
}

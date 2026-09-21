package com.nudge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.config.ProfileSlot

/** 待确认的对话框。null 表示无对话框。 */
private sealed interface ProfileDialog {
    /** 保存到空槽或覆盖已有，二者都要填名字，靠 [existingName] 区分文案。 */
    data class Save(val index: Int, val existingName: String?) : ProfileDialog
    data class Delete(val index: Int, val name: String) : ProfileDialog
}

/**
 * 预设区块。
 *
 * 不显示配置内容摘要：三项标量（灵敏度·主题·歌词）信息量低，手势绑定展开后又太长，
 * 折中出来的摘要两头不靠。让名字承担全部辨识职责，反而促使用户起个有意义的名字。
 */
@Composable
fun ProfileSection(
    slots: List<ProfileSlot>,
    onSave: (Int, String) -> Unit,
    onLoad: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

    SectionTitle("预设")
    Text(
        text = "保存当前全部配置，之后可一键切回。加载会覆盖当前配置。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
    )

    slots.forEach { slot ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slot.name ?: "槽位 ${slot.index}（空）",
                    fontSize = 15.sp,
                    fontWeight = if (slot.isEmpty) FontWeight.Normal else FontWeight.Medium,
                    color = if (slot.isEmpty) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )
            }
            if (slot.isEmpty) {
                TextButton(onClick = { dialog = ProfileDialog.Save(slot.index, null) }) {
                    Text("保存当前配置")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    // 加载不确认：它不销毁数据，当前配置随时能改回来，
                    // 且用户点加载本就是为了立刻看到效果
                    TextButton(onClick = { onLoad(slot.index) }) { Text("加载") }
                    TextButton(
                        onClick = { dialog = ProfileDialog.Save(slot.index, slot.name) }
                    ) { Text("覆盖") }
                    TextButton(
                        onClick = {
                            dialog = ProfileDialog.Delete(slot.index, slot.name.orEmpty())
                        }
                    ) { Text("删除") }
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit

        is ProfileDialog.Save -> SaveDialog(
            index = current.index,
            existingName = current.existingName,
            onConfirm = { name ->
                onSave(current.index, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is ProfileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("删除「${current.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(current.index)
                    dialog = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 保存／覆盖对话框。覆盖要二次确认——盲操下调好的一套手势配置被误点覆盖，
 * 代价远高于多点一次确认。
 */
@Composable
private fun SaveDialog(
    index: Int,
    existingName: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existingName ?: "预设 $index") }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existingName == null) "保存到槽位 $index" else "覆盖「$existingName」？")
        },
        text = {
            Column {
                if (existingName != null) {
                    Text(
                        text = "当前配置将替换它，原内容无法恢复。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            // 名字是唯一的辨识手段，空名字存进去等于存了个认不出的槽位
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text(if (existingName == null) "保存" else "覆盖") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

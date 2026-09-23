package com.nudge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.nudge.app.BuildConfig
import com.nudge.app.config.ActionType
import com.nudge.app.config.LyricsAlignment
import com.nudge.app.config.NudgeConfig
import com.nudge.app.config.ProfileSlot
import com.nudge.app.config.ThemeMode
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity
import com.nudge.app.update.ReleaseInfo
import com.nudge.app.update.UpdateState

@Composable
fun SettingsScreen(
    config: NudgeConfig,
    profiles: List<ProfileSlot>,
    hasPermission: Boolean,
    onOpenGestureBinding: (ActionType) -> Unit,
    onSensitivityChange: (Sensitivity) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onLyricsEnabledChange: (Boolean) -> Unit,
    onLyricsAlignmentChange: (LyricsAlignment) -> Unit,
    onAntiMistouchChange: (Boolean) -> Unit,
    onProfileSave: (Int, String) -> Unit,
    onProfileLoad: (Int) -> Unit,
    onProfileDelete: (Int) -> Unit,
    onRequestPermission: () -> Unit,
    currentVersion: String,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (ReleaseInfo) -> Unit,
    onInstallUpdate: (ReleaseInfo) -> Unit,
    onOpenLyricsLab: () -> Unit,
    onOpenHapticLab: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 沉浸式下窗口铺到物理边缘，设置页也要避开刘海／挖孔
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

        // 放在最前：加载预设会改掉下面所有区块的显示，符合「因在前、果在后」的阅读顺序
        ProfileSection(
            slots = profiles,
            onSave = onProfileSave,
            onLoad = onProfileLoad,
            onDelete = onProfileDelete,
        )

        // 二级菜单：一级只列动作（行数恒等于动作数，与手势数量脱钩），
        // 点进去才是该动作的手势多选页。早先是动作 × 手势全平铺，
        // 行数随两者相乘增长，加一个动作和两个手势就从 10 行涨到 21 行。
        SectionTitle("手势绑定")
        ActionType.entries.forEach { action ->
            val bound = config.bindings[action].orEmpty()
            NavRow(
                label = action.displayName,
                // 摘要直接列出已绑手势名，让「哪个动作还没绑」在一级页一眼可见
                hint = if (bound.isEmpty()) "未绑定"
                       else bound.joinToString("、") { it.displayName },
                onClick = { onOpenGestureBinding(action) },
            )
        }

        // 灵敏度只在 debug 包里可调：三档的差别要连着试才分得出来，
        // 而盲操用户没有对照条件，摆出来只会让人凭感觉乱选、再把误触归咎于应用。
        // release 固定标准档（见 ConfigStore 读取侧）。
        if (BuildConfig.DEBUG) {
            SectionTitle("灵敏度")
            Sensitivity.entries.forEach { s ->
                OptionRow(
                    label = s.displayName,
                    hint = "双击间隔 ${s.params.doubleTapWindowMs}ms，长按 ${s.params.longPressMs}ms",
                    selected = config.sensitivity == s,
                    onClick = { onSensitivityChange(s) },
                )
            }
        }

        SectionTitle("歌词")
        OptionRow(
            label = "显示歌词",
            hint = "关闭后不再请求歌词，仅显示曲目信息",
            selected = config.lyricsEnabled,
            multiSelect = true,
            onClick = { onLyricsEnabledChange(!config.lyricsEnabled) },
        )
        LyricsAlignment.entries.forEach { a ->
            OptionRow(
                label = "对齐：${a.displayName}",
                hint = null,
                selected = config.lyricsAlignment == a,
                onClick = { onLyricsAlignmentChange(a) },
            )
        }

        // 歌词动画实验室同样只在 debug 包里：它是开发期的取景器，
        // 调出来的值要手抄回 LyricsAnimSpec.DEFAULT，不做持久化也不面向用户。
        if (BuildConfig.DEBUG) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenLyricsLab)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Column {
                    Text(
                        text = "歌词动画实验室",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "用假歌词实时调滚动参数，仅 debug 包可见",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // 振动实验室同理：调出来的值要手抄回 HapticPalette，不做持久化。
        // 放在「反馈」这个独立分组下而不是塞进歌词组——两者调的是完全不同的东西。
        if (BuildConfig.DEBUG) {
            SectionTitle("反馈")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenHapticLab)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Column {
                    Text(
                        text = "振动实验室",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "逐条试听并调整各动作的振动波形，仅 debug 包可见",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            }
        }

        SectionTitle("防误触")
        // 三层合一个开关，所以 hint 要把副作用说全：用户是一次性接受全部三项。
        OptionRow(
            label = "防误触模式",
            hint = "隐藏系统栏，边缘滑动需两次才触发返回／主页；返回键连按两次才退出；" +
                   "固定屏幕使其他应用与通知无法打断，退出请长按「返回 + 概览」，" +
                   "手势导航下为上滑并按住",
            selected = config.antiMistouchEnabled,
            multiSelect = true,
            onClick = { onAntiMistouchChange(!config.antiMistouchEnabled) },
        )

        SectionTitle("主题")
        ThemeMode.entries.forEach { mode ->
            OptionRow(
                label = mode.displayName,
                hint = null,
                selected = config.themeMode == mode,
                onClick = { onThemeChange(mode) },
            )
        }

        SectionTitle("关于")
        UpdateSection(
            currentVersion = currentVersion,
            state = updateState,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
        )

        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "收藏功能仅对网易云音乐有效，且只会点亮红心，不会取消已有收藏。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * 更新区块。检查、下载、安装三步都在原地展开，不弹窗——
 * 下载过程中弹窗要么挡住界面、要么关掉就丢进度，内联反而状态更清晰。
 */
@Composable
private fun UpdateSection(
    currentVersion: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onInstall: (ReleaseInfo) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(
            text = "当前版本 $currentVersion",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )

        when (state) {
            is UpdateState.Idle,
            is UpdateState.UpToDate,
            is UpdateState.CheckFailed,
            -> {
                val hint = when (state) {
                    is UpdateState.UpToDate -> "已是最新版本"
                    is UpdateState.CheckFailed -> "检查失败，请确认网络后重试"
                    else -> null
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(onClick = onCheck, modifier = Modifier.padding(top = 8.dp)) {
                    Text("检查更新")
                }
            }

            is UpdateState.Checking -> {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在检查…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }

            is UpdateState.Available -> {
                ReleaseNotesCard(state.release)
                Button(
                    onClick = { onDownload(state.release) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("立即更新（${formatSize(state.release.apkSize)}）")
                }
            }

            is UpdateState.Downloading -> {
                ReleaseNotesCard(state.release)
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    text = "正在下载 ${formatSize(state.downloadedBytes)} / ${formatSize(state.totalBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            is UpdateState.Downloaded -> {
                ReleaseNotesCard(state.release)
                // 用户可能在系统安装器上点了取消再退回来，留一个重新安装入口，
                // 免得他为了重试而把整个包再下一遍
                Text(
                    text = "下载完成，请在系统弹出的安装界面确认",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = { onInstall(state.release) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("重新安装")
                }
            }

            is UpdateState.DownloadFailed -> {
                ReleaseNotesCard(state.release)
                Text(
                    text = "下载失败，请确认网络后重试",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = { onDownload(state.release) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesCard(release: ReleaseInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        Text(
            text = "新版本 ${release.versionName}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (release.releaseNotes.isNotBlank()) {
            // release notes 是 CI 自动生成的 markdown，原样按纯文本显示——
            // 为渲染几行 commit 列表引入 markdown 库不划算。限高可滚动，防止长更新日志顶开页面
            Text(
                text = release.releaseNotes,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 字节数转人类可读，下载进度用。 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * 通往二级页的行：右侧是「>」而非勾选框，点击整行导航。
 *
 * 与 [OptionRow] 分开而不是给它加个 mode 参数——两者的交互语义不同
 * （选中 vs 导航），合并会让调用处多出一个只在某些取值下有意义的参数。
 */
@Composable
private fun NavRow(label: String, hint: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
    }
}

@Composable
internal fun OptionRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
    multiSelect: Boolean = false,
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
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
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

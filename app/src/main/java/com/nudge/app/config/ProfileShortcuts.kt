package com.nudge.app.config

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nudge.app.R

/**
 * 把已保存的预设同步成动态 shortcut。
 *
 * 这是三星「模式与日常安排」能直接选到预设的机制：M&R 持有系统权限
 * `ACCESS_SHORTCUTS`（launcher 枚举 shortcut 用的就是它）与
 * `RESET_SHORTCUT_MANAGER_THROTTLING`，走标准 `LauncherApps` API 读取第三方应用的
 * shortcut，在「添加动作 → 其他应用程序」里把它们列成子动作。
 * 所以不需要 Tasker 中转，也不需要任何三星私有 SDK。
 *
 * label 用用户给预设起的名字而不是「槽位 N」——预设的辨识本来就全靠名字，
 * M&R 的动作列表里显示槽位号同样认不出来。
 */
object ProfileShortcuts {

    /**
     * 让 shortcut 组与槽位的实际状态一致。存/删预设后调用。
     *
     * 用 [ShortcutManagerCompat.setDynamicShortcuts] 而非 `addDynamicShortcuts`：
     * 它是「整组替换」语义，删除预设后对应 shortcut 自动消失，
     * 不用单独 remove，一次调用就同步完。
     *
     * 只为**已保存**的预设生成：空槽位不列，避免 M&R 里出现「点了没反应」的死项。
     *
     * 不判断 `isRateLimitingActive()`：频率限制只作用于后台应用，而本方法的调用时机
     * 是用户在设置页存/删预设，那必然是前台，且「应用进入前台」本身就会重置计数器。
     */
    fun sync(context: Context, slots: List<ProfileSlot>) {
        val shortcuts = slots
            .filterNot { it.isEmpty }
            .map { slot ->
                val label = slot.name.orEmpty()
                ShortcutInfoCompat.Builder(context, shortcutId(slot.index))
                    .setShortLabel(label)
                    .setLongLabel("切换到「$label」")
                    // 复用启动图标的前景层，不为此新增资源
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground))
                    .setIntent(
                        Intent(context, ProfileShortcutActivity::class.java).apply {
                            // shortcut 的 Intent 必须带 action，否则 ShortcutInfoCompat 构建即失败
                            action = Intent.ACTION_VIEW
                            putExtra(ProfileShortcutActivity.EXTRA_SLOT, slot.index.toString())
                        }
                    )
                    .build()
            }

        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // 超出 shortcut 数量上限或系统服务异常都不该影响存预设这件事本身
            Log.e(TAG, "同步预设 shortcut 失败", e)
        }
    }

    /** 与槽位号一一对应，稳定不变——外部若引用了 shortcut id，改名不会让它失效。 */
    private fun shortcutId(index: Int) = "profile_$index"

    private const val TAG = "NudgeProfileShortcuts"
}

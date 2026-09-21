package com.nudge.app.config

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import kotlinx.coroutines.runBlocking

/**
 * 预设切换的 shortcut 入口，供系统 launcher 与三星「模式与日常安排」直接调用。
 *
 * M&R 持有 `ACCESS_SHORTCUTS` 权限，靠标准 `LauncherApps` API 枚举第三方应用的
 * shortcut 并当作「子动作」列出来，所以动态 shortcut 就能被它直接选到，
 * 不需要 Tasker 中转（见 [ProfileShortcuts]）。
 *
 * shortcut 的点击是 `startActivity` 而非广播，所以必须有这个 Activity 做入口，
 * 不能让 shortcut 直接指向 [ProfileCommandReceiver]。
 *
 * 主题是透明无界面：用户点了以后看不到任何界面，只有配置被切换。
 * 刻意不用 `Theme.NoDisplay`——targetSdk ≥ 23 上它会抛
 * IllegalStateException（"did not call finish() prior to onResume() completing"）。
 */
class ProfileShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 只按槽位号切，理由同 ProfileCommandReceiver：名字可改，改完外部配置就断了
        val raw = intent?.getStringExtra(EXTRA_SLOT)
        val index = parseSlotIndex(raw)
        if (index == null) {
            Log.w(TAG, "忽略非法槽位号: $raw")
        } else {
            try {
                // 必须在 onCreate 内同步做完：下面立刻 finish，异步协程会来不及执行完。
                // 写 DataStore 是毫秒级操作。
                val loaded = runBlocking { ConfigStore(this@ProfileShortcutActivity).loadProfile(index) }
                if (loaded == null) {
                    // 空槽位不做任何事也不提示——自动化触发时用户可能没看手机
                    Log.w(TAG, "槽位 $index 为空，未切换")
                } else {
                    Log.i(TAG, "已切换到槽位 $index：${loaded.name}")
                }
            } catch (e: Exception) {
                // DataStore 读写可能因磁盘或数据损坏失败，不能让进程崩溃
                Log.e(TAG, "切换预设失败", e)
            }
        }

        // 无条件 finish 且放在 try 之外：任何分支都不能留一个透明 Activity 挂在栈上
        finish()
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        private const val TAG = "NudgeProfileShortcut"
    }
}

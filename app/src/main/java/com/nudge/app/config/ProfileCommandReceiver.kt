package com.nudge.app.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 预设切换的广播入口，供 Tasker / MacroDroid / Home Assistant / adb 调用。
 *
 * 三星「模式与日常安排」没有公开给第三方注册自定义动作的 API，对第三方应用只有
 * 「打开应用」，所以链路是 M&R → Tasker → 本广播。
 *
 * 不做 deep link Activity：它会把应用弹到前台，而「开车时 M&R 切到驾驶模式」
 * 这种场景下突然弹出全屏触摸板是危险的。广播不改变应用的可见性。
 *
 * 静态注册让界面未打开时也能接收。
 */
class ProfileCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROFILE) return

        val raw = intent.getStringExtra(EXTRA_SLOT)
        // 只按槽位号切，不支持按名字：名字是用户可改的字符串，
        // 写进 Tasker/M&R 配置后改个名就断了；槽位号固定 1..3，稳定
        val index = parseSlotIndex(raw)
        if (index == null) {
            Log.w(TAG, "忽略非法槽位号: $raw")
            return
        }

        try {
            // onReceive 返回后进程可能立即被回收，异步协程会来不及执行完，
            // 故在 10 秒的 onReceive 配额内同步等待。写 DataStore 是毫秒级操作。
            val loaded = runBlocking { ConfigStore(context).loadProfile(index) }
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

    companion object {
        const val ACTION_PROFILE = "com.nudge.app.PROFILE"
        const val EXTRA_SLOT = "slot"
        private const val TAG = "NudgeProfileCommand"
    }
}

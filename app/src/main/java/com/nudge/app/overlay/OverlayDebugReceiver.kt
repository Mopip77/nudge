package com.nudge.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 快速跳转的 adb 入口，**仅供真机验证**。口径同 `LockWallpaperDebugReceiver`。
 *
 * 这个功能没法单测的部分（`WindowManager` 加窗成不成功、气泡跟着前台应用
 * 显隐的时序、从服务里 `startActivity` 会不会被后台启动限制拦下）都要真机验，
 * 而靠界面点按测不出「现在气泡到底挂着没有」。
 *
 * ```sh
 * adb shell am broadcast -a com.nudge.app.OVERLAY \
 *   -n com.nudge.app/.overlay.OverlayDebugReceiver --es cmd on
 * ```
 *
 * 命令：`on` 开启并启动服务 / `off` 关闭并停服务 / `state` 打印权限与配置。
 */
class OverlayDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val store = OverlayStore(context)

        // runBlocking 而非异步：onReceive 返回后进程可能立即被回收。
        runBlocking {
            when (cmd) {
                "on" -> {
                    val overlay = OverlayPermissions.canDrawOverlay(context)
                    val usage = OverlayPermissions.canReadUsageStats(context)
                    Log.i(TAG, "权限 悬浮窗=$overlay 使用情况=$usage")
                    if (!overlay || !usage) {
                        Log.w(TAG, "权限不全，开了也画不出气泡")
                        return@runBlocking
                    }
                    store.save(store.currentConfig().copy(enabled = true))
                    OverlayBubbleService.start(context)
                    Log.i(TAG, "已开启")
                }

                "off" -> {
                    store.save(store.currentConfig().copy(enabled = false))
                    OverlayBubbleService.stop(context)
                    Log.i(TAG, "已关闭")
                }

                "state" -> {
                    val cfg = store.currentConfig()
                    Log.i(
                        TAG,
                        "配置=$cfg 悬浮窗=${OverlayPermissions.canDrawOverlay(context)} " +
                            "使用情况=${OverlayPermissions.canReadUsageStats(context)}",
                    )
                }

                else -> Log.w(TAG, "未知命令 $cmd")
            }
        }
    }

    private companion object {
        const val TAG = "OverlayBubble"
    }
}

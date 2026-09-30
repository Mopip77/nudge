package com.nudge.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * 读系统的使用情况事件，解出当前前台应用。
 *
 * ## 为什么是 UsageStats 而不是无障碍服务
 *
 * 无障碍服务能拿到 `TYPE_WINDOW_STATE_CHANGED` 回调，无需轮询、更省电，
 * 系统还会自动拉活。但它要求用户授予「无障碍」权限，而系统在授予时会弹
 * 一段「此应用可读取屏幕上的全部内容、可代表你操作」的警告。为一个跳转
 * 按钮要这么大的权限不成比例，也会让人合理地怀疑这个应用在干什么。
 *
 * UsageStats 的授权文案只是「使用情况访问」，与它实际要的东西相称。
 *
 * ## 查询窗口为什么要比轮询间隔大得多
 *
 * `queryEvents` 是**按时间区间**查的，区间取得与轮询间隔等宽时，两次轮询
 * 之间的抖动（系统调度延迟、事件落库延迟）会让某些事件**一次都没被查到**，
 * 表现为前台应用偶发解不出来。取 [WINDOW_MS] 远大于间隔，让相邻两次查询
 * 大幅重叠，重复读到同一条事件是无害的（[ForegroundAppResolver] 取最大值）。
 */
class ForegroundAppMonitor(context: Context) {

    private val usage =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * @return 当前前台包名；查不出来（无权限、窗口内没有事件）则 null。
     *   **null 表示「不知道」不是「没有」**，见 [ForegroundAppResolver]。
     */
    fun currentForegroundPackage(nowMs: Long = System.currentTimeMillis()): String? {
        val mgr = usage ?: return null
        val events = runCatching { mgr.queryEvents(nowMs - WINDOW_MS, nowMs) }
            // 未授权时抛 SecurityException。静默回落成「不知道」——
            // 调用方会沿用上一次的判定，不会因此闪一下。
            .getOrNull() ?: return null

        val collected = mutableListOf<AppForegroundEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val resumed = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED -> false
                // 其余类型（通知、屏幕交互、standby bucket 变化）不携带
                // 前台归属信息。真机 dumpsys 里通知事件数量远超前台事件，
                // 不过滤会把它们一起算进来。
                else -> continue
            }
            val pkg = e.packageName ?: continue
            collected += AppForegroundEvent(pkg, e.timeStamp, resumed)
        }
        return ForegroundAppResolver.resolve(collected)
    }

    private companion object {
        /** 轮询间隔的十倍量级，见类注释。 */
        const val WINDOW_MS = 10_000L
    }
}

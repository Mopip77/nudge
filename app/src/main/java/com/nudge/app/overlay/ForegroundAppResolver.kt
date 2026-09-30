package com.nudge.app.overlay

/**
 * 一条前台切换事件。**刻意不引用 `UsageEvents.Event`**——那是 Android 类，
 * 会让这里没法在 JVM 上单测。采集侧负责把系统事件翻译成这个形状。
 */
data class AppForegroundEvent(
    val packageName: String,
    val timestampMs: Long,
    /** true 为 `ACTIVITY_RESUMED`，false 为 `ACTIVITY_PAUSED`。 */
    val resumed: Boolean,
)

/**
 * 从事件流里解出「现在哪个应用在前台」。纯 Kotlin，无 Android 依赖。
 *
 * ## 为什么不直接取最后一条事件的包名
 *
 * 真机取证（SM-G9810 / One UI 5.1）：切走网易云时事件是**成对**出现的，
 * 且 `PAUSED` 与 `RESUMED` 常落在**同一毫秒**：
 *
 * ```
 * 09:24:02 ACTIVITY_PAUSED  com.netease.cloudmusic
 * 09:24:02 ACTIVITY_RESUMED com.sec.android.app.launcher
 * ```
 *
 * 只取「最后一条」在同毫秒时依赖 `sort` 的稳定性，运气不好会解出
 * 已经切走的那个包——表现为气泡在网易云退到后台之后还挂着。
 * 所以只认 `RESUMED`：前台应用的定义就是「最近一个 resume 且尚未被
 * 别人 resume 覆盖的」，`PAUSED` 不携带「接下来谁在前台」的信息。
 *
 * ## 为什么容忍空结果
 *
 * 查询窗口内可能一条 `RESUMED` 都没有（用户几分钟没切应用）。此时返回
 * null 表示**不知道**，而不是「没有前台应用」——调用方必须把它与
 * 「确定不是目标应用」区别对待，否则气泡会在用户静止看着网易云时消失。
 */
object ForegroundAppResolver {

    /**
     * @return 窗口内最后一个进入前台的包名；没有 `RESUMED` 事件则 null。
     */
    fun resolve(events: List<AppForegroundEvent>): String? =
        events.filter { it.resumed }.maxByOrNull { it.timestampMs }?.packageName
}

package com.nudge.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppResolverTest {

    private fun resumed(pkg: String, t: Long) = AppForegroundEvent(pkg, t, resumed = true)
    private fun paused(pkg: String, t: Long) = AppForegroundEvent(pkg, t, resumed = false)

    @Test
    fun `没有事件时返回未知`() {
        assertNull(ForegroundAppResolver.resolve(emptyList()))
    }

    @Test
    fun `只有 PAUSED 事件时返回未知`() {
        // PAUSED 不携带「接下来谁在前台」的信息，不能据此断言任何包在前台。
        val events = listOf(paused("com.netease.cloudmusic", 100))
        assertNull(ForegroundAppResolver.resolve(events))
    }

    @Test
    fun `取最后一个 RESUMED`() {
        val events = listOf(
            resumed("com.netease.cloudmusic", 100),
            resumed("com.sec.android.app.launcher", 200),
        )
        assertEquals("com.sec.android.app.launcher", ForegroundAppResolver.resolve(events))
    }

    @Test
    fun `同毫秒的 PAUSED 与 RESUMED 不会解出已切走的那个`() {
        // 真机取证：切走网易云时两条事件落在同一毫秒。
        //   09:24:02 ACTIVITY_PAUSED  com.netease.cloudmusic
        //   09:24:02 ACTIVITY_RESUMED com.sec.android.app.launcher
        // 「取最后一条事件」的写法在这里会依赖排序稳定性，可能解出网易云,
        // 表现为它已经退到后台了气泡还挂着。
        val events = listOf(
            resumed("com.netease.cloudmusic", 1000),
            paused("com.netease.cloudmusic", 2000),
            resumed("com.sec.android.app.launcher", 2000),
        )
        assertEquals("com.sec.android.app.launcher", ForegroundAppResolver.resolve(events))
    }

    @Test
    fun `事件乱序也按时间戳取最新`() {
        // queryEvents 的返回顺序没有文档保证，不能依赖它已排好序。
        val events = listOf(
            resumed("com.sec.android.app.launcher", 300),
            resumed("com.netease.cloudmusic", 500),
            resumed("com.nudge.app", 100),
        )
        assertEquals("com.netease.cloudmusic", ForegroundAppResolver.resolve(events))
    }
}

package com.nudge.app.wallpaper

import com.nudge.app.wallpaper.WritePolicy.Decision
import com.nudge.app.wallpaper.WritePolicy.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写入决策的时序约束。
 *
 * 这些在真机上极难验证（要造「熄屏期间连切三首」只能靠手速），而写错的
 * 代价是实打实的：多写一次就是一次几 MB 的编码加落盘，少写一次就是
 * 壁纸停在上一首。
 */
class WritePolicyTest {

    private fun policy(debounce: Long = 800L) = WritePolicy(debounce)

    // ---- 同图不写 ----

    @Test
    fun `同一张图不重复写`() {
        val p = policy()
        assertTrue(p.request(Request("a", 0), screenOn = true, true) is Decision.Write)
        // 暂停/恢复、进度更新都会再次触发监听，但图没变。
        assertEquals(
            Decision.SkipSameImage,
            p.request(Request("a", 10_000), screenOn = true, true),
        )
    }

    @Test
    fun `配置变更后同一首歌要重写`() {
        val p = policy(debounce = 800)
        p.request(Request("song1|cfgA", 0), screenOn = true, true)
        p.invalidate()
        // invalidate 解除的是「同图不写」，去抖仍然照常生效——两者是
        // 独立的闸门。所以这里要等过去抖窗口才看得到重写。
        assertEquals(
            Decision.SkipDebounced,
            p.request(Request("song1|cfgB", 100), screenOn = true, true),
        )
        assertTrue(p.request(Request("song1|cfgB", 900), screenOn = true, true) is Decision.Write)
    }

    // ---- 去抖 ----

    @Test
    fun `去抖窗口内的连续切歌只写一次`() {
        val p = policy(debounce = 800)
        assertTrue(p.request(Request("s1", 0), screenOn = true, true) is Decision.Write)
        assertEquals(Decision.SkipDebounced, p.request(Request("s2", 200), screenOn = true, true))
        assertEquals(Decision.SkipDebounced, p.request(Request("s3", 500), screenOn = true, true))
    }

    @Test
    fun `超出去抖窗口后放行`() {
        val p = policy(debounce = 800)
        p.request(Request("s1", 0), screenOn = true, true)
        assertTrue(p.request(Request("s2", 900), screenOn = true, true) is Decision.Write)
    }

    // ---- 熄屏攒住 ----

    @Test
    fun `熄屏时不写，只攒住`() {
        val p = policy()
        assertEquals(Decision.Deferred, p.request(Request("s1", 0), screenOn = false, true))
    }

    @Test
    fun `熄屏期间连切多首，亮屏后只写最后一首`() {
        val p = policy()
        p.request(Request("s1", 0), screenOn = false, true)
        p.request(Request("s2", 100), screenOn = false, true)
        p.request(Request("s3", 200), screenOn = false, true)
        // 写的是最后那首，不是第一首——攒住要覆盖而不是排队。
        assertEquals(Decision.Write("s3"), p.onScreenOn(300))
    }

    @Test
    fun `亮屏补写不受去抖限制`() {
        val p = policy(debounce = 800)
        p.request(Request("s1", 0), screenOn = true, true)      // 写了
        p.request(Request("s2", 100), screenOn = false, true)   // 熄屏攒住
        // 距上次写入仅 200ms，但熄屏期间攒的就是该写的时刻。
        assertEquals(Decision.Write("s2"), p.onScreenOn(200))
    }

    @Test
    fun `熄屏前后同一首歌，亮屏后不重写`() {
        val p = policy()
        p.request(Request("s1", 0), screenOn = true, true)
        // 同图在 request 阶段就被挡下，根本不会攒进待写项——
        // 于是亮屏时无事可做。关键是**不产生写入**，走哪条分支都行。
        assertEquals(Decision.SkipSameImage, p.request(Request("s1", 100), screenOn = false, true))
        assertTrue(p.onScreenOn(200) !is Decision.Write)
    }

    @Test
    fun `亮屏时没有待写项则什么都不做`() {
        val p = policy()
        assertTrue(p.onScreenOn(100) !is Decision.Write)
    }

    @Test
    fun `关掉熄屏攒住时，熄屏也照写`() {
        val p = policy()
        assertTrue(
            p.request(Request("s1", 0), screenOn = false, deferWhileScreenOff = false)
                is Decision.Write,
        )
    }

    // ---- 恢复后的重写：最容易漏的一条 ----

    @Test
    fun `恢复原壁纸后，同一首歌必须能重新写回`() {
        // 不清 lastWrittenKey 的话会被「同图不写」挡掉，表现为
        // 「暂停恢复原壁纸后继续播放，封面壁纸回不来」。
        val p = policy()
        p.request(Request("s1", 0), screenOn = true, true)
        p.onRestored()
        assertTrue(p.request(Request("s1", 10_000), screenOn = true, true) is Decision.Write)
    }

    @Test
    fun `恢复后要丢掉熄屏攒下的待写项`() {
        // 攒的那张是恢复之前的意图，恢复后再补写等于把刚恢复的壁纸又盖掉。
        val p = policy()
        p.request(Request("s1", 0), screenOn = false, true)
        p.onRestored()
        assertTrue(p.onScreenOn(100) !is Decision.Write)
    }
}

package com.nudge.app.wallpaper

import com.nudge.app.wallpaper.RestorePlan.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复决策。这是整个功能里唯一会造成**用户察觉不到的破坏**的地方，
 * 所以每条分支都钉住。
 */
class RestorePlanTest {

    @Test
    fun `设过独立锁屏壁纸——写回备份`() {
        assertEquals(
            Action.WriteBackup,
            RestorePlan.decide(OriginalWallpaperKind.BACKED_UP, true, false),
        )
    }

    @Test
    fun `备份丢了——退到 clear 而不是把封面留在锁屏上`() {
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.BACKED_UP, false, false),
        )
    }

    // ---- 最关键的一条 ----

    @Test
    fun `没设过独立锁屏壁纸——必须 clear，绝不能写图`() {
        // 写任何图回去都会把「锁屏跟随桌面」变成「固定一张图」，
        // 用户之后改桌面壁纸锁屏不再跟随，而且不会知道是 nudge 干的。
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.INHERITED, false, false),
        )
    }

    @Test
    fun `没设过独立锁屏壁纸时，手上有备份也不能写`() {
        // 防的是「反正有张图，写回去总没错」这种想当然的改法。
        // 即便备份和用户指定图都在，继承态也只能 clear。
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.INHERITED, true, true),
        )
    }

    @Test
    fun `所有情形都不会把继承态变成固定图`() {
        // 用穷举兜住：无论备份/用户图在不在，INHERITED 恒为 clear。
        listOf(true, false).forEach { backup ->
            listOf(true, false).forEach { user ->
                assertEquals(
                    "backup=$backup user=$user 时继承态被改成了写图",
                    Action.ClearLock,
                    RestorePlan.decide(OriginalWallpaperKind.INHERITED, backup, user),
                )
            }
        }
    }

    // ---- 用户指定图 ----

    @Test
    fun `备份失败但用户指定了恢复图——写那张`() {
        assertEquals(
            Action.WriteUserSupplied,
            RestorePlan.decide(OriginalWallpaperKind.USER_SUPPLIED, false, true),
        )
    }

    @Test
    fun `备份失败且用户没指定——退到 clear`() {
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.USER_SUPPLIED, false, false),
        )
    }

    // ---- 开启前的阻拦 ----

    @Test
    fun `备份失败且没有用户指定图时，不允许开启`() {
        // 刻意的阻拦：不能让用户在不知情的情况下丢掉原壁纸。
        assertFalse(RestorePlan.canEnable(OriginalWallpaperKind.USER_SUPPLIED, false))
    }

    @Test
    fun `备份失败但用户指定了恢复图，允许开启`() {
        assertTrue(RestorePlan.canEnable(OriginalWallpaperKind.USER_SUPPLIED, true))
    }

    @Test
    fun `能备份或是继承态时，允许开启`() {
        assertTrue(RestorePlan.canEnable(OriginalWallpaperKind.BACKED_UP, false))
        assertTrue(RestorePlan.canEnable(OriginalWallpaperKind.INHERITED, false))
    }
}

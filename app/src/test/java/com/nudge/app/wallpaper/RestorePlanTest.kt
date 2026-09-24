package com.nudge.app.wallpaper

import com.nudge.app.wallpaper.RestorePlan.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复决策。这是整个功能里唯一会造成**用户察觉不到的破坏**的地方，
 * 所以每条分支都钉住。
 *
 * 背景：Android 13+ 读不到原锁屏壁纸（`getWallpaperFile` 需要已失效的
 * `READ_EXTERNAL_STORAGE`，Google 标记 Won't Fix），所以只能靠
 * `getWallpaperId` 判断「有没有设过」，拿不到图。
 */
class RestorePlanTest {

    // ---- 继承态：最关键的一条 ----

    @Test
    fun `没设过独立锁屏壁纸——必须 clear，绝不能写图`() {
        // 写任何图回去都会把「锁屏跟随桌面」变成「固定一张图」，
        // 用户之后改桌面壁纸锁屏不再跟随，而且不会知道是 nudge 干的。
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.INHERITED, userSuppliedAvailable = false),
        )
    }

    @Test
    fun `继承态下手上有恢复图也不能写`() {
        // 防的是「反正有张图，写回去总没错」这种想当然的改法。
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.INHERITED, userSuppliedAvailable = true),
        )
    }

    @Test
    fun `所有情形都不会把继承态变成固定图`() {
        listOf(true, false).forEach { user ->
            assertEquals(
                "userSupplied=$user 时继承态被改成了写图",
                Action.ClearLock,
                RestorePlan.decide(OriginalWallpaperKind.INHERITED, user),
            )
        }
    }

    // ---- 设过独立锁屏壁纸 ----

    @Test
    fun `设过独立壁纸且用户指定了恢复图——写那张`() {
        assertEquals(
            Action.WriteUserSupplied,
            RestorePlan.decide(OriginalWallpaperKind.USER_SUPPLIED, userSuppliedAvailable = true),
        )
    }

    @Test
    fun `恢复图事后被删——退到 clear 兜底`() {
        // canEnable 本该在开启时就拦住这种组合，走到这里说明是开启之后
        // 恢复图又没了。此时 clear 是唯一能做的（总好过把封面永久留着）。
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.USER_SUPPLIED, userSuppliedAvailable = false),
        )
    }

    // ---- 开启前的阻拦 ----

    @Test
    fun `设过独立壁纸又没有恢复图时，不允许开启`() {
        // 刻意的阻拦：Android 13+ 读不到原图，开了就再也回不去。
        assertFalse(RestorePlan.canEnable(OriginalWallpaperKind.USER_SUPPLIED, false))
    }

    @Test
    fun `设过独立壁纸但指定了恢复图，允许开启`() {
        assertTrue(RestorePlan.canEnable(OriginalWallpaperKind.USER_SUPPLIED, true))
    }

    @Test
    fun `继承态无条件允许开启`() {
        // clear 就能完美还原，不需要用户准备任何东西。
        assertTrue(RestorePlan.canEnable(OriginalWallpaperKind.INHERITED, false))
        assertTrue(RestorePlan.canEnable(OriginalWallpaperKind.INHERITED, true))
    }

    // ---- 动态壁纸：真的损坏过用户数据的那条 ----

    @Test
    fun `锁屏是动态壁纸时，无论如何都不允许开启`() {
        // 设置 live wallpaper 需要 signature 级的 SET_WALLPAPER_COMPONENT，
        // 我们没有；静态的「恢复图」也替不回动态壁纸。这一档**根本没有
        // 恢复手段**，唯一负责任的做法是不让开。
        // 开发中在 S24 上真的这么弄丢过一次用户的锁屏动态壁纸。
        assertFalse(RestorePlan.canEnable(OriginalWallpaperKind.LIVE_WALLPAPER, false))
        assertFalse(
            "有恢复图也不行——静态图替不回动态壁纸",
            RestorePlan.canEnable(OriginalWallpaperKind.LIVE_WALLPAPER, true),
        )
    }

    @Test
    fun `允许开启的情形，恢复时一定不会无所适从`() {
        // 把 canEnable 与 decide 串起来：只要放行了开启，
        // 恢复就必须有一个明确且不损坏用户数据的动作。
        OriginalWallpaperKind.entries.forEach { kind ->
            listOf(true, false).forEach { user ->
                if (RestorePlan.canEnable(kind, user)) {
                    val action = RestorePlan.decide(kind, user)
                    // 继承态必须 clear；独立态有图就写图。
                    val ok = when (kind) {
                        OriginalWallpaperKind.INHERITED -> action == Action.ClearLock
                        OriginalWallpaperKind.USER_SUPPLIED -> action == Action.WriteUserSupplied
                        // 这一档不该被放行，走到这里本身就是错的
                        OriginalWallpaperKind.LIVE_WALLPAPER -> false
                    }
                    assertTrue("kind=$kind user=$user 的恢复动作不对: $action", ok)
                }
            }
        }
    }
}

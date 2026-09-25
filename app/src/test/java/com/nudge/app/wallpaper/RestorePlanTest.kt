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

    // ---- 归属检查：真机上覆盖过用户刚设好的壁纸 ----

    @Test
    fun `当前壁纸不是我们写的，什么都不能做`() {
        // 真机事故：残留的「恢复图」在关闭功能时把用户刚设好的壁纸盖掉了，
        // 而他完全不知道是 nudge 干的（表现为「装了新版还是没恢复」）。
        // 用户自己换过壁纸之后，我们手上那份「原壁纸」记录就过期了。
        OriginalWallpaperKind.entries.forEach { kind ->
            listOf(true, false).forEach { user ->
                assertEquals(
                    "kind=$kind user=$user：壁纸不归我们时不该动它",
                    Action.DoNothing,
                    RestorePlan.decide(kind, user, weOwnCurrentWallpaper = false),
                )
            }
        }
    }

    @Test
    fun `归属检查优先于所有 kind 分支`() {
        // 继承态平时是 clear，但壁纸不归我们时连 clear 都不能做——
        // clear 同样会改掉用户当前的锁屏壁纸。
        assertEquals(
            Action.DoNothing,
            RestorePlan.decide(OriginalWallpaperKind.INHERITED, false, false),
        )
    }

    @Test
    fun `壁纸归我们时，各分支照常工作`() {
        assertEquals(
            Action.ClearLock,
            RestorePlan.decide(OriginalWallpaperKind.INHERITED, false, true),
        )
        assertEquals(
            Action.WriteUserSupplied,
            RestorePlan.decide(OriginalWallpaperKind.USER_SUPPLIED, true, true),
        )
    }

    // ---- 特效壁纸：真的把用户桌面搞黑过的那条 ----

    @Test
    fun `特效壁纸绝不能 clear——穷举`() {
        // **这条是整个类里最重要的断言。**
        //
        // 真机实测（S24 Ultra / Android 16）：景深壁纸下 clear(FLAG_LOCK)
        // 会把**桌面和锁屏一起变成纯黑**，而写图只影响锁屏。两者不对称，
        // 所以这一档无论有没有恢复图都不能落到 ClearLock。
        listOf(true, false).forEach { user ->
            listOf(true, false).forEach { own ->
                val action = RestorePlan.decide(OriginalWallpaperKind.LIVE_WALLPAPER, user, own)
                assertTrue(
                    "user=$user own=$own：特效壁纸下 clear 会把桌面一起搞黑，得到 $action",
                    action != Action.ClearLock,
                )
            }
        }
    }

    @Test
    fun `特效壁纸有恢复图才允许开启`() {
        // 特效本身一定回不来（重新绑定 service 要 signature 级权限），
        // 但「恢复成一张普通静态图」远好过「桌面锁屏一起纯黑」。
        // 所以这一档按 USER_SUPPLIED 的口径处理，而不是一律拒绝。
        assertFalse(
            "没有恢复图时不能开——关闭时就只能留着封面了",
            RestorePlan.canEnable(OriginalWallpaperKind.LIVE_WALLPAPER, false),
        )
        assertTrue(
            "有恢复图就该放行，代价（丢特效）由 UI 明确告知后用户自己承担",
            RestorePlan.canEnable(OriginalWallpaperKind.LIVE_WALLPAPER, true),
        )
    }

    @Test
    fun `特效壁纸没有恢复图时宁可什么都不做`() {
        // 兜底分支（开启之后恢复图又被删了）。留着封面至少是张能看的图，
        // 而 clear 会让桌面一起黑。
        assertEquals(
            Action.DoNothing,
            RestorePlan.decide(OriginalWallpaperKind.LIVE_WALLPAPER, false, true),
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
                    // 继承态必须 clear；独立态与特效态有图就写图。
                    val ok = when (kind) {
                        OriginalWallpaperKind.INHERITED -> action == Action.ClearLock
                        OriginalWallpaperKind.USER_SUPPLIED,
                        OriginalWallpaperKind.LIVE_WALLPAPER,
                        -> action == Action.WriteUserSupplied
                    }
                    assertTrue("kind=$kind user=$user 的恢复动作不对: $action", ok)
                }
            }
        }
    }
}

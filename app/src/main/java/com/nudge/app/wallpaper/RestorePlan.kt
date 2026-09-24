package com.nudge.app.wallpaper

/**
 * 「恢复原壁纸该做什么」的决策，纯 Kotlin 无 Android 依赖。
 *
 * 从 [LockWallpaperWriter] 里抽出来单测，是因为这是整个功能里**唯一
 * 会造成用户察觉不到的破坏**的地方：选错分支会把用户的锁屏壁纸
 * 永久改掉，而他只会觉得"手机怪怪的"，不会联想到 nudge。
 *
 * 真机上验证这条要反复设置/清除壁纸，很难覆盖全分支；纯函数化之后
 * 三条分支加异常情形都能钉死。
 */
object RestorePlan {

    sealed interface Action {
        /**
         * `clear(FLAG_LOCK)` 回到「锁屏跟随桌面」。
         *
         * 用户原本没设独立锁屏壁纸时**只能**这样。写任何图回去都会把
         * 「继承」变成「固定一张」——这是静默的破坏。
         */
        data object ClearLock : Action

        /** 写用户指定的恢复图。 */
        data object WriteUserSupplied : Action
    }

    /**
     * @param kind 开启功能前探测到的原壁纸形态
     * @param userSuppliedAvailable 用户是否指定了恢复图
     */
    fun decide(kind: OriginalWallpaperKind, userSuppliedAvailable: Boolean): Action =
        when (kind) {
            // 继承态 —— 恒为 clear，**与手上有没有图无关**。
            // 这条不能因为「正好有张用户指定图」就改去写图：
            // 用户原本的锁屏是跟随桌面的，写一张进去就把这个性质破坏了。
            OriginalWallpaperKind.INHERITED -> Action.ClearLock

            OriginalWallpaperKind.USER_SUPPLIED ->
                // 没有恢复图时退到 clear。这**会**让用户丢掉原本那张独立
                // 锁屏壁纸，所以 canEnable 在一开始就拦住了这种组合——
                // 走到这里说明是开启之后恢复图又被删了，属于兜底。
                if (userSuppliedAvailable) Action.WriteUserSupplied else Action.ClearLock

            // 动态壁纸：canEnable 恒为 false，正常流程根本走不到这里。
            // 真走到了说明是历史遗留状态（早先版本开过），clear 是唯一
            // 能做的——动态壁纸我们本来就恢复不了，至少别留着封面。
            OriginalWallpaperKind.LIVE_WALLPAPER -> Action.ClearLock
        }

    /**
     * 是否允许开启功能。
     *
     * 设过独立锁屏壁纸、又没有用户指定的恢复图时**不允许开启**——
     * Android 13+ 读不到原图，开了就再也回不去了。这是个刻意的阻拦，
     * 不是校验的副产物：宁可不做，也不能静默损坏用户的东西。
     */
    fun canEnable(kind: OriginalWallpaperKind, userSuppliedAvailable: Boolean): Boolean =
        when (kind) {
            OriginalWallpaperKind.INHERITED -> true
            OriginalWallpaperKind.USER_SUPPLIED -> userSuppliedAvailable
            // 动态壁纸**无论如何都不给开**：设置 live wallpaper 需要
            // signature 级的 SET_WALLPAPER_COMPONENT，我们没有，
            // 静态的「恢复图」也替不回动态壁纸——这一档根本没有恢复手段。
            // 开发中真的这么弄丢过一次用户的锁屏动态壁纸。
            OriginalWallpaperKind.LIVE_WALLPAPER -> false
        }
}

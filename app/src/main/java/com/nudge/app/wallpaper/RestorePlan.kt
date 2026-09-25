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
         * 什么都别做。
         *
         * 当前锁屏壁纸**不是我们写的那张**——用户在这期间自己换过。
         * 此时写回「恢复图」会把他刚设好的壁纸盖掉，而他根本不知道是
         * nudge 干的。真机上出过这个事故。
         */
        data object DoNothing : Action

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
     * @param weOwnCurrentWallpaper 当前锁屏壁纸是不是我们写的那张
     *   （比对 `getWallpaperId`，见 `LockWallpaperWriter.currentLockWallpaperId`）
     */
    fun decide(
        kind: OriginalWallpaperKind,
        userSuppliedAvailable: Boolean,
        weOwnCurrentWallpaper: Boolean = true,
    ): Action {
        // **这道检查必须排在最前面，优先于所有 kind 分支。**
        // 用户自己换过壁纸的话，我们手上那张「原壁纸」的记录早就过期了，
        // 无论它属于哪一档，写回去都是在破坏他当前的选择。
        if (!weOwnCurrentWallpaper) return Action.DoNothing

        return when (kind) {
            // 继承态 —— 恒为 clear，**与手上有没有图无关**。
            // 这条不能因为「正好有张用户指定图」就改去写图：
            // 用户原本的锁屏是跟随桌面的，写一张进去就把这个性质破坏了。
            OriginalWallpaperKind.INHERITED -> Action.ClearLock

            OriginalWallpaperKind.USER_SUPPLIED ->
                // 没有恢复图时退到 clear。这**会**让用户丢掉原本那张独立
                // 锁屏壁纸，所以 canEnable 在一开始就拦住了这种组合——
                // 走到这里说明是开启之后恢复图又被删了，属于兜底。
                if (userSuppliedAvailable) Action.WriteUserSupplied else Action.ClearLock

            // **这一档绝不能 clear**，哪怕手上没有恢复图。
            //
            // 真机实测（S24 Ultra / Android 16）：景深壁纸下 clear(FLAG_LOCK)
            // 会把**桌面和锁屏一起变成纯黑**。成因是三星的景深壁纸主屏与锁屏
            // 原本是「配对」的（logcat 里的 isSystemAndLockPaired），
            // setBitmap 写封面时把配对拆开——此时桌面仍正常，只有锁屏变封面；
            // 而 clear 删掉锁屏条目后，配对已经拆了回不到「继承桌面」，
            // 桌面那个 live wallpaper 也因为丢了图源一起黑掉。
            //
            // 写图只影响锁屏（实测：启用期间桌面的景深壁纸完好），
            // 所以写图是安全的、clear 是危险的，两者不对称。
            // 没有恢复图时宁可 DoNothing 留着封面——那至少是张能看的图。
            OriginalWallpaperKind.LIVE_WALLPAPER ->
                if (userSuppliedAvailable) Action.WriteUserSupplied else Action.DoNothing
        }
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
            // 特效壁纸（景深/立体）：**有恢复图就允许开**，同 USER_SUPPLIED。
            //
            // 早先这里恒为 false，理由是「特效回不来，等于没有恢复手段」。
            // 那个判断只看了「能不能完美还原」，没看**不还原的代价**——
            // 实测 clear 会让桌面和锁屏一起变纯黑（见 decide 的注释），
            // 而写一张静态图至少是个可用的状态，特效用户自己能再开。
            //
            // 代价是开启前必须**明确告知特效会丢失**，由用户知情后决定；
            // 这与「静默破坏」是两回事。UI 侧的文案在 LockWallpaperScreen。
            OriginalWallpaperKind.LIVE_WALLPAPER -> userSuppliedAvailable
        }
}

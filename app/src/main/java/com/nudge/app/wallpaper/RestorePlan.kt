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
        /** 写回备份的原图。 */
        data object WriteBackup : Action

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
     * @param kind 开启功能时记录的原壁纸形态
     * @param backupAvailable 备份文件当前是否可用（可能被清理或损坏）
     * @param userSuppliedAvailable 用户是否指定了恢复图
     */
    fun decide(
        kind: OriginalWallpaperKind,
        backupAvailable: Boolean,
        userSuppliedAvailable: Boolean,
    ): Action = when (kind) {
        OriginalWallpaperKind.BACKED_UP ->
            // 备份没了就退到 clear：总好过把封面永久留在锁屏上。
            if (backupAvailable) Action.WriteBackup else Action.ClearLock

        // 没设过独立锁屏壁纸 —— 恒为 clear，**与有没有备份无关**。
        // 这条不能因为「手上正好有张备份图」就改去写图。
        OriginalWallpaperKind.INHERITED -> Action.ClearLock

        OriginalWallpaperKind.USER_SUPPLIED ->
            if (userSuppliedAvailable) Action.WriteUserSupplied else Action.ClearLock
    }

    /**
     * 是否允许开启功能。
     *
     * 备份失败又没有用户指定图时**不允许开启**——不能让用户在不知情的
     * 情况下丢掉原壁纸。这是个刻意的阻拦，不是校验的副产物。
     */
    fun canEnable(kind: OriginalWallpaperKind, userSuppliedAvailable: Boolean): Boolean =
        when (kind) {
            OriginalWallpaperKind.BACKED_UP, OriginalWallpaperKind.INHERITED -> true
            OriginalWallpaperKind.USER_SUPPLIED -> userSuppliedAvailable
        }
}

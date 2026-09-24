package com.nudge.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log

/**
 * 锁屏壁纸的读写与**恢复**。
 *
 * 恢复是这个功能里最需要小心的部分：写错了会让用户丢掉自己的壁纸，
 * 而且**他不会知道是 nudge 干的**。所以三种情形分得很清楚，
 * 由 [OriginalWallpaperKind] 表达，`LockWallpaperRestoreTest` 钉住。
 */
class LockWallpaperWriter(private val context: Context) {

    private val wm: WallpaperManager get() = WallpaperManager.getInstance(context)

    /**
     * 探测原锁屏壁纸属于哪种情形。**必须在第一次写入之前调用并持久化**——
     * 我们自己写过一次之后，系统就认为「设过独立锁屏壁纸」了，再探测只会
     * 拿到被自己污染的结果。
     *
     * ## 为什么不备份原图：Android 13+ 读不到，这是 Google 的既定行为
     *
     * 真机实测（Android 13 / targetSdk 34）`getWallpaperFile(FLAG_LOCK)` 抛：
     * ```
     * SecurityException: Permission android.permission.READ_EXTERNAL_STORAGE denied
     * ```
     * 而 `READ_EXTERNAL_STORAGE` 从 targetSdk 33 起对 app 已失效（系统直接
     * 拒绝、连对话框都不弹），`READ_MEDIA_IMAGES` **也救不了**——服务端
     * `StorageManager.checkPermissionReadImages` 是两道串联的与门，
     * 第一道查的就是 `READ_EXTERNAL_STORAGE`，第二道的 `READ_MEDIA_IMAGES`
     * AppOp 根本没机会执行。另一条出路 `READ_WALLPAPER_INTERNAL` 是
     * `signature|privileged`。
     *
     * Google 已把 issuetracker #237124750 标记为 **Won't Fix (Intended
     * Behavior)**，A14 起更是无条件抛异常。所以**别再尝试读原壁纸**，
     * 也不要为此去声明 `READ_MEDIA_IMAGES`：解决不了问题，只多要一个权限。
     *
     * ## 改用 `getWallpaperId`：无需任何权限
     *
     * 它在 `WallpaperManager` 里**没有** `@RequiresPermission` 注解，
     * 服务端也没有任何权限检查，只读 `mLockWallpaperMap`——而那个 map
     * 的语义恰好就是我们要区分的状态（服务端自己的注释：
     * "Returns true if the lock screen wallpaper exists (different wallpaper
     * from the system)"，且锁屏继承桌面时该 map 为空）。
     *
     * 代价是它只告诉我们「有没有」，**给不了图**。所以「设过独立锁屏壁纸」
     * 的用户只能请他自己指定一张恢复图。
     *
     * ## 三星上这个判定偏保守，是刻意接受的
     *
     * 真机实测（One UI 5.1）：`clear(FLAG_LOCK)` **不会**删掉 lock 条目，
     * `dumpsys wallpaper` 里 `Lock Wallpaper / User 0: id=16` 依然在，
     * 于是 `getWallpaperId(FLAG_LOCK)` 恒 > 0。也就是说在三星上这个判定
     * 很可能恒为 [OriginalWallpaperKind.USER_SUPPLIED]。
     *
     * **这是可接受的**，因为它偏向安全的那一侧：最坏的后果是要求用户
     * 多指定一张恢复图（一次性的麻烦），而反过来误判成「继承」会直接
     * clear 掉他真实存在的锁屏壁纸，且不可逆。
     * 与「收藏必须先读后写」同一个口径：**拿不准就别动用户的数据**。
     */
    fun detectOriginalKind(): OriginalWallpaperKind {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // FLAG_LOCK 是 API 24 引入的；更低版本没有独立锁屏壁纸的概念。
            return OriginalWallpaperKind.INHERITED
        }
        return runCatching {
            if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0) {
                // 设过独立锁屏壁纸。读不到它的内容，只能请用户指定恢复图——
                // 直接 clear 会把他原本那张锁屏壁纸弄丢。
                OriginalWallpaperKind.USER_SUPPLIED
            } else {
                OriginalWallpaperKind.INHERITED
            }
        }.getOrElse {
            Log.w(TAG, "探测锁屏壁纸状态失败，按需用户指定恢复图处理", it)
            // 探测不到就别猜：按最需要用户参与的那档处理，
            // 而不是乐观地假设「继承」然后 clear 掉他的壁纸。
            OriginalWallpaperKind.USER_SUPPLIED
        }
    }

    /**
     * 写入一张图作为锁屏壁纸。
     *
     * @return 耗时（ms），失败返回 null
     */
    fun write(bitmap: Bitmap): Long? {
        val t0 = System.currentTimeMillis()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            } else {
                // 没有 FLAG_LOCK 的年代只能整体设置。minSdk 26 实际走不到这里，
                // 留着是为了让分支完整而不是抛异常。
                wm.setBitmap(bitmap)
            }
            System.currentTimeMillis() - t0
        }.getOrElse {
            Log.w(TAG, "写入锁屏壁纸失败", it)
            null
        }
    }

    /**
     * 恢复原壁纸。**三种情形的动作完全不同，不能合并。**
     *
     * @param userSupplied [OriginalWallpaperKind.USER_SUPPLIED] 时用户指定的恢复图
     */
    fun restore(kind: OriginalWallpaperKind, userSupplied: Bitmap? = null): Boolean =
        runCatching {
            // 分支判断交给 RestorePlan（纯函数、有穷举测试兜底），
            // 这里只负责执行。两处各写一份 when 的话，改了一处忘了另一处
            // 就会出现「测试全绿但真机上把继承态写成了固定图」。
            when (RestorePlan.decide(kind, userSupplied != null)) {
                RestorePlan.Action.ClearLock -> clearLock()
                RestorePlan.Action.WriteUserSupplied ->
                    userSupplied?.let { write(it) != null } ?: clearLock()
            }
        }.getOrElse {
            Log.w(TAG, "恢复锁屏壁纸失败", it)
            false
        }

    /** 清掉独立锁屏壁纸，回到「跟随桌面」。 */
    private fun clearLock(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            wm.clear(WallpaperManager.FLAG_LOCK)
            true
        } else false
    }.getOrElse { false }

    companion object {
        private const val TAG = "LockWallpaper"
    }
}

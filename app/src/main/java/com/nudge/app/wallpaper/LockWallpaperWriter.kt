package com.nudge.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 锁屏壁纸的读写与**恢复**。
 *
 * 恢复是这个功能里最需要小心的部分：写错了会让用户丢掉自己的壁纸，
 * 而且**他不会知道是 nudge 干的**。所以三种情形分得很清楚，
 * 由 [OriginalWallpaperKind] 表达，`LockWallpaperRestoreTest` 钉住。
 */
class LockWallpaperWriter(private val context: Context) {

    private val wm: WallpaperManager get() = WallpaperManager.getInstance(context)

    /** 备份原壁纸的落点。放私有目录，不需要存储权限。 */
    private val backupFile: File get() = File(context.filesDir, BACKUP_NAME)

    /**
     * 开启功能前备份原锁屏壁纸，返回它属于哪种情形。
     *
     * `getWallpaperFile(FLAG_LOCK)` 返回 null 表示**没设过**独立锁屏壁纸
     * （锁屏继承桌面）——这是正常状态不是错误，测试机上就是如此。
     * 这两种情形的恢复动作完全不同，必须在这里分开。
     */
    fun backupOriginal(): OriginalWallpaperKind {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // FLAG_LOCK 是 API 24 引入的；更低版本没有独立锁屏壁纸的概念。
            return OriginalWallpaperKind.USER_SUPPLIED
        }
        return runCatching {
            val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
                ?: return OriginalWallpaperKind.INHERITED
            pfd.use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { input ->
                    backupFile.outputStream().use { input.copyTo(it) }
                }
            }
            OriginalWallpaperKind.BACKED_UP
        }.getOrElse {
            // 部分 One UI 版本对第三方保护壁纸文件。此时**不能**假装成功——
            // 调用方要据此要求用户显式指定一张恢复图。
            Log.w(TAG, "备份锁屏壁纸失败，需用户指定恢复图", it)
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
            when (RestorePlan.decide(kind, hasBackup(), userSupplied != null)) {
                RestorePlan.Action.WriteBackup -> {
                    val bmp = BitmapFactory.decodeFile(backupFile.absolutePath)
                    if (bmp == null) {
                        // 文件在但解不出来（损坏）。退回继承态，
                        // 总好过把封面永久留在锁屏上。
                        Log.w(TAG, "备份文件损坏，回落到 clear")
                        clearLock()
                    } else {
                        write(bmp) != null
                    }
                }

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

    /** 备份是否还在。用于设置页提示用户当前能否安全恢复。 */
    fun hasBackup(): Boolean = backupFile.exists() && backupFile.length() > 0

    companion object {
        private const val TAG = "LockWallpaper"
        private const val BACKUP_NAME = "lock_wallpaper_backup.png"
    }
}

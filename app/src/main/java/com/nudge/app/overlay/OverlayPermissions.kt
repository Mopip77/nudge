package com.nudge.app.overlay

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * 气泡需要的两项权限。**两者都不是运行时权限**，`requestPermissions` 对它们
 * 无效——必须跳系统设置页由用户手动开。
 *
 * 真机核对（`pm list permissions -f`）：
 * - `SYSTEM_ALERT_WINDOW`：`signature|development|appop|pre23|installer|setup`
 * - `PACKAGE_USAGE_STATS`：`signature|privileged|development|appop|retailDemo`
 *
 * 两者都带 `appop`，这是第三方应用唯一的入口——由用户在设置里授予那个 op。
 */
object OverlayPermissions {

    /** 能不能在别的应用上方画。 */
    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 能不能读使用情况（即能不能知道前台是哪个应用）。
     *
     * **不能用 `checkSelfPermission` 查**——它查的是 manifest 里的授予状态，
     * 而这个权限对第三方永远是 denied（`signature|privileged`）。真正决定
     * 能否调用的是 appop `GET_USAGE_STATS`，所以必须问 `AppOpsManager`。
     * 查错的表现是「明明授了权还提示未授予」。
     */
    fun canReadUsageStats(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun allGranted(context: Context): Boolean =
        canDrawOverlay(context) && canReadUsageStats(context)

    /**
     * 悬浮窗授权页。**带 package Uri** 直达本应用那一项——不带的话落到
     * 全部应用的长列表，用户得自己翻。
     */
    fun overlayIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * 使用情况访问页。
     *
     * 这个页面**不接受 package Uri**（部分 ROM 上带了会直接打不开），
     * 只能落到列表页让用户找到 nudge。所以设置页的说明里要写清要找哪一项。
     */
    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

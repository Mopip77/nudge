package com.nudge.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * 本应用不自己安装，只是拉起系统的安装确认界面——真正的安装由系统完成，
 * 用户能看到权限变更并自行确认。
 */
object UpdateInstaller {

    /**
     * minSdk 26，一律走 FileProvider。
     *
     * 从 Android 7 起直接传 `file://` 给其他应用会抛 FileUriExposedException，
     * 必须换成 content:// 并附带临时读权限——安装器是另一个进程，没有这个授权读不到文件。
     */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        // 用 ACTION_INSTALL_PACKAGE 而不是 ACTION_VIEW：真机实测 ACTION_VIEW 会弹
        // 「打开方式」选择器，APKPure、网易云、Termux 这些声明了 APK MIME 的应用全都列出来，
        // 用户得自己认出「软件包安装程序」，选错就装不上。
        // ACTION_INSTALL_PACKAGE 只解析到系统安装器一家，直达安装确认页。
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 从非 Activity 上下文启动需要这个 flag
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 安装完成后「打开」按钮能回到 nudge
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        context.startActivity(intent)
    }

    /**
     * Android 8 起「安装未知应用」是按应用授予的权限，未授权时直接拉安装器会被静默拦下。
     * 所以安装前先查一次，没授权就先把用户送到设置页。
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳转到本应用的「安装未知应用」授权页。 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

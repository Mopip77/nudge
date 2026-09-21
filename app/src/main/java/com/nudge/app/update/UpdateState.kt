package com.nudge.app.update

/**
 * 更新流程的界面状态。设置页只读这个密封类，不关心底层是网络请求还是文件下载。
 */
sealed class UpdateState {

    /** 尚未检查，设置页只显示「检查更新」按钮。 */
    data object Idle : UpdateState()

    /** 正在请求 GitHub API。 */
    data object Checking : UpdateState()

    /** 已是最新版本。 */
    data object UpToDate : UpdateState()

    /** 检查失败：无网络、超时、限流、响应结构变化，统一归到这里。 */
    data object CheckFailed : UpdateState()

    /** 发现新版本，等待用户决定是否下载。 */
    data class Available(val release: ReleaseInfo) : UpdateState()

    /**
     * 正在下载 APK。
     *
     * @param downloadedBytes 已下载字节数
     * @param totalBytes 总字节数，取自 release 资产的 size
     */
    data class Downloading(
        val release: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState() {
        /** 0f..1f，总长未知时回落为 0 避免除零。 */
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /**
     * 下载完成，已拉起系统安装器。
     *
     * 停在这个状态而不回到 Idle：安装器是另一个应用的界面，用户可能取消安装再退回来，
     * 此时保留「重新安装」入口比让他重新下载一遍友好。
     */
    data class Downloaded(val release: ReleaseInfo) : UpdateState()

    /** 下载失败，允许重试。 */
    data class DownloadFailed(val release: ReleaseInfo) : UpdateState()
}

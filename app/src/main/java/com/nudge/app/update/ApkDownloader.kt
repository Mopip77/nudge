package com.nudge.app.update

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载 APK 到应用私有缓存目录。
 *
 * 不用 DownloadManager：它把文件下到公共目录、要额外权限、还得注册广播接收完成事件，
 * 而这里只需要一个几 MB 的前台下载，自己读流反而更简单也更好控制进度回调。
 */
object ApkDownloader {

    private const val TIMEOUT_MS = 15000
    private const val BUFFER_SIZE = 8192

    /**
     * 进度回调的最小间隔。
     *
     * 每读一个 8KB 缓冲就回调一次的话，13MB 的包要回调一千多次，而调用方每次回调都要
     * 切一次主线程更新 UI——纯属浪费。200ms 一次对进度条来说已经足够跟手。
     */
    private const val PROGRESS_INTERVAL_MS = 200L

    /** 下载目录固定一个子目录，方便整体清理旧包。 */
    private fun downloadDir(context: Context): File =
        File(context.cacheDir, "update").apply { mkdirs() }

    fun apkFile(context: Context, versionName: String): File =
        File(downloadDir(context), "nudge-$versionName.apk")

    /**
     * 阻塞下载，必须在 IO 线程执行。
     *
     * @param onProgress 已下载字节数回调，调用方自行切回主线程更新 UI
     * @return 下载好的文件，失败返回 null
     */
    fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Long) -> Unit,
    ): File? {
        // 每次重新下载前清掉旧的残留包，避免上次中断的半截文件被当成完整包安装
        downloadDir(context).listFiles()?.forEach { it.delete() }

        val target = apkFile(context, release.versionName)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "nudge-android")
                // release 资产直链会 302 到对象存储，必须跟随重定向
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            var downloaded = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReported >= PROGRESS_INTERVAL_MS) {
                            lastReported = now
                            onProgress(downloaded)
                        }
                    }
                }
            }
            // 补一次终值，否则最后一段不足 200ms 的增量会丢，进度条停在 99%
            onProgress(downloaded)

            // 长度对不上说明连接中途断了，这种半截文件装不上，直接丢弃
            if (release.apkSize > 0 && downloaded != release.apkSize) {
                target.delete()
                null
            } else {
                target
            }
        } catch (e: Exception) {
            target.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }
}

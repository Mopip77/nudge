package com.nudge.app.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * 查询 GitHub 上的最新 release。
 *
 * 用 `/releases/latest` 而不是 `/releases`：这个端点本身就会跳过 draft 和 prerelease，
 * 语义上就是「官方认定的当前版本」，不用自己过滤和排序。
 *
 * 与 LyricsFetcher 一致，用 HttpURLConnection 而非 OkHttp——全 app 就这么几个请求，
 * 不值得为它引入依赖。
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Mopip77/nudge/releases/latest"

    private const val TIMEOUT_MS = 10000

    /** 阻塞调用，必须在 IO 线程执行。任何失败返回 null，调用方降级为「检查失败」。 */
    fun fetchLatest(): ReleaseInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // GitHub API 要求带 UA，否则直接 403
                setRequestProperty("User-Agent", "nudge-android")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            ReleaseInfo.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            // 无网络、超时、未认证限流（每 IP 每小时 60 次）都走这里
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 判断是否需要更新。
     *
     * 只做字符串不等比较，不解析版本号大小：`/releases/latest` 已经代表官方认定的当前版本，
     * 本地与之不一致就说明本地不是最新的。这样也不必处理 `1.10 vs 1.9` 这类分段比较的坑。
     *
     * 副作用是本地 debug 包（versionName 回落为 `1.0`）总会被判定为有更新——
     * 手动检查入口下这可以接受，用户点了才会看到，且装的确实是更新的正式版。
     */
    fun hasUpdate(currentVersion: String, latest: ReleaseInfo): Boolean =
        latest.versionName.isNotBlank() && latest.versionName != currentVersion
}

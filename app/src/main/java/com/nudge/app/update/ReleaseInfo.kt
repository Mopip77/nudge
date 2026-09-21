package com.nudge.app.update

import org.json.JSONObject

/**
 * GitHub latest release 中与更新有关的字段。
 *
 * @param versionName 去掉 `v` 前缀的 tag，如 `1.4`，直接与 BuildConfig.VERSION_NAME 比较
 * @param releaseNotes release body 原文（markdown），展示时不做渲染
 * @param apkUrl APK 资产的直链
 * @param apkSize APK 字节数，用于下载进度分母
 */
data class ReleaseInfo(
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSize: Long,
) {
    companion object {

        /** APK 资产的 content_type，据此从 assets 里挑出安装包。 */
        private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

        /**
         * 解析 `/releases/latest` 的响应。结构不符合预期（缺 tag、没有 APK 资产）时返回 null，
         * 让调用方降级为「检查失败」而不是崩溃——GitHub 改字段时不能把 app 带崩。
         *
         * 挑资产靠 content_type 而不是文件名前缀，这样 CI 里改了 APK 命名规则也不用同步改这里。
         */
        fun parse(json: String): ReleaseInfo? = try {
            val root = JSONObject(json)
            val tag = root.optString("tag_name").takeIf { it.isNotBlank() }

            val asset = (0 until (root.optJSONArray("assets")?.length() ?: 0))
                .asSequence()
                .mapNotNull { root.optJSONArray("assets")?.optJSONObject(it) }
                .firstOrNull { it.optString("content_type") == APK_CONTENT_TYPE }

            val url = asset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }

            if (tag == null || url == null) {
                null
            } else {
                ReleaseInfo(
                    versionName = tag.removePrefix("v"),
                    releaseNotes = root.optString("body").trim(),
                    apkUrl = url,
                    apkSize = asset.optLong("size"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

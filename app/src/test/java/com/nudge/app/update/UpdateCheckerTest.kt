package com.nudge.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReleaseInfo.parse 与版本比较的单测。
 *
 * 样本 JSON 取自 /repos/Mopip77/nudge/releases/latest 的真实响应（已裁剪掉无关字段），
 * 字段名和取值形态与线上一致。
 */
class UpdateCheckerTest {

    private fun releaseJson(
        tag: String = "v1.4",
        assetName: String = "nudge-1.4.apk",
        contentType: String = "application/vnd.android.package-archive",
        size: Long = 13853067,
        body: String = "**Full Changelog**: https://github.com/Mopip77/nudge/compare/v1.3...v1.4",
    ) = """
        {
          "tag_name": "$tag",
          "draft": false,
          "prerelease": false,
          "body": "$body",
          "assets": [
            {
              "name": "$assetName",
              "content_type": "$contentType",
              "size": $size,
              "browser_download_url": "https://github.com/Mopip77/nudge/releases/download/$tag/$assetName"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析真实响应能取出全部字段`() {
        val info = ReleaseInfo.parse(releaseJson())!!

        assertEquals("1.4", info.versionName)
        assertEquals(13853067L, info.apkSize)
        assertEquals(
            "https://github.com/Mopip77/nudge/releases/download/v1.4/nudge-1.4.apk",
            info.apkUrl,
        )
        assertTrue(info.releaseNotes.contains("Full Changelog"))
    }

    @Test
    fun `tag 的 v 前缀被去掉`() {
        assertEquals("2.0", ReleaseInfo.parse(releaseJson(tag = "v2.0"))!!.versionName)
    }

    @Test
    fun `tag 没有 v 前缀时原样保留`() {
        assertEquals("2.0", ReleaseInfo.parse(releaseJson(tag = "2.0"))!!.versionName)
    }

    @Test
    fun `没有 APK 资产返回 null`() {
        // 只挂了源码压缩包的 release，装不了，应当视为解析失败
        val json = releaseJson(assetName = "source.zip", contentType = "application/zip")
        assertNull(ReleaseInfo.parse(json))
    }

    @Test
    fun `assets 为空返回 null`() {
        val json = """{"tag_name": "v1.4", "body": "", "assets": []}"""
        assertNull(ReleaseInfo.parse(json))
    }

    @Test
    fun `缺少 tag_name 返回 null`() {
        val json = """
            {
              "body": "",
              "assets": [{
                "name": "nudge-1.4.apk",
                "content_type": "application/vnd.android.package-archive",
                "size": 100,
                "browser_download_url": "https://example.com/a.apk"
              }]
            }
        """.trimIndent()
        assertNull(ReleaseInfo.parse(json))
    }

    @Test
    fun `响应不是合法 JSON 返回 null 而不抛异常`() {
        assertNull(ReleaseInfo.parse("not json at all"))
        assertNull(ReleaseInfo.parse(""))
    }

    @Test
    fun `多个资产时按 content_type 挑出 APK`() {
        val json = """
            {
              "tag_name": "v1.5",
              "body": "",
              "assets": [
                {
                  "name": "mapping.txt",
                  "content_type": "text/plain",
                  "size": 10,
                  "browser_download_url": "https://example.com/mapping.txt"
                },
                {
                  "name": "nudge-1.5.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "size": 200,
                  "browser_download_url": "https://example.com/nudge-1.5.apk"
                }
              ]
            }
        """.trimIndent()

        val info = ReleaseInfo.parse(json)!!
        assertEquals("https://example.com/nudge-1.5.apk", info.apkUrl)
        assertEquals(200L, info.apkSize)
    }

    @Test
    fun `版本号不同判定为有更新`() {
        val latest = ReleaseInfo.parse(releaseJson(tag = "v1.5"))!!
        assertTrue(UpdateChecker.hasUpdate("1.4", latest))
    }

    @Test
    fun `版本号相同判定为已是最新`() {
        val latest = ReleaseInfo.parse(releaseJson(tag = "v1.4"))!!
        assertFalse(UpdateChecker.hasUpdate("1.4", latest))
    }

    @Test
    fun `本地版本比线上新也判定为有更新`() {
        // 只做不等比较的已知取舍：本地 debug 包 versionName 回落成 1.0，
        // 手动检查时会提示更新，属于预期行为
        val latest = ReleaseInfo.parse(releaseJson(tag = "v1.4"))!!
        assertTrue(UpdateChecker.hasUpdate("1.0", latest))
    }
}

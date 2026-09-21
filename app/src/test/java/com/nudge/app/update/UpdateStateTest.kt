package com.nudge.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateStateTest {

    private val release = ReleaseInfo(
        versionName = "1.5",
        releaseNotes = "",
        apkUrl = "https://example.com/nudge-1.5.apk",
        apkSize = 1000,
    )

    private fun downloading(downloaded: Long, total: Long) =
        UpdateState.Downloading(release, downloaded, total)

    @Test
    fun `进度按已下载比例计算`() {
        assertEquals(0.25f, downloading(250, 1000).progress, 0.001f)
    }

    @Test
    fun `总长为零时回落为零而不是除零崩溃`() {
        // 服务端没给 Content-Length 时 apkSize 可能是 0
        assertEquals(0f, downloading(500, 0).progress, 0.001f)
    }

    @Test
    fun `已下载超过总长时进度截断到一`() {
        assertEquals(1f, downloading(1200, 1000).progress, 0.001f)
    }
}

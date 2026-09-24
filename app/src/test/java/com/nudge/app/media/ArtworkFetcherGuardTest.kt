package com.nudge.app.media

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 非网易云播放器的 mediaId 不是数字 id，必须在发请求**之前**就被挡掉。
 *
 * 这条没法靠真机稳定复现（要另一个播放器正在播），但它是纯函数式的前置
 * 判断，单测反而是更可靠的验证手段。
 */
class ArtworkFetcherGuardTest {

    @Test
    fun `非纯数字 mediaId 直接返回 null 不发请求`() {
        // YouTube 之类播放器的 mediaId 形如这些
        listOf(
            "dQw4w9WgXcQ",
            "",
            "   ",
            "spotify:track:abc123",
            "12345abc",
            "content://media/external/audio/media/42",
        ).forEach {
            assertNull("「$it」不该走到网络请求", ArtworkFetcher.fetch(it, CoverAspect.SQUARE, 1080))
        }
    }
}

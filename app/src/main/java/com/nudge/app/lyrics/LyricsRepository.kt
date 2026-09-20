package com.nudge.app.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词加载入口。
 *
 * 不做缓存：实际使用中很少重复听同一首歌，缓存收益低，
 * 不值得引入存储与失效逻辑。一次请求仅几 KB。
 */
object LyricsRepository {

    /**
     * 按歌曲 id 取歌词。失败一律返回 [LyricsState.Unavailable]，绝不抛异常。
     *
     * 调用方需保证在歌曲切换时取消上一次调用，否则可能把旧歌的歌词
     * 显示到新歌上（用 LaunchedEffect(mediaId) 天然满足）。
     */
    suspend fun load(mediaId: String): LyricsState = withContext(Dispatchers.IO) {
        // 非网易云播放器的 mediaId 不是数字 id，查了也没意义，不浪费一次请求
        if (mediaId.isBlank() || !mediaId.all { it.isDigit() }) {
            return@withContext LyricsState.Unavailable
        }

        val raw = LyricsFetcher.fetchLrc(mediaId)
            ?: return@withContext LyricsState.Unavailable

        val lines = LrcParser.parse(raw)
        // 纯音乐的歌词字段可能只有元信息行，解析后为空
        if (lines.isEmpty()) LyricsState.Unavailable else LyricsState.Loaded(lines)
    }
}

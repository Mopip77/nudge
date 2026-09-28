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

        val lines = parseBestAvailable(raw)
        // 纯音乐的歌词字段可能只有元信息行，解析后为空
        if (lines.isEmpty()) LyricsState.Unavailable else LyricsState.Loaded(lines)
    }

    /**
     * 在两套歌词数据里**整对**择一，绝不跨轴混用。
     *
     * - 有 `yrc`（逐字）→ 用 `(yrc, ytlrc)`，得到带字级时间表的行
     * - 否则 → 用 `(lrc, tlyric)`，行为与加逐字支持之前**逐字节相同**
     *
     * **降级以整首歌为粒度**，所以不会出现同一首歌里有的行准、有的行偏。
     *
     * ## 为什么必须整对，不能把 yrc 的字级时间表挂到 lrc 的行上
     *
     * 真机实测两者**不在同一条时间轴上**：按时间戳精确匹配命中率近乎为零，
     * 按文本匹配后的偏差非常数，单曲内范围可达 [−523, +2000]ms
     * （完整取证见 `YrcParser` 的注释）。而 `ytlrc` 与 `yrc` 精确同轴
     * （命中 35/35），`tlyric` 与 `yrc` 完全不沾边（0/35）。
     *
     * 混用的表现是**扫光跑在字的前面或后面**，看着像动画没调好，
     * 不会被认成数据缺陷——正是 `LrcParser` 注释里警告过的那一类。
     * `LyricsRepositoryTest` 专门拦着这个方向。
     *
     * 已知代价：有 `yrc` 的歌，译文改从 `ytlrc` 取，措辞与行数可能与
     * `tlyric` 不同。可接受——译文缺失在本项目里本就是常态路径。
     *
     * 译文恒挂载（有就挂），**显不显示由渲染侧的开关决定**：不在这里按配置
     * 过滤，那会让开关的切换需要重新联网，而译文的字节已经一起拿回来了。
     */
    internal fun parseBestAvailable(raw: LyricsFetcher.RawLyrics): List<LyricLine> {
        val yrc = raw.yrc
        if (!yrc.isNullOrBlank()) {
            val wordLines = YrcParser.parseWithTranslation(yrc, raw.ytlrc)
            // yrc 解析为空（如整首只有元信息行）时继续往下回落，
            // 而不是直接判成「没有歌词」——lrc 那份可能是好的。
            if (wordLines.isNotEmpty()) return wordLines
        }
        return LrcParser.parseWithTranslation(raw.lrc, raw.tlyric)
    }
}

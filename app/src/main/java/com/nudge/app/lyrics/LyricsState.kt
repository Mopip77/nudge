package com.nudge.app.lyrics

/**
 * 歌词加载状态。
 *
 * [Loading] 与 [Unavailable] 在 UI 上表现一致（都不显示任何东西），
 * 区分它们只为便于调试——盲操 app 不该为"正在加载"这种事打扰用户。
 */
sealed interface LyricsState {
    /** 无歌曲在播 */
    data object Idle : LyricsState
    /** 请求中 */
    data object Loading : LyricsState
    data class Loaded(val lines: List<LyricLine>) : LyricsState
    /** 纯音乐、网络失败、接口无歌词——统一归为"没有歌词可显示" */
    data object Unavailable : LyricsState
}

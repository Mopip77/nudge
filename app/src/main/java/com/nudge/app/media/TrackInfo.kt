package com.nudge.app.media

import android.graphics.Bitmap
import android.os.SystemClock

/** 当前播放信息。 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val isLiked: Boolean,
    val album: String = "",
    /** 专辑封面，网易云实测为 363x363 ARGB_8888。无封面时为 null。 */
    val artwork: Bitmap? = null,
    /** 总时长，毫秒。未知为 0。 */
    val durationMs: Long = 0,
    /** 播放头位置，毫秒。它是 [positionUpdateTimeMs] 那一刻的快照，不可直接显示。 */
    val positionMs: Long = 0,
    /** [positionMs] 的采样时刻，SystemClock.elapsedRealtime() 基准。 */
    val positionUpdateTimeMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val isPlaying: Boolean = false,
    /** 歌曲唯一标识，用于判断是否真的换了歌（比标题可靠）。 */
    val mediaId: String = "",
) {
    /**
     * 按当前时刻推算真实播放位置。
     *
     * 播放器只在状态变化时回推 position，轮询间隔内它是不动的，
     * 直接显示会让进度条每秒一跳。用采样时刻的时间差补上。
     */
    fun currentPositionMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying || positionUpdateTimeMs <= 0) return positionMs.coerceAtLeast(0)
        val elapsed = ((nowMs - positionUpdateTimeMs) * playbackSpeed).toLong()
        val projected = positionMs + elapsed
        return if (durationMs > 0) projected.coerceIn(0, durationMs) else projected.coerceAtLeast(0)
    }

    /** 播放进度 0f..1f。时长未知时返回 0f。 */
    fun progress(nowMs: Long = SystemClock.elapsedRealtime()): Float {
        if (durationMs <= 0) return 0f
        return (currentPositionMs(nowMs).toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/** 毫秒格式化为 m:ss，用于时长展示。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** 动作执行结果，决定震动反馈模式与 UI 提示。 */
sealed interface ActionResult {
    /** 切歌成功 */
    data object Skipped : ActionResult
    /** 新点亮红心 */
    data object Liked : ActionResult
    /** 本来就已收藏，未做任何操作 */
    data object AlreadyLiked : ActionResult
    /** 找不到可控制的播放会话 */
    data object NoSession : ActionResult
    /** 其他失败 */
    data class Failed(val reason: String) : ActionResult
}

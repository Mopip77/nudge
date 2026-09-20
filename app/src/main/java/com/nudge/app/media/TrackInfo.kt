package com.nudge.app.media

/** 当前播放信息。 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val isLiked: Boolean,
)

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

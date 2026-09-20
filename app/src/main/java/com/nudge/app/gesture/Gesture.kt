package com.nudge.app.gesture

/** 支持的手势类型。 */
enum class Gesture(val displayName: String) {
    DOUBLE_TAP("双击"),
    TWO_FINGER_DOUBLE_TAP("两指双击"),
    THREE_FINGER_DOUBLE_TAP("三指双击"),
    TWO_FINGER_HOLD_TAP("两指长按 + 一指单击"),
    THREE_FINGER_HOLD_TAP("三指长按 + 一指单击"),
}

/**
 * 手势判定参数。
 *
 * @param doubleTapWindowMs 双击两次点击之间的最大间隔
 * @param multiTouchSlopMs 判定「同时按下」的时间窗
 * @param longPressMs 长按阈值
 * @param moveToleranceDp 移动容差，超出即判定为滑动并取消手势
 */
data class GestureParams(
    val doubleTapWindowMs: Long,
    val multiTouchSlopMs: Long,
    val longPressMs: Long,
    val moveToleranceDp: Float,
)

/** 灵敏度档位。越严格越难误触，但也越难触发。 */
enum class Sensitivity(val displayName: String, val params: GestureParams) {
    LOOSE("宽松", GestureParams(500L, 150L, 350L, 40f)),
    STANDARD("标准", GestureParams(350L, 100L, 500L, 24f)),
    STRICT("严格", GestureParams(250L, 60L, 700L, 12f)),
}

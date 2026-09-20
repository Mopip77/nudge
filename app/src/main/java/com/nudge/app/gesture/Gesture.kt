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
 * @param holdBaseReadyMs 「N 指长按 + 一指单击」中底座手指按下多久后算「就位」，
 *   就位后即可接受额外一指的单击。这是一个去抖用的短阈值，只用来过滤「手指刚触屏、
 *   位置还没稳定」的瞬间抖动，**不是**「长按」时长要求——真实语义是「先放上 N 指，
 *   再点一下」，底座手指只需处于按下状态，不需要刻意按住很久。
 *   因此它与 [longPressMs] 是两个独立的阈值，互不影响：[longPressMs] 仍然承担
 *   「一次轻点/双击按住太久则失效」「单击手指本身按太久则不算单击」这两处语义。
 *
 *   约束：[holdBaseReadyMs] 必须 ≥ [multiTouchSlopMs]。否则会出现「N 指几乎同时按下」
 *   与「N-1 指底座已就位、第 N 指是单独一击」同时成立的时间窗口——比如 N 指几乎同时
 *   按下又几乎同时抬起（应判定为 N 指双击的一次点击），若 holdBaseReadyMs 小于
 *   multiTouchSlopMs，最后落下的那根手指抬起时，前面的手指已经满足「就位」条件，
 *   会被误判成「N-1 指长按 + 一指单击」，与「N 指同时点击」的判定产生歧义。
 * @param moveToleranceDp 移动容差，超出即判定为滑动并取消手势
 */
data class GestureParams(
    val doubleTapWindowMs: Long,
    val multiTouchSlopMs: Long,
    val longPressMs: Long,
    val holdBaseReadyMs: Long,
    val moveToleranceDp: Float,
)

/** 灵敏度档位。越严格越难误触，但也越难触发。 */
enum class Sensitivity(val displayName: String, val params: GestureParams) {
    // 宽松档 multiTouchSlopMs=150ms，holdBaseReadyMs 必须 ≥ 150ms 才能避免与
    // 「N 指同时点击」的判定冲突，故取 150（而非按比例缩小后的 80）。
    LOOSE("宽松", GestureParams(500L, 150L, 350L, 150L, 40f)),
    STANDARD("标准", GestureParams(350L, 100L, 500L, 120L, 24f)),
    STRICT("严格", GestureParams(250L, 60L, 700L, 180L, 12f)),
}

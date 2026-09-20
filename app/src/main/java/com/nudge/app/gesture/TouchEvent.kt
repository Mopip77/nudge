package com.nudge.app.gesture

enum class TouchEventType { DOWN, MOVE, UP, CANCEL }

/**
 * 与 Android 解耦的触摸事件。
 *
 * 之所以不直接用 MotionEvent，是为了让 GestureRecognizer 能在 JVM 上单元测试——
 * MotionEvent 无法在纯 JVM 环境构造。
 *
 * @param pointerId 手指标识，同一根手指从按下到抬起保持不变
 * @param activePointerCount 本事件发生后屏幕上的手指总数
 */
data class TouchEvent(
    val type: TouchEventType,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val timeMs: Long,
    val activePointerCount: Int,
)

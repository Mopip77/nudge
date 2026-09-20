package com.nudge.app.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * 手势识别状态机。
 *
 * 纯 Kotlin 实现，不依赖任何 Android 类，可在 JVM 上单元测试。
 * 非线程安全，调用方需保证串行调用（UI 线程天然满足）。
 *
 * @param params 判定参数，来自 [Sensitivity]
 * @param densityDpi 屏幕密度，用于把 dp 容差换算为像素；测试中传 1f
 */
class GestureRecognizer(
    private val params: GestureParams,
    private val densityDpi: Float = 1f,
) {
    private val moveTolerancePx = params.moveToleranceDp * densityDpi

    /** 当前这一「批」触摸的状态。一批 = 从首指按下到全部抬起。 */
    private var batchStartMs = 0L
    private var batchPeakFingers = 0
    private var batchFirstDownMs = 0L
    private var batchInvalid = false
    private val downPositions = mutableMapOf<Int, Pair<Float, Float>>()

    /**
     * 上一批已完成的轻点，用于组成双击。
     *
     * [lastTapStartMs] 记录的是上一次点击「批次开始」的时刻（即那次的 batchStartMs），
     * 而不是抬起时刻——双击窗口判定的是两次点击起始时间的间隔，
     * 这样才能与「按住太久也算超时」的直觉一致：批次起点越晚，说明用户等待/操作越久。
     */
    private var lastTapFingers = 0
    private var lastTapStartMs = Long.MIN_VALUE

    fun reset() {
        batchStartMs = 0L
        batchPeakFingers = 0
        batchFirstDownMs = 0L
        batchInvalid = false
        downPositions.clear()
        lastTapFingers = 0
        lastTapStartMs = Long.MIN_VALUE
    }

    fun onTouchEvent(event: TouchEvent): Gesture? {
        return when (event.type) {
            TouchEventType.DOWN -> { onDown(event); null }
            TouchEventType.MOVE -> { onMove(event); null }
            TouchEventType.UP -> onUp(event)
            TouchEventType.CANCEL -> { reset(); null }
        }
    }

    private fun onDown(event: TouchEvent) {
        if (downPositions.isEmpty()) {
            batchStartMs = event.timeMs
            batchFirstDownMs = event.timeMs
            batchPeakFingers = 0
            batchInvalid = false
        }
        // 超出同时性窗口落下的手指，说明不是「同时按下」
        if (event.timeMs - batchFirstDownMs > params.multiTouchSlopMs) {
            batchInvalid = true
        }
        downPositions[event.pointerId] = event.x to event.y
        batchPeakFingers = max(batchPeakFingers, event.activePointerCount)
    }

    private fun onMove(event: TouchEvent) {
        val start = downPositions[event.pointerId] ?: return
        if (abs(event.x - start.first) > moveTolerancePx ||
            abs(event.y - start.second) > moveTolerancePx
        ) {
            batchInvalid = true
        }
    }

    private fun onUp(event: TouchEvent): Gesture? {
        downPositions.remove(event.pointerId)
        if (event.activePointerCount > 0) return null

        // 全部手指已抬起，这一批结束
        val fingers = batchPeakFingers
        val heldTooLong = event.timeMs - batchStartMs > params.longPressMs
        val valid = !batchInvalid && !heldTooLong && fingers in 1..3

        if (!valid) {
            lastTapFingers = 0
            lastTapStartMs = Long.MIN_VALUE
            return null
        }

        val withinWindow = batchStartMs - lastTapStartMs <= params.doubleTapWindowMs
        if (lastTapFingers == fingers && withinWindow) {
            lastTapFingers = 0
            lastTapStartMs = Long.MIN_VALUE
            return doubleTapFor(fingers)
        }

        lastTapFingers = fingers
        lastTapStartMs = batchStartMs
        return null
    }

    private fun doubleTapFor(fingers: Int): Gesture? = when (fingers) {
        1 -> Gesture.DOUBLE_TAP
        2 -> Gesture.TWO_FINGER_DOUBLE_TAP
        3 -> Gesture.THREE_FINGER_DOUBLE_TAP
        else -> null
    }
}

package com.nudge.app.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * 手势识别状态机。
 *
 * 纯 Kotlin 实现，不依赖任何 Android 类，可在 JVM 上单元测试。
 * 非线程安全，调用方需保证串行调用（UI 线程天然满足）。
 *
 * 识别两类手势：
 * 1. N 指双击——N 指同时按下抬起两次
 * 2. N 指长按 + 一指单击——N 指按住超过阈值后，额外一指 down-up
 *
 * @param params 判定参数，来自 [Sensitivity]
 * @param densityDpi 屏幕密度，用于把 dp 容差换算为像素；测试中传 1f
 */
class GestureRecognizer(
    private val params: GestureParams,
    private val densityDpi: Float,
) {
    private val moveTolerancePx = params.moveToleranceDp * densityDpi

    /** 当前这一「批」触摸的状态。一批 = 从首指按下到全部抬起。 */
    private var batchStartMs = 0L
    private var batchPeakFingers = 0
    private var batchFirstDownMs = 0L
    private var batchInvalid = false
    private val downPositions = mutableMapOf<Int, Pair<Float, Float>>()
    private val downTimes = mutableMapOf<Int, Long>()

    /**
     * 上一批已完成的轻点，用于组成双击。
     *
     * [lastTapEndMs] 记录的是上一次点击「全部手指抬起」的时刻，
     * 双击窗口从这一刻算到下一次按下——即「抬起→按下」的间隔，
     * 而不是两次按下时刻之差，这样窗口大小才不受第一次按住时长的影响。
     */
    private var lastTapFingers = 0
    private var lastTapEndMs = Long.MIN_VALUE

    /**
     * 长按 + 单击已触发过的时间，用于冷却期判定。
     *
     * 哨兵值不用 [Long.MIN_VALUE]：冷却判定要做 `event.timeMs - lastHoldTapFireMs`，
     * 若哨兵是 Long.MIN_VALUE，任何非负的 event.timeMs 减去它都会发生 Long 溢出，
     * 结果变成一个极大的负数，反而让「从未触发过」被误判为「仍在冷却期」。
     * 除以 2 后即使再减去一个正的时间戳也不会溢出，且仍远小于任何真实时间戳。
     */
    private var lastHoldTapFireMs = Long.MIN_VALUE / 2

    /** 本批中是否已经触发过长按+单击，用于避免收尾时误判为双击。 */
    private var batchProducedHoldTap = false

    fun reset() {
        batchStartMs = 0L
        batchPeakFingers = 0
        batchFirstDownMs = 0L
        batchInvalid = false
        batchProducedHoldTap = false
        downPositions.clear()
        downTimes.clear()
        lastTapFingers = 0
        lastTapEndMs = Long.MIN_VALUE
        lastHoldTapFireMs = Long.MIN_VALUE / 2
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
            batchProducedHoldTap = false
        }
        // 超出同时性窗口落下的手指，说明不是「同时按下」。
        // 但如果已构成长按底座，额外落下的手指是单击而非同时按下，不应据此判无效。
        if (event.timeMs - batchFirstDownMs > params.multiTouchSlopMs && !isHoldBaseReady(event.timeMs)) {
            batchInvalid = true
        }
        downPositions[event.pointerId] = event.x to event.y
        downTimes[event.pointerId] = event.timeMs
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
        val holdTap = tryHoldTap(event)
        if (holdTap != null) {
            downPositions.remove(event.pointerId)
            downTimes.remove(event.pointerId)
            return holdTap
        }

        downPositions.remove(event.pointerId)
        downTimes.remove(event.pointerId)
        if (event.activePointerCount > 0) return null

        // 全部手指已抬起，这一批结束
        val producedHoldTap = batchProducedHoldTap
        batchProducedHoldTap = false

        val fingers = batchPeakFingers
        val heldTooLong = event.timeMs - batchStartMs > params.longPressMs
        val valid = !batchInvalid && !heldTooLong && fingers in 1..3 && !producedHoldTap

        if (!valid) {
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return null
        }

        val withinWindow = batchStartMs - lastTapEndMs <= params.doubleTapWindowMs
        if (lastTapFingers == fingers && withinWindow) {
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return doubleTapFor(fingers)
        }

        lastTapFingers = fingers
        lastTapEndMs = event.timeMs
        return null
    }

    /**
     * 判断「长按底座」是否已就绪：当前按住的手指中，
     * 除本次抬起的这根以外，都已按住超过长按阈值。
     */
    private fun isHoldBaseReady(nowMs: Long, excludingPointerId: Int? = null): Boolean {
        val base = downTimes.filterKeys { it != excludingPointerId }
        if (base.size !in 2..3) return false
        return base.values.all { nowMs - it >= params.longPressMs }
    }

    /** 尝试把本次抬起识别为「长按 + 单击」。 */
    private fun tryHoldTap(event: TouchEvent): Gesture? {
        if (batchInvalid) return null
        val downAt = downTimes[event.pointerId] ?: return null

        // 这根手指本身必须是短促单击，而非长按底座的一部分
        if (event.timeMs - downAt > params.longPressMs) return null
        if (!isHoldBaseReady(downAt, excludingPointerId = event.pointerId)) return null

        // 冷却期抑制连击误触
        if (event.timeMs - lastHoldTapFireMs < COOLDOWN_MS) return null

        val baseFingers = downTimes.keys.count { it != event.pointerId }
        val gesture = when (baseFingers) {
            2 -> Gesture.TWO_FINGER_HOLD_TAP
            3 -> Gesture.THREE_FINGER_HOLD_TAP
            else -> return null
        }
        lastHoldTapFireMs = event.timeMs
        batchProducedHoldTap = true
        return gesture
    }

    private fun doubleTapFor(fingers: Int): Gesture? = when (fingers) {
        1 -> Gesture.DOUBLE_TAP
        2 -> Gesture.TWO_FINGER_DOUBLE_TAP
        3 -> Gesture.THREE_FINGER_DOUBLE_TAP
        else -> null
    }

    private companion object {
        /**
         * 长按+单击的冷却期，避免连击误触发。
         *
         * 双击类手势不需要显式冷却：触发后状态已重置，再次触发必须重新完成
         * 两次完整点击，本身就受 doubleTapWindow 约束。而长按+单击的底座手指
         * 始终按住，缺少这道闸门就会被抖动连续触发。
         */
        const val COOLDOWN_MS = 300L
    }
}

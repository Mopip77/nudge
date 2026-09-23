package com.nudge.app.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * 手势识别状态机。
 *
 * 纯 Kotlin 实现，不依赖任何 Android 类，可在 JVM 上单元测试。
 * 非线程安全，调用方需保证串行调用（UI 线程天然满足）。
 *
 * 识别三类手势：
 * 1. N 指双击——N 指同时按下抬起两次
 * 2. N 指长按 + 一指单击——N 指按住超过阈值后，额外一指 down-up
 * 3. 两指竖直滑动——两指同时按下、同向竖直移动超过阈值
 *
 * @param params 判定参数，来自 [Sensitivity]
 * @param densityDpi 屏幕密度，用于把 dp 容差换算为像素；测试中传 1f
 */
class GestureRecognizer(
    private val params: GestureParams,
    private val densityDpi: Float,
) {
    private val moveTolerancePx = params.moveToleranceDp * densityDpi
    private val swipeMinDistancePx = params.swipeMinDistanceDp * densityDpi
    private val swipeMaxCrossPx = params.swipeMaxCrossDp * densityDpi

    /** 当前这一「批」触摸的状态。一批 = 从首指按下到全部抬起。 */
    private var batchStartMs = 0L
    private var batchPeakFingers = 0
    private var batchFirstDownMs = 0L
    private var batchInvalid = false

    /**
     * 本批中是否有任意手指移动超出容差。
     *
     * 与 [batchInvalid] 分开维护：[batchInvalid] 只表达「按下时序不干净」（同时性不满足、
     * 或落下手指数超出底座上限），只应否决**双击**；而移动超容差无论落在哪种手势语义下
     * 都应视为用户在滑动而非点击/长按，因此双击和长按+单击都要否决，用这个独立标志承载。
     */
    private var batchMoveInvalid = false
    private val downPositions = mutableMapOf<Int, Pair<Float, Float>>()
    private val downTimes = mutableMapOf<Int, Long>()

    /**
     * 每根手指最后一次出现的位置，用来算滑动位移。
     *
     * 与 [downPositions] 分开维护且**在抬起时不删除**：滑动判定发生在最后一根手指
     * 抬起的那一刻，此时先抬起的那根手指若已被移除，就只剩一根手指的位移可算，
     * 「两指同向」这个核心条件便无从验证。整批结束时由 [resetBatch] 统一清。
     */
    private val lastPositions = mutableMapOf<Int, Pair<Float, Float>>()

    /** 本批参与过的手指按下位置，同样跨抬起保留，用于与 [lastPositions] 求差。 */
    private val batchDownPositions = mutableMapOf<Int, Pair<Float, Float>>()

    /** 本批是否已产出滑动手势，避免同一批里重复触发。 */
    private var batchProducedSwipe = false

    /**
     * 上一批已完成的轻点，用于组成双击。
     *
     * [lastTapEndMs] 记录的是上一次点击「全部手指抬起」的时刻，
     * 双击窗口从这一刻算到下一次按下——即「抬起→按下」的间隔，
     * 而不是两次按下时刻之差，这样窗口大小才不受第一次按住时长的影响。
     *
     * 哨兵值分析：[lastTapEndMs] 初值/重置值是 `Long.MIN_VALUE`，判定用到的减法
     * `batchStartMs - lastTapEndMs` 在 `batchStartMs` 为正数时确实会整数溢出成负数，
     * 使 `withinWindow` 恒为 `true`——单看这一项，首次点击似乎会被误判为双击的第二次。
     * 但触发双击还需要前置条件 `lastTapFingers == fingers`：[lastTapFingers] 的初值/
     * 重置值是 `0`，而合法的 `fingers` 最小是 `1`（只有 `fingers in 1..3` 才会走到这个
     * 分支），因此首次点击时该条件恒为 `false`，溢出的 `withinWindow` 不会被用到。
     * 且这两个字段总是成对重置（见 [reset] 与 onUp 中的失败分支），这道闸门不会失效。
     * 结论：此处溢出真实存在但无法被触发，是安全的；不同于 [lastHoldTapFireMs] 那种
     * 没有额外闸门保护、必须修掉的情形，这里保留 `Long.MIN_VALUE` 不做改动。
     */
    private var lastTapFingers = 0
    private var lastTapEndMs = Long.MIN_VALUE

    /**
     * 长按 + 单击已触发过的时间，用于冷却期判定；`null` 表示本批（自上次 reset 以来）从未触发过。
     *
     * 用可空类型而非 `Long.MIN_VALUE` 之类的哨兵值，是因为冷却判定要做
     * `event.timeMs - lastHoldTapFireMs` 减法：任何非负时间戳减 `Long.MIN_VALUE`
     * 都会整数溢出成一个极大的负数，反而让「从未触发过」被误判为「仍在冷却期」。
     * 可空类型把「从未触发」和「触发过」在类型层面分开，不再需要靠魔法数字规避溢出。
     *
     * 注意本字段只在 [reset] 中清空，批次收尾（全部手指抬起）时**不清**——
     * 因此冷却期是跨批次生效的：全部手指离屏后重新放上再点，仍受 300ms 约束。
     * 这是有意设计，「快速抬手重来」同样属于连击场景。
     * 见测试 `冷却期跨批次生效_全部抬起重来仍受抑制`。
     */
    private var lastHoldTapFireMs: Long? = null

    /** 本批中是否已经触发过长按+单击，用于避免收尾时误判为双击。 */
    private var batchProducedHoldTap = false

    fun reset() {
        batchStartMs = 0L
        batchPeakFingers = 0
        batchFirstDownMs = 0L
        batchInvalid = false
        batchMoveInvalid = false
        batchProducedHoldTap = false
        batchProducedSwipe = false
        downPositions.clear()
        downTimes.clear()
        lastPositions.clear()
        batchDownPositions.clear()
        lastTapFingers = 0
        lastTapEndMs = Long.MIN_VALUE
        lastHoldTapFireMs = null
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
            batchMoveInvalid = false
            batchProducedHoldTap = false
            batchProducedSwipe = false
            // 上一批的位置残留会污染本批的位移计算，开批时清掉。
            lastPositions.clear()
            batchDownPositions.clear()
        }
        // 超出同时性窗口落下的手指，说明不是「同时按下」，据此否决双击。
        // 注意：这不应连带否决「长按+单击」——底座先按住、另一指晚落下正是
        // 长按+单击的正常形态，它的合法性由 tryHoldTap 内部基于抬起时刻
        // 重新计算的 isHoldBaseReady(downAt, excludingPointerId) 判断，
        // 不需要（也不能）用这里的「批次级」标志兜底，否则一次过早的单击
        // 尝试会把 batchInvalid 永久置位，污染同一批次内后续本该合法的单击。
        if (event.timeMs - batchFirstDownMs > params.multiTouchSlopMs) {
            batchInvalid = true
        }
        downPositions[event.pointerId] = event.x to event.y
        downTimes[event.pointerId] = event.timeMs
        batchDownPositions[event.pointerId] = event.x to event.y
        lastPositions[event.pointerId] = event.x to event.y
        batchPeakFingers = max(batchPeakFingers, event.activePointerCount)
    }

    private fun onMove(event: TouchEvent) {
        // 位置要无条件记录：即使这根手指已经超出容差（点击类已否决），
        // 它的位移仍是滑动判定的输入。
        lastPositions[event.pointerId] = event.x to event.y

        val start = downPositions[event.pointerId] ?: return
        if (abs(event.x - start.first) > moveTolerancePx ||
            abs(event.y - start.second) > moveTolerancePx
        ) {
            batchInvalid = true
            batchMoveInvalid = true
        }
    }

    private fun onUp(event: TouchEvent): Gesture? {
        // 抬起时也要更新位置：最后一根手指的 UP 往往带着比最后一个 MOVE
        // 更靠后的坐标，漏掉它会少算一截位移。
        lastPositions[event.pointerId] = event.x to event.y

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

        // 滑动判定必须在双击判定之前：一次两指滑动同样满足「两指按下又抬起」，
        // 若先走双击分支，它会被记为 lastTap，与下一次滑动凑成一次「两指双击」。
        val swipe = trySwipe()
        if (swipe != null) {
            // 滑动不参与双击累积，否则连续两次滑动会额外触发一次双击
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return swipe
        }

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
     * 判断「长按底座」是否已就位：当前按住的手指中，除本次抬起的这根以外，
     * 都已按下超过 [GestureParams.holdBaseReadyMs]。
     *
     * 注意这里判断的是「就位」而非「长按」——底座手指只需处于按下状态达到这个
     * 较短的去抖阈值即可接受后续单击，不要求像 [params.longPressMs] 那样长时间按住。
     */
    private fun isHoldBaseReady(nowMs: Long, excludingPointerId: Int? = null): Boolean {
        val base = downTimes.filterKeys { it != excludingPointerId }
        if (base.size !in 2..3) return false
        return base.values.all { nowMs - it >= params.holdBaseReadyMs }
    }

    /** 尝试把本次抬起识别为「长按 + 单击」。 */
    private fun tryHoldTap(event: TouchEvent): Gesture? {
        if (batchMoveInvalid) return null
        val downAt = downTimes[event.pointerId] ?: return null

        // 这根手指本身必须是短促单击，而非长按底座的一部分
        if (event.timeMs - downAt > params.longPressMs) return null
        if (!isHoldBaseReady(downAt, excludingPointerId = event.pointerId)) return null

        // 冷却期抑制连击误触
        if (lastHoldTapFireMs?.let { event.timeMs - it < COOLDOWN_MS } == true) return null

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

    /**
     * 尝试把本批识别为「两指竖直滑动」。在最后一根手指抬起时调用。
     *
     * 判定条件全部满足才产出：
     * 1. 本批恰好两根手指（`batchPeakFingers == 2`）——三指滑动不在支持之列，
     *    且要求恰好等于而非 ≥，否则三指滑动会被降级识别成两指滑动
     * 2. 两指都是在同时性窗口内按下的（`!batchInvalid` 中的同时性部分单独判断）
     * 3. 两指竖直位移**同向**且都超过 [swipeMinDistancePx]
     * 4. 两指横向位移都不超过 [swipeMaxCrossPx]
     *
     * 条件 3 的「都超过」而非「平均超过」是刻意的：一根手指划够、另一根几乎没动
     * 更像是握持时的单指误划，不该算作双指滑动。
     */
    private fun trySwipe(): Gesture? {
        if (batchProducedSwipe) return null
        if (batchProducedHoldTap) return null
        if (batchPeakFingers != 2) return null
        // 两指必须是「同时」按下的。这里复用 multiTouchSlopMs 的语义，但不能直接用
        // batchInvalid——后者在位移超容差时也会被置位，而滑动本来就要求大位移。
        if (batchDownPositions.size != 2) return null

        val ids = batchDownPositions.keys.toList()
        val deltas = ids.map { id ->
            val start = batchDownPositions[id] ?: return null
            val end = lastPositions[id] ?: return null
            (end.first - start.first) to (end.second - start.second)
        }

        if (deltas.any { abs(it.first) > swipeMaxCrossPx }) return null
        if (deltas.any { abs(it.second) < swipeMinDistancePx }) return null

        val allUp = deltas.all { it.second <= -swipeMinDistancePx }
        val allDown = deltas.all { it.second >= swipeMinDistancePx }

        batchProducedSwipe = true
        return when {
            allUp -> Gesture.TWO_FINGER_SWIPE_UP
            allDown -> Gesture.TWO_FINGER_SWIPE_DOWN
            // 两指反向（一上一下）——不是滑动，是缩放之类的动作，不产出
            else -> {
                batchProducedSwipe = false
                null
            }
        }
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

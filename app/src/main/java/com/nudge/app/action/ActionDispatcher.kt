package com.nudge.app.action

import android.content.Context
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.media.ActionResult
import com.nudge.app.media.MediaCommand
import com.nudge.app.media.MediaControlRepository

/**
 * 把识别到的手势映射为动作并执行，附带震动反馈。
 *
 * 盲操场景下用户看不到屏幕，震动是确认操作结果的唯一渠道，
 * 因此不同结果必须有可区分的震动模式。
 */
class ActionDispatcher(
    context: Context,
    private val repository: MediaControlRepository,
) {
    private val haptics = HapticPlayer(context)

    /** 执行手势对应的动作。手势未绑定任何动作时返回 null。 */
    fun dispatch(gesture: Gesture, config: NudgeConfig): ActionResult? {
        val action = config.gestureToAction(gesture) ?: return null
        return dispatch(action)
    }

    /** 外部广播和手势共用动作及反馈，不受手势绑定配置影响。 */
    fun dispatch(action: ActionType): ActionResult {
        return dispatch(when (action) {
            ActionType.NEXT_TRACK -> MediaCommand.NEXT
            ActionType.LIKE -> MediaCommand.LIKE
            // toggle 语义：盲操下用户听得见当前状态，不需要区分 PLAY / PAUSE
            ActionType.PLAY_PAUSE -> MediaCommand.PLAY_PAUSE
        })
    }

    fun dispatch(command: MediaCommand): ActionResult {
        val result = repository.execute(command)
        vibrateFor(result)
        return result
    }

    /**
     * 按结果选一条**形状**不同的波形，而不是只改时长。
     *
     * 早先五种反馈全是单震或近似单震，只有时长差别（50/30-80-30/20/200ms），
     * 盲操下几乎分不出来——尤其切歌与已收藏，除了长短没有任何别的差异，
     * 而长短在没有对照时不可辨。
     *
     * 现在每种占一个节奏形状（见 [HapticPalette]），差异是类别而非程度。
     * 波形经 [HapticOverride] 取，debug 包里实验室调的值能直接作用到
     * 真实手势反馈上——只在实验室里能听到的话，调出来的参数没法在
     * 真实使用节奏下验证。
     */
    private fun vibrateFor(result: ActionResult) {
        haptics.play(HapticOverride.specFor(HapticPalette.idFor(result)))
    }
}

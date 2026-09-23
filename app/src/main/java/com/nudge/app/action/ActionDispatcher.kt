package com.nudge.app.action

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

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

    private fun vibrateFor(result: ActionResult) {
        val effect = when (result) {
            ActionResult.Skipped, ActionResult.PlaybackCommandSent ->
                VibrationEffect.createOneShot(50, DEFAULT_AMPLITUDE)
            ActionResult.Liked -> VibrationEffect.createWaveform(
                longArrayOf(0, 30, 80, 30), -1
            )
            ActionResult.AlreadyLiked -> VibrationEffect.createOneShot(20, DEFAULT_AMPLITUDE)
            ActionResult.NoSession, is ActionResult.Failed ->
                VibrationEffect.createOneShot(200, DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = VibrationEffect.DEFAULT_AMPLITUDE
    }
}

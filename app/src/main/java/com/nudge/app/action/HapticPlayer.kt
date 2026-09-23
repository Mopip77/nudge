package com.nudge.app.action

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 把 [HapticSpec] 渲染出来的包络交给系统振动器。
 *
 * 独立于 [ActionDispatcher]：实验室要能在不触发任何媒体操作的前提下
 * 单独试听波形，两者若揉在一起，试听就得伪造一个 `ActionResult`，
 * 而那会让「试听」和「真的执行了动作」在代码上难以区分。
 */
class HapticPlayer(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** 播放一条波形。会打断正在进行的振动——后来的反馈总是更相关。 */
    fun play(spec: HapticSpec) {
        val waveform = spec.render()
        if (waveform.timings.isEmpty()) return

        val effect = VibrationEffect.createWaveform(waveform.timings, waveform.amplitudes, -1)

        // 用 USAGE_TOUCH 之外的 usage，是为了拿到更高的振幅上限。
        //
        // 真机取证（SM-G9810 / One UI 5.1，`dumpsys vibrator_manager`）：
        // `mVibrationIntensities` 里 TOUCH=(MEDIUM_LOW) 而 MEDIA=(HIGH)，
        // 系统按 usage 对振幅做缩放。「迸发」最需要的就是上限，
        // 挂 TOUCH 等于自己把天花板压低一档。
        //
        // 取 MEDIA 而非 NOTIFICATION（后者同为 HIGH）：这是用户主动操作的
        // 即时反馈，不是通知；挂 NOTIFICATION 在某些 ROM 上会受
        // 勿扰模式影响而被整个静音，那会让盲操下唯一的反馈渠道消失。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, MEDIA_ATTRIBUTES)
        } else {
            vibrator.vibrate(effect)
        }
    }

    /** 立刻停止。实验室里连点试听时用，免得两条波形叠在一起分不清。 */
    fun cancel() {
        vibrator.cancel()
    }

    private companion object {
        @get:androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
        val MEDIA_ATTRIBUTES: VibrationAttributes
            get() = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_MEDIA)
                .build()
    }
}

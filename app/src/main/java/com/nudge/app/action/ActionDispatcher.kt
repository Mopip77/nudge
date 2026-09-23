package com.nudge.app.action

import android.content.Context
import com.nudge.app.config.ActionType
import com.nudge.app.config.ConfigStore
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.media.ActionResult
import com.nudge.app.media.MediaCommand
import com.nudge.app.media.MediaControlRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 把识别到的手势映射为动作并执行，附带震动反馈。
 *
 * 盲操场景下用户看不到屏幕，震动是确认操作结果的唯一渠道，
 * 因此不同结果必须有可区分的震动模式。
 */
class ActionDispatcher(
    private val context: Context,
    private val repository: MediaControlRepository,
) {
    private val haptics = HapticPlayer(context)

    /**
     * 歌词开关要读改写 DataStore，所以 dispatcher 需要它。
     *
     * 懒初始化而不是构造参数：两个调用方之一是 [MediaCommandReceiver]，
     * 它每收一次广播就新建一个 dispatcher，而绝大多数命令根本不碰配置。
     */
    private val configStore: ConfigStore by lazy { ConfigStore(context) }

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
            ActionType.TOGGLE_LYRICS -> MediaCommand.TOGGLE_LYRICS
        })
    }

    fun dispatch(command: MediaCommand): ActionResult {
        // 歌词开关不经播放器，走独立分支：repository.execute 的整条链路
        // （找会话、发命令、读回状态）对它毫无意义，硬塞进去只会让
        // MediaControlRepository 多一个它管不着的职责。
        val result = if (command == MediaCommand.TOGGLE_LYRICS) {
            toggleLyrics()
        } else {
            repository.execute(command)
        }
        vibrateFor(result)
        return result
    }

    /**
     * 读当前值取反再写回，返回切换**之后**的状态。
     *
     * 用 `runBlocking` 而非异步协程：调用方之一是 [MediaCommandReceiver]，
     * `onReceive` 返回后进程可能立即被回收，异步写 DataStore 会来不及执行完
     * （与 `ProfileCommandReceiver` 同一条理由）。手势路径上这里本就在
     * IO 线程（见 `MainActivity` 的 `Dispatchers.IO`），阻塞几毫秒无妨。
     *
     * 先读后写在这里是**安全的**，与收藏那条 toggle 缺陷不同：
     * 读的是本应用自己的 DataStore，不存在「外部异步更新导致读到旧值」
     * 的窗口；而收藏读的是播放器回推的 metadata。
     */
    private fun toggleLyrics(): ActionResult = runBlocking {
        val next = !configStore.config.first().lyricsEnabled
        configStore.setLyricsEnabled(next)
        ActionResult.LyricsToggled(next)
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

package com.nudge.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nudge.app.BuildConfig

/**
 * 歌词动画实验室调出来的参数，**进程内共享、不落盘**。
 *
 * 存在的理由：实验室的预览框只有 340dp 高，能看到的行数远少于真实全屏，
 * 而梯度的观感恰恰与可见行数强相关——在预览框里调好的曲线，铺到整屏
 * 十几行上未必还是那个味道。有了这个共享位，改完参数返回主界面就能
 * 在真实歌词、真实尺寸、真实换行节奏下判断，而不必每试一组都重新编译。
 *
 * **只在 debug 包里生效**：[current] 在 release 下恒返回 [LyricsAnimSpec.DEFAULT]，
 * 与实验室入口本身被 `BuildConfig.DEBUG` 挡掉是同一个口径。
 *
 * 刻意**不持久化**，杀进程即回默认：
 *
 * - 歌词动画的好坏没有「因人而异」的成分，做成用户可存的配置只会让线上
 *   出现一堆没人能复现的观感问题（与灵敏度只在 debug 可调是同一条理由）。
 * - 不落盘就不存在「用户留下一个自己看不到、也改不回的状态」，
 *   不必像 `sensitivity` 那样再为 release 侧写一套忽略存量值的逻辑。
 *
 * 所以这仍然是**开发期的取景器**：调好之后要把值手抄回 [LyricsAnimSpec.DEFAULT]
 * （实验室里的「打印当前参数」会生成可直接粘贴的构造调用），
 * 否则重启一次就没了。
 */
object LyricsAnimOverride {

    /**
     * 用 Compose 的 `mutableStateOf` 而不是普通 `var`：读它的是
     * `TrackpadScreen` 里的 composable，普通字段改了不会触发重组，
     * 返回主界面后要等下一次换行才偶然生效，调参时会误判成「没生效」。
     */
    private var override by mutableStateOf<LyricsAnimSpec?>(null)

    /**
     * 当前应当生效的参数。release 恒为默认值。
     *
     * 必须在 composable 里调用（它会读 Compose 状态并建立订阅）。
     */
    val current: LyricsAnimSpec
        @Composable
        get() = if (BuildConfig.DEBUG) {
            override ?: LyricsAnimSpec.DEFAULT
        } else {
            LyricsAnimSpec.DEFAULT
        }

    /**
     * 当前是否正在用实验室调出来的参数（而非代码里的默认值）。
     *
     * 主界面据此显示一个角标。没有这个提示的话，「刚才那下观感变化到底是
     * 参数生效了，还是这首歌本来就长这样」分不清——而调参全靠肉眼比对，
     * 这个不确定性会让整个取景器不可信。release 恒 false，角标不存在。
     */
    val isActive: Boolean
        @Composable
        get() = BuildConfig.DEBUG && override != null

    /** 实验室里每次改动都写回这里，主界面随之重组。 */
    fun set(spec: LyricsAnimSpec) {
        override = spec
    }

    /**
     * 清掉覆盖，回到代码里的默认值。实验室的「恢复默认」用。
     *
     * 与 `set(LyricsAnimSpec.DEFAULT)` 渲染结果一样，但**角标状态不同**：
     * 前者让 [isActive] 变回 false、角标消失，后者角标仍亮着。
     * 既然角标的用途就是回答「现在跑的是不是实验室参数」，
     * 这两种状态就必须分开。
     */
    fun clear() {
        override = null
    }

    /**
     * 实验室重新进入时读回上次调的值，否则每次进去都从默认重来、
     * 刚调好的一组白丢。
     *
     * 返回 null 表示从未调过（或刚 [clear] 过）——此时实验室起手取
     * [LyricsAnimSpec.DEFAULT]。
     */
    fun peek(): LyricsAnimSpec? = override
}

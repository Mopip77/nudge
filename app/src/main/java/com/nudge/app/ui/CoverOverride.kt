package com.nudge.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nudge.app.BuildConfig
import com.nudge.app.media.CoverAspect
import com.nudge.app.media.DEFAULT_COVER_ASPECT

/**
 * 封面实验室调出来的请求比例，**进程内共享、不落盘**。
 *
 * 形状与理由全同 [LyricsAnimOverride]：这是开发期的取景器，
 * 调好之后要把值抄回 [DEFAULT_COVER_ASPECT]，重启一次就没了。
 *
 * 为什么比例值得单独开一个取景器：网易云的 `?param=WxH` 做的是**居中裁切**，
 * 所以非方比例会把封面的上下裁掉一部分。裁多少好看没法推理——
 * 4:5 可能正好去掉留白，9:16 可能把人脸裁没，而这取决于封面本身的构图。
 * 只能逐档切着看。
 *
 * **只在 debug 包里生效**：[current] 在 release 下恒返回默认比例。
 */
object CoverOverride {

    /**
     * 同 [LyricsAnimOverride]：用 `mutableStateOf` 而非普通 `var`，
     * 否则改了不触发重组，返回主界面要等切歌才偶然生效。
     */
    private var override by mutableStateOf<CoverAspect?>(null)

    /** 当前应当生效的比例。release 恒为默认值。必须在 composable 里调。 */
    val current: CoverAspect
        @Composable
        get() = if (BuildConfig.DEBUG) override ?: DEFAULT_COVER_ASPECT else DEFAULT_COVER_ASPECT

    /**
     * 供**非 composable** 的调用方读取（`MainActivity` 里拉封面的那段
     * 是普通协程代码，不在组合里）。
     *
     * 与 [current] 的区别只是不建立重组订阅——重组由读 [current] 的
     * 那一侧负责触发。
     */
    fun currentValue(): CoverAspect =
        if (BuildConfig.DEBUG) override ?: DEFAULT_COVER_ASPECT else DEFAULT_COVER_ASPECT

    /**
     * 当前是否正在用实验室调出来的比例。
     *
     * **复用主界面那个 LAB 角标**，不新增一个：角标要回答的是
     * 「现在跑的是不是实验室参数」，这个问题对三个实验室是同一个。
     */
    val isActive: Boolean
        @Composable
        get() = BuildConfig.DEBUG && override != null

    fun set(aspect: CoverAspect) {
        override = aspect
    }

    /**
     * 清掉覆盖，回到代码里的默认比例。
     *
     * 与 `set(DEFAULT_COVER_ASPECT)` 渲染结果一样但**角标状态不同**，
     * 所以两者必须分开（同 [LyricsAnimOverride.clear]）。
     */
    fun clear() {
        override = null
    }

    /** 实验室重新进入时读回上次调的值。null 表示从未调过（或刚 [clear] 过）。 */
    fun peek(): CoverAspect? = override
}

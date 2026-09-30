package com.nudge.app.overlay

/** 判定气泡显隐所需的全部外部状态。 */
data class BubbleInputs(
    /** 功能开关。 */
    val enabled: Boolean,
    /**
     * 当前前台包名。**null 表示「查不出来」而非「没有」**——
     * 见 [ForegroundAppResolver.resolve] 的注释。
     */
    val foregroundPackage: String?,
    val screenOn: Boolean,
    /** 上一次的判定结果。前台未知时沿用它。 */
    val previouslyVisible: Boolean,
)

/**
 * 气泡该不该显示。纯函数，无 Android 依赖——这套时序（熄屏、前台未知、
 * 切到 nudge 自己）在真机上极难复现，只能靠单测覆盖，口径同 `WritePolicy`。
 */
object BubblePolicy {

    /**
     * 会显示气泡的前台应用。
     *
     * 只有网易云：本应用的收藏、歌词、高清封面全部只对网易云成立
     * （见 CLAUDE.md「媒体控制的适用范围」），对别的播放器弹一个
     * 跳转按钮，点进去多半是个空壳界面。
     *
     * 做成集合而非单个常量，是为了将来加播放器时只改这里。
     */
    val TARGET_PACKAGES: Set<String> = setOf("com.netease.cloudmusic")

    /** nudge 自己。在自己界面上再挂一个「打开 nudge」是无意义的。 */
    const val SELF_PACKAGE = "com.nudge.app"

    fun visible(inputs: BubbleInputs): Boolean {
        if (!inputs.enabled) return false
        // 熄屏时藏起来：看不见的气泡没有价值，而 WindowManager 的视图
        // 即便不可见也会参与合成。口径同锁屏壁纸的「熄屏不写」。
        if (!inputs.screenOn) return false

        val fg = inputs.foregroundPackage
        // 前台未知（查询窗口内没有 RESUMED 事件，即用户一直没切应用）时
        // **保持现状**。改成「未知即隐藏」会让气泡在用户安静看着网易云
        // 几分钟后自己消失——那正是最该显示的时候。
            ?: return inputs.previouslyVisible

        if (fg == SELF_PACKAGE) return false
        return fg in TARGET_PACKAGES
    }
}

package com.nudge.app.overlay

/** 气泡贴在哪一侧。拖动松手后吸附到最近的一边。 */
enum class BubbleEdge(val displayName: String) {
    LEFT("左侧"),
    RIGHT("右侧"),
}

/**
 * 快速跳转气泡的配置。
 *
 * **独立于 [com.nudge.app.config.NudgeConfig]、不进 `ProfileCodec`**：
 * 这是「在别的应用上方显示」这件事的设备级设定，与手势绑定那套无关，
 * 切预设不该把它改掉。理由同 `LockWallpaperConfig`，也顺带避开了
 * 「加配置项要同时改五处」那条连锁。
 */
data class OverlayConfig(
    val enabled: Boolean,
    val edge: BubbleEdge,
    /** 竖向位置，占屏幕高度的比例。 */
    val yRatio: Float,
    val sizeDp: Int,
    /** 不透明度。默认不到 1，免得完全盖住网易云的界面元素。 */
    val alpha: Float,
) {
    companion object {
        val DEFAULT = OverlayConfig(
            // 默认关：这个功能要两项特殊权限，且会在别的应用上方画东西——
            // 默认开等于替用户做了一个他没同意的决定。
            enabled = false,
            edge = BubbleEdge.RIGHT,
            // 0.18 大致是网易云顶部搜索栏之下、歌单封面之上的空白带，
            // 真机上量的：这一条横向没有可点的控件，气泡不挡功能。
            yRatio = 0.18f,
            sizeDp = 48,
            alpha = 0.75f,
        )

        val Y_RATIO_RANGE = 0.02f..0.94f
        val SIZE_RANGE = 36..72
        val ALPHA_RANGE = 0.3f..1f
    }
}

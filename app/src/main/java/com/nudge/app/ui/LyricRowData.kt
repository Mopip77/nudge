package com.nudge.app.ui

/**
 * 歌词里的**一行**要展示的全部内容。
 *
 * 抽出这层是为了让「一行」不再等同于「一段文字」：译文之后还可能有
 * 罗马音（网易云的 `romalrc`）、逐字卡拉OK（`klyric`）等同属一行的内容。
 * 渲染与动画都以 Row 为单位，新增内容只需往这里加字段、
 * 在 [nominalLines] 里计入高度，不必再动一次动画逻辑。
 *
 * **Row 是动画的原子单位**：原文与译文共享同一个 alpha / blur 值和
 * 同一条缓动曲线，作为整体淡入淡出。对齐 Apple Music——译文在视觉上是
 * 这一句的一部分，不是一个跟它抢焦点的独立行。这也让
 * [LyricsAnimSpec] 的梯度函数（按行索引）完全不必改动。
 *
 * 纯 Kotlin，不依赖 Android：[nominalLines] 参与锚点计算，要能 JVM 单测。
 */
data class LyricRowData(
    /** 原文，恒非空。 */
    val text: String,
    /** 中文译文，null 表示这一行没有译文（常态，见 `LrcParser.parseWithTranslation`）。 */
    val translation: String? = null,
) {
    /**
     * 这一行占**几个标称行高**，用于锚点累加。
     *
     * 关键在于它只取决于**数据本身**（有没有译文），组合期就能算出来，
     * 不依赖任何测量结果——这正是它能用来定位锚点、而折行不能的原因。
     * 折行高度取决于句子长短与屏幕宽度，是不可预测的，所以像现在一样
     * 被排除在锚点之外（只影响行与行之间的间距，不影响焦点位置）。
     */
    val nominalLines: Int get() = if (translation != null) 2 else 1
}

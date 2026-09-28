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
     * 这一行的**标称高度**（dp），用于锚点累加。
     *
     * 关键在于它只取决于**数据本身**（有没有译文），组合期就能算出来，
     * 不依赖任何测量结果——这正是它能用来定位锚点、而折行不能的原因。
     * 折行高度取决于句子长短与屏幕宽度，是不可预测的，所以像现在一样
     * 被排除在锚点之外（只影响行与行之间的间距，不影响焦点位置）。
     *
     * **不能写成「几个 LINE_HEIGHT」**：带译文的行不是整两倍高，
     * 而是 原文行高 + 间距 + 译文行高 ≈ 1.6 倍。按 2 倍算会让锚点目标
     * 每带译文一行就高估约半个行高，真机表现为**换行时整列先往下跳一帧
     * 再被动画拖回来**——逐帧量到的就是 82~84px 的单帧跳变
     * （行高 136px 的 60%，而标准是不超过 10%）。
     *
     * 这个缺陷肉眼几乎看不出来（跳变只持续一帧，紧接着就是正常的
     * 400ms 平滑位移），只有逐帧互相关才量得出来。
     */
    fun nominalHeightDp(
        lineHeightDp: Float = LINE_HEIGHT_DP,
        translationGapDp: Float = TRANSLATION_GAP_DP,
        translationLineDp: Float = TRANSLATION_LINE_DP,
    ): Float = if (translation != null) {
        lineHeightDp + translationGapDp + translationLineDp
    } else {
        lineHeightDp
    }

    /** 标称高度换算成像素。[density] 取 `LocalDensity.current.density`。 */
    fun nominalHeightPx(density: Float): Float = nominalHeightDp() * density

    companion object {
        /**
         * 这三个常量必须与 `LyricsOverlay` 里的渲染值保持一致——它们描述的是
         * 同一套排版。分开写是因为锚点计算要在**纯 Kotlin** 侧可单测
         * （`Dp` 是 Android 类型），与 `LyricsAnimSpec` 用 Float 表示 dp 同理。
         *
         * 58 = 字号 27sp × 行高倍率 1.3（35.1）+ 排版余量，与早先
         * 24sp / 52dp 的比例一致。**字号一改这里必须跟着改**，
         * 否则大字被上下行挤掉。
         *
         * 连带影响：锚点按标称高度累加，行高涨 6dp 会让焦点下沉
         * 3×6=18dp（约三分之一行）。这是已知且接受的——补偿它需要
         * 一个 dp 级的偏移量参数，而 [anchorRow] 是整数行号，
         * 提一行（58dp）又会补过头。
         */
        const val LINE_HEIGHT_DP = 58f

        /** 原文与译文之间的间距。 */
        const val TRANSLATION_GAP_DP = 4f

        /**
         * 译文占的高度：字号 17sp × 行高倍率 1.3，再加上下的排版余量。
         *
         * 取 26 而不是精确的 22.1：译文行的实际占位还含 Text 自身的
         * 字体度量余量，实测按 26 算时锚点与真实排版最接近。
         * 这个值**偏小比偏大好**——偏大会让整列往下跳（就是当初按
         * 2 倍行高算的那个缺陷），偏小只是让锚点略微上移，观感无损。
         */
        const val TRANSLATION_LINE_DP = 26f
    }
}

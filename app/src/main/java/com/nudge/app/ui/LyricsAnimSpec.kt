package com.nudge.app.ui

/**
 * 歌词滚动动画的全部可调参数。
 *
 * 抽成数据类而不是散在 [LyricsOverlay] 里的常量，是为了让 debug 包的
 * 「歌词动画实验室」能在运行时换一整套参数看效果——这些值的好坏只能靠
 * 连着对比试出来，改常数再重编译一轮要一分多钟，试不了几组就没耐心了。
 *
 * **纯 Kotlin，不依赖任何 Android 类**（长度一律用 Float 表示 dp 数值而非
 * `Dp`），与 `GestureRecognizer` 同理：参数之间有若干必须维持的不变式
 * （见各字段注释），要能在 JVM 上单测。
 *
 * release 包里只有 [DEFAULT] 会被用到，实验室调出来的值最终要手写回这里。
 * 刻意不做持久化：这是开发期的取景器，不是给用户的配置项——歌词动画的
 * 好坏没有「因人而异」的成分，把它做成用户可调只会让线上出现一堆没人
 * 能复现的观感问题。
 */
data class LyricsAnimSpec(
    /**
     * 当前行固定在屏幕上的第几行（0 基，0 表示贴着容器顶边）。
     *
     * 早先是恒定居中（容器高度的一半）。改为固定行号是因为居中不符合
     * 阅读的实际重心：人读歌词时注意力在「下一句是什么」，正下方的预读区
     * 比正上方已唱过的行重要得多，把焦点压到偏上的位置才能让预读区更大。
     *
     * 取 2（即视觉上的第 3 行）：上方留两行已唱过的做上下文，再少就会
     * 出现「刚唱完的那句立刻滑出视野」，换行时缺少来处、显得突兀。
     */
    val anchorRow: Int = 2,

    /**
     * 弹簧刚度的梯度端点：屏幕**顶部**最软、**底部**最硬。
     *
     * 这是这版与前一版最本质的差别。前一版用 `abs(index - current)` 取
     * 无向距离，梯度以当前行为中心向两侧对称扩散——结果是当前行上方那些
     * 已经快滑出视野的行，和下方刚进场的行拿到同样的刚度，「拖尾」在上下
     * 两个方向同时出现。但列表整体只往一个方向（上）走，物理上被拖拽的
     * 只能是**后面的**行，最上面那行反倒最先到位，这就是用户说的
     * 「最上面那条歌词往上弹也有动画，逻辑不对」。
     *
     * 改成沿屏幕位置单调递增后，整列是一条连续的阻尼梯度：顶部的行最软，
     * 被下面的行「拖着」慢慢挪出视野；底部的行最硬，干脆利落地顶上来。
     *
     * 梯度基准用**目标位置**（动画结束后这行落在第几行）而不是实时位置：
     * 后者会让刚度随自身动画状态变化，构成非线性反馈，容易自激振荡。
     * 目标位置是静态量，每次换行只重算一次。
     */
    val stiffnessTop: Float = 40f,
    val stiffnessBottom: Float = 260f,

    /**
     * 阻尼比的梯度端点，方向与刚度一致：顶部低（有回弹），底部高（不过冲）。
     *
     * 底部取 1f（临界阻尼）：新进场的行来回晃会很廉价，且它正是要被读的
     * 下一句，晃动直接妨碍阅读。顶部 0.58f 有一次可见回弹，即用户要的
     * 「阻尼拖拉」。低于 0.5 会晃两下以上，看着像故障而不是物理感。
     */
    val dampingTop: Float = 0.58f,
    val dampingBottom: Float = 1f,

    /**
     * 刚度／阻尼梯度铺开的行数，从 [anchorRow] 往下数。
     *
     * 超出这个跨度的行统一取 [stiffnessBottom]／[dampingBottom]——
     * 否则窗口底部那些离得很远的行会硬到与紧邻当前行的没有区别，
     * 梯度全被压在最上面几行，看着又回到「整体平移」。
     *
     * 往上则一直插值到 0 行（屏幕顶），跨度由 [anchorRow] 决定，
     * 所以上方那两行之间的刚度差比下方大——这是想要的：
     * 视野边缘的拖尾要明显，正在读的那几行要稳。
     */
    val gradientRampLines: Float = 6f,

    /**
     * 当前行位移落定的目标时长（毫秒），仅用于实验室里显示估算值。
     *
     * 弹簧没有显式时长，这个字段不参与计算，只是把「刚度调到多少大概
     * 多久落定」这件事摆在界面上，免得调参时只能盯着一个没有量纲的数字。
     */
    val nominalSettleMs: Int = 450,

    /** 最远处行的模糊半径（dp）。峰值要守住「最远处仍认得出字」。 */
    val maxBlurDp: Float = 9f,

    /**
     * 模糊达到 [maxBlurDp] 所需的距离（行）。
     *
     * 与 [maxBlurDp] **配着调**：决定观感的是曲线斜率而不只是峰值。
     * 只抬峰值不拉跨度，紧邻当前行的一两行会跟着糊掉，「预读下一句」就没了。
     */
    val blurRampLines: Float = 10f,

    /**
     * 模糊／透明度在当前行**上方**的跨度倍率。
     *
     * 1f 表示上下对称，即与前一版一致。小于 1 会让上方的行更快糊掉、
     * 淡出得更狠——考虑到焦点已压到第 3 行、上方只剩两行已唱过的内容，
     * 理论上可以更激进，但默认先保持对称：位移的梯度这次已经改成有向的，
     * 再同时把清晰度也改成有向，出了问题分不清是哪个变量在起作用。
     * 留给实验室去试。
     */
    val upperFadeScale: Float = 1f,

    /** 非当前行的起始透明度，随距离线性衰减到 [alphaFar]。 */
    val alphaNear: Float = 0.55f,
    val alphaFar: Float = 0.3f,

    /** 透明度每远一行衰减的量。 */
    val alphaStep: Float = 0.035f,

    /**
     * 淡入淡出（透明度、模糊）的过渡时长。
     *
     * 要**短于**位移落定的时间：等长时换行途中新的当前行会「边移动边对焦」，
     * 先建立焦点再收尾位移才对。位移整体调慢后这个值也跟着抬了一档，
     * 但比例关系（约位移的 2/3）保持不变。
     */
    val fadeAnimMs: Int = 300,
) {
    /**
     * 第 [screenRow] 行（0 基，0 为屏幕顶）的弹簧刚度。
     *
     * `screenRow` 可以是负数（目标位置在容器顶边之外的行），此时按 0 处理：
     * 那些行已经在裁切区外，再软也看不见，没必要继续外推。
     */
    fun stiffnessAt(screenRow: Int): Float = interpolate(screenRow, stiffnessTop, stiffnessBottom)

    /** 第 [screenRow] 行的阻尼比，方向与 [stiffnessAt] 一致。 */
    fun dampingAt(screenRow: Int): Float = interpolate(screenRow, dampingTop, dampingBottom)

    /**
     * 把屏幕行号映射到 [0,1]，再在 [top]、[bottom] 之间线性插值。
     *
     * [anchorRow] 以上按 `screenRow / anchorRow` 铺开（跨度小、变化陡），
     * 以下按 `(screenRow - anchorRow) / gradientRampLines` 铺开并封顶。
     * 两段在 anchorRow 处连续（都等于 anchorRow / 总跨度对应的那个值），
     * 靠的是把整条曲线定义成分段线性而不是两条独立曲线。
     */
    private fun interpolate(screenRow: Int, top: Float, bottom: Float): Float {
        // 当前行在整条梯度上的归一化位置：上方占 anchorRow 行，下方占 ramp 行
        val totalSpan = anchorRow + gradientRampLines
        if (totalSpan <= 0f) return bottom
        val t = (screenRow.toFloat() / totalSpan).coerceIn(0f, 1f)
        return top + (bottom - top) * t
    }

    /**
     * 距当前行 [offset] 行处的模糊半径（dp）。[offset] 为负表示在当前行上方。
     *
     * 当前行恒为 0：它是阅读焦点，任何模糊都是倒扣分。
     */
    fun blurDpAt(offset: Int): Float {
        if (offset == 0) return 0f
        val ramp = if (offset < 0) blurRampLines * upperFadeScale else blurRampLines
        if (ramp <= 0f) return maxBlurDp
        return maxBlurDp * (kotlin.math.abs(offset) / ramp).coerceIn(0f, 1f)
    }

    /** 距当前行 [offset] 行处的透明度。[isCurrent] 单独传：前奏期没有当前行。 */
    fun alphaAt(offset: Int, isCurrent: Boolean): Float {
        if (isCurrent) return 1f
        val distance = kotlin.math.abs(offset)
        val step = if (offset < 0 && upperFadeScale > 0f) alphaStep / upperFadeScale else alphaStep
        return (alphaNear - (distance - 1) * step).coerceIn(alphaFar, 1f)
    }

    companion object {
        /** 真机调出来的一组值，release 包恒用这套。 */
        val DEFAULT = LyricsAnimSpec()
    }
}

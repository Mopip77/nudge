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
     * 取 3（即视觉上的第 4 行）：上方留三行已唱过的做上下文。
     * 早先取 2 实测仍偏上——预读区是够了，但焦点贴着容器顶边，
     * 上方那点上下文被边缘淡出吃掉大半，看着像「当前行被顶在天花板上」。
     */
    val anchorRow: Int = 3,

    /**
     * 缓动曲线「懒惰度」的梯度端点：屏幕**顶部**最干脆、**底部**最懒。
     *
     * 这是整块动画的核心参数，含义是**贝塞尔曲线第一个控制点的 x**
     * （见 `LyricsOverlay.easingFor`）。值越大，起步越慢、后段越赶，
     * 即「被前面的行拖着走」的感觉越强。
     *
     * 方向：列表整体往**上**走，最上面那行是这趟位移里走得最久、
     * 最先该落定的；越靠下的行越是被拖着走，起步越慢。
     * 所以懒惰度沿屏幕**自上而下递增**。
     *
     * **这里不是弹簧，没有过冲**。早先用 `spring(dampingRatio, stiffness)`，
     * 那是 PID 式的模型：快速拉到目标再来回震荡，越软的行震得越厉害。
     * 实际要的是**单调逼近**——永远不越过上一行的位置，只是趋近速度不同。
     * 震荡在盲操场景里尤其糟：焦点行晃一下会被读成「歌词跳了」。
     *
     * 曾经写反过一版（顶懒底干脆），理由是「被拖拽的只能是后面的行」，
     * 这句话没错，但**「后面的行」指的是下方的行**。
     * 更早还有一版用 `abs(index - current)` 取无向距离，梯度以当前行为中心
     * 对称扩散，拖尾在上下两个方向同时出现。
     *
     * 梯度基准用**目标位置**（动画结束后这行落在第几行）而不是实时位置：
     * 后者会让曲线随自身动画状态变化，构成非线性反馈。
     * 目标位置是静态量，每次换行只重算一次。
     *
     * 两端的差要足够大，否则看不出错峰——相邻行的曲线太接近，
     * 肉眼会把整列合成一个刚体，退化成「整列线性滚动」。
     */
    val easeTop: Float = 0.1f,
    val easeBottom: Float = 0.95f,

    /**
     * 懒惰度梯度铺开的行数，从屏幕顶边（第 0 行）往下数。
     *
     * 超出这个跨度的行统一取 [easeBottom]，即最懒档。
     * 那些行还在裁切边界外或边缘淡出区里，再懒也只是「慢慢飘进来」，
     * 不会干扰阅读。
     *
     * 取 7 是为了让跨度大致覆盖可视区：铺得太窄（比如 3）会让梯度在第 3 行
     * 就跑完，往下全是同一档，又退化成「上面几行动、下面一坨一起动」；
     * 铺得太开则相邻行的差太小，链条感反而弱。
     */
    val gradientRampLines: Float = 7f,

    /**
     * 整列位移的时长（毫秒）。**所有行共用**——同时开始、同时结束。
     *
     * 错峰不靠时长差，靠各行不同的缓动曲线（见 [easeTop] / [easeBottom]）：
     * 下面的行起步更慢、后段赶上来，滑动途中行距会先拉开再收拢，
     * 这就是「被拖着走」的观感来源。
     *
     * 让时长也跟着变的话，快歌连续换行时下面的行会追不上，
     * 位移累积起来越滚越偏。同时结束是个硬约束。
     *
     * 420ms 比早先弹簧的落定估算（约 290ms）略长：补间没有弹簧那条
     * 长长的收敛尾巴，同样时长下感觉更快，要补回来一点。
     */
    val settleTweenMs: Int = 420,

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
     * 与 [fadeDelayMs] 配着看：位移先走完，焦点**后**建立。
     * 早先的口径正相反（fade 短于位移、先对焦再收尾位移），实测那样
     * 换行途中字一边移动一边对焦，两个变化叠在一起反而显得急。
     * 现在拉长到 360ms 并配延迟，收尾这一段变得从容。
     */
    val fadeAnimMs: Int = 360,

    /**
     * 淡入淡出**开始前**的等待时长（毫秒）。
     *
     * 这是「先滑到位、再换焦点」的关键。没有它的话，透明度和模糊从换行
     * 那一刻就开始变，与位移同时进行——视觉上是「一边往上滚一边对焦」，
     * 两件事挤在一起，读起来急。
     *
     * 取值要略小于当前行的位移时长（[settleTweenMs]，默认 420ms），
     * 让两段**稍有交叠**而不是完全排队：完全排队会有一个能察觉的停顿，
     * 反而不连贯。
     *
     * 只延迟淡入淡出，不延迟位移——位移必须立刻响应演唱，
     * 晚一拍会让人觉得歌词跟不上。
     */
    val fadeDelayMs: Int = 320,
) {
    /**
     * 第 [screenRow] 行（0 基，0 为屏幕顶）的缓动「懒惰度」。
     *
     * 返回值直接当贝塞尔曲线第一个控制点的 x 用：越大起步越慢。
     * 因为 y 恒为 0、终点控制点固定，这条曲线**单调不减**，
     * 位移只会逼近目标，不会越过——即用户要的「趋近于 0」而非震荡。
     *
     * `screenRow` 可以是负数（目标位置在容器顶边之外的行），此时按 0 处理：
     * 那些行已经滑出裁切区，按最干脆档处理即可，没必要继续外推。
     */
    fun easeAt(screenRow: Int): Float = interpolate(screenRow, easeTop, easeBottom)

    /**
     * 把屏幕行号映射到 [0,1]，再在 [top]、[bottom] 之间线性插值。
     *
     * 梯度**与 [anchorRow] 无关**：它描述的是「这行在屏幕上处于什么位置」，
     * 而不是「离当前行多远」。当前行只是恰好落在这条曲线的某一点上，
     * 挪动锚点会让它取到不同的快慢，但不会改变整条曲线本身。
     *
     * 早先这里把跨度写成 `anchorRow + gradientRampLines`、让曲线在锚点处
     * 分两段，是残留的「以当前行为中心」的思路——梯度一旦改成纯粹的
     * 屏幕位置函数，锚点就不该出现在这个式子里。
     */
    private fun interpolate(screenRow: Int, top: Float, bottom: Float): Float {
        if (gradientRampLines <= 0f) return bottom
        val t = (screenRow.toFloat() / gradientRampLines).coerceIn(0f, 1f)
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

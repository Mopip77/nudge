package com.nudge.app.action

import com.nudge.app.media.ActionResult

/**
 * 五种反馈各自的波形。**纯 Kotlin**，与 [HapticSpec] 同理由。
 *
 * ## 区分度靠「形状」，不靠数值
 *
 * 早先五种反馈只有时长差别（50ms / 30-80-30 / 20ms / 200ms），盲操下
 * 基本分不出来——尤其「切歌 50ms 单震」与「已收藏 20ms 单震」，
 * 除了长短没有任何别的差异，而长短在没有对照时几乎不可辨。
 *
 * 现在每种反馈占一个**节奏形状**：
 *
 * | 反馈 | 形状 | 语义 |
 * |---|---|---|
 * | 切歌 | 单记重击 | 最高频操作，越短越好 |
 * | 收藏成功 | 加速脉冲列 + 迸发 | 蓄力到迸发，「心被填满」 |
 * | 已收藏 | 两记轻快短击 | 「这个我已经有了」 |
 * | 播放/暂停 | 两记等距中性击 | toggle，不区分方向 |
 * | 失败 | 减速渐弱脉冲列 | 泄气，是收藏那条的镜像 |
 *
 * 形状之间是**类别差异**（有没有迸发、节奏是加速还是减速、几记），
 * 不需要对照就能认出来；而振幅的绝对值没有对照根本分不出来，
 * 所以不拿它当区分维度。
 */
object HapticPalette {

    /**
     * 切歌：单记干脆的重击。
     *
     * 切歌是最高频的操作，任何超过 100ms 的反馈连着用几次都会累。
     * 只给一下，不啰嗦——「干脆」本身就是与其余四种（都是多脉冲）
     * 最大的区别。
     */
    val NEXT = HapticSpec(
        pulses = 1,
        pulseMs = 42,
        startAmp = 0.95f,
        endAmp = 0.95f,
        burstMs = 0,
    )

    /**
     * 收藏成功：加速脉冲列 + 迸发。整个方案里唯一「有仪式感」的一条。
     *
     * 间隔从 90ms 压到 16ms（`gapCurve = 1.9` 让压缩集中在后段，
     * 即「越到后面越急」），振幅同时从 0.28 爬到 0.62——**刻意不爬满**，
     * 把顶上那截留给迸发。爬满了迸发就没有落差，「集中」二字就落空了。
     *
     * 迸发前留 50ms 静默：蓄力列此时节奏已经压到最密，紧接着一记重击
     * 会跟最后几个脉冲黏成一团。那段空白是冲击力的来源。
     *
     * 这是唯一允许长到 400ms 以上的反馈：收藏是低频且值得庆祝的动作。
     */
    val LIKED = HapticSpec(
        pulses = 7,
        pulseMs = 22,
        startGapMs = 90,
        endGapMs = 16,
        startAmp = 0.28f,
        endAmp = 0.62f,
        gapCurve = 1.9f,
        ampCurve = 1.1f,
        burstAmp = 1f,
        burstMs = 70,
        burstGapMs = 50,
    )

    /**
     * 已收藏：三记又轻又快的短促脉冲，像一次轻颤，「这个我已经有了」。
     *
     * 与切歌的单记重击的区别是**记数**，这是早先 20ms 单震最大的问题——
     * 它与切歌的 50ms 单震手感几乎一样，只差长短，而长短在没有对照时
     * 几乎不可辨。
     *
     * 取三记而非两记是为了与 [PLAY_PAUSE] 拉开：两者若都是两记匀速，
     * 就只剩振幅和间隔宽度的差别，而那恰恰是本方案判定为
     * **不可靠的区分维度**（见类注释）。`HapticSpecTest` 里
     * 「五种波形的形状两两不同」拦着这条——它当初正是抓出了
     * 这两条撞形状的问题。
     *
     * 振幅压得比切歌低：这是一个「什么都没发生」的通知，不该抢戏。
     */
    val ALREADY_LIKED = HapticSpec(
        pulses = 3,
        pulseMs = 14,
        startGapMs = 42,
        endGapMs = 42,
        startAmp = 0.4f,
        endAmp = 0.4f,
        burstMs = 0,
    )

    /**
     * 播放/暂停：两记等距、力度中性的脉冲。
     *
     * 刻意**不区分 play 与 pause**：盲操下用户听得见音乐停没停，
     * 振动再去区分方向是冗余信息，还得让 `ActionResult` 携带方向，
     * 为一个用户本来就知道的事增加一条数据通路不划算。
     *
     * 与「已收藏」同为两记，靠振幅与间隔拉开：这条更重、间隔更宽，
     * 像「咚·咚」而非「哒哒」。两者的使用场景也不重叠（一个是收藏手势的
     * 结果，一个是播放手势的结果），混淆的代价很低。
     */
    val PLAY_PAUSE = HapticSpec(
        pulses = 2,
        pulseMs = 30,
        startGapMs = 70,
        endGapMs = 70,
        startAmp = 0.75f,
        endAmp = 0.75f,
        burstMs = 0,
    )

    /**
     * 失败 / 无会话：减速且渐弱的脉冲列，「泄气」。
     *
     * 是 [LIKED] 的镜像——那条是间隔收紧 + 振幅渐强 + 迸发收尾，
     * 这条是间隔拉开 + 振幅渐弱 + 无迸发。两条在语义上正好相反，
     * 绝不会混淆，这正是「形状即类别」的好处。
     *
     * 早先是 200ms 一记长震，那是最典型的「一段持续振动」，
     * 除了长之外不传递任何信息。
     */
    val FAILED = HapticSpec(
        pulses = 3,
        pulseMs = 30,
        startGapMs = 60,
        endGapMs = 150,
        startAmp = 0.8f,
        endAmp = 0.3f,
        gapCurve = 1f,
        ampCurve = 1f,
        burstMs = 0,
    )

    /**
     * 歌词已打开：两记**收紧**的脉冲，「支棱起来了」。
     *
     * 这一对（开/关）是整套波形里唯一**必须区分方向**的：其余动作的结果
     * 用户要么听得见（播放/暂停），要么本来就是单向的（切歌、收藏）。
     * 而歌词是纯视觉的，盲操下看不见屏幕就不知道自己切成了哪一边，
     * 用同一条波形等于没有反馈。
     *
     * 方向语义直接借用 [LIKED] / [FAILED] 那一对已经确立的隐喻：
     * 间隔收紧 = 起来了，间隔拉开 = 下去了。用户学会一次就能套用到这里。
     *
     * 形状签名 `(2, false, 加速)`——与既有五条都不撞（见
     * `HapticSpecTest` 的「形状两两不同」）。
     */
    val LYRICS_ON = HapticSpec(
        pulses = 2,
        pulseMs = 20,
        startGapMs = 90,
        endGapMs = 40,
        startAmp = 0.5f,
        endAmp = 0.8f,
        burstMs = 0,
    )

    /**
     * 歌词已关闭：两记**拉开**的脉冲，[LYRICS_ON] 的镜像。
     *
     * 形状签名 `(2, false, 减速)`。与「播放/暂停」同为两记但节奏走向不同
     * （那条是匀速），这是三者之间唯一的区分维度——振幅不算数，
     * 见类注释里关于「振幅没有对照分不出来」的那段。
     */
    val LYRICS_OFF = HapticSpec(
        pulses = 2,
        pulseMs = 20,
        startGapMs = 40,
        endGapMs = 90,
        startAmp = 0.8f,
        endAmp = 0.5f,
        burstMs = 0,
    )

    /** 全部波形，实验室按这个顺序铺页面。 */
    val ALL: List<HapticSlot> = listOf(
        HapticSlot(HapticId.NEXT, "下一首", "单记干脆的重击。最高频操作，越短越好", NEXT),
        HapticSlot(HapticId.LIKED, "收藏成功", "加速脉冲列 + 迸发，蓄力到「填满」", LIKED),
        HapticSlot(HapticId.ALREADY_LIKED, "已收藏", "两记轻快短击，「这个我已经有了」", ALREADY_LIKED),
        HapticSlot(HapticId.PLAY_PAUSE, "播放/暂停", "两记等距中性击，不区分方向", PLAY_PAUSE),
        HapticSlot(HapticId.LYRICS_ON, "歌词已打开", "两记收紧，「起来了」", LYRICS_ON),
        HapticSlot(HapticId.LYRICS_OFF, "歌词已关闭", "两记拉开，上一条的镜像", LYRICS_OFF),
        HapticSlot(HapticId.FAILED, "失败 / 无会话", "减速渐弱，收藏那条的镜像", FAILED),
    )

    /** 某个 [ActionResult] 该用哪条波形。 */
    fun idFor(result: ActionResult): HapticId = when (result) {
        ActionResult.Skipped -> HapticId.NEXT
        ActionResult.PlaybackCommandSent -> HapticId.PLAY_PAUSE
        ActionResult.Liked -> HapticId.LIKED
        ActionResult.AlreadyLiked -> HapticId.ALREADY_LIKED
        // 歌词是纯视觉的，盲操下唯一能知道切成了哪一边的渠道就是这两条波形
        is ActionResult.LyricsToggled ->
            if (result.shown) HapticId.LYRICS_ON else HapticId.LYRICS_OFF
        ActionResult.NoSession, is ActionResult.Failed -> HapticId.FAILED
    }

    /** 代码里写死的默认波形。实验室的覆盖不经过这里。 */
    fun defaultOf(id: HapticId): HapticSpec = when (id) {
        HapticId.NEXT -> NEXT
        HapticId.LIKED -> LIKED
        HapticId.ALREADY_LIKED -> ALREADY_LIKED
        HapticId.PLAY_PAUSE -> PLAY_PAUSE
        HapticId.LYRICS_ON -> LYRICS_ON
        HapticId.LYRICS_OFF -> LYRICS_OFF
        HapticId.FAILED -> FAILED
    }
}

/**
 * 波形的稳定标识。
 *
 * 用枚举而非直接拿 [HapticSpec] 当 key：实验室要按 id 存覆盖值，
 * 而 [HapticSpec] 是会被改的数据类，拿它当 key 改一下就找不回原来那条了。
 */
enum class HapticId {
    NEXT,
    LIKED,
    ALREADY_LIKED,
    PLAY_PAUSE,
    LYRICS_ON,
    LYRICS_OFF,
    FAILED,
}

/** 实验室里的一块：标识、给人看的名字、一句说明，以及默认波形。 */
data class HapticSlot(
    val id: HapticId,
    val label: String,
    val hint: String,
    val default: HapticSpec,
)

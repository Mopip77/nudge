package com.nudge.app.wallpaper

import com.nudge.app.media.CoverAspect
import com.nudge.app.media.DEFAULT_COVER_ASPECT

/**
 * 锁屏专辑封面壁纸的配置。
 *
 * **刻意独立于 `NudgeConfig`，也不进 `ProfileCodec`。**
 * 锁屏壁纸是设备级的环境设定，与「手势绑定 / 灵敏度」这类操作习惯不是
 * 一回事——切预设不该顺带改壁纸行为。这同时避开了 CLAUDE.md
 * 「加配置项要同时改五处」的连锁修改。
 */
data class LockWallpaperConfig(
    /** 总开关。默认关——这个功能会改用户的锁屏壁纸，不能默认开。 */
    val enabled: Boolean = false,

    /**
     * 清晰区中心占屏高的比例。
     *
     * 开放给用户调是因为**视觉中心因人而异**：时钟大小、有没有放小组件、
     * One UI 版本不同，锁屏上"空着的那块"位置都不一样。写死一个值
     * 只对作者自己合适。
     */
    val centerY: Float = BackdropGeometry.DEFAULT_CENTER_Y,

    /** 清晰区大小的调节量，1.0 为默认。越小清晰区越窄、模糊延伸越多。 */
    val sharpScale: Float = 1f,

    /** 最远那档的模糊半径（dp）。越大延伸区越朦胧。 */
    val maxBlurDp: Float = BackdropGeometry.BLUR_STEPS_DP.last(),

    /** 整体压暗。锁屏上要压住封面才看得清系统的白字。 */
    val scrimAlpha: Float = 0.30f,

    /** 裁切比例，复用主界面那套。 */
    val aspect: CoverAspect = DEFAULT_COVER_ASPECT,

    /** 暂停多久后恢复原壁纸（秒）。沿用 MusWall 的默认值 5。 */
    val restoreDelaySec: Int = 5,

    /** 非网易云时用 MediaSession 那张 363 位图烘焙。 */
    val fallbackToLowRes: Boolean = true,

    /**
     * 熄屏时攒住不写，亮屏后补写最后一次。
     *
     * 这是四条优化里收益最大的一条：正常听歌时屏幕大多是黑的，用户根本
     * 看不到锁屏，写了白写——而每次写入都是一次几 MB 的 PNG 无损编码
     * 加落盘（见设计文档 1.1）。顺带消掉大部分「写入时锁屏重绘」的可见性。
     */
    val deferWhileScreenOff: Boolean = true,

    /**
     * 烘焙缩放。壁纸本就是模糊背景，降一点分辨率能显著减少编码量。
     * 默认 1.0（不降）——观感影响待实测，不确定就别默认省。
     */
    val renderScale: Float = 1f,
) {
    /**
     * 参与「同图不写」比对的指纹。
     *
     * 只含**影响成品像素**的项：`restoreDelaySec` / `deferWhileScreenOff`
     * 改了不必重写壁纸。漏掉会让用户调了参数看不到变化，多算了则会白写。
     */
    val renderFingerprint: String
        get() = listOf(
            centerY, sharpScale, maxBlurDp, scrimAlpha, aspect.name, renderScale,
        ).joinToString(",")

    companion object {
        val DEFAULT = LockWallpaperConfig()

        /** 各可调项的取值范围，UI 的滑块与解码的回落都用它，避免两处写两套。 */
        val CENTER_Y_RANGE = 0.20f..0.80f
        val SHARP_SCALE_RANGE = 0.40f..1.60f
        val MAX_BLUR_RANGE = 8f..80f
        val SCRIM_RANGE = 0f..0.70f
        val RESTORE_DELAY_RANGE = 0..120
        val RENDER_SCALE_RANGE = 0.50f..1f
    }
}

/**
 * 开启功能时原锁屏壁纸的状态。**恢复逻辑的正确性全靠它**。
 *
 * 只有两种，因为 **Android 13+ 读不到原壁纸的内容**（`getWallpaperFile`
 * 需要 targetSdk 33 起已失效的 `READ_EXTERNAL_STORAGE`，Google 标记为
 * Won't Fix，详见 `LockWallpaperWriter.detectOriginalKind` 的注释）。
 * 所以没有「自动备份原图」这一档——能知道的只是「有没有设过」。
 *
 * 判定用 `getWallpaperId(FLAG_LOCK)`，它不需要任何权限。
 * **必须在第一次写入之前判定并持久化**：我们写过一次之后，
 * 系统就认为「设过」了。
 */
enum class OriginalWallpaperKind {
    /**
     * 没设过独立锁屏壁纸，锁屏继承桌面 → 恢复时 `clear(FLAG_LOCK)` 即完美还原。
     *
     * **此时写任何图回去都是错的**：那会把「继承桌面」变成「固定一张图」，
     * 用户之后改桌面壁纸锁屏不再跟随，而他不会知道是 nudge 干的。
     */
    INHERITED,

    /**
     * 设过独立的**静态图**锁屏壁纸。内容读不到，只能请用户自己指定一张恢复图。
     *
     * 没有指定时**不允许开启功能**——直接 clear 会把他原本那张锁屏壁纸
     * 弄丢，而这是不可逆的。宁可不做也不能静默损坏。
     */
    USER_SUPPLIED,

    /**
     * 锁屏画面由一个 **live wallpaper 组件**渲染——锁屏自己绑了一个，
     * 或者锁屏继承的**桌面**绑了一个。**恢复时只能写图，绝不能 clear。**
     *
     * ## 「用户明明选的是一张照片」——这两件事不矛盾
     *
     * 真机上（S24 Ultra / One UI 8.5）是
     * `com.samsung.android.wallpaper.live.layered.LayeredWallpaperService`，
     * 也就是三星的**景深/立体壁纸**：用户挑的确实是相册里一张普通照片，
     * 只是开了那个「主体浮在时钟前面」的效果，系统就把它包成了
     * live wallpaper 组件。所以**从用户视角是静态图，从系统视角是动态壁纸**。
     *
     * 别用「动态壁纸」这个词跟用户解释，他会觉得莫名其妙——真机上就发生过。
     *
     * ## 特效必然丢失，但**不能因此就 clear**
     *
     * 重新绑定那个 service 需要 `SET_WALLPAPER_COMPONENT`（signature 级），
     * 第三方拿不到，所以特效一定回不来，写回去的是一张平面图。
     *
     * 早先据此判断「这一档没有任何恢复手段」而一律拒绝开启，**那是错的**：
     * 真机实测（S24 Ultra / Android 16，见 `RestorePlan` 的注释）表明
     * `clear` 的后果比「丢特效」严重得多——**桌面和锁屏会一起变纯黑**。
     * 相比之下「恢复成一张普通静态图」是个可用的状态，用户想要特效
     * 随时能自己再开一次。
     *
     * 所以这一档现在按 [USER_SUPPLIED] 同样的口径处理：
     * 有恢复图才允许开启，恢复时写那张图。区别只在开启前要**额外告知
     * 特效会丢失**——这是用户知情同意的代价，不是静默破坏。
     */
    LIVE_WALLPAPER,
}

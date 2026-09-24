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
     * 锁屏用的是**动态壁纸**（live wallpaper）。**一律拒绝开启。**
     *
     * 这是开发中真实损坏过用户数据的一条：S24 Ultra 上锁屏是三星的
     * `LayeredWallpaperService`，功能开启后写入静态图把它顶掉，
     * 而恢复侧**根本没有对应的动作**——设置 live wallpaper 需要
     * `SET_WALLPAPER_COMPONENT`，那是 signature 权限，第三方拿不到。
     * 用户指定的「恢复图」也救不了：静态图替不回动态壁纸。
     *
     * 也就是说这一档**无论如何都恢复不了**，唯一负责任的做法是
     * 从一开始就不让开，并在界面上说清楚要先把锁屏壁纸换成静态图。
     */
    LIVE_WALLPAPER,
}

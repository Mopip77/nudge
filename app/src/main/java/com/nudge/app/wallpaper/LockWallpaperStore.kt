package com.nudge.app.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nudge.app.media.CoverAspect
import com.nudge.app.media.DEFAULT_COVER_ASPECT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 锁屏壁纸配置的持久化。
 *
 * **独立的 DataStore 文件**，不与 `nudge_config` 混在一起：这套配置不进
 * 预设（见 [LockWallpaperConfig] 的注释），分开存能让「加配置项要同时改
 * 五处」那条连锁完全不适用于它。
 */
private val Context.lockWallpaperStore: DataStore<Preferences> by
    preferencesDataStore(name = "lock_wallpaper")

class LockWallpaperStore(private val context: Context) {

    val config: Flow<LockWallpaperConfig> = context.lockWallpaperStore.data.map { p ->
        // 逐项宽容回落：解不出来就用默认值，不让一个坏值导致整个功能起不来。
        LockWallpaperConfig(
            enabled = p[ENABLED] ?: LockWallpaperConfig.DEFAULT.enabled,
            centerY = p[CENTER_Y]?.coerceIn(LockWallpaperConfig.CENTER_Y_RANGE)
                ?: LockWallpaperConfig.DEFAULT.centerY,
            sharpScale = p[SHARP_SCALE]?.coerceIn(LockWallpaperConfig.SHARP_SCALE_RANGE)
                ?: LockWallpaperConfig.DEFAULT.sharpScale,
            maxBlurDp = p[MAX_BLUR]?.coerceIn(LockWallpaperConfig.MAX_BLUR_RANGE)
                ?: LockWallpaperConfig.DEFAULT.maxBlurDp,
            scrimAlpha = p[SCRIM]?.coerceIn(LockWallpaperConfig.SCRIM_RANGE)
                ?: LockWallpaperConfig.DEFAULT.scrimAlpha,
            aspect = p[ASPECT]?.let { n -> CoverAspect.entries.firstOrNull { it.name == n } }
                ?: DEFAULT_COVER_ASPECT,
            restoreDelaySec = p[RESTORE_DELAY]?.coerceIn(LockWallpaperConfig.RESTORE_DELAY_RANGE)
                ?: LockWallpaperConfig.DEFAULT.restoreDelaySec,
            fallbackToLowRes = p[FALLBACK_LOW_RES] ?: LockWallpaperConfig.DEFAULT.fallbackToLowRes,
            deferWhileScreenOff = p[DEFER_SCREEN_OFF]
                ?: LockWallpaperConfig.DEFAULT.deferWhileScreenOff,
            renderScale = p[RENDER_SCALE]?.coerceIn(LockWallpaperConfig.RENDER_SCALE_RANGE)
                ?: LockWallpaperConfig.DEFAULT.renderScale,
        )
    }

    suspend fun save(cfg: LockWallpaperConfig) {
        context.lockWallpaperStore.edit { p ->
            p[ENABLED] = cfg.enabled
            p[CENTER_Y] = cfg.centerY
            p[SHARP_SCALE] = cfg.sharpScale
            p[MAX_BLUR] = cfg.maxBlurDp
            p[SCRIM] = cfg.scrimAlpha
            p[ASPECT] = cfg.aspect.name
            p[RESTORE_DELAY] = cfg.restoreDelaySec
            p[FALLBACK_LOW_RES] = cfg.fallbackToLowRes
            p[DEFER_SCREEN_OFF] = cfg.deferWhileScreenOff
            p[RENDER_SCALE] = cfg.renderScale
        }
    }

    /**
     * 记录开启功能时原壁纸属于哪种情形。**恢复的正确性全靠它。**
     */
    suspend fun saveOriginalKind(kind: OriginalWallpaperKind) {
        context.lockWallpaperStore.edit { it[ORIGINAL_KIND] = kind.name }
    }

    /** 当前配置的一次性快照，供非响应式的调用方（设置页初始化、广播入口）用。 */
    suspend fun currentConfig(): LockWallpaperConfig = config.first()

    /**
     * 设置页用的同步写入。
     *
     * 设置页的回调不是 suspend（`Switch.onCheckedChange` / `Slider` 的
     * `onValueChangeFinished` 都是普通 lambda），而这里写的量很小、
     * 只在用户松手时发生，`runBlocking` 一次是毫秒级。
     */
    fun saveBlocking(cfg: LockWallpaperConfig) = runBlocking { save(cfg) }

    fun saveOriginalKindBlocking(kind: OriginalWallpaperKind) =
        runBlocking { saveOriginalKind(kind) }

    /**
     * 同步读取原壁纸形态。服务里用，`runBlocking` 的理由同
     * `ProfileCommandReceiver`：恢复要在 `onDestroy` 里同步完成，
     * 异步协程来不及执行完进程就没了。读的是本地 DataStore，毫秒级。
     */
    fun originalKind(): OriginalWallpaperKind = runBlocking {
        val name = runCatching {
            context.lockWallpaperStore.data.map { it[ORIGINAL_KIND] }.first()
        }.getOrNull()
        OriginalWallpaperKind.entries.firstOrNull { it.name == name }
            // 读不出来时按「继承」处理：clear 是**最保守**的动作，
            // 它顶多让用户少一张自定义锁屏壁纸，而写错图会造成静默破坏。
            ?: OriginalWallpaperKind.INHERITED
    }

    /** 用户指定的恢复图。没有则 null。 */
    fun userSuppliedBitmap(): Bitmap? {
        val f = userSuppliedFile
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    /**
     * 存一份用户指定的恢复图到私有目录。
     *
     * **必须存副本而不是记 Uri**：用户选的那张图可能来自相册，
     * 之后被删除或权限被回收，到恢复的时候就读不到了——而恢复是
     * 「关掉功能」时才发生的，中间可能隔几个月。
     *
     * 存 PNG（无损）：这张图会被原样写回锁屏，再压一次 JPEG 没道理。
     */
    fun saveUserSupplied(bitmap: Bitmap) {
        userSuppliedFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    val userSuppliedFile: File get() = File(context.filesDir, USER_SUPPLIED_NAME)

    private companion object {
        val ENABLED = booleanPreferencesKey("lw_enabled")
        val CENTER_Y = floatPreferencesKey("lw_center_y")
        val SHARP_SCALE = floatPreferencesKey("lw_sharp_scale")
        val MAX_BLUR = floatPreferencesKey("lw_max_blur")
        val SCRIM = floatPreferencesKey("lw_scrim")
        val ASPECT = stringPreferencesKey("lw_aspect")
        val RESTORE_DELAY = intPreferencesKey("lw_restore_delay")
        val FALLBACK_LOW_RES = booleanPreferencesKey("lw_fallback_low_res")
        val DEFER_SCREEN_OFF = booleanPreferencesKey("lw_defer_screen_off")
        val RENDER_SCALE = floatPreferencesKey("lw_render_scale")
        val ORIGINAL_KIND = stringPreferencesKey("lw_original_kind")
        const val USER_SUPPLIED_NAME = "lock_wallpaper_user.png"
    }
}

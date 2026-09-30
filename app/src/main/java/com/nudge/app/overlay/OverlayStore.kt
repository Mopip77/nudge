package com.nudge.app.overlay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.overlayStore: DataStore<Preferences> by
    preferencesDataStore(name = "overlay_bubble")

/** 气泡配置的持久化。逐项宽容回落，坏值不至于让整个功能起不来。 */
class OverlayStore(private val context: Context) {

    val config: Flow<OverlayConfig> = context.overlayStore.data.map { p ->
        OverlayConfig(
            enabled = p[ENABLED] ?: OverlayConfig.DEFAULT.enabled,
            edge = p[EDGE]?.let { n -> BubbleEdge.entries.firstOrNull { it.name == n } }
                ?: OverlayConfig.DEFAULT.edge,
            yRatio = p[Y_RATIO]?.coerceIn(OverlayConfig.Y_RATIO_RANGE)
                ?: OverlayConfig.DEFAULT.yRatio,
            sizeDp = p[SIZE]?.coerceIn(OverlayConfig.SIZE_RANGE)
                ?: OverlayConfig.DEFAULT.sizeDp,
            alpha = p[ALPHA]?.coerceIn(OverlayConfig.ALPHA_RANGE)
                ?: OverlayConfig.DEFAULT.alpha,
        )
    }

    suspend fun save(cfg: OverlayConfig) {
        context.overlayStore.edit { p ->
            p[ENABLED] = cfg.enabled
            p[EDGE] = cfg.edge.name
            p[Y_RATIO] = cfg.yRatio
            p[SIZE] = cfg.sizeDp
            p[ALPHA] = cfg.alpha
        }
    }

    /**
     * 只写位置。**拖动松手时走这条而不是整份 save**：服务里持有的 cfg
     * 可能比 DataStore 旧（用户刚在设置页改过大小还没推送过来），
     * 整份写回会把那次修改盖掉。
     */
    fun savePositionBlocking(edge: BubbleEdge, yRatio: Float) = runBlocking {
        context.overlayStore.edit { p ->
            p[EDGE] = edge.name
            p[Y_RATIO] = yRatio.coerceIn(OverlayConfig.Y_RATIO_RANGE)
        }
    }

    suspend fun currentConfig(): OverlayConfig = config.first()

    /** 设置页用的同步写入，理由同 `LockWallpaperStore.saveBlocking`。 */
    fun saveBlocking(cfg: OverlayConfig) = runBlocking { save(cfg) }

    private companion object {
        val ENABLED = booleanPreferencesKey("ov_enabled")
        val EDGE = stringPreferencesKey("ov_edge")
        val Y_RATIO = floatPreferencesKey("ov_y_ratio")
        val SIZE = intPreferencesKey("ov_size")
        val ALPHA = floatPreferencesKey("ov_alpha")
    }
}

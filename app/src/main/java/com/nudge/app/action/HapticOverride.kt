package com.nudge.app.action

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nudge.app.BuildConfig

/**
 * 振动实验室调出来的波形，**进程内共享、不落盘**。
 *
 * 与 `LyricsAnimOverride` 同一套设计，理由也相同（见那边的注释）：
 * 开发期的取景器，调好之后把值手抄回 [HapticPalette]，重启即回默认。
 *
 * 振动这里还多一条不落盘的理由：**振动强度受系统设置和机型影响极大**，
 * 用户存下来的一组值换台机器完全是另一个手感，线上出现「振动很怪」
 * 的反馈时根本无从复现。
 *
 * 与歌词那边的差别是这里存的是**一组**波形（五种反馈各一条），
 * 所以用 map 而非单个值——某一条被调过，其余四条仍取代码里的默认值。
 */
object HapticOverride {

    /**
     * 用 `mutableStateOf` 持有整个 map 而不是 `mutableStateMapOf`：
     * 实验室要按「有没有任何一条被改过」点亮角标、要一键恢复全部，
     * 整体替换的语义比逐 key 增删更好推理。map 本身只有五个条目，
     * 每次改动重建一个新的毫无性能问题。
     */
    private var overrides by mutableStateOf<Map<HapticId, HapticSpec>>(emptyMap())

    /**
     * [id] 当前应当生效的波形。release 恒为代码里的默认值。
     *
     * **不是 `@Composable`**——与 `LyricsAnimOverride.current` 的关键区别：
     * 读它的是 [ActionDispatcher]（手势触发时从普通函数里调），不是 composable。
     * 标成 `@Composable` 会让它没法在真实反馈路径上用，
     * 实验室调的参数就只能在实验室里听到，失去意义。
     */
    fun specFor(id: HapticId): HapticSpec {
        if (!BuildConfig.DEBUG) return HapticPalette.defaultOf(id)
        return overrides[id] ?: HapticPalette.defaultOf(id)
    }

    /**
     * 是否有任何一条被调过。主界面据此显示角标，与歌词实验室同口径：
     * 没有这个提示就分不清「刚才那下手感变化是参数生效了，还是错觉」。
     */
    val isActive: Boolean
        get() = BuildConfig.DEBUG && overrides.isNotEmpty()

    /** 实验室里每次改动都写回这里。 */
    fun set(id: HapticId, spec: HapticSpec) {
        overrides = overrides + (id to spec)
    }

    /** 清掉全部覆盖，回到代码里的默认值。 */
    fun clear() {
        overrides = emptyMap()
    }

    /** 实验室重新进入时读回上次调的值，否则每次进去都从默认重来。 */
    fun peek(id: HapticId): HapticSpec? = overrides[id]
}

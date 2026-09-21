package com.nudge.app.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 可绑定手势的动作。 */
enum class ActionType(val displayName: String) {
    NEXT_TRACK("下一首"),
    LIKE("收藏"),
}

enum class ThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("白天"),
    DARK("夜间"),
}

/**
 * 一个动作可绑多个手势（如「下一首」同时接受双击和两指双击），
 * 但一个手势只能属于一个动作——否则一次手势会触发两个动作。
 * 这条反向约束由 [gestureToAction] 的读取口径和 [ConfigStore.addBinding] 的抢占共同保证。
 */
data class NudgeConfig(
    val bindings: Map<ActionType, Set<Gesture>>,
    val sensitivity: Sensitivity,
    val themeMode: ThemeMode,
    /** 关闭后既不显示歌词，也不发起歌词网络请求。 */
    val lyricsEnabled: Boolean,
    /**
     * 开启后进入主界面自动调用 `startLockTask()` 固定屏幕。
     *
     * 默认关：它会弹系统确认框，且退出方式（长按返回+概览）需要用户预先知道，
     * 不该在用户没主动选择时强加。对「放口袋里盲操」这类场景才值得开。
     */
    val screenPinningEnabled: Boolean,
) {
    /** 反查：某手势绑定到了哪个动作。未绑定返回 null。 */
    fun gestureToAction(gesture: Gesture): ActionType? =
        bindings.entries.firstOrNull { gesture in it.value }?.key

    companion object {
        val DEFAULT = NudgeConfig(
            bindings = mapOf(
                ActionType.NEXT_TRACK to setOf(Gesture.TWO_FINGER_DOUBLE_TAP),
                ActionType.LIKE to setOf(Gesture.THREE_FINGER_DOUBLE_TAP),
            ),
            sensitivity = Sensitivity.STANDARD,
            themeMode = ThemeMode.SYSTEM,
            lyricsEnabled = true,
            screenPinningEnabled = false,
        )
    }
}

/**
 * 绑定的持久化格式：逗号分隔的枚举名。
 *
 * 刻意沿用 [stringPreferencesKey] 而不换成 stringSetPreferencesKey——同名 key 换类型
 * 是不兼容变更。逗号格式让旧数据（单个枚举名）天然解析成单元素集合，无需迁移代码。
 */
internal fun encodeGestures(gestures: Set<Gesture>): String = gestures.joinToString(",") { it.name }

/** 未知名字直接丢弃，这样枚举重命名后读旧数据不会崩。 */
internal fun decodeGestures(stored: String): Set<Gesture> =
    stored.split(",")
        .mapNotNull { name -> Gesture.entries.firstOrNull { it.name == name.trim() } }
        .toSet()

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nudge_config")

class ConfigStore(private val context: Context) {

    val config: Flow<NudgeConfig> = context.dataStore.data.map { prefs ->
        NudgeConfig(
            bindings = ActionType.entries.associateWith { action ->
                // 没写过 key 才回落到默认；写过空串表示用户主动清空，要保持空集
                prefs[bindingKey(action)]
                    ?.let { decodeGestures(it) }
                    ?: NudgeConfig.DEFAULT.bindings[action].orEmpty()
            },
            sensitivity = prefs[SENSITIVITY_KEY]
                ?.let { name -> Sensitivity.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.sensitivity,
            themeMode = prefs[THEME_KEY]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.themeMode,
            lyricsEnabled = prefs[LYRICS_ENABLED_KEY] ?: NudgeConfig.DEFAULT.lyricsEnabled,
            screenPinningEnabled = prefs[SCREEN_PINNING_KEY]
                ?: NudgeConfig.DEFAULT.screenPinningEnabled,
        )
    }

    /** 给动作加一个手势。同一手势不能同时绑定两个动作，故先解除它在别处的占用。 */
    suspend fun addBinding(action: ActionType, gesture: Gesture) {
        context.dataStore.edit { prefs ->
            ActionType.entries.forEach { other ->
                if (other == action) return@forEach
                val current = prefs.gesturesOf(other)
                if (gesture in current) {
                    prefs[bindingKey(other)] = encodeGestures(current - gesture)
                }
            }
            prefs[bindingKey(action)] = encodeGestures(prefs.gesturesOf(action) + gesture)
        }
    }

    /** 解除动作的一个手势。允许清空到空集——此时该动作无法触发，由 UI 明示「未绑定」。 */
    suspend fun removeBinding(action: ActionType, gesture: Gesture) {
        context.dataStore.edit { prefs ->
            prefs[bindingKey(action)] = encodeGestures(prefs.gesturesOf(action) - gesture)
        }
    }

    suspend fun setSensitivity(sensitivity: Sensitivity) {
        context.dataStore.edit { it[SENSITIVITY_KEY] = sensitivity.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_KEY] = mode.name }
    }

    suspend fun setLyricsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[LYRICS_ENABLED_KEY] = enabled }
    }

    suspend fun setScreenPinningEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SCREEN_PINNING_KEY] = enabled }
    }

    private companion object {
        val SENSITIVITY_KEY = stringPreferencesKey("sensitivity")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val LYRICS_ENABLED_KEY = booleanPreferencesKey("lyrics_enabled")
        val SCREEN_PINNING_KEY = booleanPreferencesKey("screen_pinning_enabled")
        fun bindingKey(action: ActionType) = stringPreferencesKey("binding_${action.name}")

        /** 写入侧必须和读取侧用同一套回落规则，否则改 A 会把未写过的 B 悄悄重置成空。 */
        fun Preferences.gesturesOf(action: ActionType): Set<Gesture> =
            this[bindingKey(action)]
                ?.let { decodeGestures(it) }
                ?: NudgeConfig.DEFAULT.bindings[action].orEmpty()
    }
}

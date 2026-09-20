package com.nudge.app.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

data class NudgeConfig(
    val bindings: Map<ActionType, Gesture>,
    val sensitivity: Sensitivity,
    val themeMode: ThemeMode,
) {
    /** 反查：某手势绑定到了哪个动作。未绑定返回 null。 */
    fun gestureToAction(gesture: Gesture): ActionType? =
        bindings.entries.firstOrNull { it.value == gesture }?.key

    companion object {
        val DEFAULT = NudgeConfig(
            bindings = mapOf(
                ActionType.NEXT_TRACK to Gesture.TWO_FINGER_DOUBLE_TAP,
                ActionType.LIKE to Gesture.THREE_FINGER_DOUBLE_TAP,
            ),
            sensitivity = Sensitivity.STANDARD,
            themeMode = ThemeMode.SYSTEM,
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nudge_config")

class ConfigStore(private val context: Context) {

    val config: Flow<NudgeConfig> = context.dataStore.data.map { prefs ->
        NudgeConfig(
            bindings = ActionType.entries.mapNotNull { action ->
                val stored = prefs[bindingKey(action)]
                val gesture = stored?.let { name ->
                    Gesture.entries.firstOrNull { it.name == name }
                } ?: NudgeConfig.DEFAULT.bindings[action]
                gesture?.let { action to it }
            }.toMap(),
            sensitivity = prefs[SENSITIVITY_KEY]
                ?.let { name -> Sensitivity.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.sensitivity,
            themeMode = prefs[THEME_KEY]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.themeMode,
        )
    }

    /** 绑定手势到动作。同一手势不能同时绑定两个动作，故先解除它在别处的占用。 */
    suspend fun setBinding(action: ActionType, gesture: Gesture) {
        context.dataStore.edit { prefs ->
            ActionType.entries.forEach { other ->
                if (other != action && prefs[bindingKey(other)] == gesture.name) {
                    prefs.remove(bindingKey(other))
                }
            }
            prefs[bindingKey(action)] = gesture.name
        }
    }

    suspend fun setSensitivity(sensitivity: Sensitivity) {
        context.dataStore.edit { it[SENSITIVITY_KEY] = sensitivity.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_KEY] = mode.name }
    }

    private companion object {
        val SENSITIVITY_KEY = stringPreferencesKey("sensitivity")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        fun bindingKey(action: ActionType) = stringPreferencesKey("binding_${action.name}")
    }
}

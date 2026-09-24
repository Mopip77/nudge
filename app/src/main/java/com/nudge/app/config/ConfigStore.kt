package com.nudge.app.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nudge.app.BuildConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 可绑定手势的动作。 */
enum class ActionType(val displayName: String) {
    NEXT_TRACK("下一首"),
    LIKE("收藏"),
    // 默认不绑任何手势：播放/暂停误触代价虽低（可逆、听得见），但手势池已经够用，
    // 绑哪个交给用户自己在设置页决定。
    PLAY_PAUSE("播放/暂停"),
    /**
     * 切换歌词显示。**唯一不经播放器的动作**，只改本机配置。
     *
     * 同样默认不绑手势，理由同播放/暂停。另有一条：它误触的代价比其余动作
     * 都低（纯视觉、立刻可见、再做一次就回来了），所以不值得占用一个
     * 好记的手势位。
     */
    TOGGLE_LYRICS("显示歌词"),
}

enum class ThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("白天"),
    DARK("夜间"),
}

/** 歌词的水平对齐方式。 */
enum class LyricsAlignment(val displayName: String) {
    CENTER("居中"),
    START("左对齐"),
}

/**
 * 播放界面的视觉外壳。**与 [NudgeConfig.lyricsEnabled] 正交**：
 * 本项管「壳」（卡片 vs 整屏封面），歌词显隐仍由 lyricsEnabled 管，
 * 于是四种组合都成立。两者合并成一个三选一枚举会破坏
 * [ActionType.TOGGLE_LYRICS] 的语义——那个动作切的是歌词，不是布局。
 */
enum class DisplayMode(val displayName: String, val hint: String) {
    /**
     * 早先唯一的形态：顶栏 + 一块圆角卡片。默认值。
     *
     * 默认留在这里而不是新的封面模式：升级后观感不变是默认值的本分，
     * 且这个形态不显示大封面，在工作场合不会一眼被看出在放歌——
     * 这是它作为独立选项保留下来的理由，不只是为了兼容。
     */
    SIMPLE("简洁模式", "卡片式布局，不显示专辑封面，适合工作场合"),

    /** 整屏专辑封面，中间清晰、上下模糊羽化延伸。 */
    ALBUM("专辑封面模式", "整屏专辑封面，沉浸式"),
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
    val lyricsAlignment: LyricsAlignment,
    /** 播放界面的视觉外壳，与 [lyricsEnabled] 正交。见 [DisplayMode]。 */
    val displayMode: DisplayMode,
    /**
     * 防误触模式总开关，一次管三层：沉浸式粘性 + 全屏手势排除区、
     * 返回键双击才退出、屏幕固定。
     *
     * 三层服务同一个目的（盲操时防意外退出），所以同生同灭而不再分开配置。
     * 默认开：盲操是本应用的核心场景，防误触本就该是默认态。
     * 代价是首次进主界面会弹屏幕固定的系统确认框，不想要的整个关掉即可。
     */
    val antiMistouchEnabled: Boolean,
) {
    /** 反查：某手势绑定到了哪个动作。未绑定返回 null。 */
    fun gestureToAction(gesture: Gesture): ActionType? =
        bindings.entries.firstOrNull { gesture in it.value }?.key

    companion object {
        val DEFAULT = NudgeConfig(
            // 每个 ActionType 都要显式出现，包括绑定为空的。
            // 读取侧（ConfigStore.config、ProfileCodec.decode）都按 ActionType.entries
            // 全量构造 map，「缺 key」与「空集」在那里等价，但对 equals 不等价——
            // 漏写会让 round-trip 出来的 config 多一个空集键而与 DEFAULT 判不相等。
            bindings = mapOf(
                ActionType.NEXT_TRACK to setOf(Gesture.TWO_FINGER_DOUBLE_TAP),
                ActionType.LIKE to setOf(Gesture.THREE_FINGER_DOUBLE_TAP),
                // 播放/暂停默认不绑：手势池已够用，绑哪个交给用户决定
                ActionType.PLAY_PAUSE to emptySet(),
                // 同上。空集必须显式写出，不能靠「不写」表达——读取侧按
                // ActionType.entries 全量构造 map，缺 key 与空集在那里等价，
                // 但对 equals 不等价，漏写会让 ProfileCodecTest 的 round-trip 挂掉。
                ActionType.TOGGLE_LYRICS to emptySet(),
            ),
            sensitivity = Sensitivity.STANDARD,
            themeMode = ThemeMode.SYSTEM,
            lyricsEnabled = true,
            lyricsAlignment = LyricsAlignment.CENTER,
            displayMode = DisplayMode.SIMPLE,
            antiMistouchEnabled = true,
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

/** 槽位数固定为 3。槽位号是广播协议的对外标识，扩容会改变外部已配置好的自动化语义。 */
const val PROFILE_SLOT_COUNT = 3

/** 一个预设槽位。空槽的 [name] 与 [config] 均为 null。 */
data class ProfileSlot(val index: Int, val name: String?, val config: NudgeConfig?) {
    val isEmpty: Boolean get() = name == null || config == null
}

/**
 * 解析外部传入的槽位号。非法值返回 null 而不抛异常——
 * 广播的参数来自 Tasker/adb 等外部工具，什么都可能传进来。
 */
fun parseSlotIndex(raw: String?): Int? =
    raw?.trim()?.toIntOrNull()?.takeIf { it in 1..PROFILE_SLOT_COUNT }

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
            // release 忽略存量值，恒为标准档：灵敏度的 UI 只在 debug 包里，
            // 装过 debug 又换回 release 的用户会留下一个自己既看不到、也改不回的
            // 非标准档位，线上问题就无从复现了。不写盘，换回 debug 仍是原值。
            sensitivity = if (BuildConfig.DEBUG) {
                prefs[SENSITIVITY_KEY]
                    ?.let { name -> Sensitivity.entries.firstOrNull { it.name == name } }
                    ?: NudgeConfig.DEFAULT.sensitivity
            } else {
                Sensitivity.STANDARD
            },
            themeMode = prefs[THEME_KEY]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.themeMode,
            lyricsEnabled = prefs[LYRICS_ENABLED_KEY] ?: NudgeConfig.DEFAULT.lyricsEnabled,
            lyricsAlignment = prefs[LYRICS_ALIGNMENT_KEY]
                ?.let { name -> LyricsAlignment.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.lyricsAlignment,
            displayMode = prefs[DISPLAY_MODE_KEY]
                ?.let { name -> DisplayMode.entries.firstOrNull { it.name == name } }
                ?: NudgeConfig.DEFAULT.displayMode,
            antiMistouchEnabled = prefs[ANTI_MISTOUCH_KEY]
                ?: NudgeConfig.DEFAULT.antiMistouchEnabled,
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

    suspend fun setLyricsAlignment(alignment: LyricsAlignment) {
        context.dataStore.edit { it[LYRICS_ALIGNMENT_KEY] = alignment.name }
    }

    suspend fun setDisplayMode(mode: DisplayMode) {
        context.dataStore.edit { it[DISPLAY_MODE_KEY] = mode.name }
    }

    suspend fun setAntiMistouchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ANTI_MISTOUCH_KEY] = enabled }
    }

    /** 恒为 [PROFILE_SLOT_COUNT] 个元素，空槽也占位——界面靠固定槽位保持位置稳定。 */
    val profiles: Flow<List<ProfileSlot>> = context.dataStore.data.map { prefs ->
        (1..PROFILE_SLOT_COUNT).map { index ->
            val stored = ProfileCodec.decode(prefs[profileKey(index)])
            ProfileSlot(index, stored?.name, stored?.config)
        }
    }

    suspend fun saveProfile(index: Int, name: String, config: NudgeConfig) {
        require(index in 1..PROFILE_SLOT_COUNT) { "槽位号越界: $index" }
        val encoded = ProfileCodec.encode(StoredProfile(name, config))
        context.dataStore.edit { it[profileKey(index)] = encoded }
    }

    /** 读取预设内容但不应用。返回 null 表示空槽。 */
    suspend fun readProfile(index: Int): StoredProfile? {
        if (index !in 1..PROFILE_SLOT_COUNT) return null
        return ProfileCodec.decode(context.dataStore.data.first()[profileKey(index)])
    }

    /**
     * 把预设应用成当前配置。返回被应用的预设，空槽返回 null 且不做任何修改。
     *
     * 所有字段**全部**写入，包括值等于默认值的项。不能做「等于默认就不写」的优化：
     * bindings 的读取侧口径是「没写过 key 才回落默认，写过空串表示用户主动清空」，
     * 跳过写入会把用户存的空绑定静默恢复成默认绑定。
     */
    suspend fun loadProfile(index: Int): StoredProfile? {
        val stored = readProfile(index) ?: return null
        val config = stored.config
        context.dataStore.edit { prefs ->
            ActionType.entries.forEach { action ->
                prefs[bindingKey(action)] = encodeGestures(config.bindings[action].orEmpty())
            }
            prefs[SENSITIVITY_KEY] = config.sensitivity.name
            prefs[THEME_KEY] = config.themeMode.name
            prefs[LYRICS_ENABLED_KEY] = config.lyricsEnabled
            prefs[LYRICS_ALIGNMENT_KEY] = config.lyricsAlignment.name
            prefs[DISPLAY_MODE_KEY] = config.displayMode.name
            prefs[ANTI_MISTOUCH_KEY] = config.antiMistouchEnabled
        }
        return stored
    }

    suspend fun deleteProfile(index: Int) {
        require(index in 1..PROFILE_SLOT_COUNT) { "槽位号越界: $index" }
        context.dataStore.edit { it.remove(profileKey(index)) }
    }

    private companion object {
        val SENSITIVITY_KEY = stringPreferencesKey("sensitivity")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val LYRICS_ENABLED_KEY = booleanPreferencesKey("lyrics_enabled")
        val LYRICS_ALIGNMENT_KEY = stringPreferencesKey("lyrics_alignment")
        val DISPLAY_MODE_KEY = stringPreferencesKey("display_mode")
        // 刻意换新 key 而不沿用旧的 screen_pinning_enabled：旧值的语义是
        // 「是否固定屏幕」，与新的「是否启用整套防误触」不等价。把旧的 false
        // 迁移过来会顺带关掉用户从没关过的沉浸式与双击返回，比直接丢弃更糟。
        val ANTI_MISTOUCH_KEY = booleanPreferencesKey("anti_mistouch_enabled")
        fun bindingKey(action: ActionType) = stringPreferencesKey("binding_${action.name}")
        fun profileKey(index: Int) = stringPreferencesKey("profile_$index")

        /** 写入侧必须和读取侧用同一套回落规则，否则改 A 会把未写过的 B 悄悄重置成空。 */
        fun Preferences.gesturesOf(action: ActionType): Set<Gesture> =
            this[bindingKey(action)]
                ?.let { decodeGestures(it) }
                ?: NudgeConfig.DEFAULT.bindings[action].orEmpty()
    }
}

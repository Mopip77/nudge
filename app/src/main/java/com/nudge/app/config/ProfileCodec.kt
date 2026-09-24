package com.nudge.app.config

import com.nudge.app.gesture.Sensitivity
import org.json.JSONObject

/** 一个预设的内容：名字 + 完整配置快照。槽位号不在其中，由存储位置决定。 */
data class StoredProfile(val name: String, val config: NudgeConfig)

/**
 * 预设的存储格式。
 *
 * 整份预设序列化成一个 JSON 串而非扁平展开成十几个 DataStore key：
 * [NudgeConfig] 还在加字段，JSON 方案加字段只需改这里一处，
 * 扁平展开要同时动 key 表、读、写三处。
 *
 * 不依赖任何 Android 类，故可在 JVM 上直接单测——预设的正确性全靠 round-trip 保证。
 */
object ProfileCodec {

    fun encode(profile: StoredProfile): String {
        val bindings = JSONObject()
        profile.config.bindings.forEach { (action, gestures) ->
            bindings.put(action.name, encodeGestures(gestures))
        }
        return JSONObject().apply {
            put(KEY_NAME, profile.name)
            put(KEY_BINDINGS, bindings)
            put(KEY_SENSITIVITY, profile.config.sensitivity.name)
            put(KEY_THEME, profile.config.themeMode.name)
            put(KEY_LYRICS, profile.config.lyricsEnabled)
            put(KEY_LYRICS_ALIGN, profile.config.lyricsAlignment.name)
            put(KEY_DISPLAY_MODE, profile.config.displayMode.name)
            put(KEY_ANTI_MISTOUCH, profile.config.antiMistouchEnabled)
        }.toString()
    }

    /**
     * 解码。空槽位、数据损坏、名字缺失都返回 null；
     * 单个字段不认识则回落到 [NudgeConfig.DEFAULT] 的对应值。
     *
     * 宽容是刻意的：枚举重命名后读旧数据不能崩，这与 [decodeGestures]
     * 「未知名字直接丢弃」、[ConfigStore] 读取侧「读不到就回落默认」是同一口径。
     */
    fun decode(raw: String?): StoredProfile? {
        if (raw.isNullOrBlank()) return null
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }

        val name = json.optString(KEY_NAME).trim()
        if (name.isEmpty()) return null

        val default = NudgeConfig.DEFAULT
        val storedBindings = json.optJSONObject(KEY_BINDINGS)
        return StoredProfile(
            name = name,
            config = NudgeConfig(
                bindings = ActionType.entries.associateWith { action ->
                    // 整个 bindings 或某个动作缺失都回落默认；
                    // 写过的空串是「用户主动清空」，要保持空集
                    val stored = storedBindings?.let {
                        if (it.has(action.name)) it.optString(action.name) else null
                    }
                    stored?.let { decodeGestures(it) } ?: default.bindings[action].orEmpty()
                },
                sensitivity = json.optString(KEY_SENSITIVITY)
                    .let { stored -> Sensitivity.entries.firstOrNull { it.name == stored } }
                    ?: default.sensitivity,
                themeMode = json.optString(KEY_THEME)
                    .let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: default.themeMode,
                lyricsEnabled = json.optBoolean(KEY_LYRICS, default.lyricsEnabled),
                lyricsAlignment = json.optString(KEY_LYRICS_ALIGN)
                    .let { stored -> LyricsAlignment.entries.firstOrNull { it.name == stored } }
                    ?: default.lyricsAlignment,
                displayMode = json.optString(KEY_DISPLAY_MODE)
                    .let { stored -> DisplayMode.entries.firstOrNull { it.name == stored } }
                    ?: default.displayMode,
                // 老预设里没有这个字段，回落到默认（开），与 ConfigStore 废弃旧 key
                // 后统一按新默认起步的口径一致
                antiMistouchEnabled = json.optBoolean(
                    KEY_ANTI_MISTOUCH,
                    default.antiMistouchEnabled,
                ),
            ),
        )
    }

    private const val KEY_NAME = "name"
    private const val KEY_BINDINGS = "bindings"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_THEME = "themeMode"
    private const val KEY_LYRICS = "lyricsEnabled"
    private const val KEY_LYRICS_ALIGN = "lyricsAlignment"
    private const val KEY_DISPLAY_MODE = "displayMode"
    private const val KEY_ANTI_MISTOUCH = "antiMistouchEnabled"
}

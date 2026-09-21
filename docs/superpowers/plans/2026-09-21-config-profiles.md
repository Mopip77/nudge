# 配置预设（Profile）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前全部配置存成最多三个命名预设，可加载，并导出广播入口供三星「模式与日常安排」经 Tasker 中转自动切换。

**Architecture:** 一个预设是完整 `NudgeConfig` 快照 + 名字，序列化成 JSON 存进 DataStore 的三个固定 key。编解码抽成纯 Kotlin 的 `ProfileCodec`（无 Android 依赖，可 JVM 单测），`ConfigStore` 扩展三个方法，界面加「预设」区块，再加一个照 `MediaCommandReceiver` 写的 `ProfileCommandReceiver`。

**Tech Stack:** Kotlin, Jetpack Compose, Preferences DataStore, org.json, JUnit 4

## Global Constraints

- 每条 Gradle 命令必须显式带 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`，用仓库内 `./gradlew`
- Gradle 需要写 `~/.gradle`，跑构建命令时要关沙箱（`dangerouslyDisableSandbox: true`）
- 代码注释和文档用中文；注释写**为什么**，不写代码已表达的**是什么**
- 文档和注释里不写本机绝对路径，用 `<项目根目录>` 这类占位符（public repo）
- `ProfileCodec` **不得**引入任何 Android 类依赖，必须能在 JVM 上直接单测
- 本应用绝不注册自己的 MediaSession（本计划不涉及，但勿顺手添加）
- 枚举名以代码为准：`Sensitivity` 是 `LOOSE`/`STANDARD`/`STRICT`（**不是** `RELAXED`）；
  `ThemeMode` 是 `SYSTEM`/`LIGHT`/`DARK`；`ActionType` 是 `NEXT_TRACK`/`LIKE`
- 槽位号固定 `1..3`，删除后**不重排**——槽位号是广播协议的对外标识

### 对 spec 的一处修正

spec §五 写「加载后给一个 Snackbar」。实际设置页没有 `Scaffold`/`SnackbarHost`，
为一条提示重构整个设置页布局不划算。**改用 `Toast`**——`MainActivity` 已在用
（`MainActivity.kt:221`），和项目现有做法一致。其余设计不变。

---

### Task 1: ProfileCodec —— 预设的 JSON 编解码

纯 Kotlin，无 Android 依赖。这是整个功能唯一能自动化测试的核心，先做。

**Files:**
- Create: `app/src/main/java/com/nudge/app/config/ProfileCodec.kt`
- Test: `app/src/test/java/com/nudge/app/config/ProfileCodecTest.kt`

**Interfaces:**
- Consumes: `NudgeConfig`、`ActionType`、`ThemeMode`（`config/ConfigStore.kt`）；
  `Gesture`、`Sensitivity`（`gesture/Gesture.kt`）；
  `encodeGestures`/`decodeGestures`（`config/ConfigStore.kt`，已是 `internal`，同包可用）
- Produces:
  - `data class StoredProfile(val name: String, val config: NudgeConfig)`
  - `object ProfileCodec { fun encode(profile: StoredProfile): String;
    fun decode(raw: String?): StoredProfile? }`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/nudge/app/config/ProfileCodecTest.kt`：

```kotlin
package com.nudge.app.config

import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCodecTest {

    @Test
    fun `默认配置 round-trip 后相等`() {
        val original = StoredProfile("预设 1", NudgeConfig.DEFAULT)
        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    /**
     * 空绑定是「用户主动清空」，不是「没配过」。它必须能原样存取，
     * 否则加载预设后该动作会被静默恢复成默认绑定。
     */
    @Test
    fun `空绑定 round-trip 后仍为空集`() {
        val config = NudgeConfig.DEFAULT.copy(
            bindings = mapOf(
                ActionType.NEXT_TRACK to setOf(Gesture.DOUBLE_TAP),
                ActionType.LIKE to emptySet(),
            )
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(StoredProfile("夜跑", config)))
        assertEquals(emptySet<Gesture>(), decoded?.config?.bindings?.get(ActionType.LIKE))
    }

    @Test
    fun `一个动作绑多个手势 round-trip 后保持`() {
        val config = NudgeConfig.DEFAULT.copy(
            bindings = mapOf(
                ActionType.NEXT_TRACK to setOf(
                    Gesture.DOUBLE_TAP,
                    Gesture.TWO_FINGER_DOUBLE_TAP,
                    Gesture.THREE_FINGER_HOLD_TAP,
                ),
                ActionType.LIKE to setOf(Gesture.THREE_FINGER_DOUBLE_TAP),
            )
        )
        val original = StoredProfile("多绑", config)
        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    @Test
    fun `非默认的标量字段 round-trip 后保持`() {
        val config = NudgeConfig.DEFAULT.copy(
            sensitivity = Sensitivity.STRICT,
            themeMode = ThemeMode.DARK,
            lyricsEnabled = false,
            screenPinningEnabled = true,
        )
        val original = StoredProfile("严格夜间", config)
        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    // —— 以下为宽容解码：枚举重命名或数据损坏后不能崩 ——

    @Test
    fun `缺失字段回落默认值`() {
        val decoded = ProfileCodec.decode("""{"name":"只有名字"}""")
        assertEquals("只有名字", decoded?.name)
        assertEquals(NudgeConfig.DEFAULT, decoded?.config)
    }

    @Test
    fun `未知灵敏度名回落默认`() {
        val decoded = ProfileCodec.decode("""{"name":"x","sensitivity":"TURBO"}""")
        assertEquals(NudgeConfig.DEFAULT.sensitivity, decoded?.config?.sensitivity)
    }

    @Test
    fun `未知主题名回落默认`() {
        val decoded = ProfileCodec.decode("""{"name":"x","themeMode":"NEON"}""")
        assertEquals(NudgeConfig.DEFAULT.themeMode, decoded?.config?.themeMode)
    }

    /** 未知手势名逐个丢弃，已认识的仍保留——半坏的数据不该整条作废。 */
    @Test
    fun `未知手势名被丢弃而保留已知手势`() {
        val decoded = ProfileCodec.decode(
            """{"name":"x","bindings":{"NEXT_TRACK":"DOUBLE_TAP,FOUR_FINGER_SWIPE"}}"""
        )
        assertEquals(
            setOf(Gesture.DOUBLE_TAP),
            decoded?.config?.bindings?.get(ActionType.NEXT_TRACK),
        )
    }

    /** bindings 里没出现的动作要回落默认，而不是变成空集。 */
    @Test
    fun `bindings 缺某动作时该动作回落默认绑定`() {
        val decoded = ProfileCodec.decode(
            """{"name":"x","bindings":{"NEXT_TRACK":"DOUBLE_TAP"}}"""
        )
        assertEquals(
            NudgeConfig.DEFAULT.bindings[ActionType.LIKE],
            decoded?.config?.bindings?.get(ActionType.LIKE),
        )
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(ProfileCodec.decode("{ 这不是 json"))
    }

    @Test
    fun `空槽位的 null 与空串返回 null`() {
        assertNull(ProfileCodec.decode(null))
        assertNull(ProfileCodec.decode(""))
        assertNull(ProfileCodec.decode("   "))
    }

    /** 名字缺失或为空的记录视为无效槽位——没有名字就无法辨识，等于空槽。 */
    @Test
    fun `缺名字或空名字返回 null`() {
        assertNull(ProfileCodec.decode("""{"sensitivity":"STRICT"}"""))
        assertNull(ProfileCodec.decode("""{"name":"  "}"""))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'com.nudge.app.config.ProfileCodecTest'
```

Expected: 编译失败，报 `Unresolved reference: StoredProfile` 与 `Unresolved reference: ProfileCodec`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/nudge/app/config/ProfileCodec.kt`：

```kotlin
package com.nudge.app.config

import com.nudge.app.gesture.Gesture
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
            put(KEY_PINNING, profile.config.screenPinningEnabled)
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
                    .let { name -> Sensitivity.entries.firstOrNull { it.name == name } }
                    ?: default.sensitivity,
                themeMode = json.optString(KEY_THEME)
                    .let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: default.themeMode,
                lyricsEnabled = json.optBoolean(KEY_LYRICS, default.lyricsEnabled),
                screenPinningEnabled = json.optBoolean(KEY_PINNING, default.screenPinningEnabled),
            ),
        )
    }

    private const val KEY_NAME = "name"
    private const val KEY_BINDINGS = "bindings"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_THEME = "themeMode"
    private const val KEY_LYRICS = "lyricsEnabled"
    private const val KEY_PINNING = "screenPinningEnabled"
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'com.nudge.app.config.ProfileCodecTest'
```

Expected: BUILD SUCCESSFUL，13 个测试全过

若 `空绑定 round-trip 后仍为空集` 失败并报 org.json 相关的 "not mocked"，
检查 `app/build.gradle.kts:78` 的 `testImplementation("org.json:json:20231013")` 是否还在。
**不要**改用 `returnDefaultValues = true` —— 那会让所有未 mock 的 Android 调用静默返回 null。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/nudge/app/config/ProfileCodec.kt \
        app/src/test/java/com/nudge/app/config/ProfileCodecTest.kt
git commit -m "feat: 预设的 JSON 编解码，解码宽容回落默认"
```

---

### Task 2: ConfigStore 的预设读写

**Files:**
- Modify: `app/src/main/java/com/nudge/app/config/ConfigStore.kt`
- Test: `app/src/test/java/com/nudge/app/config/ProfileSlotTest.kt`

**Interfaces:**
- Consumes: `ProfileCodec`、`StoredProfile`（Task 1）
- Produces:
  - `const val PROFILE_SLOT_COUNT = 3`（顶层，`config` 包）
  - `data class ProfileSlot(val index: Int, val name: String?, val config: NudgeConfig?)`
    带 `val isEmpty: Boolean`
  - `fun parseSlotIndex(raw: String?): Int?`（顶层，`config` 包）—— Task 4 复用
  - `ConfigStore.profiles: Flow<List<ProfileSlot>>` —— 恒为 3 个元素，index 1..3
  - `suspend fun ConfigStore.saveProfile(index: Int, name: String, config: NudgeConfig)`
  - `suspend fun ConfigStore.readProfile(index: Int): StoredProfile?` —— 只读不应用
  - `suspend fun ConfigStore.loadProfile(index: Int): StoredProfile?` —— 应用并返回被应用的预设
  - `suspend fun ConfigStore.deleteProfile(index: Int)`

- [ ] **Step 1: 写失败的测试**

`ProfileSlot` 与 `parseSlotIndex` 是纯逻辑，可单测；DataStore 部分需真机验证。

创建 `app/src/test/java/com/nudge/app/config/ProfileSlotTest.kt`：

```kotlin
package com.nudge.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSlotTest {

    @Test
    fun `名字与配置俱在时不是空槽`() {
        val slot = ProfileSlot(1, "夜跑", NudgeConfig.DEFAULT)
        assertFalse(slot.isEmpty)
    }

    @Test
    fun `名字与配置为 null 时是空槽`() {
        assertTrue(ProfileSlot(2, null, null).isEmpty)
    }

    @Test
    fun `合法槽位号解析正确`() {
        assertEquals(1, parseSlotIndex("1"))
        assertEquals(2, parseSlotIndex("2"))
        assertEquals(3, parseSlotIndex("3"))
    }

    /** 外部工具传什么都有可能，越界与非数字一律返回 null 而非抛异常。 */
    @Test
    fun `越界或非法槽位号返回 null`() {
        assertNull(parseSlotIndex("0"))
        assertNull(parseSlotIndex("4"))
        assertNull(parseSlotIndex("-1"))
        assertNull(parseSlotIndex("abc"))
        assertNull(parseSlotIndex(""))
        assertNull(parseSlotIndex(null))
        assertNull(parseSlotIndex("1.0"))
        assertNull(parseSlotIndex("99999999999999999999"))
    }

    @Test
    fun `槽位号两侧空白被容忍`() {
        assertEquals(2, parseSlotIndex(" 2 "))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'com.nudge.app.config.ProfileSlotTest'
```

Expected: 编译失败，报 `Unresolved reference: ProfileSlot` 与 `Unresolved reference: parseSlotIndex`

- [ ] **Step 3: 写实现**

在 `ConfigStore.kt` 中，紧跟 `decodeGestures` 之后（`private val Context.dataStore` 之前）插入：

```kotlin
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
```

在 `ConfigStore` 类里，`setScreenPinningEnabled` 之后插入：

```kotlin
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
     * 五个字段**全部**写入，包括值等于默认值的项。不能做「等于默认就不写」的优化：
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
            prefs[SCREEN_PINNING_KEY] = config.screenPinningEnabled
        }
        return stored
    }

    suspend fun deleteProfile(index: Int) {
        require(index in 1..PROFILE_SLOT_COUNT) { "槽位号越界: $index" }
        context.dataStore.edit { it.remove(profileKey(index)) }
    }
```

在 `private companion object` 里，`bindingKey` 之后加一行：

```kotlin
        fun profileKey(index: Int) = stringPreferencesKey("profile_$index")
```

在文件顶部的 import 区补两个 import（按字母序插入现有 import 中）：

```kotlin
import kotlinx.coroutines.flow.first
```

`ProfileCodec`、`StoredProfile`、`ProfileSlot` 同包，无需 import。

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'com.nudge.app.config.ProfileSlotTest'
```

Expected: BUILD SUCCESSFUL，6 个测试全过

- [ ] **Step 5: 跑全量测试确认无回归**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
```

Expected: BUILD SUCCESSFUL。原有 75 个测试 + Task 1 的 13 个 + 本任务 6 个 = 94 个全过

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/nudge/app/config/ConfigStore.kt \
        app/src/test/java/com/nudge/app/config/ProfileSlotTest.kt
git commit -m "feat: ConfigStore 支持预设存取，加载时全量写入"
```

---

### Task 3: 设置页「预设」区块

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/ProfileSection.kt`
- Modify: `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/nudge/app/MainActivity.kt`

新建独立文件而不是继续堆进 `SettingsScreen.kt`：三个对话框加区块本身约 150 行，
`SettingsScreen.kt` 已 410 行，合进去会让它难以整体阅读。

**Interfaces:**
- Consumes: `ProfileSlot`、`PROFILE_SLOT_COUNT`（Task 2）；
  `SectionTitle`（`SettingsScreen.kt`，需从 `private` 改为 `internal`）
- Produces:
  - `@Composable fun ProfileSection(slots: List<ProfileSlot>, onSave: (Int, String) -> Unit,
    onLoad: (Int) -> Unit, onDelete: (Int) -> Unit)`
  - `SettingsScreen` 新增四个参数：`profiles: List<ProfileSlot>`、
    `onProfileSave: (Int, String) -> Unit`、`onProfileLoad: (Int) -> Unit`、
    `onProfileDelete: (Int) -> Unit`

- [ ] **Step 1: 把 SectionTitle 改成 internal 以便跨文件复用**

在 `SettingsScreen.kt:362`，把

```kotlin
@Composable
private fun SectionTitle(text: String) {
```

改成

```kotlin
@Composable
internal fun SectionTitle(text: String) {
```

- [ ] **Step 2: 写 ProfileSection**

创建 `app/src/main/java/com/nudge/app/ui/ProfileSection.kt`：

```kotlin
package com.nudge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.nudge.app.config.ProfileSlot

/** 待确认的对话框。null 表示无对话框。 */
private sealed interface ProfileDialog {
    /** 保存到空槽或覆盖已有，二者都要填名字，靠 [existingName] 区分文案。 */
    data class Save(val index: Int, val existingName: String?) : ProfileDialog
    data class Delete(val index: Int, val name: String) : ProfileDialog
}

/**
 * 预设区块。
 *
 * 不显示配置内容摘要：三项标量（灵敏度·主题·歌词）信息量低，手势绑定展开后又太长，
 * 折中出来的摘要两头不靠。让名字承担全部辨识职责，反而促使用户起个有意义的名字。
 */
@Composable
fun ProfileSection(
    slots: List<ProfileSlot>,
    onSave: (Int, String) -> Unit,
    onLoad: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

    SectionTitle("预设")
    Text(
        text = "保存当前全部配置，之后可一键切回。加载会覆盖当前配置。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
    )

    slots.forEach { slot ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slot.name ?: "槽位 ${slot.index}（空）",
                    fontSize = 15.sp,
                    fontWeight = if (slot.isEmpty) FontWeight.Normal else FontWeight.Medium,
                    color = if (slot.isEmpty) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )
            }
            if (slot.isEmpty) {
                TextButton(onClick = { dialog = ProfileDialog.Save(slot.index, null) }) {
                    Text("保存当前配置")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    // 加载不确认：它不销毁数据，当前配置随时能改回来，
                    // 且用户点加载本就是为了立刻看到效果
                    TextButton(onClick = { onLoad(slot.index) }) { Text("加载") }
                    TextButton(
                        onClick = { dialog = ProfileDialog.Save(slot.index, slot.name) }
                    ) { Text("覆盖") }
                    TextButton(
                        onClick = {
                            dialog = ProfileDialog.Delete(slot.index, slot.name.orEmpty())
                        }
                    ) { Text("删除") }
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit

        is ProfileDialog.Save -> SaveDialog(
            index = current.index,
            existingName = current.existingName,
            onConfirm = { name ->
                onSave(current.index, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is ProfileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("删除「${current.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(current.index)
                    dialog = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 保存／覆盖对话框。覆盖要二次确认——盲操下调好的一套手势配置被误点覆盖，
 * 代价远高于多点一次确认。
 */
@Composable
private fun SaveDialog(
    index: Int,
    existingName: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existingName ?: "预设 $index") }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existingName == null) "保存到槽位 $index" else "覆盖「$existingName」？")
        },
        text = {
            Column {
                if (existingName != null) {
                    Text(
                        text = "当前配置将替换它，原内容无法恢复。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            // 名字是唯一的辨识手段，空名字存进去等于存了个认不出的槽位
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text(if (existingName == null) "保存" else "覆盖") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
```

- [ ] **Step 3: 接到 SettingsScreen**

在 `SettingsScreen.kt` 的参数列表里，`config: NudgeConfig` 之后插入：

```kotlin
    profiles: List<ProfileSlot>,
```

在 `onScreenPinningChange: (Boolean) -> Unit,` 之后插入：

```kotlin
    onProfileSave: (Int, String) -> Unit,
    onProfileLoad: (Int) -> Unit,
    onProfileDelete: (Int) -> Unit,
```

补 import：

```kotlin
import com.nudge.app.config.ProfileSlot
```

在「权限」区块的闭合 `}` 之后、`ActionType.entries.forEach` 之前插入调用。
加载预设会改掉下面所有区块的显示，放在最前符合「因在前、果在后」的阅读顺序：

```kotlin
        ProfileSection(
            slots = profiles,
            onSave = onProfileSave,
            onLoad = onProfileLoad,
            onDelete = onProfileDelete,
        )
```

- [ ] **Step 4: 接到 MainActivity**

在 `MainActivity.kt` 里 `configStore.config` 的 `collectAsState` 附近，加一个 profiles 的收集。
先确认现有写法：

```bash
ag -n 'collectAsState|configStore.config' app/src/main/java/com/nudge/app/MainActivity.kt
```

按现有 config 的收集方式加一行同构的（若 config 用
`configStore.config.collectAsState(initial = NudgeConfig.DEFAULT)`，则写）：

```kotlin
val profiles by configStore.profiles.collectAsState(
    initial = (1..PROFILE_SLOT_COUNT).map { ProfileSlot(it, null, null) }
)
```

初值必须是三个空槽而不是 `emptyList()`——否则首帧区块会是空白，随后才跳出三行。

在 `SettingsScreen(` 调用里，`config = ...` 之后加 `profiles = profiles,`，
并在 `onScreenPinningChange` 之后加三个回调：

```kotlin
                            onProfileSave = { index, name ->
                                scope.launch { configStore.saveProfile(index, name, config) }
                            },
                            onProfileLoad = { index ->
                                scope.launch {
                                    val loaded = configStore.loadProfile(index)
                                    if (loaded != null) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "已加载「${loaded.name}」",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            onProfileDelete = { index ->
                                scope.launch { configStore.deleteProfile(index) }
                            },
```

补 import：

```kotlin
import com.nudge.app.config.PROFILE_SLOT_COUNT
import com.nudge.app.config.ProfileSlot
```

`Toast` 与 `scope` 已在该文件中（`MainActivity.kt:8`、`rememberCoroutineScope`），无需重复引入。

- [ ] **Step 5: 编译验证**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

若报 `onProfileSave` 里的 `config` 未解析，说明 `SettingsScreen` 调用点处 config 变量
名不同，用该处实际的变量名替换。

- [ ] **Step 6: 跑全量测试**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
```

Expected: BUILD SUCCESSFUL，94 个测试全过

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/nudge/app/ui/ProfileSection.kt \
        app/src/main/java/com/nudge/app/ui/SettingsScreen.kt \
        app/src/main/java/com/nudge/app/MainActivity.kt
git commit -m "feat: 设置页预设区块，覆盖与删除二次确认"
```

---

### Task 4: 广播入口 ProfileCommandReceiver

照 `MediaCommandReceiver`（`action/MediaCommandReceiver.kt`）写，同一套协议风格。

**Files:**
- Create: `app/src/main/java/com/nudge/app/config/ProfileCommandReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ConfigStore.loadProfile`、`parseSlotIndex`（Task 2）
- Produces: `ProfileCommandReceiver.ACTION_PROFILE = "com.nudge.app.PROFILE"`、
  `EXTRA_SLOT = "slot"`

`parseSlotIndex` 的测试已在 Task 2 完成，本任务无新的可单测纯逻辑——
Receiver 主体是 Android 框架交互，靠真机验证（Step 4）。

- [ ] **Step 1: 写 Receiver**

创建 `app/src/main/java/com/nudge/app/config/ProfileCommandReceiver.kt`：

```kotlin
package com.nudge.app.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 预设切换的广播入口，供 Tasker / MacroDroid / Home Assistant / adb 调用。
 *
 * 三星「模式与日常安排」没有公开给第三方注册自定义动作的 API，对第三方应用只有
 * 「打开应用」，所以链路是 M&R → Tasker → 本广播。
 *
 * 不做 deep link Activity：它会把应用弹到前台，而「开车时 M&R 切到驾驶模式」
 * 这种场景下突然弹出全屏触摸板是危险的。广播不改变应用的可见性。
 *
 * 静态注册让界面未打开时也能接收。
 */
class ProfileCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROFILE) return

        val raw = intent.getStringExtra(EXTRA_SLOT)
        // 只按槽位号切，不支持按名字：名字是用户可改的字符串，
        // 写进 Tasker/M&R 配置后改个名就断了；槽位号固定 1..3，稳定
        val index = parseSlotIndex(raw)
        if (index == null) {
            Log.w(TAG, "忽略非法槽位号: $raw")
            return
        }

        try {
            // onReceive 返回后进程可能立即被回收，异步协程会来不及执行完，
            // 故在 10 秒的 onReceive 配额内同步等待。写 DataStore 是毫秒级操作。
            val loaded = runBlocking { ConfigStore(context).loadProfile(index) }
            if (loaded == null) {
                // 空槽位不做任何事也不提示——自动化触发时用户可能没看手机
                Log.w(TAG, "槽位 $index 为空，未切换")
            } else {
                Log.i(TAG, "已切换到槽位 $index：${loaded.name}")
            }
        } catch (e: Exception) {
            // DataStore 读写可能因磁盘或数据损坏失败，不能让进程崩溃
            Log.e(TAG, "切换预设失败", e)
        }
    }

    companion object {
        const val ACTION_PROFILE = "com.nudge.app.PROFILE"
        const val EXTRA_SLOT = "slot"
        private const val TAG = "NudgeProfileCommand"
    }
}
```

切换后不震动不弹 Toast：这是自动化触发的，用户可能根本没看手机。
只打 Log 供排查，和「盲操工具不该在启动时弹更新提示」是同一种克制。

- [ ] **Step 2: 注册到 Manifest**

在 `app/src/main/AndroidManifest.xml` 里，现有 `MediaCommandReceiver` 的 `</receiver>`
之后插入：

```xml
        <!-- 预设切换。三星「模式与日常安排」经 Tasker 中转调用，无需前台界面。 -->
        <receiver
            android:name=".config.ProfileCommandReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.nudge.app.PROFILE" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: 编译并跑全量测试**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug test
```

Expected: BUILD SUCCESSFUL，94 个测试全过

- [ ] **Step 4: 真机验证广播**

装上 debug 包，在设置页把槽位 1 存为「预设 1」（标准灵敏度）、
槽位 2 存为「宽松」（宽松灵敏度），槽位 3 留空。然后：

```bash
# 切到槽位 2，设置页的灵敏度应变为「宽松」
adb shell am broadcast -a com.nudge.app.PROFILE --es slot 2 \
  -n com.nudge.app/.config.ProfileCommandReceiver

# 切到空槽位 3，应无变化且不崩
adb shell am broadcast -a com.nudge.app.PROFILE --es slot 3 \
  -n com.nudge.app/.config.ProfileCommandReceiver

# 非法槽位号，应无变化且不崩
adb shell am broadcast -a com.nudge.app.PROFILE --es slot 9 \
  -n com.nudge.app/.config.ProfileCommandReceiver

# 看日志确认三次的判定
adb logcat -d -s NudgeProfileCommand
```

Expected: 第一条日志 `已切换到槽位 2：宽松`；第二条 `槽位 3 为空，未切换`；
第三条 `忽略非法槽位号: 9`。无 crash。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/nudge/app/config/ProfileCommandReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: 预设切换的广播入口，供三星模式经 Tasker 中转调用"
```

---

### Task 5: 文档

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: 确认 README 结构**

```bash
ag -n '^#|广播|Home Assistant' README.md | head -30
```

- [ ] **Step 2: 在 CLAUDE.md 补预设一节**

在「## 应用内更新」之前插入：

```markdown
## 配置预设

三个固定槽位，每个存一份完整 `NudgeConfig` 快照 + 名字，JSON 序列化进 DataStore
的 `profile_1/2/3`。编解码在 `ProfileCodec`，纯 Kotlin 无 Android 依赖，可 JVM 单测。

### 加载预设必须全量写入

`loadProfile` 要把五个配置项**全部**写进 DataStore，包括值等于默认值的项，
不能做「等于默认就不写」的优化。`bindings` 的读取侧口径是「没写过 key 才回落默认，
写过空串表示用户主动清空」——跳过写入会把用户存的空绑定静默恢复成默认绑定。

### 不记录「当前预设」

加载是一次性覆写，之后改配置与来源预设再无关系，三个槽位没有「选中」态。
若记录 activeProfile 并在改配置时自动写回，盲操下用户改了灵敏度就会静默污染存档，
和收藏那条 toggle 缺陷是同一类问题。「覆盖」是唯一的写回路径，且带二次确认。

### 槽位号固定 1..3，删除后不重排

槽位号是广播协议的对外标识，重排会让已配置好的自动化指向别的预设。
同理只支持按槽位号切换，不支持按名字——名字可改，改完外部配置就断了。

### 三星「模式与日常安排」需经 Tasker 中转

M&R 没有公开给第三方注册自定义动作的 API，对第三方应用只有「打开应用」。
链路是 M&R → Tasker/MacroDroid → `com.nudge.app.PROFILE` 广播 → nudge。

刻意不做 deep link Activity：它会把应用弹到前台，而「开车时切到驾驶模式」
这种场景下突然弹出全屏触摸板是危险的。广播不改变应用可见性。
```

- [ ] **Step 3: 在 README 补用法**

在 README 现有广播／Home Assistant 一节旁边（按 Step 1 看到的实际结构就近插入）加：

```markdown
### 预设切换

设置页可把当前全部配置存成最多三个命名预设。外部自动化工具可用广播切换：

```bash
adb shell am broadcast -a com.nudge.app.PROFILE --es slot 2 \
  -n com.nudge.app/.config.ProfileCommandReceiver
```

`slot` 取 `1`/`2`/`3`，对应设置页的三个槽位。空槽位不做任何操作。

三星「模式与日常安排」不支持直接调用第三方应用的自定义动作，需经 Tasker
或 MacroDroid 中转：让 M&R 的模式触发 Tasker 任务，由 Tasker 发送上述广播。
```

- [ ] **Step 4: 提交**

```bash
git add CLAUDE.md README.md
git commit -m "docs: 预设功能的约束与广播用法"
```

---

## 完成后的真机回归清单

自动化测试覆盖了编解码与槽位号解析，以下必须真机验证：

- [ ] **空绑定预设的往返**（防 Task 2 §loadProfile 那个坑回归）：
  把「收藏」的所有手势取消勾选 → 存为槽位 1 → 重新勾上三指双击 →
  加载槽位 1 → 断言「收藏」仍显示「未绑定」且无任何手势勾选
- [ ] **覆盖确认**：对已有槽位点「覆盖」，取消后内容不变；确认后内容更新为当前配置
- [ ] **删除确认**：取消后槽位仍在；确认后变回「槽位 N（空）」
- [ ] **加载即时生效**：加载一个主题不同的预设，设置页主题单选与整体配色立刻跟着变
- [ ] **加载的 Toast**：显示「已加载「<名字>」」
- [ ] **空名字**：对话框里清空名字后「保存」按钮应禁用
- [ ] **重启存活**：存三个预设 → 杀进程重开 → 三个槽位名字仍在
- [ ] **广播三态**：见 Task 4 Step 4 的三条 adb 命令与预期日志
- [ ] **界面未打开时的广播**：杀掉应用进程后发广播，再打开设置页确认配置已切换

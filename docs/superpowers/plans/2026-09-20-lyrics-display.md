# 实时歌词展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 nudge 主界面的触摸板区域下方，展示与播放进度同步的逐行滚动歌词，作为若隐若现的背景层。

**Architecture:** 用 `MediaMetadata.METADATA_KEY_MEDIA_ID`（即网易云真实歌曲 ID）直接查询网易云歌词接口，拿到 LRC 后用纯 Kotlin 解析器转为行列表，再由 Compose 层按 `TrackInfo.currentPositionMs()` 推算的播放位置滚动高亮。歌词层画在触摸层**内部底层**，不加任何 pointer 修饰符，因此完全不参与触摸。

**Tech Stack:** Kotlin + Jetpack Compose (BOM 2024.02.00)、`HttpURLConnection`（JDK 自带）、`org.json`（Android 内置）、JUnit 4。

## Global Constraints

这些约束来自设计文档，**每个任务都隐含包含**：

- **不引入任何新的第三方依赖**。HTTP 用 `java.net.HttpURLConnection`，JSON 用 `org.json.JSONObject`。不得添加 OkHttp / Retrofit / Gson / Moshi / kotlinx-serialization。
- **`LrcParser` 与 `LyricLine` 必须是纯 Kotlin，不 import 任何 `android.*` 类**。理由同 `GestureRecognizer`：解析边界条件多，必须能在 JVM 上单测。
- **绝不修改 `TrackpadScreen` 中 `pointerInteropFilter` 相关的任何代码**。歌词层不得添加任何 pointer 修饰符（`pointerInput` / `clickable` / `scrollable` / `pointerInteropFilter`）。
- **所有失败路径静默降级为"不显示歌词"**，绝不弹 Toast、Snackbar 或任何错误提示。
- 构建与测试命令**必须显式带 JAVA_HOME**：`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test`
- 代码注释用中文，注释写**为什么**而非**是什么**。
- 网络请求超时 5 秒（连接与读取各 5000ms）。
- 接口 URL 与请求头（缺 Referer 可能被拒）：
  ```
  https://music.163.com/api/song/lyric?id=<ID>&lv=1&kv=1&tv=-1
  User-Agent: Mozilla/5.0
  Referer:    https://music.163.com
  ```

## File Structure

| 文件 | 职责 | 新建/修改 |
|---|---|---|
| `app/src/main/java/com/nudge/app/lyrics/LyricLine.kt` | 数据类 + 按位置查找当前行索引。纯 Kotlin | 新建 |
| `app/src/main/java/com/nudge/app/lyrics/LrcParser.kt` | LRC 文本 → `List<LyricLine>`。纯 Kotlin | 新建 |
| `app/src/main/java/com/nudge/app/lyrics/LyricsState.kt` | 状态密封接口 | 新建 |
| `app/src/main/java/com/nudge/app/lyrics/LyricsFetcher.kt` | HTTP 请求 + 取 `lrc.lyric` | 新建 |
| `app/src/main/java/com/nudge/app/lyrics/LyricsRepository.kt` | 按 mediaId 组织拉取与解析 | 新建 |
| `app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt` | Compose 渲染层 | 新建 |
| `app/src/test/java/com/nudge/app/lyrics/LyricLineTest.kt` | 查找逻辑单测 | 新建 |
| `app/src/test/java/com/nudge/app/lyrics/LrcParserTest.kt` | 解析单测 | 新建 |
| `app/src/main/AndroidManifest.xml` | 加 INTERNET 权限 | 修改 |
| `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt` | 在触摸 Box 内嵌入歌词层 | 修改 |
| `app/src/main/java/com/nudge/app/MainActivity.kt` | 驱动歌词加载 | 修改 |

任务顺序：纯逻辑（可单测）→ 网络 → UI → 接线。前两个任务完全不碰 Android，风险最低。

---

### Task 1: LyricLine 数据类与当前行查找

**Files:**
- Create: `app/src/main/java/com/nudge/app/lyrics/LyricLine.kt`
- Test: `app/src/test/java/com/nudge/app/lyrics/LyricLineTest.kt`

**Interfaces:**
- Consumes: 无（第一个任务）
- Produces:
  - `data class LyricLine(val timeMs: Long, val text: String)`
  - `fun List<LyricLine>.indexAt(positionMs: Long): Int` — 返回当前应高亮的行索引；位置早于首行或列表为空时返回 `-1`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/nudge/app/lyrics/LyricLineTest.kt`：

```kotlin
package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricLineTest {

    private val lines = listOf(
        LyricLine(1000, "第一行"),
        LyricLine(3000, "第二行"),
        LyricLine(5000, "第三行"),
    )

    @Test
    fun `位置早于首行返回 -1`() {
        assertEquals(-1, lines.indexAt(0))
        assertEquals(-1, lines.indexAt(999))
    }

    @Test
    fun `恰好等于时间戳命中该行`() {
        assertEquals(0, lines.indexAt(1000))
        assertEquals(1, lines.indexAt(3000))
        assertEquals(2, lines.indexAt(5000))
    }

    @Test
    fun `位置在两行之间命中前一行`() {
        assertEquals(0, lines.indexAt(2999))
        assertEquals(1, lines.indexAt(4999))
    }

    @Test
    fun `位置晚于末行命中末行`() {
        assertEquals(2, lines.indexAt(999_999))
    }

    @Test
    fun `空列表返回 -1`() {
        assertEquals(-1, emptyList<LyricLine>().indexAt(1000))
    }

    @Test
    fun `负数位置返回 -1`() {
        assertEquals(-1, lines.indexAt(-500))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests '*LyricLineTest*'`
Expected: 编译失败，`Unresolved reference: LyricLine`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/nudge/app/lyrics/LyricLine.kt`：

```kotlin
package com.nudge.app.lyrics

/**
 * 一行歌词。[timeMs] 是这行开始演唱的时刻（相对歌曲开头）。
 *
 * 刻意不含 Android 依赖：解析与查找的边界条件多，必须能在 JVM 上单测。
 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 当前时刻应高亮的行索引，无则 -1。
 *
 * 前奏期间（位置早于首行）返回 -1 而非 0，避免第一行在没唱之前就亮着。
 * 列表已按时间升序（[LrcParser] 保证），用二分查找而非线性扫描——
 * 这个函数在滚动时会被高频调用。
 */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    if (isEmpty() || positionMs < first().timeMs) return -1

    var low = 0
    var high = size - 1
    var result = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests '*LyricLineTest*'`
Expected: BUILD SUCCESSFUL，6 个测试全过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/nudge/app/lyrics/LyricLine.kt app/src/test/java/com/nudge/app/lyrics/LyricLineTest.kt
git commit -m "feat: 歌词行数据类与当前行二分查找"
```

---

### Task 2: LRC 解析器

**Files:**
- Create: `app/src/main/java/com/nudge/app/lyrics/LrcParser.kt`
- Test: `app/src/test/java/com/nudge/app/lyrics/LrcParserTest.kt`

**Interfaces:**
- Consumes: `LyricLine`（Task 1）
- Produces: `object LrcParser { fun parse(raw: String): List<LyricLine> }` — 按时间升序；无有效行时返回空列表

**背景（真机实测的真实格式）：** 网易云返回的 LRC 毫秒部分实测均为 3 位（`[00:21.762]`），但 LRC 标准允许 2 位，且这是非公开接口，解析器需对两者都宽容。`tlyric` 中实测出现 `[by:Lvemiwxq]` 这类元信息行。歌词中实测有**空文本时间戳行**（如 `[00:03.000]`），它们代表停顿留白，**必须保留**——丢弃会让后续行的高亮时机错位。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/nudge/app/lyrics/LrcParserTest.kt`：

```kotlin
package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `解析标准三位毫秒`() {
        val result = LrcParser.parse("[00:21.762]素胚勾勒出青花笔锋浓转淡")
        assertEquals(1, result.size)
        assertEquals(21762L, result[0].timeMs)
        assertEquals("素胚勾勒出青花笔锋浓转淡", result[0].text)
    }

    @Test
    fun `解析两位毫秒按百分秒换算`() {
        val result = LrcParser.parse("[00:21.76]测试")
        assertEquals(21760L, result[0].timeMs)
    }

    @Test
    fun `解析无毫秒部分`() {
        val result = LrcParser.parse("[01:05]测试")
        assertEquals(65_000L, result[0].timeMs)
    }

    @Test
    fun `分钟数正确换算`() {
        val result = LrcParser.parse("[02:03.500]测试")
        assertEquals(123_500L, result[0].timeMs)
    }

    @Test
    fun `元信息行被忽略`() {
        val raw = """
            [by:Lvemiwxq]
            [00:10.000]真正的歌词
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(1, result.size)
        assertEquals("真正的歌词", result[0].text)
    }

    @Test
    fun `一行多时间戳展开为多行`() {
        val result = LrcParser.parse("[00:10.000][01:20.000]重复的副歌")
        assertEquals(2, result.size)
        assertEquals(10_000L, result[0].timeMs)
        assertEquals(80_000L, result[1].timeMs)
        assertTrue(result.all { it.text == "重复的副歌" })
    }

    @Test
    fun `空文本时间戳行保留`() {
        // 空行代表停顿留白，丢弃会让后续行高亮时机错位
        val raw = """
            [00:03.000]
            [00:10.000]歌词
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(2, result.size)
        assertEquals("", result[0].text)
    }

    @Test
    fun `结果按时间升序排列`() {
        val raw = """
            [00:30.000]第三
            [00:10.000]第一
            [00:20.000]第二
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), result.map { it.timeMs })
        assertEquals(listOf("第一", "第二", "第三"), result.map { it.text })
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   \n  \n ").isEmpty())
    }

    @Test
    fun `纯元信息无歌词返回空列表`() {
        assertTrue(LrcParser.parse("[by:someone]\n[ar:artist]").isEmpty())
    }

    @Test
    fun `畸形输入不抛异常`() {
        assertTrue(LrcParser.parse("[00:10.000 缺右括号").isEmpty())
        assertTrue(LrcParser.parse("[aa:bb.ccc]非数字").isEmpty())
        assertTrue(LrcParser.parse("没有任何时间戳的纯文本").isEmpty())
    }

    @Test
    fun `歌词文本两端空白被裁剪`() {
        val result = LrcParser.parse("[00:10.000]  有空格的歌词  ")
        assertEquals("有空格的歌词", result[0].text)
    }

    @Test
    fun `真实网易云片段完整解析`() {
        // 取自真机实测 id=185811《发如雪》的实际返回
        val raw = """
            [00:00.000] 作词 : 方文山
            [00:03.000]
            [00:21.762]素胚勾勒出青花笔锋浓转淡
            [00:26.224]瓶身描绘的牡丹一如你初妆
        """.trimIndent()
        val result = LrcParser.parse(raw)
        assertEquals(4, result.size)
        assertEquals("作词 : 方文山", result[0].text)
        assertEquals("", result[1].text)
        assertEquals(21762L, result[2].timeMs)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests '*LrcParserTest*'`
Expected: 编译失败，`Unresolved reference: LrcParser`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/nudge/app/lyrics/LrcParser.kt`：

```kotlin
package com.nudge.app.lyrics

/**
 * LRC 歌词解析。
 *
 * 刻意不含 Android 依赖，可在 JVM 上单测——真实 LRC 的边界情况很多
 * （毫秒位数不一、一行多时间戳、元信息行、空停顿行），靠真机手测不现实。
 */
object LrcParser {

    /**
     * 时间戳 `[mm:ss.SSS]`。毫秒部分可选且位数不定：
     * 网易云实测均为 3 位，但 LRC 标准允许 2 位，接口非公开故保持宽容。
     * 分隔符除 `.` 外也接受 `:`，某些歌词源会这么写。
     */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        for (line in raw.lineSequence()) {
            val tags = TIME_TAG.findAll(line).toList()
            // 无时间戳的行是元信息（[by:xxx]）或垃圾，直接丢弃
            if (tags.isEmpty()) continue

            // 文本是最后一个时间戳之后的部分：一行可能挂多个时间戳，
            // 表示同一句在多处重复出现
            val text = line.substring(tags.last().range.last + 1).trim()

            for (tag in tags) {
                val timeMs = toMillis(tag) ?: continue
                result.add(LyricLine(timeMs, text))
            }
        }

        // 一行多时间戳展开后顺序是乱的，且个别歌词源本身不保证有序，
        // 而 indexAt 的二分查找依赖升序
        return result.sortedBy { it.timeMs }
    }

    private fun toMillis(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]

        // 两位是百分秒（.76 = 760ms），三位才是毫秒，按位数补零而非直接相加
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes * 60_000 + seconds * 1000 + millis
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests '*LrcParserTest*'`
Expected: BUILD SUCCESSFUL，13 个测试全过

- [ ] **Step 5: 跑全量测试确认无回归**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test`
Expected: BUILD SUCCESSFUL，原有 30 个测试 + 新增 19 个全过

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/nudge/app/lyrics/LrcParser.kt app/src/test/java/com/nudge/app/lyrics/LrcParserTest.kt
git commit -m "feat: LRC 歌词解析器"
```

---

### Task 3: 歌词状态与网络获取

**Files:**
- Create: `app/src/main/java/com/nudge/app/lyrics/LyricsState.kt`
- Create: `app/src/main/java/com/nudge/app/lyrics/LyricsFetcher.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `LyricLine`（Task 1）
- Produces:
  - `sealed interface LyricsState`，成员：`LyricsState.Idle`、`LyricsState.Loading`、`LyricsState.Loaded(val lines: List<LyricLine>)`、`LyricsState.Unavailable`
  - `object LyricsFetcher { fun fetchLrc(songId: String): String? }` — 返回原始 LRC 文本，任何失败返回 `null`。**阻塞调用，必须在 IO 线程执行**

本任务无单测：纯网络 IO，真机验证。

- [ ] **Step 1: 加 INTERNET 权限**

修改 `app/src/main/AndroidManifest.xml`，在现有 `VIBRATE` 权限下方加一行：

```xml
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: 写状态定义**

创建 `app/src/main/java/com/nudge/app/lyrics/LyricsState.kt`：

```kotlin
package com.nudge.app.lyrics

/**
 * 歌词加载状态。
 *
 * [Loading] 与 [Unavailable] 在 UI 上表现一致（都不显示任何东西），
 * 区分它们只为便于调试——盲操 app 不该为"正在加载"这种事打扰用户。
 */
sealed interface LyricsState {
    /** 无歌曲在播 */
    data object Idle : LyricsState
    /** 请求中 */
    data object Loading : LyricsState
    data class Loaded(val lines: List<LyricLine>) : LyricsState
    /** 纯音乐、网络失败、接口无歌词——统一归为"没有歌词可显示" */
    data object Unavailable : LyricsState
}
```

- [ ] **Step 3: 写网络获取**

创建 `app/src/main/java/com/nudge/app/lyrics/LyricsFetcher.kt`：

```kotlin
package com.nudge.app.lyrics

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从网易云拉取歌词原文。
 *
 * 之所以能直接用歌曲 id 查（而不必按歌名搜索匹配），是因为真机实测
 * MediaMetadata 的 METADATA_KEY_MEDIA_ID 就是网易云的真实歌曲 id
 * （已核对 song/detail 返回的歌名、歌手、时长逐项一致）。
 * 这让歌词与音频天然同源，不存在版本不符导致的时间轴偏移。
 *
 * 用 HttpURLConnection 而非 OkHttp：只有这一个请求，不值得为它引入依赖。
 */
object LyricsFetcher {

    private const val TIMEOUT_MS = 5000

    /** 阻塞调用，必须在 IO 线程执行。任何失败返回 null，调用方降级为无歌词。 */
    fun fetchLrc(songId: String): String? {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // 缺 Referer 时接口可能拒绝返回
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://music.163.com")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
                .optJSONObject("lrc")
                ?.optString("lyric")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 无网络、超时、JSON 结构变化都走这里。静默失败，不打扰盲操。
            null
        } finally {
            connection?.disconnect()
        }
    }
}
```

- [ ] **Step 4: 编译确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/nudge/app/lyrics/LyricsState.kt app/src/main/java/com/nudge/app/lyrics/LyricsFetcher.kt app/src/main/AndroidManifest.xml
git commit -m "feat: 歌词状态定义与网易云歌词接口获取"
```

---

### Task 4: 歌词仓库

**Files:**
- Create: `app/src/main/java/com/nudge/app/lyrics/LyricsRepository.kt`

**Interfaces:**
- Consumes: `LyricsFetcher.fetchLrc`、`LrcParser.parse`、`LyricsState`（Task 2、3）
- Produces: `object LyricsRepository { suspend fun load(mediaId: String): LyricsState }` — 内部切 IO 线程；`mediaId` 为空或非纯数字时直接返回 `Unavailable` 且不发请求

- [ ] **Step 1: 写实现**

创建 `app/src/main/java/com/nudge/app/lyrics/LyricsRepository.kt`：

```kotlin
package com.nudge.app.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词加载入口。
 *
 * 不做缓存：实际使用中很少重复听同一首歌，缓存收益低，
 * 不值得引入存储与失效逻辑。一次请求仅几 KB。
 */
object LyricsRepository {

    /**
     * 按歌曲 id 取歌词。失败一律返回 [LyricsState.Unavailable]，绝不抛异常。
     *
     * 调用方需保证在歌曲切换时取消上一次调用，否则可能把旧歌的歌词
     * 显示到新歌上（用 LaunchedEffect(mediaId) 天然满足）。
     */
    suspend fun load(mediaId: String): LyricsState = withContext(Dispatchers.IO) {
        // 非网易云播放器的 mediaId 不是数字 id，查了也没意义，不浪费一次请求
        if (mediaId.isBlank() || !mediaId.all { it.isDigit() }) {
            return@withContext LyricsState.Unavailable
        }

        val raw = LyricsFetcher.fetchLrc(mediaId)
            ?: return@withContext LyricsState.Unavailable

        val lines = LrcParser.parse(raw)
        // 纯音乐的歌词字段可能只有元信息行，解析后为空
        if (lines.isEmpty()) LyricsState.Unavailable else LyricsState.Loaded(lines)
    }
}
```

- [ ] **Step 2: 编译确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/nudge/app/lyrics/LyricsRepository.kt
git commit -m "feat: 歌词仓库，串联获取与解析"
```

---

### Task 5: 歌词渲染层

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt`

**Interfaces:**
- Consumes: `LyricsState`、`LyricLine`、`indexAt`（Task 1、3）、`TrackInfo`（现有）
- Produces: `@Composable fun LyricsOverlay(state: LyricsState, track: TrackInfo?, modifier: Modifier = Modifier)`

**关键约束：**

1. **不得添加任何 pointer 修饰符**。这个 composable 只负责画。
2. **驱动滚动的 tick 状态必须留在本组件内部**，不得提升到 `TrackpadScreen`。Compose 只重组读取了变化状态的作用域，状态留在这里，触摸层就不会因歌词刷新而重组。
3. **当前行索引用 `derivedStateOf`**，让重组只在行号真正变化时发生，而非每个 tick（一行歌词通常持续数秒，实际重组频率远低于 tick 频率）。

视觉参数（来自设计文档 §5.3）：当前行 alpha 0.85 / 17sp / Medium，相邻行 alpha 0.35 / 15sp，更远行 alpha 0.18 / 15sp；全部居中；当前行固定在容器垂直中央；切换动画 350ms `FastOutSlowInEasing`。

- [ ] **Step 1: 写实现**

创建 `app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt`：

```kotlin
package com.nudge.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.lyrics.indexAt
import com.nudge.app.media.TrackInfo
import kotlinx.coroutines.delay

/**
 * 歌词滚动的刷新间隔。
 *
 * 比进度条的 500ms 密，否则换行会明显滞后于演唱。这块区域同时是触摸板，
 * 高频刷新有拖慢手势响应的风险，故刷新只更新本组件内部的 tick 状态
 * （不提升到 TrackpadScreen），且当前行用 derivedStateOf 记忆化——
 * 实际重组只发生在行号变化时，一行歌词通常持续数秒。
 */
private const val LYRIC_TICK_MS = 100L

/** 一行歌词占的高度，决定滚动步长。 */
private val LINE_HEIGHT = 34.dp

/** 当前行上下各显示几行。取 3 是因为再多也会滚出容器且更淡到看不见。 */
private const val VISIBLE_NEIGHBORS = 3

/** 换行动画时长，"跟得上换行"与"看得出动效"的折中。 */
private const val SCROLL_ANIM_MS = 350

/**
 * 触摸板底下的歌词背景层。
 *
 * **纯展示，绝不参与触摸**：本组件不添加任何 pointer 修饰符，
 * 触摸层浮在其上完整接收手势。盲操场景下可交互的歌词会与手势语义冲突
 * （一次滑动到底是"跳转歌词"还是"切歌手势"？），故只读是长期设计。
 */
@Composable
fun LyricsOverlay(
    state: LyricsState,
    track: TrackInfo?,
    modifier: Modifier = Modifier,
) {
    val lines = (state as? LyricsState.Loaded)?.lines
    // 没歌词就什么都不画：加载中、纯音乐、网络失败表现一致，不打扰用户
    if (lines.isNullOrEmpty() || track == null) return

    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.mediaId, track.isPlaying) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(LYRIC_TICK_MS)
        }
    }

    // derivedStateOf 让下游只在行号真正变化时重组，而不是每个 tick 都重组。
    // key 必须含 track：它是普通参数不是 State，只 key lines 会让
    // lambda 一直捕获旧 track，切歌后进度推算仍按上一首算。
    val currentIndex by remember(lines, track) {
        derivedStateOf { lines.indexAt(track.currentPositionMs(nowMs)) }
    }

    // 前奏期间 indexAt 返回 -1，此时把第一行当作"即将唱的行"对齐到中央
    val anchorIndex = if (currentIndex < 0) 0 else currentIndex
    val lineHeightPx = with(LocalDensity.current) { LINE_HEIGHT.toPx() }
    val offsetY by animateFloatAsState(
        targetValue = -anchorIndex * lineHeightPx,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricScroll",
    )

    Box(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // graphicsLayer 的位移走绘制阶段，不触发重组
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationY = offsetY }
        ) {
            lines.forEachIndexed { index, line ->
                val distance = kotlin.math.abs(index - anchorIndex)
                // 超出可见范围的行不画，避免长歌词把上千个 Text 都组合出来
                if (distance > VISIBLE_NEIGHBORS) return@forEachIndexed

                val isCurrent = index == currentIndex
                LyricRow(text = line.text, isCurrent = isCurrent, distance = distance)
            }
        }
    }
}

@Composable
private fun LyricRow(text: String, isCurrent: Boolean, distance: Int) {
    val alpha = when {
        isCurrent -> 0.85f
        distance == 1 -> 0.35f
        else -> 0.18f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(SCROLL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )

    Text(
        text = text,
        textAlign = TextAlign.Center,
        fontSize = if (isCurrent) 17.sp else 15.sp,
        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .graphicsLayer { this.alpha = animatedAlpha },
    )
}
```

- [ ] **Step 2: 编译确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 确认没有引入 pointer 修饰符**

Run: `ag -n "pointerInput|clickable|scrollable|pointerInteropFilter" app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt`
Expected: 无任何输出（歌词层不参与触摸）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt
git commit -m "feat: 歌词滚动渲染层"
```

---

### Task 6: 接线到主界面

**Files:**
- Modify: `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt`
- Modify: `app/src/main/java/com/nudge/app/MainActivity.kt`

**Interfaces:**
- Consumes: `LyricsOverlay`（Task 5）、`LyricsRepository.load`（Task 4）、`LyricsState`（Task 3）
- Produces: 无（终端任务）

**关键：** `LyricsOverlay` 必须放在触摸 `Box` 的**内部、最底层**——即在 `Box` 的内容里作为第一个子元素。`Box` 自身的 `.pointerInteropFilter` 修饰符保持原样不动。

- [ ] **Step 1: 给 TrackpadScreen 加 lyricsState 参数**

修改 `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt` 的函数签名：

```kotlin
@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
fun TrackpadScreen(
    track: TrackInfo?,
    config: NudgeConfig,
    hasPermission: Boolean,
    lyricsState: LyricsState,
    onGesture: (Gesture) -> Unit,
    onOpenSettings: () -> Unit,
) {
```

并在文件顶部 import 区加入：

```kotlin
import com.nudge.app.lyrics.LyricsState
```

- [ ] **Step 2: 在触摸 Box 内部底层嵌入歌词**

同文件中，找到触摸 `Box` 的内容部分（约 `TrackpadScreen.kt:135`）。

**注意**：`contentAlignment = Alignment.Center,` 在本文件中出现两次，另一处在 `AlbumArt`（约 :319）。目标是紧跟在 `.pointerInteropFilter { ... }` 之后的那一处。当前是：

```kotlin
            contentAlignment = Alignment.Center,
        ) {
            if (!hasPermission) {
                Text(
                    text = "需要通知使用权才能控制播放\n点击右上角设置授予",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
        }
```

改为（歌词作为第一个子元素，即最底层）：

```kotlin
            contentAlignment = Alignment.Center,
        ) {
            // 歌词画在最底层，触摸事件由外层 Box 的 pointerInteropFilter 接收，
            // 本层不加任何 pointer 修饰符，故不影响手势识别
            LyricsOverlay(state = lyricsState, track = track)

            if (!hasPermission) {
                Text(
                    text = "需要通知使用权才能控制播放\n点击右上角设置授予",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
        }
```

- [ ] **Step 3: 在 MainActivity 驱动歌词加载**

修改 `app/src/main/java/com/nudge/app/MainActivity.kt`，在 import 区加入：

```kotlin
import com.nudge.app.lyrics.LyricsRepository
import com.nudge.app.lyrics.LyricsState
```

在 `var hasPermission by remember { ... }` 这一行下方，加入歌词状态：

```kotlin
            var lyricsState by remember { mutableStateOf<LyricsState>(LyricsState.Idle) }
```

在轮询播放状态的 `LaunchedEffect(Unit) { ... }` 之后，加入歌词加载：

```kotlin
            // 歌曲变化时重新拉歌词。以 mediaId 为 key，切歌会自动取消上一次
            // 未完成的请求，避免旧歌词错配到新歌上。
            val mediaId = track?.mediaId
            LaunchedEffect(mediaId) {
                lyricsState = if (mediaId.isNullOrBlank()) {
                    LyricsState.Idle
                } else {
                    LyricsState.Loading
                    LyricsRepository.load(mediaId)
                }
            }
```

- [ ] **Step 4: 把状态传给 TrackpadScreen**

同文件中，找到 **`TrackpadScreen(`** 调用（约 `MainActivity.kt:113`），在其 `hasPermission = hasPermission,` 下方加一行。

**注意**：`hasPermission = hasPermission,` 出现两次，另一处属于 `SettingsScreen(`（约 :91）——改错地方会编译失败。确认上方三行是 `TrackpadScreen(` / `track = track,` / `config = config,` 再动手：

```kotlin
                            lyricsState = lyricsState,
```

- [ ] **Step 5: 编译确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 确认手势代码未被改动**

Run: `git diff app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt | ag "^[-+].*pointerInteropFilter|^-.*recognizer|^-.*handleMotionEvent"`
Expected: 无任何输出（证明手势链路零改动）

- [ ] **Step 7: 跑全量测试**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test`
Expected: BUILD SUCCESSFUL，全部测试通过

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt app/src/main/java/com/nudge/app/MainActivity.kt
git commit -m "feat: 歌词层接入主界面"
```

---

### Task 7: 真机验证

**Files:** 无代码改动（除非发现问题）

本任务无法自动化，必须在真机执行。设备：三星 SM-G9810，需已安装并授予通知使用权，网易云正在播放。

- [ ] **Step 1: 安装到真机**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nudge.app/.MainActivity
```
Expected: `Success`，app 启动

- [ ] **Step 2: 验证歌词显示与同步**

在网易云播放一首**有歌词的歌**（如《发如雪》），观察 nudge 主界面。

Expected:
- 触摸板区域底部浮现歌词，居中对齐
- 当前行明显比上下行清晰
- 歌词与演唱同步，无明显偏移（容忍 ±0.5 秒）
- 换行时是平滑过渡而非瞬切

- [ ] **Step 3: 手势回归测试（最关键）**

这是本功能唯一的真实性能风险点。在歌词正在滚动时，逐一测试所有已绑定手势各 5 次。

Expected: 识别成功率与开启歌词前**无差异**，无触摸卡顿或延迟感。

**若发现手势变迟钝**：把 `LyricsOverlay.kt` 中的 `LYRIC_TICK_MS` 从 100 改为 200，重新验证。滚动平滑度让位于手势可靠性——这是设计文档明确的取舍。

- [ ] **Step 4: 验证切歌**

执行「下一首」手势若干次，含快速连续切歌。

Expected: 歌词跟随更新为新歌；快速连切时不出现"显示上一首歌词"的错配。

- [ ] **Step 5: 验证降级路径**

依次测试：
1. 播放一首**纯音乐**（无歌词）
2. 打开飞行模式后切歌

Expected: 两种情况下均**静默不显示歌词**，无任何错误提示，app 其余功能（手势、切歌、收藏）正常可用。

- [ ] **Step 6: 验证暂停**

暂停播放。

Expected: 歌词停止滚动；恢复播放后歌词位置正确，无跳跃或错位。

- [ ] **Step 7: 若有参数调整则提交**

仅在前述步骤中调整了视觉或性能参数时执行：

```bash
git add app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt
git commit -m "fix: 根据真机实测调整歌词显示参数"
```

- [ ] **Step 8: 更新 CLAUDE.md**

在 `CLAUDE.md` 的「架构」小节的数据流图中，`MediaControlRepository` 那一行下方补充歌词链路：

```
LyricsRepository ──► LyricsFetcher (music.163.com) ──► LrcParser
                     用 MediaMetadata 的 MEDIA_ID 直接查，歌词与音频同源
```

并在「三条不能违反的约束」之后新增一节：

```markdown
## 歌词层的约束

歌词是**纯展示背景层**，画在触摸板 Box 的内部底层，**不得添加任何 pointer 修饰符**
（`pointerInput` / `clickable` / `scrollable`）。盲操场景下可交互的歌词会与手势语义
冲突——一次滑动究竟是"跳转歌词"还是"切歌手势"无法区分。

歌词滚动的刷新频率（`LYRIC_TICK_MS`）高于进度条，而这块区域同时是触摸板。
tick 状态刻意留在 `LyricsOverlay` 内部不上提，当前行用 `derivedStateOf` 记忆化，
这样歌词刷新不会触发触摸层重组。**若真机上手势变迟钝，降低刷新频率**——
滚动平滑度让位于手势可靠性。

歌词能直接用 `METADATA_KEY_MEDIA_ID` 查询，是因为真机实测确认它就是网易云的
真实歌曲 id（已核对 song/detail 的歌名、歌手、时长逐项一致）。因此歌词与音频
同源，不需要按歌名搜索匹配，也不存在版本不符导致的时间轴偏移。
```

```bash
git add CLAUDE.md
git commit -m "docs: 补充歌词层架构与约束"
```

---

## Self-Review

**Spec 覆盖检查：**

| 设计文档要求 | 对应任务 |
|---|---|
| §2.1 用 MEDIA_ID 直接查，不搜索匹配 | Task 3（`fetchLrc`）、Task 4（数字校验） |
| §2.2 歌词接口与请求头 | Task 3 |
| §3 五个新文件 + 纯 Kotlin 解析器 | Task 1–5 |
| §4 状态模型四态 | Task 3 |
| §4 切歌取消旧请求 | Task 6 Step 3（`LaunchedEffect(mediaId)`） |
| §5.1 歌词不参与触摸 | Task 5 Step 3、Task 6 Step 6（均有验证命令） |
| §5.2 重组隔离三条措施 | Task 5（tick 内部化、`derivedStateOf`、`graphicsLayer`） |
| §5.2 手势回归验收 | Task 7 Step 3（含降频退路） |
| §5.3 视觉参数 | Task 5 `LyricRow` |
| §5.4 不做缓存 | Task 4 注释明确 |
| §5.5 只加 INTERNET、不引依赖 | Task 3 Step 1、Global Constraints |
| §6 全部失败路径静默降级 | Task 3（`fetchLrc` 返回 null）、Task 4、Task 5（空则不画） |
| §7 单元测试 | Task 1（6 个）、Task 2（13 个） |
| §7 真机验证 5 项 | Task 7 Step 2–6 |
| §8 不做逐字/翻译/缓存/交互 | 无对应任务（正确，本就不做） |

无遗漏。

**占位符扫描：** 无 TBD/TODO，每个代码步骤均含完整可粘贴的代码。

**类型一致性核对：**
- `LyricLine(timeMs, text)` — Task 1 定义，Task 2、5 使用，一致
- `List<LyricLine>.indexAt(Long): Int` — Task 1 定义，Task 5 使用，一致
- `LrcParser.parse(String): List<LyricLine>` — Task 2 定义，Task 4 使用，一致
- `LyricsFetcher.fetchLrc(String): String?` — Task 3 定义，Task 4 使用，一致
- `LyricsRepository.load(String): LyricsState` — Task 4 定义，Task 6 使用，一致
- `LyricsState` 四个成员名 — Task 3 定义，Task 4、5、6 使用，一致
- `LyricsOverlay(state, track, modifier)` — Task 5 定义，Task 6 以 `state =`/`track =` 具名调用，一致
- `TrackInfo.currentPositionMs(Long)`、`.mediaId`、`.isPlaying` — 现有代码已有，Task 5、6 使用，一致

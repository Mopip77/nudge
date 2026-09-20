# nudge 手势盲操音乐控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Android 前台应用，把整屏当触控板，用多指复合手势盲操控制音乐的「下一首」和「网易云收藏」。

**Architecture:** 分层设计。`GestureRecognizer` 是纯 Kotlin 状态机（无 Android UI 依赖，可在 JVM 单元测试），把 MotionEvent 序列解析为 Gesture 枚举；`MediaControlRepository` 封装 MediaSession 控制；`ActionDispatcher` 把手势按配置表映射到动作并触发震动反馈；Compose UI 层负责采集原始触摸事件和展示播放状态。

**Tech Stack:** Kotlin 1.9.22 + Jetpack Compose + DataStore + MediaSession + JUnit

## Global Constraints

以下约束来自 spec，适用于所有任务：

- **nudge 自身绝不注册 MediaSession**——否则会抢走媒体按键，导致 `dispatchMediaKeyEvent` 回退路径失效
- **收藏必须「先读后写」**——`setRating` 实测为 toggle，必须先读 `METADATA_KEY_USER_RATING.hasHeart()`，仅在未收藏时才调用，实现「只点亮，永不取消」
- 网易云包名：`com.netease.cloudmusic`
- 红心 custom action id：`com.netease.cloudmusic.STAR`（运行时按 name 匹配 `like` 动态查找，不硬编码）
- minSdk 26 / targetSdk 34 / compileSdk 34
- AGP 8.1.4 / Gradle 8.2 / Kotlin 1.9.22 / Compose Compiler 1.5.10
- 构建必须显式设置 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`（系统默认 JDK 为 8）
- 手势采集使用 `pointerInteropFilter` 获取原始 MotionEvent，不使用 Compose 高层手势 API
- 所有构建命令需在沙箱外执行（Gradle daemon 需要本地 TCP 连接）

### 标准构建命令

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$JAVA_HOME/bin:$PATH
unset JAVA_TOOL_OPTIONS
cd /Users/bjhl/project/mine/nudge
```

## File Structure

| 文件 | 职责 |
|---|---|
| `settings.gradle.kts` | Gradle 项目配置 |
| `build.gradle.kts` | 根构建脚本，插件版本 |
| `app/build.gradle.kts` | 应用模块构建配置 |
| `app/src/main/AndroidManifest.xml` | 清单，声明 Activity 和 NotificationListenerService |
| `app/src/main/java/com/nudge/app/gesture/Gesture.kt` | Gesture 枚举、Sensitivity 档位与参数映射 |
| `app/src/main/java/com/nudge/app/gesture/TouchEvent.kt` | 与 Android 解耦的触摸事件数据类 |
| `app/src/main/java/com/nudge/app/gesture/GestureRecognizer.kt` | 手势识别状态机（纯 Kotlin） |
| `app/src/main/java/com/nudge/app/media/TrackInfo.kt` | 播放信息数据类 |
| `app/src/main/java/com/nudge/app/media/NudgeNotificationListener.kt` | 换取通知使用权 |
| `app/src/main/java/com/nudge/app/media/MediaControlRepository.kt` | 媒体控制封装 |
| `app/src/main/java/com/nudge/app/action/ActionDispatcher.kt` | 手势→动作映射与震动反馈 |
| `app/src/main/java/com/nudge/app/config/ConfigStore.kt` | DataStore 配置持久化 |
| `app/src/main/java/com/nudge/app/ui/theme/Theme.kt` | 白天/夜间主题 |
| `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt` | 主界面触控板 |
| `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt` | 设置页 |
| `app/src/main/java/com/nudge/app/MainActivity.kt` | 入口 Activity |
| `app/src/test/java/com/nudge/app/gesture/GestureRecognizerTest.kt` | 手势识别单元测试（重点） |

---

### Task 1: 项目脚手架与 Compose 工具链验证

建立可构建的空项目，验证 Compose 工具链在本机可用。这一步必须先于所有编码——如果 Compose 编译器版本配错，后续所有任务都会被阻塞。

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/java/com/nudge/app/MainActivity.kt`
- Create: `gradle.properties`
- Create: `local.properties`

**Interfaces:**
- Consumes: 无
- Produces: 可构建的 Gradle 项目，`MainActivity` 作为应用入口

- [ ] **Step 1: 创建 Gradle 配置文件**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "nudge"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`local.properties`:
```properties
sdk.dir=/Users/bjhl/Library/Android/sdk
```

- [ ] **Step 2: 创建应用模块构建脚本**

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nudge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nudge.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

- [ ] **Step 3: 创建清单与主题**

`app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Nudge" parent="android:Theme.Material.NoActionBar" />
</resources>
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:label="nudge"
        android:supportsRtl="true"
        android:theme="@style/Theme.Nudge">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.Nudge">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

- [ ] **Step 4: 创建最小 MainActivity**

`app/src/main/java/com/nudge/app/MainActivity.kt`:
```kotlin
package com.nudge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("nudge")
                }
            }
        }
    }
}
```

- [ ] **Step 5: 构建验证 Compose 工具链**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle assembleDebug
```
Expected: `BUILD SUCCESSFUL`。首次构建需下载 Compose 依赖，可能耗时数分钟。
若报 Compose 编译器版本不匹配，检查 `kotlinCompilerExtensionVersion` 是否为 `1.5.10`（对应 Kotlin 1.9.22）。

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res app/src/main/java/com/nudge/app/MainActivity.kt
git commit -m "feat: 项目脚手架，验证 Compose 工具链"
```

---

### Task 2: 手势数据类型与灵敏度档位

定义整个手势系统的词汇表。纯数据，无逻辑，为后续状态机铺路。

**Files:**
- Create: `app/src/main/java/com/nudge/app/gesture/Gesture.kt`
- Create: `app/src/main/java/com/nudge/app/gesture/TouchEvent.kt`
- Test: `app/src/test/java/com/nudge/app/gesture/SensitivityTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class Gesture { DOUBLE_TAP, TWO_FINGER_DOUBLE_TAP, THREE_FINGER_DOUBLE_TAP, TWO_FINGER_HOLD_TAP, THREE_FINGER_HOLD_TAP }`
  - `enum class Sensitivity { LOOSE, STANDARD, STRICT }`，属性 `params: GestureParams`
  - `data class GestureParams(doubleTapWindowMs: Long, multiTouchSlopMs: Long, longPressMs: Long, moveToleranceDp: Float)`
  - `data class TouchEvent(type: TouchEventType, pointerId: Int, x: Float, y: Float, timeMs: Long, activePointerCount: Int)`
  - `enum class TouchEventType { DOWN, MOVE, UP, CANCEL }`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/nudge/app/gesture/SensitivityTest.kt`:
```kotlin
package com.nudge.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTest {

    @Test
    fun `宽松档参数符合 spec`() {
        val p = Sensitivity.LOOSE.params
        assertEquals(500L, p.doubleTapWindowMs)
        assertEquals(150L, p.multiTouchSlopMs)
        assertEquals(350L, p.longPressMs)
        assertEquals(40f, p.moveToleranceDp, 0.01f)
    }

    @Test
    fun `标准档参数符合 spec`() {
        val p = Sensitivity.STANDARD.params
        assertEquals(350L, p.doubleTapWindowMs)
        assertEquals(100L, p.multiTouchSlopMs)
        assertEquals(500L, p.longPressMs)
        assertEquals(24f, p.moveToleranceDp, 0.01f)
    }

    @Test
    fun `严格档参数符合 spec`() {
        val p = Sensitivity.STRICT.params
        assertEquals(250L, p.doubleTapWindowMs)
        assertEquals(60L, p.multiTouchSlopMs)
        assertEquals(700L, p.longPressMs)
        assertEquals(12f, p.moveToleranceDp, 0.01f)
    }

    @Test
    fun `档位越严格 容差越小 长按越久`() {
        assertTrue(Sensitivity.LOOSE.params.doubleTapWindowMs > Sensitivity.STRICT.params.doubleTapWindowMs)
        assertTrue(Sensitivity.LOOSE.params.moveToleranceDp > Sensitivity.STRICT.params.moveToleranceDp)
        assertTrue(Sensitivity.LOOSE.params.longPressMs < Sensitivity.STRICT.params.longPressMs)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest --tests '*SensitivityTest*'
```
Expected: 编译失败，`Unresolved reference: Sensitivity`

- [ ] **Step 3: 实现数据类型**

`app/src/main/java/com/nudge/app/gesture/Gesture.kt`:
```kotlin
package com.nudge.app.gesture

/** 支持的手势类型。 */
enum class Gesture(val displayName: String) {
    DOUBLE_TAP("双击"),
    TWO_FINGER_DOUBLE_TAP("两指双击"),
    THREE_FINGER_DOUBLE_TAP("三指双击"),
    TWO_FINGER_HOLD_TAP("两指长按 + 一指单击"),
    THREE_FINGER_HOLD_TAP("三指长按 + 一指单击"),
}

/**
 * 手势判定参数。
 *
 * @param doubleTapWindowMs 双击两次点击之间的最大间隔
 * @param multiTouchSlopMs 判定「同时按下」的时间窗
 * @param longPressMs 长按阈值
 * @param moveToleranceDp 移动容差，超出即判定为滑动并取消手势
 */
data class GestureParams(
    val doubleTapWindowMs: Long,
    val multiTouchSlopMs: Long,
    val longPressMs: Long,
    val moveToleranceDp: Float,
)

/** 灵敏度档位。越严格越难误触，但也越难触发。 */
enum class Sensitivity(val displayName: String, val params: GestureParams) {
    LOOSE("宽松", GestureParams(500L, 150L, 350L, 40f)),
    STANDARD("标准", GestureParams(350L, 100L, 500L, 24f)),
    STRICT("严格", GestureParams(250L, 60L, 700L, 12f)),
}
```

`app/src/main/java/com/nudge/app/gesture/TouchEvent.kt`:
```kotlin
package com.nudge.app.gesture

enum class TouchEventType { DOWN, MOVE, UP, CANCEL }

/**
 * 与 Android 解耦的触摸事件。
 *
 * 之所以不直接用 MotionEvent，是为了让 GestureRecognizer 能在 JVM 上单元测试——
 * MotionEvent 无法在纯 JVM 环境构造。
 *
 * @param pointerId 手指标识，同一根手指从按下到抬起保持不变
 * @param activePointerCount 本事件发生后屏幕上的手指总数
 */
data class TouchEvent(
    val type: TouchEventType,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val timeMs: Long,
    val activePointerCount: Int,
)
```

- [ ] **Step 4: 运行测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest --tests '*SensitivityTest*'
```
Expected: `BUILD SUCCESSFUL`，4 个测试通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nudge/app/gesture app/src/test/java/com/nudge/app/gesture
git commit -m "feat: 手势数据类型与灵敏度档位"
```

---

### Task 3: 双击类手势识别（单指/两指/三指）

实现状态机的第一半：三种双击手势。这是整个项目逻辑最密集的部分，严格 TDD。

**Files:**
- Create: `app/src/main/java/com/nudge/app/gesture/GestureRecognizer.kt`
- Test: `app/src/test/java/com/nudge/app/gesture/GestureRecognizerTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `Gesture`, `Sensitivity`, `GestureParams`, `TouchEvent`, `TouchEventType`
- Produces:
  - `class GestureRecognizer(params: GestureParams, densityDpi: Float = 1f)`
  - `fun onTouchEvent(event: TouchEvent): Gesture?` —— 识别到手势时返回，否则返回 null
  - `fun reset()`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/nudge/app/gesture/GestureRecognizerTest.kt`:
```kotlin
package com.nudge.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手势识别测试。
 *
 * 辅助函数用「抬起手指数」的语义构造事件序列，避免手工计算 activePointerCount。
 * density 固定为 1f，因此 dp 与 px 数值相等，移动容差可直接用像素表达。
 */
class GestureRecognizerTest {

    private fun recognizer(s: Sensitivity = Sensitivity.STANDARD) =
        GestureRecognizer(s.params, densityDpi = 1f)

    /** 构造 n 指同时按下再同时抬起的一次「轻点」，返回识别结果（取最后一个非 null）。 */
    private fun GestureRecognizer.tap(
        fingers: Int,
        startTime: Long,
        downGapMs: Long = 10L,
        holdMs: Long = 50L,
        x: Float = 100f,
        y: Float = 100f,
    ): Gesture? {
        var result: Gesture? = null
        for (i in 0 until fingers) {
            val r = onTouchEvent(
                TouchEvent(TouchEventType.DOWN, i, x + i * 50, y, startTime + i * downGapMs, i + 1)
            )
            if (r != null) result = r
        }
        val upStart = startTime + fingers * downGapMs + holdMs
        for (i in 0 until fingers) {
            val r = onTouchEvent(
                TouchEvent(TouchEventType.UP, i, x + i * 50, y, upStart + i * downGapMs, fingers - i - 1)
            )
            if (r != null) result = r
        }
        return result
    }

    @Test
    fun `单指双击在窗口内触发`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
        assertEquals(Gesture.DOUBLE_TAP, r.tap(1, startTime = 200))
    }

    @Test
    fun `单指双击超时不触发`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
        // 标准档 doubleTapWindow=350ms，第二次点击在 500ms 后开始
        assertNull(r.tap(1, startTime = 600))
    }

    @Test
    fun `两指双击触发`() {
        val r = recognizer()
        assertNull(r.tap(2, startTime = 0))
        assertEquals(Gesture.TWO_FINGER_DOUBLE_TAP, r.tap(2, startTime = 200))
    }

    @Test
    fun `三指双击触发`() {
        val r = recognizer()
        assertNull(r.tap(3, startTime = 0))
        assertEquals(Gesture.THREE_FINGER_DOUBLE_TAP, r.tap(3, startTime = 250))
    }

    @Test
    fun `手指数不一致的两次点击不触发`() {
        val r = recognizer()
        assertNull(r.tap(2, startTime = 0))
        assertNull(r.tap(3, startTime = 200))
    }

    @Test
    fun `多指按下间隔超过同时性窗口则不算同时按下`() {
        val r = recognizer()
        // 标准档 multiTouchSlop=100ms，两指间隔 200ms
        assertNull(r.tap(2, startTime = 0, downGapMs = 200))
        assertNull(r.tap(2, startTime = 600, downGapMs = 200))
    }

    @Test
    fun `按下后移动超过容差则取消手势`() {
        val r = recognizer()
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1)))
        // 标准档 moveTolerance=24dp，density=1 故为 24px；移动 100px 远超容差
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.MOVE, 0, 200f, 100f, 20, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 200f, 100f, 40, 0)))
        // 第二次点击不应与被取消的第一次组成双击
        assertNull(r.tap(1, startTime = 100))
    }

    @Test
    fun `容差内的轻微抖动不取消手势`() {
        val r = recognizer()
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.MOVE, 0, 110f, 100f, 20, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 110f, 100f, 40, 0)))
        assertEquals(Gesture.DOUBLE_TAP, r.tap(1, startTime = 150))
    }

    @Test
    fun `单次点击不触发任何手势`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
    }

    @Test
    fun `CANCEL 事件重置状态`() {
        val r = recognizer()
        assertNull(r.tap(1, startTime = 0))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.CANCEL, 0, 100f, 100f, 100, 0)))
        assertNull(r.tap(1, startTime = 200))
    }

    @Test
    fun `宽松档允许更长的双击间隔`() {
        val r = recognizer(Sensitivity.LOOSE)
        assertNull(r.tap(1, startTime = 0))
        // 450ms 间隔在宽松档(500ms)内，在标准档(350ms)外
        assertEquals(Gesture.DOUBLE_TAP, r.tap(1, startTime = 450))
    }

    @Test
    fun `严格档拒绝标准档能接受的间隔`() {
        val r = recognizer(Sensitivity.STRICT)
        assertNull(r.tap(1, startTime = 0))
        // 300ms 间隔在标准档(350ms)内，在严格档(250ms)外
        assertNull(r.tap(1, startTime = 300))
    }

    @Test
    fun `长按单指后抬起不算双击的第一次点击`() {
        val r = recognizer()
        // 按住 800ms 远超长按阈值，不应计入双击序列
        assertNull(r.tap(1, startTime = 0, holdMs = 800))
        assertNull(r.tap(1, startTime = 1000))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest --tests '*GestureRecognizerTest*'
```
Expected: 编译失败，`Unresolved reference: GestureRecognizer`

- [ ] **Step 3: 实现状态机（双击部分）**

`app/src/main/java/com/nudge/app/gesture/GestureRecognizer.kt`:
```kotlin
package com.nudge.app.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * 手势识别状态机。
 *
 * 纯 Kotlin 实现，不依赖任何 Android 类，可在 JVM 上单元测试。
 * 非线程安全，调用方需保证串行调用（UI 线程天然满足）。
 *
 * @param params 判定参数，来自 [Sensitivity]
 * @param densityDpi 屏幕密度，用于把 dp 容差换算为像素；测试中传 1f
 */
class GestureRecognizer(
    private val params: GestureParams,
    private val densityDpi: Float,
) {
    private val moveTolerancePx = params.moveToleranceDp * densityDpi

    /** 当前这一「批」触摸的状态。一批 = 从首指按下到全部抬起。 */
    private var batchStartMs = 0L
    private var batchPeakFingers = 0
    private var batchFirstDownMs = 0L
    private var batchInvalid = false
    private val downPositions = mutableMapOf<Int, Pair<Float, Float>>()

    /** 上一批已完成的轻点，用于组成双击。 */
    private var lastTapFingers = 0
    private var lastTapEndMs = Long.MIN_VALUE

    fun reset() {
        batchStartMs = 0L
        batchPeakFingers = 0
        batchFirstDownMs = 0L
        batchInvalid = false
        downPositions.clear()
        lastTapFingers = 0
        lastTapEndMs = Long.MIN_VALUE
    }

    fun onTouchEvent(event: TouchEvent): Gesture? {
        return when (event.type) {
            TouchEventType.DOWN -> { onDown(event); null }
            TouchEventType.MOVE -> { onMove(event); null }
            TouchEventType.UP -> onUp(event)
            TouchEventType.CANCEL -> { reset(); null }
        }
    }

    private fun onDown(event: TouchEvent) {
        if (downPositions.isEmpty()) {
            batchStartMs = event.timeMs
            batchFirstDownMs = event.timeMs
            batchPeakFingers = 0
            batchInvalid = false
        }
        // 超出同时性窗口落下的手指，说明不是「同时按下」
        if (event.timeMs - batchFirstDownMs > params.multiTouchSlopMs) {
            batchInvalid = true
        }
        downPositions[event.pointerId] = event.x to event.y
        batchPeakFingers = max(batchPeakFingers, event.activePointerCount)
    }

    private fun onMove(event: TouchEvent) {
        val start = downPositions[event.pointerId] ?: return
        if (abs(event.x - start.first) > moveTolerancePx ||
            abs(event.y - start.second) > moveTolerancePx
        ) {
            batchInvalid = true
        }
    }

    private fun onUp(event: TouchEvent): Gesture? {
        downPositions.remove(event.pointerId)
        if (event.activePointerCount > 0) return null

        // 全部手指已抬起，这一批结束
        val fingers = batchPeakFingers
        val heldTooLong = event.timeMs - batchStartMs > params.longPressMs
        val valid = !batchInvalid && !heldTooLong && fingers in 1..3

        if (!valid) {
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return null
        }

        val withinWindow = batchStartMs - lastTapEndMs <= params.doubleTapWindowMs
        if (lastTapFingers == fingers && withinWindow) {
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return doubleTapFor(fingers)
        }

        lastTapFingers = fingers
        lastTapEndMs = event.timeMs
        return null
    }

    private fun doubleTapFor(fingers: Int): Gesture? = when (fingers) {
        1 -> Gesture.DOUBLE_TAP
        2 -> Gesture.TWO_FINGER_DOUBLE_TAP
        3 -> Gesture.THREE_FINGER_DOUBLE_TAP
        else -> null
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest --tests '*GestureRecognizerTest*'
```
Expected: `BUILD SUCCESSFUL`，13 个测试全部通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nudge/app/gesture/GestureRecognizer.kt app/src/test/java/com/nudge/app/gesture/GestureRecognizerTest.kt
git commit -m "feat: 双击类手势识别（单指/两指/三指）"
```

---

### Task 4: 长按+单击类手势识别

实现状态机的第二半。触发时机：第 N+1 指抬起瞬间即触发，不等长按手指抬起。

**Files:**
- Modify: `app/src/main/java/com/nudge/app/gesture/GestureRecognizer.kt`
- Modify: `app/src/test/java/com/nudge/app/gesture/GestureRecognizerTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `GestureRecognizer`
- Produces: 同一个类，新增对 `TWO_FINGER_HOLD_TAP` / `THREE_FINGER_HOLD_TAP` 的识别

- [ ] **Step 1: 追加失败的测试**

在 `GestureRecognizerTest.kt` 类内追加：
```kotlin
    /** 构造「N 指按住 + 第 N+1 指单击」序列。 */
    private fun GestureRecognizer.holdAndTap(
        holdFingers: Int,
        holdStartMs: Long,
        tapAtMs: Long,
        tapDurationMs: Long = 50L,
    ): Gesture? {
        var result: Gesture? = null
        for (i in 0 until holdFingers) {
            onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, holdStartMs + i * 10, i + 1))
        }
        val tapId = holdFingers
        onTouchEvent(
            TouchEvent(TouchEventType.DOWN, tapId, 400f, 300f, tapAtMs, holdFingers + 1)
        )
        val r = onTouchEvent(
            TouchEvent(TouchEventType.UP, tapId, 400f, 300f, tapAtMs + tapDurationMs, holdFingers)
        )
        if (r != null) result = r
        return result
    }

    @Test
    fun `两指长按加一指单击触发`() {
        val r = recognizer()
        // 标准档 longPressMs=500，第三指在 600ms 时点击
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.holdAndTap(holdFingers = 2, holdStartMs = 0, tapAtMs = 600)
        )
    }

    @Test
    fun `三指长按加一指单击触发`() {
        val r = recognizer()
        assertEquals(
            Gesture.THREE_FINGER_HOLD_TAP,
            r.holdAndTap(holdFingers = 3, holdStartMs = 0, tapAtMs = 700)
        )
    }

    @Test
    fun `长按时长不足则单击不触发`() {
        val r = recognizer()
        // 第三指在 200ms 点击，未达 500ms 长按阈值
        assertNull(r.holdAndTap(holdFingers = 2, holdStartMs = 0, tapAtMs = 200))
    }

    @Test
    fun `长按期间可连续单击多次触发`() {
        val r = recognizer()
        for (i in 0 until 2) {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, i * 10L, i + 1))
        }
        // 第一次单击
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2))
        )
        // 第二次单击（间隔超过 300ms 冷却期）
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 1000, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 1050, 2))
        )
    }

    @Test
    fun `冷却期内的重复单击被抑制`() {
        val r = recognizer()
        for (i in 0 until 2) {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, i * 10L, i + 1))
        }
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        assertEquals(
            Gesture.TWO_FINGER_HOLD_TAP,
            r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2))
        )
        // 紧接着再点（距上次触发仅 100ms，小于 300ms 冷却期）
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 700, 3))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 750, 2)))
    }

    @Test
    fun `长按的手指移动超容差则单击不触发`() {
        val r = recognizer()
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 0, 100f, 100f, 0, 1))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 1, 150f, 100f, 10, 2))
        // 长按手指滑动 100px，超过 24px 容差
        r.onTouchEvent(TouchEvent(TouchEventType.MOVE, 0, 300f, 100f, 300, 2))
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2)))
    }

    @Test
    fun `四指长按加一指单击不触发任何手势`() {
        val r = recognizer()
        assertNull(r.holdAndTap(holdFingers = 4, holdStartMs = 0, tapAtMs = 700))
    }

    @Test
    fun `长按加单击后所有手指抬起不产生双击误判`() {
        val r = recognizer()
        for (i in 0 until 2) {
            r.onTouchEvent(TouchEvent(TouchEventType.DOWN, i, 100f + i * 50, 100f, i * 10L, i + 1))
        }
        r.onTouchEvent(TouchEvent(TouchEventType.DOWN, 2, 400f, 300f, 600, 3))
        r.onTouchEvent(TouchEvent(TouchEventType.UP, 2, 400f, 300f, 650, 2))
        // 长按的两指抬起，不应再产生手势
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 0, 100f, 100f, 700, 1)))
        assertNull(r.onTouchEvent(TouchEvent(TouchEventType.UP, 1, 150f, 100f, 710, 0)))
    }
```

- [ ] **Step 2: 运行测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest --tests '*GestureRecognizerTest*'
```
Expected: FAIL，新增的 8 个测试中至少 `两指长按加一指单击触发` 失败（期望 TWO_FINGER_HOLD_TAP，实际 null）

- [ ] **Step 3: 扩展状态机支持长按+单击**

完整替换 `GestureRecognizer.kt`：
```kotlin
package com.nudge.app.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * 手势识别状态机。
 *
 * 纯 Kotlin 实现，不依赖任何 Android 类，可在 JVM 上单元测试。
 * 非线程安全，调用方需保证串行调用（UI 线程天然满足）。
 *
 * 识别两类手势：
 * 1. N 指双击——N 指同时按下抬起两次
 * 2. N 指长按 + 一指单击——N 指按住超过阈值后，额外一指 down-up
 *
 * @param params 判定参数，来自 [Sensitivity]
 * @param densityDpi 屏幕密度，用于把 dp 容差换算为像素；测试中传 1f
 */
class GestureRecognizer(
    private val params: GestureParams,
    private val densityDpi: Float,
) {
    private val moveTolerancePx = params.moveToleranceDp * densityDpi

    /** 当前这一「批」触摸的状态。一批 = 从首指按下到全部抬起。 */
    private var batchStartMs = 0L
    private var batchPeakFingers = 0
    private var batchFirstDownMs = 0L
    private var batchInvalid = false
    private val downPositions = mutableMapOf<Int, Pair<Float, Float>>()
    private val downTimes = mutableMapOf<Int, Long>()

    /** 上一批已完成的轻点，用于组成双击。 */
    private var lastTapFingers = 0
    private var lastTapEndMs = Long.MIN_VALUE

    /** 长按 + 单击已触发过的时间，用于冷却期判定。 */
    private var lastHoldTapFireMs = Long.MIN_VALUE

    /** 本批中是否已经触发过长按+单击，用于避免收尾时误判为双击。 */
    private var batchProducedHoldTap = false

    fun reset() {
        batchStartMs = 0L
        batchPeakFingers = 0
        batchFirstDownMs = 0L
        batchInvalid = false
        batchProducedHoldTap = false
        downPositions.clear()
        downTimes.clear()
        lastTapFingers = 0
        lastTapEndMs = Long.MIN_VALUE
        lastHoldTapFireMs = Long.MIN_VALUE
    }

    fun onTouchEvent(event: TouchEvent): Gesture? {
        return when (event.type) {
            TouchEventType.DOWN -> { onDown(event); null }
            TouchEventType.MOVE -> { onMove(event); null }
            TouchEventType.UP -> onUp(event)
            TouchEventType.CANCEL -> { reset(); null }
        }
    }

    private fun onDown(event: TouchEvent) {
        if (downPositions.isEmpty()) {
            batchStartMs = event.timeMs
            batchFirstDownMs = event.timeMs
            batchPeakFingers = 0
            batchInvalid = false
            batchProducedHoldTap = false
        }
        // 超出同时性窗口落下的手指，说明不是「同时按下」。
        // 但如果已构成长按底座，额外落下的手指是单击而非同时按下，不应据此判无效。
        if (event.timeMs - batchFirstDownMs > params.multiTouchSlopMs && !isHoldBaseReady(event.timeMs)) {
            batchInvalid = true
        }
        downPositions[event.pointerId] = event.x to event.y
        downTimes[event.pointerId] = event.timeMs
        batchPeakFingers = max(batchPeakFingers, event.activePointerCount)
    }

    private fun onMove(event: TouchEvent) {
        val start = downPositions[event.pointerId] ?: return
        if (abs(event.x - start.first) > moveTolerancePx ||
            abs(event.y - start.second) > moveTolerancePx
        ) {
            batchInvalid = true
        }
    }

    private fun onUp(event: TouchEvent): Gesture? {
        val holdTap = tryHoldTap(event)
        if (holdTap != null) {
            downPositions.remove(event.pointerId)
            downTimes.remove(event.pointerId)
            return holdTap
        }

        downPositions.remove(event.pointerId)
        downTimes.remove(event.pointerId)
        if (event.activePointerCount > 0) return null

        // 全部手指已抬起，这一批结束
        val producedHoldTap = batchProducedHoldTap
        batchProducedHoldTap = false

        val fingers = batchPeakFingers
        val heldTooLong = event.timeMs - batchStartMs > params.longPressMs
        val valid = !batchInvalid && !heldTooLong && fingers in 1..3 && !producedHoldTap

        if (!valid) {
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return null
        }

        val withinWindow = batchStartMs - lastTapEndMs <= params.doubleTapWindowMs
        if (lastTapFingers == fingers && withinWindow) {
            lastTapFingers = 0
            lastTapEndMs = Long.MIN_VALUE
            return doubleTapFor(fingers)
        }

        lastTapFingers = fingers
        lastTapEndMs = event.timeMs
        return null
    }

    /**
     * 判断「长按底座」是否已就绪：当前按住的手指中，
     * 除本次抬起的这根以外，都已按住超过长按阈值。
     */
    private fun isHoldBaseReady(nowMs: Long, excludingPointerId: Int? = null): Boolean {
        val base = downTimes.filterKeys { it != excludingPointerId }
        if (base.size !in 2..3) return false
        return base.values.all { nowMs - it >= params.longPressMs }
    }

    /** 尝试把本次抬起识别为「长按 + 单击」。 */
    private fun tryHoldTap(event: TouchEvent): Gesture? {
        if (batchInvalid) return null
        val downAt = downTimes[event.pointerId] ?: return null

        // 这根手指本身必须是短促单击，而非长按底座的一部分
        if (event.timeMs - downAt > params.longPressMs) return null
        if (!isHoldBaseReady(downAt, excludingPointerId = event.pointerId)) return null

        // 冷却期抑制连击误触
        if (event.timeMs - lastHoldTapFireMs < COOLDOWN_MS) return null

        val baseFingers = downTimes.keys.count { it != event.pointerId }
        val gesture = when (baseFingers) {
            2 -> Gesture.TWO_FINGER_HOLD_TAP
            3 -> Gesture.THREE_FINGER_HOLD_TAP
            else -> return null
        }
        lastHoldTapFireMs = event.timeMs
        batchProducedHoldTap = true
        return gesture
    }

    private fun doubleTapFor(fingers: Int): Gesture? = when (fingers) {
        1 -> Gesture.DOUBLE_TAP
        2 -> Gesture.TWO_FINGER_DOUBLE_TAP
        3 -> Gesture.THREE_FINGER_DOUBLE_TAP
        else -> null
    }

    private companion object {
        /**
         * 长按+单击的冷却期，避免连击误触发。
         *
         * 双击类手势不需要显式冷却：触发后状态已重置，再次触发必须重新完成
         * 两次完整点击，本身就受 doubleTapWindow 约束。而长按+单击的底座手指
         * 始终按住，缺少这道闸门就会被抖动连续触发。
         */
        const val COOLDOWN_MS = 300L
    }
}
```

- [ ] **Step 4: 运行全部手势测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest --tests '*GestureRecognizerTest*'
```
Expected: `BUILD SUCCESSFUL`，21 个测试全部通过（13 个双击 + 8 个长按）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nudge/app/gesture/GestureRecognizer.kt app/src/test/java/com/nudge/app/gesture/GestureRecognizerTest.kt
git commit -m "feat: 长按+单击类手势识别"
```

---

### Task 5: 媒体控制层

封装 MediaSession 控制，实现「下一首」和「只点亮不取消」的收藏。这是承载 spec 中最关键业务规则的模块。

**Files:**
- Create: `app/src/main/java/com/nudge/app/media/TrackInfo.kt`
- Create: `app/src/main/java/com/nudge/app/media/NudgeNotificationListener.kt`
- Create: `app/src/main/java/com/nudge/app/media/MediaControlRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class TrackInfo(title: String, artist: String, isLiked: Boolean)`
  - `sealed interface ActionResult`，成员 `Skipped`, `Liked`, `AlreadyLiked`, `NoSession`, `Failed`
  - `class MediaControlRepository(context: Context)`
  - `fun skipNext(): ActionResult`
  - `fun like(): ActionResult`
  - `fun currentTrack(): TrackInfo?`
  - `fun hasNotificationAccess(): Boolean`
  - `companion object { fun openNotificationSettings(context: Context) }`

- [ ] **Step 1: 创建数据类型与通知监听服务**

`app/src/main/java/com/nudge/app/media/TrackInfo.kt`:
```kotlin
package com.nudge.app.media

/** 当前播放信息。 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val isLiked: Boolean,
)

/** 动作执行结果，决定震动反馈模式与 UI 提示。 */
sealed interface ActionResult {
    /** 切歌成功 */
    data object Skipped : ActionResult
    /** 新点亮红心 */
    data object Liked : ActionResult
    /** 本来就已收藏，未做任何操作 */
    data object AlreadyLiked : ActionResult
    /** 找不到可控制的播放会话 */
    data object NoSession : ActionResult
    /** 其他失败 */
    data class Failed(val reason: String) : ActionResult
}
```

`app/src/main/java/com/nudge/app/media/NudgeNotificationListener.kt`:
```kotlin
package com.nudge.app.media

import android.service.notification.NotificationListenerService

/**
 * 仅用于换取 MediaSessionManager.getActiveSessions() 所需的通知使用权。
 *
 * 不处理任何通知内容——之所以需要这个服务，是因为 Android 把「读取活跃媒体会话」
 * 的能力与通知使用权绑定，普通应用没有别的途径。
 */
class NudgeNotificationListener : NotificationListenerService()
```

- [ ] **Step 2: 在清单中注册服务**

在 `app/src/main/AndroidManifest.xml` 的 `</application>` 之前插入：
```xml
        <service
            android:name=".media.NudgeNotificationListener"
            android:exported="true"
            android:label="nudge"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
```

- [ ] **Step 3: 实现媒体控制仓库**

`app/src/main/java/com/nudge/app/media/MediaControlRepository.kt`:
```kotlin
package com.nudge.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.KeyEvent

/**
 * 媒体控制封装。
 *
 * 重要约束：本应用绝不注册自己的 MediaSession，否则会抢走媒体按键，
 * 导致 dispatchMediaKeyEvent 回退路径失效。
 */
class MediaControlRepository(private val context: Context) {

    private val sessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent =
        ComponentName(context, NudgeNotificationListener::class.java)

    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }

    private fun sessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    /** 网易云的会话，收藏功能仅对它有效。 */
    private fun neteaseController(): MediaController? =
        sessions().firstOrNull { it.packageName == NETEASE_PACKAGE }

    /**
     * 切歌的目标：优先网易云；网易云无会话时退而选第一个正在播放的会话。
     * 这样「下一首」对任意音乐应用都可用。
     */
    private fun skipTargetController(): MediaController? =
        neteaseController()
            ?: sessions().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    fun currentTrack(): TrackInfo? {
        val controller = skipTargetController() ?: return null
        val md = controller.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        return TrackInfo(title = title, artist = artist, isLiked = readIsLiked(controller))
    }

    private fun readIsLiked(controller: MediaController): Boolean =
        controller.metadata
            ?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            ?.hasHeart() == true

    fun skipNext(): ActionResult {
        val controller = skipTargetController()
        if (controller != null) {
            controller.transportControls.skipToNext()
            return ActionResult.Skipped
        }
        // 回退：无通知使用权时用媒体按键，零权限但无法指定目标
        return if (dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)) {
            ActionResult.Skipped
        } else {
            ActionResult.NoSession
        }
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            // 必须成对发送 DOWN + UP，只发 DOWN 很多播放器不响应
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 收藏当前歌曲，语义为「只点亮，永不取消」。
     *
     * 网易云的 setRating 实测为 toggle（忽略传入的布尔值，只当切换信号），
     * 因此必须先读 USER_RATING 判断当前状态，已收藏时不做任何操作。
     * 否则盲操下会静默取消用户已有的收藏。
     */
    fun like(): ActionResult {
        val controller = neteaseController() ?: return ActionResult.NoSession

        if (readIsLiked(controller)) return ActionResult.AlreadyLiked

        val actions = controller.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_SET_RATING != 0L) {
            controller.transportControls.setRating(Rating.newHeartRating(true))
            return ActionResult.Liked
        }

        // 兜底：动态查找 like custom action，不硬编码 id 以适应网易云改版
        val likeAction = controller.playbackState?.customActions?.firstOrNull {
            it.action.contains("STAR", ignoreCase = true) ||
                it.name.toString().contains("like", ignoreCase = true)
        }
        if (likeAction != null) {
            controller.transportControls.sendCustomAction(likeAction.action, null)
            return ActionResult.Liked
        }

        return ActionResult.Failed("网易云未暴露收藏能力")
    }

    companion object {
        const val NETEASE_PACKAGE = "com.netease.cloudmusic"

        fun openNotificationSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nudge/app/media app/src/main/AndroidManifest.xml
git commit -m "feat: 媒体控制层，收藏实现只点亮不取消"
```

---

### Task 6: 配置持久化

用 DataStore 存手势绑定、灵敏度、主题。

**Files:**
- Create: `app/src/main/java/com/nudge/app/config/ConfigStore.kt`

**Interfaces:**
- Consumes: Task 2 的 `Gesture`, `Sensitivity`
- Produces:
  - `enum class ActionType(displayName: String) { NEXT_TRACK, LIKE }`
  - `enum class ThemeMode(displayName: String) { SYSTEM, LIGHT, DARK }`
  - `data class NudgeConfig(bindings: Map<ActionType, Gesture>, sensitivity: Sensitivity, themeMode: ThemeMode)`，含 `fun gestureToAction(g: Gesture): ActionType?`
  - `class ConfigStore(context: Context)`
  - `val config: Flow<NudgeConfig>`
  - `suspend fun setBinding(action: ActionType, gesture: Gesture)` —— 自动解除该手势在其他动作上的占用
  - `suspend fun setSensitivity(s: Sensitivity)`
  - `suspend fun setThemeMode(m: ThemeMode)`

- [ ] **Step 1: 实现配置存储**

`app/src/main/java/com/nudge/app/config/ConfigStore.kt`:
```kotlin
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
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nudge/app/config
git commit -m "feat: DataStore 配置持久化"
```

---

### Task 7: 动作分发与震动反馈

把手势按配置映射到动作，并给出可区分的震动反馈——盲操下震动是用户确认结果的唯一渠道。

**Files:**
- Create: `app/src/main/java/com/nudge/app/action/ActionDispatcher.kt`

**Interfaces:**
- Consumes: Task 2 `Gesture`；Task 5 `MediaControlRepository`, `ActionResult`；Task 6 `NudgeConfig`, `ActionType`
- Produces:
  - `class ActionDispatcher(context: Context, repository: MediaControlRepository)`
  - `fun dispatch(gesture: Gesture, config: NudgeConfig): ActionResult?` —— 手势未绑定时返回 null

- [ ] **Step 1: 实现分发器**

`app/src/main/java/com/nudge/app/action/ActionDispatcher.kt`:
```kotlin
package com.nudge.app.action

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.media.ActionResult
import com.nudge.app.media.MediaControlRepository

/**
 * 把识别到的手势映射为动作并执行，附带震动反馈。
 *
 * 盲操场景下用户看不到屏幕，震动是确认操作结果的唯一渠道，
 * 因此不同结果必须有可区分的震动模式。
 */
class ActionDispatcher(
    context: Context,
    private val repository: MediaControlRepository,
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** 执行手势对应的动作。手势未绑定任何动作时返回 null。 */
    fun dispatch(gesture: Gesture, config: NudgeConfig): ActionResult? {
        val action = config.gestureToAction(gesture) ?: return null
        val result = when (action) {
            ActionType.NEXT_TRACK -> repository.skipNext()
            ActionType.LIKE -> repository.like()
        }
        vibrateFor(result)
        return result
    }

    private fun vibrateFor(result: ActionResult) {
        val effect = when (result) {
            ActionResult.Skipped -> VibrationEffect.createOneShot(50, DEFAULT_AMPLITUDE)
            ActionResult.Liked -> VibrationEffect.createWaveform(
                longArrayOf(0, 30, 80, 30), -1
            )
            ActionResult.AlreadyLiked -> VibrationEffect.createOneShot(20, DEFAULT_AMPLITUDE)
            ActionResult.NoSession, is ActionResult.Failed ->
                VibrationEffect.createOneShot(200, DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = VibrationEffect.DEFAULT_AMPLITUDE
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nudge/app/action
git commit -m "feat: 动作分发与震动反馈"
```

---

### Task 8: 主题与触控板主界面

Compose UI：全屏触控区 + 播放信息 + 红心状态。

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt`

**Interfaces:**
- Consumes: Task 2 `Gesture`, `GestureRecognizer`, `TouchEvent`, `TouchEventType`；Task 5 `TrackInfo`；Task 6 `NudgeConfig`, `ThemeMode`, `ActionType`
- Produces:
  - `@Composable fun NudgeTheme(themeMode: ThemeMode, content: @Composable () -> Unit)`
  - `@Composable fun TrackpadScreen(track: TrackInfo?, config: NudgeConfig, hasPermission: Boolean, onGesture: (Gesture) -> Unit, onOpenSettings: () -> Unit)`

- [ ] **Step 1: 实现主题**

`app/src/main/java/com/nudge/app/ui/theme/Theme.kt`:
```kotlin
package com.nudge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.nudge.app.config.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B6CB0),
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB3E8),
    background = Color(0xFF101114),
    surface = Color(0xFF1A1C20),
    onBackground = Color(0xFFE8E8EA),
    onSurface = Color(0xFFE8E8EA),
)

@Composable
fun NudgeTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
```

- [ ] **Step 2: 实现触控板界面**

`app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt`:
```kotlin
package com.nudge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.MotionEvent
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.GestureRecognizer
import com.nudge.app.gesture.TouchEvent
import com.nudge.app.gesture.TouchEventType
import com.nudge.app.media.TrackInfo
import kotlinx.coroutines.launch

@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
fun TrackpadScreen(
    track: TrackInfo?,
    config: NudgeConfig,
    hasPermission: Boolean,
    onGesture: (Gesture) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val density = LocalDensity.current.density
    // 灵敏度变化时重建识别器
    val recognizer = remember(config.sensitivity, density) {
        GestureRecognizer(config.sensitivity.params, density)
    }
    var touchPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // 手势触发时整屏闪一下：把 alpha 置 1 后动画归零
    val flashAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(track = track, onOpenSettings = onOpenSettings)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    touchPoints.forEach { point ->
                        drawCircle(
                            color = Color.Gray.copy(alpha = 0.35f),
                            radius = 48f,
                            center = point,
                        )
                    }
                    if (flashAlpha.value > 0f) {
                        drawRect(color = Color.White.copy(alpha = flashAlpha.value * 0.5f))
                    }
                }
                .pointerInteropFilter { motionEvent ->
                    handleMotionEvent(motionEvent, recognizer) { gesture ->
                        scope.launch {
                            flashAlpha.snapTo(1f)
                            flashAlpha.animateTo(0f, tween(durationMillis = 250))
                        }
                        onGesture(gesture)
                    }
                    touchPoints = currentPoints(motionEvent)
                    true
                },
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

        BindingHint(config = config)
    }
}

@Composable
private fun TopBar(track: TrackInfo?, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track?.title ?: "未检测到播放",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (track != null && track.artist.isNotEmpty()) {
                Text(
                    text = track.artist,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
        if (track != null) {
            Icon(
                imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (track.isLiked) "已收藏" else "未收藏",
                tint = if (track.isLiked) Color(0xFFE04B5A)
                       else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun BindingHint(config: NudgeConfig) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionType.entries.forEach { action ->
            val gesture = config.bindings[action]
            if (gesture != null) {
                Text(
                    text = "${gesture.displayName} → ${action.displayName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/** 把 MotionEvent 翻译成与 Android 解耦的 TouchEvent 喂给识别器。 */
private fun handleMotionEvent(
    event: MotionEvent,
    recognizer: GestureRecognizer,
    onGesture: (Gesture) -> Unit,
) {
    val index = event.actionIndex
    val type = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> TouchEventType.DOWN
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> TouchEventType.UP
        MotionEvent.ACTION_MOVE -> TouchEventType.MOVE
        MotionEvent.ACTION_CANCEL -> TouchEventType.CANCEL
        else -> return
    }

    if (type == TouchEventType.MOVE) {
        // MOVE 事件携带所有手指的位置，逐个上报
        for (i in 0 until event.pointerCount) {
            recognizer.onTouchEvent(
                TouchEvent(
                    type = TouchEventType.MOVE,
                    pointerId = event.getPointerId(i),
                    x = event.getX(i),
                    y = event.getY(i),
                    timeMs = event.eventTime,
                    activePointerCount = event.pointerCount,
                )
            )?.let(onGesture)
        }
        return
    }

    // UP 类事件中，抬起的这根手指仍计入 pointerCount，故需减一
    val activeCount = if (type == TouchEventType.UP) event.pointerCount - 1 else event.pointerCount
    recognizer.onTouchEvent(
        TouchEvent(
            type = type,
            pointerId = event.getPointerId(index),
            x = event.getX(index),
            y = event.getY(index),
            timeMs = event.eventTime,
            activePointerCount = activeCount,
        )
    )?.let(onGesture)
}

/** 当前屏幕上所有手指的位置，用于绘制涟漪。 */
private fun currentPoints(event: MotionEvent): List<Offset> {
    if (event.actionMasked == MotionEvent.ACTION_UP ||
        event.actionMasked == MotionEvent.ACTION_CANCEL
    ) return emptyList()

    val liftedIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
        event.actionIndex
    } else -1

    return (0 until event.pointerCount)
        .filter { it != liftedIndex }
        .map { Offset(event.getX(it), event.getY(it)) }
}
```

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nudge/app/ui
git commit -m "feat: 主题与触控板主界面"
```

---

### Task 9: 设置页

绑定配置、灵敏度、主题、权限引导。

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: Task 2 `Gesture`, `Sensitivity`；Task 6 `NudgeConfig`, `ActionType`, `ThemeMode`
- Produces:
  - `@Composable fun SettingsScreen(config: NudgeConfig, hasPermission: Boolean, onBindingChange: (ActionType, Gesture) -> Unit, onSensitivityChange: (Sensitivity) -> Unit, onThemeChange: (ThemeMode) -> Unit, onRequestPermission: () -> Unit, onBack: () -> Unit)`

- [ ] **Step 1: 实现设置页**

`app/src/main/java/com/nudge/app/ui/SettingsScreen.kt`:
```kotlin
package com.nudge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.config.ActionType
import com.nudge.app.config.NudgeConfig
import com.nudge.app.config.ThemeMode
import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity

@Composable
fun SettingsScreen(
    config: NudgeConfig,
    hasPermission: Boolean,
    onBindingChange: (ActionType, Gesture) -> Unit,
    onSensitivityChange: (Sensitivity) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SectionTitle("权限")
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = if (hasPermission) "通知使用权：已授予" else "通知使用权：未授予",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!hasPermission) {
                Text(
                    text = "控制播放与读取歌曲信息需要此权限",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("前往授予")
                }
            }
        }

        // 每个动作绑定一个手势，手势互斥由 ConfigStore.setBinding 保证
        ActionType.entries.forEach { action ->
            SectionTitle("${action.displayName} 的手势")
            Gesture.entries.forEach { gesture ->
                val occupiedBy = config.gestureToAction(gesture)
                val selected = config.bindings[action] == gesture
                val occupiedByOther = occupiedBy != null && occupiedBy != action
                OptionRow(
                    label = gesture.displayName,
                    hint = if (occupiedByOther) "已绑定「${occupiedBy.displayName}」" else null,
                    selected = selected,
                    onClick = { onBindingChange(action, gesture) },
                )
            }
        }

        SectionTitle("灵敏度")
        Sensitivity.entries.forEach { s ->
            OptionRow(
                label = s.displayName,
                hint = "双击间隔 ${s.params.doubleTapWindowMs}ms，长按 ${s.params.longPressMs}ms",
                selected = config.sensitivity == s,
                onClick = { onSensitivityChange(s) },
            )
        }

        SectionTitle("主题")
        ThemeMode.entries.forEach { mode ->
            OptionRow(
                label = mode.displayName,
                hint = null,
                selected = config.themeMode == mode,
                onClick = { onThemeChange(mode) },
            )
        }

        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "收藏功能仅对网易云音乐有效，且只会点亮红心，不会取消已有收藏。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun OptionRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nudge/app/ui/SettingsScreen.kt
git commit -m "feat: 设置页"
```

---

### Task 10: 组装 MainActivity 并真机验证

把所有模块接起来，装到真机上跑通全链路。

**Files:**
- Modify: `app/src/main/java/com/nudge/app/MainActivity.kt`

**Interfaces:**
- Consumes: 前面所有任务的产物
- Produces: 可运行的完整应用

- [ ] **Step 1: 组装 MainActivity**

完整替换 `app/src/main/java/com/nudge/app/MainActivity.kt`:
```kotlin
package com.nudge.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import com.nudge.app.action.ActionDispatcher
import com.nudge.app.config.ConfigStore
import com.nudge.app.config.NudgeConfig
import com.nudge.app.media.MediaControlRepository
import com.nudge.app.media.TrackInfo
import com.nudge.app.ui.SettingsScreen
import com.nudge.app.ui.TrackpadScreen
import com.nudge.app.ui.theme.NudgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: MediaControlRepository
    private lateinit var dispatcher: ActionDispatcher
    private lateinit var configStore: ConfigStore

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = MediaControlRepository(this)
        dispatcher = ActionDispatcher(this, repository)
        configStore = ConfigStore(this)

        // 盲操场景下屏幕熄灭就没法操作了
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val config by configStore.config.collectAsState(initial = NudgeConfig.DEFAULT)
            val scope = rememberCoroutineScope()
            var showSettings by remember { mutableStateOf(false) }
            var track by remember { mutableStateOf<TrackInfo?>(null) }
            var hasPermission by remember { mutableStateOf(repository.hasNotificationAccess()) }

            // 轮询播放状态。MediaController 回调需要绑定/解绑生命周期管理，
            // 而本应用是前台短时使用，1 秒轮询更简单且开销可忽略。
            LaunchedEffect(Unit) {
                while (true) {
                    hasPermission = repository.hasNotificationAccess()
                    track = repository.currentTrack()
                    delay(1000)
                }
            }

            NudgeTheme(themeMode = config.themeMode) {
                Surface {
                    if (showSettings) {
                        SettingsScreen(
                            config = config,
                            hasPermission = hasPermission,
                            onBindingChange = { action, gesture ->
                                scope.launch { configStore.setBinding(action, gesture) }
                            },
                            onSensitivityChange = {
                                scope.launch { configStore.setSensitivity(it) }
                            },
                            onThemeChange = {
                                scope.launch { configStore.setThemeMode(it) }
                            },
                            onRequestPermission = {
                                MediaControlRepository.openNotificationSettings(this@MainActivity)
                            },
                            onBack = { showSettings = false },
                        )
                    } else {
                        TrackpadScreen(
                            track = track,
                            config = config,
                            hasPermission = hasPermission,
                            onGesture = { gesture ->
                                val result = dispatcher.dispatch(gesture, config)
                                if (result != null) {
                                    // 动作执行后立即刷新，让红心状态尽快反映变化
                                    track = repository.currentTrack()
                                }
                            },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 构建并安装到真机**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `BUILD SUCCESSFUL` 且 `Success`

- [ ] **Step 3: 授予通知使用权并启动**

```bash
adb shell cmd notification allow_listener com.nudge.app/com.nudge.app.media.NudgeNotificationListener
adb shell am start -n com.nudge.app/.MainActivity
```
Expected: 应用启动，顶部显示当前播放的歌名（需网易云正在播放）

- [ ] **Step 4: 真机验证下一首**

在手机上用两指双击屏幕中央（默认绑定），然后：
```bash
adb shell dumpsys media_session | grep "description=" | head -2
```
Expected: 歌名发生变化，手机有一次短震

- [ ] **Step 5: 真机验证收藏与幂等性**

先记录当前状态：
```bash
adb shell dumpsys media_session | grep -E "description=" | head -2
```
在手机上三指双击 → 应双震。然后**再次三指双击** → 应为单次极短震（AlreadyLiked）。

用探针确认红心状态未被取消：
```bash
adb shell am start -n com.nudge.probe/.ProbeActivity
adb logcat -c && adb shell am broadcast -a com.nudge.probe.DUMP && sleep 2 && adb logcat -d -s NudgeProbe:I | grep USER_RATING
```
Expected: `hasHeart=true`（两次收藏手势后仍为 true，证明「只点亮不取消」生效）

- [ ] **Step 6: 运行全部单元测试**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH && unset JAVA_TOOL_OPTIONS && cd /Users/bjhl/project/mine/nudge && gradle :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`，25 个测试全部通过

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nudge/app/MainActivity.kt
git commit -m "feat: 组装应用入口，真机验证通过"
```

---

## 验收标准

- [ ] 25 个单元测试全部通过
- [ ] 两指双击可切歌，伴随短震
- [ ] 三指双击可点亮网易云红心，伴随双震
- [ ] **对已收藏歌曲重复执行收藏手势，红心保持点亮**（不被取消）
- [ ] 设置页可改绑定、灵敏度、主题，重启应用后配置保留
- [ ] 同一手势不能同时绑定两个动作
- [ ] 白天/夜间主题可切换，跟随系统模式生效
- [ ] 未授予通知使用权时有明确提示与跳转入口

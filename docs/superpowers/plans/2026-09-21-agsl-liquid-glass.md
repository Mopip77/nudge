# AGSL Liquid Glass 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用手写 AGSL shader 给 trackpad 做出真正的 liquid glass——边缘 SDF 折射 + 色散 + 镜面高光，中央清晰透出歌词。

**Architecture:** AGSL shader 通过 `RuntimeShader` + `RenderEffect.createRuntimeShaderEffect` 挂在 `graphicsLayer` 上。因为 Android 的 `RenderEffect` 只能采样图层**自身**内容，歌词层必须放进挂了 renderEffect 的图层**内部**，由 shader 统一处理：边缘折射、中央原样。参数先由一个临时「玻璃实验室」页面在真机上调定，再固化。

**Tech Stack:** Kotlin, Jetpack Compose (UI 1.6.1 / BOM 2024.02.00), AGSL, `android.graphics.RuntimeShader` (API 33+)

## Global Constraints

这些约束适用于**每一个** task，不再逐条重复：

- **不得修改 `app/src/main/java/com/nudge/app/gesture/` 下任何文件。** `GestureRecognizer` 是纯 Kotlin 状态机、不依赖任何 Android 类，这个性质必须保持。
- **不得改动触摸链路的接线方式。** `TrackpadScreen` 中触摸区 `Box` 上的 `pointerInteropFilter` 及其 lambda 体保持原样；不得新增任何 pointer 修饰符，不得拦截或消费触摸事件。
- **不引入任何第三方依赖。** `app/build.gradle.kts` 的 `dependencies` 块不得新增条目。
- **不改动 `app/src/main/java/com/nudge/app/ui/LyricsOverlay.kt` 的排版逻辑。**
- **不改动 release 构建与发布流程。**
- 所有代码注释用**中文**，写「为什么」不写「是什么」。
- **`RuntimeShader` 需要 API 33+**，所有使用处必须有 `Build.VERSION.SDK_INT >= 33` 判断；低版本走兜底（保持当前不透明 surface 外观），只需不崩。
- 构建命令**必须显式带 JAVA_HOME**，且因 Gradle 需要写 `~/.gradle`，跑构建时要 `dangerouslyDisableSandbox: true`：
  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
  ```
- 单测基线是 **74 个**（CLAUDE.md 里写的 72 已过时）。每个 task 结束时必须全绿。
- **不得声称视觉效果「良好」「漂亮」——你看不到屏幕。** 只陈述写了什么代码、编译安装是否成功。

## File Structure

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/nudge/app/ui/glass/GlassShader.kt`（新建） | AGSL 源码字符串 + `GlassParams` 数据类。纯数据，无 composable。 |
| `app/src/main/java/com/nudge/app/ui/glass/GlassModifier.kt`（新建） | `Modifier.liquidGlass(params)`，负责 `RuntimeShader` 创建、uniform 设置、API 33 判断与兜底。 |
| `app/src/main/java/com/nudge/app/ui/glass/GlassLab.kt`（新建，**临时**） | 调参实验室页面。参数调定后整个文件删除。 |
| `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt`（修改） | 接入玻璃层；触点与命中反馈改为玻璃语言；修 dp/px bug。 |
| `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt`（修改，**临时**） | 加实验室入口。参数调定后回滚。 |
| `app/src/main/java/com/nudge/app/MainActivity.kt`（修改，**临时**） | 实验室页面的路由。参数调定后回滚。 |
| `app/src/main/AndroidManifest.xml`（修改） | 通知监听服务 label 与应用名解耦。 |

`glass/` 独立成包：shader 逻辑与现有 UI 代码关注点不同，且实验室是临时脚手架，独立目录便于最后清理。

---

### Task 1: AGSL shader 源码与参数模型

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/glass/GlassShader.kt`
- Test: `app/src/test/java/com/nudge/app/ui/glass/GlassParamsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class GlassParams(refractiveIndex: Float, edgeWidth: Float, chromatic: Float, blurRadius: Float, cornerRadius: Float, highlight: Float, tint: Float)` — 全部 `Float`
  - `GlassParams.DEFAULT: GlassParams`
  - `GlassParams.coerced(): GlassParams` — 把各字段夹到合法区间
  - `const val GLASS_SHADER_SRC: String` — AGSL 源码
  - 各参数的区间常量：`REFRACTIVE_INDEX_RANGE`、`EDGE_WIDTH_RANGE`、`CHROMATIC_RANGE`、`BLUR_RADIUS_RANGE`、`CORNER_RADIUS_RANGE`、`HIGHLIGHT_RANGE`、`TINT_RANGE`，类型均为 `ClosedFloatingPointRange<Float>`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/nudge/app/ui/glass/GlassParamsTest.kt`：

```kotlin
package com.nudge.app.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassParamsTest {

    @Test
    fun `默认参数落在各自合法区间内`() {
        val p = GlassParams.DEFAULT
        assertTrue(p.refractiveIndex in REFRACTIVE_INDEX_RANGE)
        assertTrue(p.edgeWidth in EDGE_WIDTH_RANGE)
        assertTrue(p.chromatic in CHROMATIC_RANGE)
        assertTrue(p.blurRadius in BLUR_RADIUS_RANGE)
        assertTrue(p.cornerRadius in CORNER_RADIUS_RANGE)
        assertTrue(p.highlight in HIGHLIGHT_RANGE)
        assertTrue(p.tint in TINT_RANGE)
    }

    @Test
    fun `coerced 把越界值夹回区间端点`() {
        val p = GlassParams(
            refractiveIndex = 99f,
            edgeWidth = -5f,
            chromatic = 99f,
            blurRadius = -1f,
            cornerRadius = 9999f,
            highlight = -3f,
            tint = 42f,
        ).coerced()

        assertEquals(REFRACTIVE_INDEX_RANGE.endInclusive, p.refractiveIndex, 0f)
        assertEquals(EDGE_WIDTH_RANGE.start, p.edgeWidth, 0f)
        assertEquals(CHROMATIC_RANGE.endInclusive, p.chromatic, 0f)
        assertEquals(BLUR_RADIUS_RANGE.start, p.blurRadius, 0f)
        assertEquals(CORNER_RADIUS_RANGE.endInclusive, p.cornerRadius, 0f)
        assertEquals(HIGHLIGHT_RANGE.start, p.highlight, 0f)
        assertEquals(TINT_RANGE.endInclusive, p.tint, 0f)
    }

    @Test
    fun `coerced 不改动已在区间内的值`() {
        val p = GlassParams.DEFAULT
        assertEquals(p, p.coerced())
    }

    @Test
    fun `shader 源码声明了全部 uniform 与入口函数`() {
        // shader 编译错误只在真机运行时才暴露，这里至少保证 uniform 名字
        // 与 GlassModifier 里 setFloatUniform 用的字符串对得上。
        val src = GLASS_SHADER_SRC
        listOf(
            "uniform shader content;",
            "uniform float2 size;",
            "uniform float refractiveIndex;",
            "uniform float edgeWidth;",
            "uniform float chromatic;",
            "uniform float cornerRadius;",
            "uniform float highlight;",
            "uniform float tint;",
            "half4 main(",
        ).forEach { token ->
            assertTrue("shader 源码缺少 `$token`", src.contains(token))
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests '*GlassParamsTest*'`
Expected: 编译失败，`Unresolved reference: GlassParams`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/nudge/app/ui/glass/GlassShader.kt`：

```kotlin
package com.nudge.app.ui.glass

/**
 * 玻璃效果的可调参数。
 *
 * 这些值没有「正确答案」——液态玻璃的观感全在参数，且只能在真机屏幕上判断。
 * 默认值是按光学原理推的起点，由玻璃实验室（[GlassLab]）在真机上调定后固化。
 */
data class GlassParams(
    /** 折射率。1.0 为不折射，越大边缘弯折越强。真玻璃约 1.5。 */
    val refractiveIndex: Float,
    /** 折射带宽度，占短边的比例。只有距边缘这个范围内的像素会被扭曲。 */
    val edgeWidth: Float,
    /** 色散强度。R/G/B 三通道采样偏移量，产生棱镜彩边。 */
    val chromatic: Float,
    /** 背景模糊半径（dp）。先模糊后折射，折射作用在已模糊内容上才像厚玻璃。 */
    val blurRadius: Float,
    /** 圆角半径（dp）。 */
    val cornerRadius: Float,
    /** 边缘镜面高光强度。 */
    val highlight: Float,
    /** 整体染色强度，玻璃自身的淡色。 */
    val tint: Float,
) {
    /** 把各字段夹回合法区间。滑块与固化值都走这里，避免越界参数把 shader 喂出 NaN。 */
    fun coerced(): GlassParams = GlassParams(
        refractiveIndex = refractiveIndex.coerceIn(REFRACTIVE_INDEX_RANGE),
        edgeWidth = edgeWidth.coerceIn(EDGE_WIDTH_RANGE),
        chromatic = chromatic.coerceIn(CHROMATIC_RANGE),
        blurRadius = blurRadius.coerceIn(BLUR_RADIUS_RANGE),
        cornerRadius = cornerRadius.coerceIn(CORNER_RADIUS_RANGE),
        highlight = highlight.coerceIn(HIGHLIGHT_RANGE),
        tint = tint.coerceIn(TINT_RANGE),
    )

    companion object {
        val DEFAULT = GlassParams(
            refractiveIndex = 1.5f,
            edgeWidth = 0.18f,
            chromatic = 0.02f,
            blurRadius = 16f,
            cornerRadius = 28f,
            highlight = 0.5f,
            tint = 0.08f,
        )
    }
}

val REFRACTIVE_INDEX_RANGE = 1f..3f
val EDGE_WIDTH_RANGE = 0.02f..0.5f
val CHROMATIC_RANGE = 0f..0.1f
val BLUR_RADIUS_RANGE = 0f..40f
val CORNER_RADIUS_RANGE = 0f..64f
val HIGHLIGHT_RANGE = 0f..2f
val TINT_RANGE = 0f..0.4f

/**
 * 液态玻璃 AGSL shader。
 *
 * 核心是 SDF 驱动的**边缘**折射：圆角矩形的有符号距离场给出每个像素到边缘的
 * 距离，只有落在 edgeWidth 带内的像素被扭曲，中央原样输出。这与真玻璃一致
 * （平板中央不折射，只有边缘曲面折射），也正是歌词能保持清晰可读的原因——
 * 中央区域 shader 根本没动它。
 *
 * 距离场的梯度就是表面法线，折射方向沿法线，过圆角时不会失真。
 *
 * **调用方必须先把内容模糊再喂进来**（见 GlassModifier 的 chain 顺序）：
 * 折射作用在已模糊的内容上才读作「厚玻璃」，反过来只是「一张模糊贴纸」。
 */
const val GLASS_SHADER_SRC = """
uniform shader content;
uniform float2 size;
uniform float refractiveIndex;
uniform float edgeWidth;
uniform float chromatic;
uniform float cornerRadius;
uniform float highlight;
uniform float tint;

// 圆角矩形的有符号距离场：内部为负，边界为 0，外部为正。
// min/max 而非条件分支——GPU 上分支昂贵，这个写法是图形学惯例。
float sdRoundedBox(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 fragCoord) {
    float2 center = size * 0.5;
    float2 p = fragCoord - center;
    float2 halfSize = center;

    float d = sdRoundedBox(p, halfSize, cornerRadius);

    // 折射带按短边比例算，保证宽高不同时带宽观感一致
    float band = min(size.x, size.y) * edgeWidth;

    // d 为负（内部）。-d 是到边缘的距离，0 表示贴边。
    // t: 1 在最边缘，0 在带内侧边界及更深处。
    float t = clamp(1.0 + d / band, 0.0, 1.0);

    if (t <= 0.0) {
        // 中央平板区：原样输出，不折射不染色。歌词的可读性靠这一支保住。
        return content.eval(fragCoord);
    }

    // 表面法线 = 距离场的梯度，用中心差分求。
    // 步长 1px：再小会被浮点精度吃掉，再大圆角处法线会失真。
    float e = 1.0;
    float2 grad = float2(
        sdRoundedBox(p + float2(e, 0.0), halfSize, cornerRadius)
            - sdRoundedBox(p - float2(e, 0.0), halfSize, cornerRadius),
        sdRoundedBox(p + float2(0.0, e), halfSize, cornerRadius)
            - sdRoundedBox(p - float2(0.0, e), halfSize, cornerRadius)
    );
    float gradLen = length(grad);
    // 梯度在中轴线上会塌缩成 0，除之前先兜底，否则出 NaN 满屏黑块
    float2 normal = gradLen > 0.0001 ? grad / gradLen : float2(0.0, 0.0);

    // 厚度剖面：用 sqrt 让边缘像球面隆起而非线性斜面。
    // 线性会读作「斜切的倒角」，sqrt 才是「鼓起来的玻璃」。
    float profile = sqrt(t);

    // 位移量：折射率越大弯折越强
    float displacement = band * profile * (refractiveIndex - 1.0);
    float2 refracted = fragCoord - normal * displacement;

    // 色散：R/G/B 沿法线方向错开采样，边缘出棱镜彩边
    float2 disp = normal * displacement * chromatic * 10.0;
    half4 c;
    c.r = content.eval(refracted + disp).r;
    c.g = content.eval(refracted).g;
    c.b = content.eval(refracted - disp).b;
    c.a = content.eval(refracted).a;

    // 镜面高光：假想光源在左上，法线朝向光源的边缘最亮
    float2 lightDir = normalize(float2(-0.6, -0.8));
    float spec = pow(clamp(dot(normal, lightDir), 0.0, 1.0), 3.0) * profile * highlight;

    // 玻璃自身的淡色，靠边缘越浓
    c.rgb = c.rgb + half3(spec) + half3(tint * profile);

    return clamp(c, 0.0, 1.0);
}
"""
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests '*GlassParamsTest*'`
Expected: PASS，4 个测试全绿

- [ ] **Step 5: 跑全量测试，确认没打破基线**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test`
Expected: BUILD SUCCESSFUL，78 tests（74 基线 + 本 task 新增 4 个），0 failures

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/nudge/app/ui/glass/GlassShader.kt \
        app/src/test/java/com/nudge/app/ui/glass/GlassParamsTest.kt
git commit -m "feat: AGSL 液态玻璃 shader 源码与参数模型

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `Modifier.liquidGlass` 接线

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/glass/GlassModifier.kt`

**Interfaces:**
- Consumes: `GlassParams`、`GLASS_SHADER_SRC`（Task 1）
- Produces: `fun Modifier.liquidGlass(params: GlassParams): Modifier`

**本 task 没有单元测试。** `RuntimeShader` 是 Android 类，JVM 单测里只有会抛
「not mocked」的桩实现，且 shader 编译发生在 GPU 上。验证靠编译 + 真机安装启动。
不要为此引入 Robolectric（会带来新依赖，违反全局约束）。

- [ ] **Step 1: 写实现**

创建 `app/src/main/java/com/nudge/app/ui/glass/GlassModifier.kt`：

```kotlin
package com.nudge.app.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * 把内容渲染成液态玻璃：边缘折射 + 色散 + 镜面高光，中央原样透出。
 *
 * **要被折射的内容必须在这个 modifier 所修饰的 composable 内部。**
 * Android 的 RenderEffect 只能采样图层自身的内容，采不到图层背后的像素
 * （没有 Windows CreateHostBackdropBrush 那样的合成器级接口）。所以这里
 * 是「父层方案」：歌词放进来，由 shader 统一处理。
 *
 * API < 33 时返回 Modifier 原样——RuntimeShader 是 Android 13 才有的。
 * 本应用是自用工具，目标机 API 33，低版本只保证不崩，不追求观感等价。
 */
fun Modifier.liquidGlass(params: GlassParams): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.composed {
        val p = params.coerced()
        val density = LocalDensity.current
        // shader 编译是有成本的，只在源码变化时重建（实际是永不重建）。
        // 参数变化只更新 uniform，不重新编译。
        val shader = remember { RuntimeShader(GLASS_SHADER_SRC) }
        val blurPx = with(density) { p.blurRadius.dp.toPx() }
        val cornerPx = with(density) { p.cornerRadius.dp.toPx() }

        graphicsLayer {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("refractiveIndex", p.refractiveIndex)
            shader.setFloatUniform("edgeWidth", p.edgeWidth)
            shader.setFloatUniform("chromatic", p.chromatic)
            shader.setFloatUniform("cornerRadius", cornerPx)
            shader.setFloatUniform("highlight", p.highlight)
            shader.setFloatUniform("tint", p.tint)

            val glass = RenderEffect.createRuntimeShaderEffect(shader, "content")

            // 顺序是关键：blur 在前、折射在后。
            // createChainEffect(outer, inner) 先跑 inner 再把结果喂给 outer，
            // 所以 glass 作外层、blur 作内层，等价于「先模糊，再折射已模糊的内容」。
            // 反过来是「把一张模糊贴纸再模糊一遍」，读不出厚度。
            renderEffect = if (blurPx > 0f) {
                RenderEffect.createChainEffect(
                    glass,
                    RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP),
                )
            } else {
                glass
            }.asComposeRenderEffect()
        }
    }
}
```

注意：上面用到了 `dp` 扩展，文件顶部 import 需补 `androidx.compose.ui.unit.dp`。

- [ ] **Step 2: 确认编译通过**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

如果报 `Unresolved reference: dp`，在文件顶部补 `import androidx.compose.ui.unit.dp`。

- [ ] **Step 3: 跑全量测试**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test`
Expected: BUILD SUCCESSFUL，78 tests，0 failures

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/nudge/app/ui/glass/GlassModifier.kt
git commit -m "feat: Modifier.liquidGlass 接线 RuntimeShader

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 玻璃实验室页面

**Files:**
- Create: `app/src/main/java/com/nudge/app/ui/glass/GlassLab.kt`
- Modify: `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/nudge/app/MainActivity.kt`

**Interfaces:**
- Consumes: `GlassParams`、`GlassParams.DEFAULT`、各 `*_RANGE` 常量（Task 1）、`Modifier.liquidGlass`（Task 2）
- Produces: `@Composable fun GlassLab(onBack: () -> Unit)`

**这是临时脚手架**，参数调定后 Task 6 会整个删掉。因此：不做持久化、不写 README、不加设置项。

- [ ] **Step 1: 写实验室页面**

创建 `app/src/main/java/com/nudge/app/ui/glass/GlassLab.kt`：

```kotlin
package com.nudge.app.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 玻璃参数调试页。**临时脚手架，参数调定后整个文件删除。**
 *
 * 存在理由：液态玻璃的成败全在参数，而参数只能在真机屏幕上判断。
 * 没有滑块就得「改代码 → 编译 → 安装 → 看 → 再改」地盲调，一轮好几分钟。
 */
@Composable
fun GlassLab(onBack: () -> Unit) {
    var params by remember { mutableStateOf(GlassParams.DEFAULT) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("玻璃实验室", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
            Row {
                Button(onClick = { params = GlassParams.DEFAULT }) { Text("重置") }
                Button(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("返回") }
            }
        }

        // 预览区：放一些高对比度内容，玻璃的折射与色散在细密条纹上最容易看出来
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(vertical = 12.dp)
                .liquidGlass(params),
            contentAlignment = Alignment.Center,
        ) {
            GlassPreviewContent()
        }

        // 当前参数值，调好后照抄进 GlassParams.DEFAULT
        Text(
            text = params.asCodeSnippet(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            ParamSlider("折射率", params.refractiveIndex, REFRACTIVE_INDEX_RANGE) {
                params = params.copy(refractiveIndex = it)
            }
            ParamSlider("边缘带宽", params.edgeWidth, EDGE_WIDTH_RANGE) {
                params = params.copy(edgeWidth = it)
            }
            ParamSlider("色散", params.chromatic, CHROMATIC_RANGE) {
                params = params.copy(chromatic = it)
            }
            ParamSlider("模糊半径", params.blurRadius, BLUR_RADIUS_RANGE) {
                params = params.copy(blurRadius = it)
            }
            ParamSlider("圆角", params.cornerRadius, CORNER_RADIUS_RANGE) {
                params = params.copy(cornerRadius = it)
            }
            ParamSlider("高光", params.highlight, HIGHLIGHT_RANGE) {
                params = params.copy(highlight = it)
            }
            ParamSlider("染色", params.tint, TINT_RANGE) {
                params = params.copy(tint = it)
            }
        }
    }
}

/** 把当前参数打印成可直接粘进 GlassParams.DEFAULT 的 Kotlin 代码。 */
private fun GlassParams.asCodeSnippet(): String =
    "refractiveIndex = ${"%.2f".format(refractiveIndex)}f, " +
        "edgeWidth = ${"%.3f".format(edgeWidth)}f, " +
        "chromatic = ${"%.3f".format(chromatic)}f,\n" +
        "blurRadius = ${"%.1f".format(blurRadius)}f, " +
        "cornerRadius = ${"%.1f".format(cornerRadius)}f, " +
        "highlight = ${"%.2f".format(highlight)}f, " +
        "tint = ${"%.3f".format(tint)}f"

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label  ${"%.3f".format(value)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * 预览内容：细密的对角条纹 + 文字。
 *
 * 条纹是刻意的——折射和色散作用在平坦色块上几乎看不出来，
 * 必须有高频细节才能显出扭曲和彩边。
 */
@Composable
private fun GlassPreviewContent() {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val stripe = 14f
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color = androidx.compose.ui.graphics.Color(0xFF4A90D9),
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x + size.height, size.height),
                strokeWidth = stripe / 2f,
            )
            x += stripe
        }
    }
    Text(
        text = "液态玻璃",
        fontSize = 28.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
```

- [ ] **Step 2: 加设置页入口**

在 `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt` 中，为 `SettingsScreen` 增加一个参数 `onOpenGlassLab: () -> Unit`，并在页面里加一个入口按钮。

先读文件找到参数列表末尾（`onBack: () -> Unit` 之前）插入：

```kotlin
    onOpenGlassLab: () -> Unit,
```

然后在页面内容的末尾、返回按钮之前，加一个入口。具体位置按文件现有结构就近插入，形如：

```kotlin
        // 临时入口：玻璃参数调定后连同 GlassLab.kt 一并删除
        androidx.compose.material3.TextButton(onClick = onOpenGlassLab) {
            Text("玻璃实验室（临时）")
        }
```

- [ ] **Step 3: 加路由**

在 `app/src/main/java/com/nudge/app/MainActivity.kt` 中：

在 `var showSettings by remember { mutableStateOf(false) }` 下方加：

```kotlin
            // 临时：玻璃参数调定后删除
            var showGlassLab by remember { mutableStateOf(false) }
```

把 `if (showSettings) { ... } else { ... }` 改为三分支，最前面加：

```kotlin
                    if (showGlassLab) {
                        BackHandler { showGlassLab = false }
                        com.nudge.app.ui.glass.GlassLab(onBack = { showGlassLab = false })
                    } else if (showSettings) {
```

并给 `SettingsScreen(...)` 的调用补上参数：

```kotlin
                            onOpenGlassLab = { showGlassLab = true },
```

- [ ] **Step 4: 编译 + 测试**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```
Expected: 两条都 BUILD SUCCESSFUL，78 tests 0 failures

- [ ] **Step 5: 装到真机并验证不崩**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nudge.app/.MainActivity
sleep 3
adb logcat -d -t 200 | grep -iE 'FATAL|AndroidRuntime|RuntimeShader|Shader' | head -30
```

Expected: 安装 `Success`，启动后无 FATAL。

**特别注意**：如果 logcat 里出现 shader 编译错误（形如 `error: ... ` 带行号），
说明 AGSL 源码有语法问题，必须修好——这是本 task 最可能失败的地方，
因为 AGSL 与 GLSL 有差异（`vec2`→`float2`、返回类型必须 `half4`、
不支持某些内置函数）。如实报告错误原文，不要跳过。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat: 玻璃实验室调参页（临时脚手架）

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: 停下来交给作者调参**

本 task 完成后**暂停**，报告：实验室已装机可用，请作者在真机上调参，
把页面上显示的参数代码片段回传。Task 4 依赖这个结果。

---

### Task 4: trackpad 接入玻璃 + 修 dp/px bug

**Files:**
- Modify: `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt`

**Interfaces:**
- Consumes: `GlassParams`（Task 1）、`Modifier.liquidGlass`（Task 2）、作者在 Task 3 调定的参数值
- Produces: 无（终端改动）

**前置条件**：Task 3 已完成且作者已回传调定的参数值。若尚未拿到，先用
`GlassParams.DEFAULT`，并在报告里说明参数未经真机调定。

- [ ] **Step 1: 把调定的参数写进 DEFAULT**

用作者回传的数值替换 `GlassShader.kt` 里 `GlassParams.DEFAULT` 的字段值。
如果作者尚未回传，跳过本步。

- [ ] **Step 2: 触摸区接入玻璃层**

修改 `TrackpadScreen.kt` 中 trackpad 的 `Box`（当前在 `app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt:107-152`）。

关键：**`pointerInteropFilter` 必须留在最外层的 Box 上，保持原样**。玻璃层
包在内部，歌词放进玻璃层里。改为：

```kotlin
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
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
            // 玻璃层包住歌词：RenderEffect 只能采样图层自身内容，
            // 要被折射的东西必须在里面（见 Modifier.liquidGlass 注释）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(GlassParams.DEFAULT.cornerRadius.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .liquidGlass(GlassParams.DEFAULT),
            ) {
                if (config.lyricsEnabled) {
                    LyricsOverlay(state = lyricsState, track = track)
                }
            }

            // 触摸反馈画在玻璃之上：手指是按在玻璃表面的，不该被自己折射。
            // touchPoints 的坐标来自外层 Box 的 MotionEvent，而本 Box 是
            // fillMaxSize 的兄弟节点、边界与外层完全重合，故坐标可直接用，
            // 不需要做偏移换算。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(GlassParams.DEFAULT.cornerRadius.dp))
                    .drawBehind {
                        touchPoints.forEach { point ->
                            drawTouchGlow(point, touchGlowRadiusPx)
                        }
                        if (flashAlpha.value > 0f) {
                            drawRect(color = Color.White.copy(alpha = flashAlpha.value * 0.18f))
                        }
                    },
            )

            if (!hasPermission) {
                Text(
                    text = "需要通知使用权才能控制播放\n点击右上角设置授予",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
        }
```

在 composable 顶部（`val scope = rememberCoroutineScope()` 附近）加：

```kotlin
    // 原代码 radius 写死 48f 是**像素**，在 xxhdpi 上只有 16dp、mdpi 上却是 48dp，
    // 同一份代码在不同机型上差三倍。改为按 dp 折算。
    val touchGlowRadiusPx = with(LocalDensity.current) { TOUCH_GLOW_RADIUS.toPx() }
```

- [ ] **Step 3: 加触点光晕绘制**

在 `TrackpadScreen.kt` 文件末尾（`currentPoints` 之后）加：

```kotlin
/** 触点光晕半径。原先写死 48px，在不同 dpi 上差三倍，改为 dp。 */
private val TOUCH_GLOW_RADIUS = 26.dp

/**
 * 玻璃表面的触点：径向渐变光晕而非实心圆。
 *
 * 实心灰圆读起来是贴在玻璃上的贴纸；玻璃上的触点应该是局部的亮度扰动，
 * 中心亮、向外化开。
 */
private fun DrawScope.drawTouchGlow(center: Offset, radiusPx: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.38f),
            0.55f to Color.White.copy(alpha = 0.14f),
            1f to Color.Transparent,
            center = center,
            radius = radiusPx,
        ),
        radius = radiusPx,
        center = center,
    )
}
```

需要补的 import：
```kotlin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nudge.app.ui.glass.GlassParams
import com.nudge.app.ui.glass.liquidGlass
```

- [ ] **Step 4: 编译 + 测试**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```
Expected: 两条都 BUILD SUCCESSFUL，78 tests 0 failures

- [ ] **Step 5: 装机验证**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nudge.app/.MainActivity
sleep 3
adb logcat -d -t 200 | grep -iE 'FATAL|AndroidRuntime' | head -20
adb shell input tap 540 1200
adb shell input swipe 300 1200 800 1200 300
sleep 1
adb logcat -d -t 100 | grep -iE 'FATAL|AndroidRuntime' | head -20
```

Expected: 安装成功、启动无 FATAL、注入触摸后仍无 FATAL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/nudge/app/ui/TrackpadScreen.kt \
        app/src/main/java/com/nudge/app/ui/glass/GlassShader.kt
git commit -m "feat: trackpad 接入液态玻璃，触点改光晕并修 dp/px bug

原触点半径 48f 是像素写死，xxhdpi 上只有 16dp、mdpi 上是 48dp。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 通知监听服务 label 与应用名解耦

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: 无
- Produces: 无

**背景**：`AndroidManifest.xml:12` 的 `android:label="nudge"` 与 `:44` 服务的
`android:label="nudge"` 是两处独立字面量。上一轮并排装三个变体时发现，系统
「通知使用权」列表里三个变体全叫 nudge，无法分辨该授权给谁。提成资源引用后，
将来再做并排对比只需覆盖一处。

- [ ] **Step 1: 建 strings.xml**

创建 `app/src/main/res/values/strings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">nudge</string>
    <!-- 系统「通知使用权」列表里显示的名字。与 app_name 分开定义：
         并排安装多个调试变体时要靠它分辨该授权给哪一个。 -->
    <string name="notification_listener_label">nudge</string>
</resources>
```

- [ ] **Step 2: 改 manifest 引用资源**

在 `app/src/main/AndroidManifest.xml` 中，把 `<application>` 的
`android:label="nudge"` 改为 `android:label="@string/app_name"`；
把 `<service>` 的 `android:label="nudge"` 改为
`android:label="@string/notification_listener_label"`。

- [ ] **Step 3: 编译验证资源解析正确**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
~/Library/Android/sdk/build-tools/34.0.0/aapt2 dump badging \
  app/build/outputs/apk/debug/app-debug.apk | grep -E "application-label|package: name"
```

Expected: `application-label:'nudge'`、`package: name='com.nudge.app'`

若 `build-tools/34.0.0` 路径不存在，用 `ls ~/Library/Android/sdk/build-tools/` 找实际版本号。

- [ ] **Step 4: 跑全量测试**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test`
Expected: BUILD SUCCESSFUL，78 tests 0 failures

- [ ] **Step 5: 提交**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "refactor: 应用名与通知监听服务 label 提为资源

并排安装多个调试变体时，系统通知使用权列表里全叫 nudge 无法分辨。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 拆除实验室脚手架

**Files:**
- Delete: `app/src/main/java/com/nudge/app/ui/glass/GlassLab.kt`
- Modify: `app/src/main/java/com/nudge/app/ui/SettingsScreen.kt`（回滚 Task 3 的改动）
- Modify: `app/src/main/java/com/nudge/app/MainActivity.kt`（回滚 Task 3 的改动）
- Modify: `CLAUDE.md`

**前置条件**：作者已确认玻璃参数调定、视觉效果满意。**在此之前不要执行本 task。**

- [ ] **Step 1: 删除实验室**

```bash
rm app/src/main/java/com/nudge/app/ui/glass/GlassLab.kt
```

- [ ] **Step 2: 回滚设置页入口**

在 `SettingsScreen.kt` 中删除 `onOpenGlassLab: () -> Unit` 参数与那个
`TextButton(onClick = onOpenGlassLab)` 入口块。

- [ ] **Step 3: 回滚路由**

在 `MainActivity.kt` 中删除 `showGlassLab` 状态、`if (showGlassLab) { ... } else` 分支、
以及 `SettingsScreen(...)` 调用里的 `onOpenGlassLab = { showGlassLab = true },`。

- [ ] **Step 4: 更新 CLAUDE.md**

在 `CLAUDE.md` 的「测试」一节，把「72 个单元测试」改为实际数字（跑一次
`./gradlew test` 看 XML 统计，应为 78）。

在「架构」一节的图里，`TrackpadScreen` 那行下方补一行说明玻璃层：

```
TrackpadScreen / SettingsScreen  (Compose)
        │            └── Modifier.liquidGlass → AGSL RuntimeShader (API 33+)
```

并在「四条不能违反的约束」之后新增一节：

```markdown
## 液态玻璃 shader

触摸区的玻璃质感是手写 AGSL（`ui/glass/`），不是半透明卡片。两个要点：

**折射只在边缘。** SDF 给出到圆角矩形边界的距离，只有 `edgeWidth` 带内的
像素被扭曲，中央原样输出——这既符合真玻璃（平板中央不折射），也是歌词
能保持清晰可读的原因。改 shader 时不要让折射蔓延到中央。

**必须先模糊后折射。** `RenderEffect.createChainEffect(glass, blur)` 的顺序
不能反：折射作用在已模糊的内容上才读作「厚玻璃」，反过来只是一张模糊贴纸。

**要被折射的内容必须在挂了 `liquidGlass` 的 composable 内部。** Android 的
RenderEffect 只能采样图层自身内容，采不到图层背后的像素。所以歌词层是玻璃的
子节点，而触摸反馈画在玻璃**之上**（手指按在玻璃表面，不该被自己折射）。

`RuntimeShader` 需要 API 33+，低于此版本直接退化为不透明卡片——这是刻意的，
本应用是自用工具，不为旧版本维护第二套观感。
```

- [ ] **Step 5: 全量验证**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nudge.app/.MainActivity
sleep 3
adb logcat -d -t 200 | grep -iE 'FATAL|AndroidRuntime' | head -20
```

Expected: 测试全绿、编译成功、安装成功、启动无 FATAL

再确认实验室确实没了：
```bash
ag -u 'GlassLab|onOpenGlassLab|showGlassLab' app/src || echo "脚手架已清除"
```
Expected: `脚手架已清除`

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "chore: 拆除玻璃实验室脚手架，参数已固化

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## 执行顺序与暂停点

Task 1 → 2 → 3 → **暂停，作者真机调参** → 4 → 5 → **暂停，作者确认效果** → 6

Task 5 与 Task 1~4 无依赖，可以任意时机插入。

**两个暂停点是硬性的**：实现者看不到屏幕，参数和最终效果只能由作者判定。

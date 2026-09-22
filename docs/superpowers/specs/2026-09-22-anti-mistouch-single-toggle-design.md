# 防误触三层合并为单一开关

## 背景

当前防误触是三层独立机制，强度递增，但**开关粒度不一致**：

| 层 | 实现位置 | 当前是否可配置 |
| --- | --- | --- |
| 沉浸式粘性 + 全屏手势排除区 | `ui/AntiMistouch.kt`、`MainActivity.onWindowFocusChanged` | 否，硬编码默认生效 |
| 返回键连按两次才退出 | `MainActivity` 主界面的 `BackHandler` | 否，硬编码默认生效 |
| 屏幕固定 | `MainActivity.applyScreenPinning` | 是，`screenPinningEnabled`，默认关 |

前两层不可关，第三层可关，用户在设置页看到的是一个叫「固定屏幕」的开关加一行
「系统栏已默认隐藏」的说明文字——说明文字描述的是关不掉的行为，开关只管第三层。
这个割裂没有道理：三层服务的是同一个目的（盲操时防意外退出），要么都要，要么都不要。

## 目标

把三层合并到一个「防误触模式」开关下，同生同灭。

## 配置模型

### 字段与默认值

`NudgeConfig.screenPinningEnabled: Boolean = false`
→ `NudgeConfig.antiMistouchEnabled: Boolean = true`。

默认值从 `false` 翻到 `true`。理由：合并后这个开关管着原本**默认生效**的两层，
若默认 `false`，沉浸式与双击返回会从「默认开」退化成「默认关」，
对盲操这个核心场景是功能倒退。

### 存储 key

新增 `anti_mistouch_enabled`，旧 `screen_pinning_enabled` **直接废弃**，不做迁移。

已知代价：老用户升级后，旧 key 里存的值被无视，统一按新默认 `true` 起步。
接受这个代价而不写迁移分支，是因为旧值的语义（「是否固定屏幕」）与新语义
（「是否启用整套防误触」）并不等价——把旧的 `false` 迁移成新的 `false`
会顺带关掉用户从没关过的前两层，比直接丢弃更糟。

### 预设编解码

`ProfileCodec` 的 JSON 字段 `KEY_PINNING = "screenPinningEnabled"`
→ `KEY_ANTI_MISTOUCH = "antiMistouchEnabled"`。

老预设的 JSON 里没有新字段，`decode` 的 `optBoolean(key, default)` 宽容回落
会解出默认 `true`，与上面「不做迁移」的口径一致。

### 五处改动

遵守 CLAUDE.md「加配置项要同时改五处」：

1. `NudgeConfig` 的字段与 `DEFAULT`
2. `ConfigStore.config` 的读取 + `setScreenPinningEnabled` → `setAntiMistouchEnabled`
3. `ConfigStore.loadProfile` 的**全量写入**（写 `ANTI_MISTOUCH_KEY`，即便值等于默认）
4. `ProfileCodec` 的 encode / decode
5. `SettingsScreen` 的 UI 与 `MainActivity` 的回调

## 行为

三层统一受 `config.antiMistouchEnabled` 控制。

### 第 1 层：沉浸式 + 手势排除区

`AntiMistouch.kt` 新增两个反向操作：

- `Activity.exitImmersiveMode()`：`setDecorFitsSystemWindows(window, true)`、
  `show(WindowInsetsCompat.Type.systemBars())`
- `View.clearSystemGestureExclusion()`：`setSystemGestureExclusionRects(this, emptyList())`

关闭时**完全恢复系统默认**：显示系统栏、清空排除区、窗口不再铺到物理边缘。
语义最干净——「防误触模式」关 = 就是个普通全屏 app。

`onWindowFocusChanged` 改成双向分支。这里有个时序问题：它是 Activity 回调，
拿不到 Compose 里的 `config`。解法是在 Activity 上存一个字段
（如 `private var antiMistouchEnabled = NudgeConfig.DEFAULT.antiMistouchEnabled`），
由 Compose 侧的 `LaunchedEffect(config.antiMistouchEnabled)` 写入并**立即施加一次**，
这样配置一改就生效，不必等焦点变化。`onWindowFocusChanged` 读该字段决定走哪个分支。

注意：`windowInsetsPadding(WindowInsets.safeDrawing)` 保持不变。
它在非沉浸式下同样正确（此时避的是真实的系统栏而非切口），无需按开关切换。

### 第 2 层：双击返回

`BackHandler(enabled = config.antiMistouchEnabled) { ... }`。

用 `enabled` 参数而不是在回调里判断：关闭时 handler 整个不拦截，
返回键走系统默认直接退出，语义比「拦下来再手动 finish」更准。
设置页的 `BackHandler { showSettings = false }` 不受影响——
那是页面导航，与防误触无关。

### 第 3 层：屏幕固定

`LaunchedEffect(config.screenPinningEnabled)` → `LaunchedEffect(config.antiMistouchEnabled)`，
`applyScreenPinning` 函数体不动（已有 `lockTaskModeState` 幂等判断）。

已知副作用：默认 `true` 意味着**升级后首次进主界面会弹系统的「固定屏幕？」确认框**，
而此前绝大多数用户没开过这一项。这是三合一的必然结果，已确认接受：
确认框只弹一次，不想要的去设置页关掉整个防误触模式。

## 设置页

`SettingsScreen` 的「防误触」分组：

- `OptionRow` 标签「固定屏幕」→「防误触模式」
- `hint` 要把三件事说全，因为用户现在是一次性接受全部副作用：
  系统栏隐藏且边缘滑动需两次才触发返回／主页、返回键连按两次才退出、
  屏幕被固定（退出需长按「返回 + 概览」，手势导航下为上滑并按住）
- 删掉分组末尾那行独立说明文字（`SettingsScreen.kt:189-194`，
  「系统栏已默认隐藏……」）——它描述的是「默认生效、不可关」的旧事实，合并后不成立

回调 `onScreenPinningChange` → `onAntiMistouchChange`。

## 测试

`ProfileCodecTest`：
- round-trip 覆盖新字段（把现有用例里的 `screenPinningEnabled = true` 换成
  `antiMistouchEnabled = false`，用非默认值才能验出 round-trip 真的生效）
- 新增宽容解码用例：JSON 缺 `antiMistouchEnabled` 字段 → 解出默认 `true`

`ConfigStore` 与 `MainActivity` 依赖 Android 框架，按既有惯例真机验证。
关键回归项：
- 关闭防误触模式 → 断言系统栏出现、边缘滑动一次即返回、返回键一次即退出、屏幕未固定
- 开启 → 断言系统栏隐藏、返回键需按两次、弹出屏幕固定确认框
- 切走再切回（触发 `onWindowFocusChanged`）→ 断言两个方向的状态都被正确重新施加
- 存一份「防误触关」的预设 → 改成开 → 加载该预设 → 断言三层都关掉了
  （验 `loadProfile` 的全量写入）

## 文档

`CLAUDE.md` 的「防误触」一节要同步改：三层不再是「默认生效 / 设置项」的混合，
而是统一由一个默认开的开关控制；「屏幕固定默认关」这条结论作废。

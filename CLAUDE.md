# CLAUDE.md

nudge —— Android 手势盲操音乐控制 app。Kotlin + Jetpack Compose，minSdk 26 / targetSdk 34。

## 构建

系统默认 JDK 是 8，跑不了这个项目，**每条 Gradle 命令都要显式带 JAVA_HOME**：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

用仓库内的 `./gradlew`（8.7），不要用系统的 `gradle`——本机那个是 8.2，且在沙箱里会因无法写 `~/.gradle/native` 而失败。Gradle 需要写 `~/.gradle`，跑构建命令时要关沙箱。

## 架构

```
TrackpadScreen / SettingsScreen  (Compose)
        │ MotionEvent
GestureRecognizer  ──► Gesture         ConfigStore (DataStore)
        │
ActionDispatcher  ──► Vibrator
        │
MediaControlRepository ──► NotificationListenerService → MediaSessionManager
                           回退: AudioManager.dispatchMediaKeyEvent

UpdateChecker ──► GitHub API /releases/latest
ApkDownloader ──► UpdateInstaller → FileProvider → 系统安装器
```

`GestureRecognizer` 是**纯 Kotlin 状态机，不依赖任何 Android 类**，所以能在 JVM 上直接单元测试。这是刻意的设计——多指手势边界条件太多，靠真机手测不现实。**改手势逻辑时必须保持这个性质**，不要引入 Android 依赖。

## 四条不能违反的约束

这些都是真机实测踩出来的，不是推测。改动相关代码前先读这里。

### 1. 收藏必须「先读后写」，只点亮永不取消

网易云的收藏入口实测是 **toggle**——它忽略传入的布尔值，只当切换信号。直接调用会把已收藏的歌**取消掉**，而盲操下用户根本察觉不到，属于静默数据损失。

因此 `MediaControlRepository.like()` 必须先读 `METADATA_KEY_USER_RATING` 的 `hasHeart()`，已收藏就直接返回 `AlreadyLiked` 不做任何操作。

注意：`USER_RATING` 对未收藏的歌也报 `isRated=true`，**只有 `hasHeart()` 有区分度**。

### 2. 本应用绝不注册自己的 MediaSession

否则会抢走媒体按键，导致 `dispatchMediaKeyEvent` 回退路径失效。这是社区实测中最常见的坑。

### 3. `holdBaseReadyMs` 必须 ≥ `multiTouchSlopMs`

否则「N 指几乎同时按下」会同时满足「N-1 指底座已就位 + 一指单击」，两个手势判定撞车。宽松档因此取 150ms 而不是等比例缩放的值。改灵敏度参数表时要维持这个不变式，`Sensitivity` 里有对应注释。

另外 `holdBaseReadyMs` 与 `longPressMs` 是**两个独立阈值**，别合并：前者是底座就位的去抖阈值（短），后者管「按太久则不算单击」（长）。

### 4. 拉起安装器必须用 `ACTION_INSTALL_PACKAGE`，不能用 `ACTION_VIEW`

真机实测：`ACTION_VIEW` + APK 的 MIME 会弹「打开方式」选择器，把 APKPure、网易云音乐、
Termux 这些声明了同一 MIME 的应用全列出来，用户得自己认出「软件包安装程序」，选错就装不上。

`ACTION_INSTALL_PACKAGE` 在实测机型上只解析到系统安装器一家，直达安装确认页。它虽然被标了
deprecated（官方推荐 `PackageInstaller` Session API），但那套要多写一个安装结果广播接收器，
对一个手动触发的更新入口不划算。

## 防误触

盲操场景里误触的代价是**不对称**的：误触发一次「下一首」只是烦人，但误滑退出应用后
用户看不见屏幕、根本不知道自己已经退出，后续所有手势都打在别的应用上。所以这里优先
防「意外退出」，而不是防「手势识别错」。

三层，强度递增：

1. **沉浸式粘性 + 全屏手势排除区**（`ui/AntiMistouch.kt`，默认生效）
2. **返回键连按两次才退出**（`MainActivity`，仅主界面，设置页不加）
3. **屏幕固定**（`applyScreenPinning`，设置项，默认关）

### 沉浸式与手势排除区是**配套的**，不能只用一个

系统默认每条边只认最底部 200dp 的手势排除区，全屏范围会被截断。官方对该限制的
唯一豁免是「导航栏处于粘性隐藏状态」——所以 `excludeFromSystemGestures()` 单独调用
在全屏触摸区上基本无效，必须先 `enterImmersiveMode()`。

必须用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，不能用 `BEHAVIOR_SHOW_BARS_BY_SWIPE`：
前者才是「二次触发」语义（第一次边缘滑动只召出系统栏，第二次才真的导航），
后者一次滑动就永久恢复系统栏，起不到防误触作用。

两者都在 `onWindowFocusChanged` 里重新施加，缺一不可：粘性沉浸在切走再切回后会丢失；
排除区要 decorView 的实际尺寸，`onCreate` 时还没测量完，拿到的是 0。

底部 home / 快速切换手势**无法排除**，系统不提供接口。这是必要的——它是用户唯一可靠的
逃生通道，否则粘性沉浸 + 全边排除会把人锁死在应用里。别去找绕过它的办法。

### 屏幕固定默认关，且不做 device owner

非 device owner 时 `startLockTask()` 退化为屏幕固定：弹系统确认框，长按「返回+概览」可退出。
这个强度正好——挡住误触但不锁死用户。真 kiosk 要 DPC 白名单（`setLockTaskPackages`），
需要 device owner 权限，普通应用不该做。

`stopLockTask()` 在未固定时会抛异常，`startLockTask()` 在已固定时无效果，故调用前先查
`lockTaskModeState`。有测试拦着「默认关」这个方向。

### 没做接近传感器口袋模式

它与 `FLAG_KEEP_SCREEN_ON`（盲操要保持亮屏）的设计意图冲突，且各家 ROM 传感器行为差异大，
容易变成新的 bug 源。这是权衡后的决定，不是遗漏。

### 沉浸式的副作用：窗口铺到物理边缘

`setDecorFitsSystemWindows(false)` 后内容会画到刘海／挖孔下面，两个页面的根布局都加了
`windowInsetsPadding(WindowInsets.safeDrawing)`。用 `safeDrawing` 而非 `statusBars`——
系统栏此时本就是隐藏的，真正要避的是切口。

## 应用内更新

设置页手动触发，不做启动自动检查——这是刻意的，盲操工具不该在启动时弹更新提示。

版本比较**只做字符串不等判断**，不解析版本号大小：`/releases/latest` 就代表官方认定的当前版本，
本地与之不一致即非最新。已知副作用是本地 debug 包（versionName 回落为 `1.0`）总会提示有更新，
手动入口下可以接受。

`REQUEST_INSTALL_PACKAGES` 权限在 Android 8+ 还需要用户在系统设置里单独授予，
`UpdateInstaller.canInstall()` 先查再跳，否则拉安装器会被静默拦下。

## 媒体控制的适用范围

- **下一首**：对任意播放器有效。目标优先网易云，无网易云会话时取第一个 PLAYING 的会话。
- **收藏**：**仅网易云**。走 custom action 动态查找（匹配 `STAR` 或 name 含 `like`），**不要硬编码 action id**，以适应网易云改版。

真机实测网易云的 `actions` 位掩码**不含** `ACTION_SET_RATING`，所以收藏只能走 custom action。设计文档 §5.2 里的 `setRating` 写法是早期方案，以代码为准。

## 测试

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
```

75 个单元测试，主体在 `GestureRecognizer`——正例（五种手势 × 三档灵敏度）、边界（阈值临界）、负例（滑动、指数不符、超时）。**动手势逻辑必须补相应测试**，尤其是防误触的负例。

`org.json` 在 JVM 单测里只有会抛「not mocked」的桩实现，所以 `build.gradle.kts` 里额外引了
`org.json:json` 作为 testImplementation 覆盖掉它。**不要改用 `returnDefaultValues = true`**——
那会让所有未 mock 的 Android 调用静默返回 null，把真实失败一并掩盖掉（`ReleaseInfo.parse`
的解析失败正是被它掩盖过一次）。

涉及媒体控制和震动的部分没有自动化测试，需要真机验证。关键回归项：对**已收藏**的歌重复执行收藏手势，断言 `hasHeart` 保持 true 不变（防 toggle 缺陷回归）。

## 发布

推 `v*` tag 触发 GitHub Actions 自动构建签名 APK 并发布 Release。版本号从 tag 取，无需改 `build.gradle.kts`。

`app/build.gradle.kts` 里 `versionName`/`versionCode` 读环境变量、signingConfig 在 keystore 缺失时不注册——这是为了让本地构建不受影响，别改成硬失败。

## 约定

- 代码注释和文档用中文
- 注释写**为什么**，不写代码已经表达的**是什么**。现有注释里大量记录了真机实测结论和设计约束，这是这个项目注释的主要价值，保持这个风格
- 文档和注释里不写本机绝对路径，用 `<项目根目录>`、`<Android SDK 路径>` 这类占位符代替。这是 public repo，`/Users/<用户名>/...` 会暴露开发机的用户名和目录结构

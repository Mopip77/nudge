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
        │                                   ▲── ProfileCodec (预设 JSON)
        │                                   ▲── ProfileCommandReceiver ◄── 广播 (HA / adb / Tasker)
        │                                   ▲── ProfileShortcutActivity ◄── 动态 shortcut
        │                                   │       ▲ ProfileShortcuts.sync  (launcher / 三星 M&R)
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

#### 灵敏度只在 debug 包里可调

三档的差别要连着试才分得出来，而盲操用户没有对照条件，把选项摆出来只会让人
凭感觉乱选、再把误触归咎于应用。所以设置页的「灵敏度」分组包在 `BuildConfig.DEBUG` 里，
**release 恒为 `STANDARD`**。

`Sensitivity` 枚举、`NudgeConfig.sensitivity` 字段、DataStore key、`ProfileCodec`
的编解码**全部保留**，只藏 UI——Kotlin 没有条件编译，真要按变体摘字段得分叉 source set，
成本高且收益可疑；保留字段还让 `GestureRecognizer` 的三档测试继续有效。

release 的读取侧（`ConfigStore.config`）**忽略存量值**而非沿用：装过 debug 又换回
release 的用户会留下一个自己既看不到、也改不回的非标准档位，线上问题就无从复现。
忽略但**不写盘**，换回 debug 仍是原值。

真机验证过这条闭环（debug 调成严格 → 换 release，UI 消失且 DataStore 仍是 `STRICT`
→ 换回 debug，严格档还在）。判断装的是哪个变体看 `dumpsys package com.nudge.app`
的 `flags`：debug 包带 `DEBUGGABLE`，release 没有。

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

1. **沉浸式粘性 + 全屏手势排除区**（`ui/AntiMistouch.kt`）
2. **返回键连按两次才退出**（`MainActivity`，仅主界面，设置页不加）
3. **屏幕固定**（`applyScreenPinning`）

### 三层由一个开关统管，同生同灭

配置项是 `antiMistouchEnabled`（设置页的「防误触模式」），**默认开**。

它们服务的是同一个目的，分开配置没有道理——早先只有第 3 层可配、前两层硬编码
默认生效，用户看到的是一个叫「固定屏幕」的开关加一行「系统栏已默认隐藏」的说明，
开关管不着说明里写的事。

关闭时**完全恢复系统默认**（`exitImmersiveMode` + `clearSystemGestureExclusion`
+ `stopLockTask`），而不是只松一半：语义要么是「防误触的 app」要么是「普通全屏 app」，
中间态只会让人猜不透当前到底拦不拦返回手势。

默认开是刻意的：这个开关管着原本默认生效的前两层，若默认关，它们会从「默认开」
退化成「默认关」，对盲操这个核心场景是功能倒退。代价是升级后首次进主界面会弹
屏幕固定的系统确认框（此前多数用户没开过这项），确认框只弹一次，不想要的整个关掉。
`NudgeConfigTest` 有测试拦着「默认开」这个方向。

开关值由 `LaunchedEffect` 写进 `MainActivity.antiMistouchEnabled` 字段供
`onWindowFocusChanged` 读取——后者是 Activity 回调，拿不到 Compose 里的 config。
该字段初值是 **null**（而非 `DEFAULT` 的 true）：config 首帧必然是 `DEFAULT`，
真实值稍后才从 DataStore 到达，若用 true 起步，关掉防误触的用户每次启动都会
被先固定一下屏幕再解开。null 表示「配置未就绪，什么都别做」。

第 2 层用 `BackHandler(enabled = ...)` 而非在回调里判断：关闭时 handler 整个不拦截，
返回键走系统默认，语义比「拦下来再手动 finish」更准。

#### 废弃了旧的 `screen_pinning_enabled` key

新 key 是 `anti_mistouch_enabled`，旧的直接弃用不做迁移。旧值语义是「是否固定屏幕」，
与新的「是否启用整套防误触」不等价——把旧的 false 迁过来会顺带关掉用户从没关过的
前两层，比丢弃更糟。老数据（DataStore 旧 key、预设 JSON 里的 `screenPinningEnabled`）
一律走宽容回落，解出新默认「开」。

### 沉浸式与手势排除区是**配套的**，不能只用一个

系统默认每条边只认最底部 200dp 的手势排除区，全屏范围会被截断。官方对该限制的
唯一豁免是「导航栏处于粘性隐藏状态」——所以 `excludeFromSystemGestures()` 单独调用
在全屏触摸区上基本无效，必须先 `enterImmersiveMode()`。

必须用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，不能用 `BEHAVIOR_SHOW_BARS_BY_SWIPE`：
前者才是「二次触发」语义（第一次边缘滑动只召出系统栏，第二次才真的导航），
后者一次滑动就永久恢复系统栏，起不到防误触作用。

两者都在 `onWindowFocusChanged` 里重新施加，缺一不可：粘性沉浸在切走再切回后会丢失；
排除区要 decorView 的实际尺寸，`onCreate` 时还没测量完，拿到的是 0。撤销侧同理，
两个反向操作也要成对调用。

底部 home / 快速切换手势**无法排除**，系统不提供接口。这是必要的——它是用户唯一可靠的
逃生通道，否则粘性沉浸 + 全边排除会把人锁死在应用里。别去找绕过它的办法。

### 屏幕固定不做 device owner

非 device owner 时 `startLockTask()` 退化为屏幕固定：弹系统确认框，长按「返回+概览」可退出。
这个强度正好——挡住误触但不锁死用户。真 kiosk 要 DPC 白名单（`setLockTaskPackages`），
需要 device owner 权限，普通应用不该做。

`stopLockTask()` 在未固定时会抛异常，`startLockTask()` 在已固定时无效果，故调用前先查
`lockTaskModeState`。

### 没做接近传感器口袋模式

它与 `FLAG_KEEP_SCREEN_ON`（盲操要保持亮屏）的设计意图冲突，且各家 ROM 传感器行为差异大，
容易变成新的 bug 源。这是权衡后的决定，不是遗漏。

### 沉浸式的副作用：窗口铺到物理边缘

`setDecorFitsSystemWindows(false)` 后内容会画到刘海／挖孔下面，两个页面的根布局都加了
`windowInsetsPadding(WindowInsets.safeDrawing)`。用 `safeDrawing` 而非 `statusBars`——
系统栏此时本就是隐藏的，真正要避的是切口。

这个 padding **不随防误触开关切换**：`safeDrawing` 在非沉浸式下同样正确
（此时避的是真实的系统栏而非切口），不必也不该分两套。

## 配置预设

三个固定槽位，每个存一份完整 `NudgeConfig` 快照 + 名字，JSON 序列化进 DataStore
的 `profile_1/2/3`。编解码在 `ProfileCodec`，纯 Kotlin 无 Android 依赖，可 JVM 单测。

### 加配置项要同时改五处

`NudgeConfig` 加字段时，漏掉任何一处都会留下静默的缺陷：

1. `NudgeConfig` 的字段与 `DEFAULT`
2. `ConfigStore.config` 的读取（读不到回落默认）与对应 setter
3. **`ConfigStore.loadProfile` 的写入**——见下条，漏了会让加载预设时该项不被重置
4. `ProfileCodec` 的 encode / decode（decode 要宽容回落）
5. 设置页的 UI 与 `MainActivity` 的回调

### 加载预设必须全量写入

`loadProfile` 要把五个配置项**全部**写进 DataStore，包括值等于默认值的项，
不能做「等于默认就不写」的优化。`bindings` 的读取侧口径是「没写过 key 才回落默认，
写过空串表示用户主动清空」——跳过写入会把用户存的空绑定静默恢复成默认绑定。

### 不记录「当前预设」

加载是一次性覆写，之后改配置与来源预设再无关系，三个槽位没有「选中」态。
若记录 activeProfile 并在改配置时自动写回，盲操下用户改了灵敏度就会静默污染存档，
和收藏那条 toggle 缺陷是同一类问题。「覆盖」是唯一的写回路径，且带二次确认。

### 槽位号固定 1..3，删除后不重排

槽位号是对外标识——广播的 `slot` 参数与 shortcut 的 `id`（`profile_$index`）都用它，
重排会让已配置好的自动化指向别的预设。
同理只支持按槽位号切换，不支持按名字——名字可改，改完外部配置就断了。

### 三星「模式与日常安排」读的是动态 shortcut

M&R 不需要任何私有 SDK，也不需要 Tasker 中转：它持有系统权限
`ACCESS_SHORTCUTS`（launcher 枚举 shortcut 用的就是它）与
`RESET_SHORTCUT_MANAGER_THROTTLING`，走标准 `LauncherApps` API 读第三方应用的
shortcut，在「添加动作 → 其他应用程序」里把它们列成子动作。

真机取证（Android 13 / One UI 5.1）：`dumpsys package com.samsung.android.app.routines`
里两个权限都 `granted=true`；存两个预设后 `dumpsys shortcut` 出现两条
`flags=0x85 [DynIc-rStr]`，`/data/system_ce/0/shortcut_service/packages/com.nudge.app.xml`
里 title 就是用户起的预设名。

所以 `ProfileShortcuts.sync` 把已保存的预设写成动态 shortcut，
入口是 `ProfileShortcutActivity`。只为**已保存**的预设生成：
空槽位不列，避免 M&R 里出现「点了没反应」的死项。

`setDynamicShortcuts` 而非 `addDynamicShortcuts`——整组替换语义让删除预设后
对应 shortcut 自动消失，不必单独 remove。

不判断 `isRateLimitingActive()`：频率限制只作用于后台应用，而 sync 的调用时机是
用户在设置页存/删预设，那必然是前台，且「应用进入前台」本身就会重置计数器。

shortcut 的 label 用预设名字而非「槽位 N」：预设的辨识本来就全靠名字
（这也是当初决定不显示配置摘要的理由），M&R 的动作列表里显示槽位号同样认不出来。
代价是改名后 M&R 里已配置的动作要重选一次——可接受，`id` 仍是稳定的 `profile_$index`。

#### 入口 Activity 不能用 `Theme.NoDisplay`

targetSdk ≥ 23 上它会抛 `IllegalStateException`
（"did not call finish() prior to onResume() completing"）。必须用
`Theme.Translucent.NoTitleBar`（即 `Theme.Nudge.Invisible`），
这也是官方与 AOSP Email 的修法。

配 `excludeFromRecents` + `noHistory`，保证它不出现在最近任务里、不留栈。
`finish()` 无条件调用且放在 try 之外——任何分支都不能留一个透明 Activity 挂在栈上。

#### 广播入口保留

shortcut 点击是 `startActivity` 而非广播，所以两条路径不能共用一个入口。
广播留给不会枚举 shortcut 的工具（HA、adb），且与 `MediaCommandReceiver` 协议风格一致。

刻意不做 deep link Activity：它会把应用弹到前台，而「开车时切到驾驶模式」
这种场景下突然弹出全屏触摸板是危险的。广播与透明 shortcut Activity 都不改变应用可见性
（真机实测：应用未运行时触发 shortcut，launcher 保持在前台，最近任务里不新增条目）。

`onReceive` 与 `onCreate` 里都用 `runBlocking` 而非异步协程：返回后进程可能立即被回收
（Activity 则是紧接着 finish），异步写 DataStore 会来不及执行完。写入是毫秒级，
远在 10 秒配额内。

## 歌词展示

对齐 Apple Music 的观感，`ui/LyricsOverlay.kt`。**纯展示，绝不接触摸**——
盲操下可交互的歌词会与手势语义冲突。

### 滚动是逐行弹簧，不是整列平移

每行持有自己的 `Animatable` 去追同一个目标位移，**刚度随距离递减**
（900 → 28，4 行内跑完）。别改回「整列一个 `translationY` + 单条补间」：
那样所有行同时同速移动，就是 Apple Music 与普通歌词控件最直观的差别。

错峰与阻尼是**同一套机制的两个侧面**，所以没有独立的 delay 参数：
刚度递减既产生相位差（下一行先顶上来、后面几行被依次拖上来），
又让远处行阻尼比更低（从裁切边界外进来时的过冲回弹）。
加第二套动画去单独控制其中一个，两条曲线会互相打架。

`key(index)` 绑绝对行号同时决定每行 Animatable 的身份。绑错会让某行的
弹簧状态被下一行接手，位移从别人的当前值继续跑，表现为换行时随机抽搐。

### 字号统一，焦点只由清晰度建立

当前行与其余行**同字号**，区分全靠透明度 + 模糊。早先当前行大一档、
靠 scale 放大，衍生出一串补偿逻辑（排版宽度反向收窄、当前行行高补足、
scale 必须排在 blur 之前），统一字号后这些全部不需要了。

模糊**第 1 行即起步**，缓升到最远 9dp。封顶要守住「最远处仍认得出字」
——早先 12dp 那版第 5 行开外糊成色块，层次其实止步于前四行。

**峰值与跨度要配着调**（`MAX_BLUR` / `BLUR_RAMP_LINES`）：决定观感的是
曲线斜率而不只是峰值。只抬峰值不拉跨度，紧邻当前行的一两行会跟着糊掉，
"预读下一句"就没了。

淡入淡出（260ms）要**短于**位移落定的时间：等长时换行途中新的当前行会
「边移动边对焦」，先建立焦点再收尾位移才对。

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

94 个单元测试，主体在 `GestureRecognizer`——正例（五种手势 × 三档灵敏度）、边界（阈值临界）、负例（滑动、指数不符、超时）。**动手势逻辑必须补相应测试**，尤其是防误触的负例。

预设部分由 `ProfileCodecTest` 覆盖 round-trip 与宽容解码，`ProfileSlotTest` 覆盖槽位号解析。

`org.json` 在 JVM 单测里只有会抛「not mocked」的桩实现，所以 `build.gradle.kts` 里额外引了
`org.json:json` 作为 testImplementation 覆盖掉它。**不要改用 `returnDefaultValues = true`**——
那会让所有未 mock 的 Android 调用静默返回 null，把真实失败一并掩盖掉（`ReleaseInfo.parse`
的解析失败正是被它掩盖过一次）。

涉及媒体控制、震动、shortcut 的部分没有自动化测试（Activity 与 ShortcutManager
都是 Android 框架），需要真机验证。关键回归项：

- 对**已收藏**的歌重复执行收藏手势，断言 `hasHeart` 保持 true 不变（防 toggle 缺陷回归）
- 把某动作的手势全部取消勾选 → 存成预设 → 改回有绑定 → 加载该预设 →
  断言该动作仍显示「未绑定」（防 `loadProfile` 跳过写入的缺陷回归）
- 防误触模式开/关各一次，断言三层同步变化。`dumpsys` 能同时看到三层的状态，
  不必靠肉眼判断：

  ```bash
  adb shell dumpsys activity activities | ag -u 'mLockTaskModeState'   # 第 3 层
  adb shell dumpsys window | ag -u 'mSystemGestureExclusion'           # 第 1 层
  ```

  开启时应分别是 `PINNED` 与覆盖整屏的 `SkRegion`（如 `(0,78,1080,2400)`，
  78 是刘海高度）；关闭时是 `NONE` 与只剩滚动条的小矩形。第 2 层看返回键
  按一次是否退出。
- 存两个预设后查 shortcut，断言两条都在且 title 是用户起的名字；
  删掉一个后再查，断言只剩一条（防 sync 漏调或误用 `addDynamicShortcuts` 回归）：

  ```bash
  adb shell 'su -c "cat /data/system_ce/0/shortcut_service/packages/com.nudge.app.xml"' \
    | tr -c '[:print:]\n' '\n' | ag -u 'profile_'
  ```

  `dumpsys shortcut` 会把 label 打成 `***`（即便有 root），要看真实文案只能读上面这个
  落盘文件。
- 应用**未运行**时触发 shortcut，断言 launcher 仍在前台、最近任务里不新增 nudge 条目、
  logcat 无「did not call finish」异常：

  ```bash
  adb shell am force-stop com.nudge.app
  adb shell am start -n com.nudge.app/.config.ProfileShortcutActivity --es slot 1
  ```

## 发布

推 `v*` tag 触发 GitHub Actions 自动构建签名 APK 并发布 Release。版本号从 tag 取，无需改 `build.gradle.kts`。

`app/build.gradle.kts` 里 `versionName`/`versionCode` 读环境变量、signingConfig 在 keystore 缺失时不注册——这是为了让本地构建不受影响，别改成硬失败。

## 约定

- 代码注释和文档用中文
- 注释写**为什么**，不写代码已经表达的**是什么**。现有注释里大量记录了真机实测结论和设计约束，这是这个项目注释的主要价值，保持这个风格
- 文档和注释里不写本机绝对路径，用 `<项目根目录>`、`<Android SDK 路径>` 这类占位符代替。这是 public repo，`/Users/<用户名>/...` 会暴露开发机的用户名和目录结构

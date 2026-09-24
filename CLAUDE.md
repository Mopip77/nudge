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
        ├── PlayerHeader     两种显示模式共用，差异只有 onDark
        ├── AlbumBackdrop    仅专辑封面模式：模糊铺底 + 清晰封面 + 压暗
        └── LyricsOverlay    两种模式共用，仅 textColor 不同
        │ MotionEvent
GestureRecognizer  ──► Gesture         ConfigStore (DataStore)
        │                                   ▲── ProfileCodec (预设 JSON)
        │                                   ▲── ProfileCommandReceiver ◄── 广播 (HA / adb / Tasker)
        │                                   ▲── ProfileShortcutActivity ◄── 动态 shortcut
        │                                   │       ▲ ProfileShortcuts.sync  (launcher / 三星 M&R)
ActionDispatcher  ──► HapticPalette → HapticSpec.render() → HapticPlayer → Vibrator
        │                    ▲── HapticOverride ◄── HapticLabScreen (debug)
        │
MediaControlRepository ──► NotificationListenerService → MediaSessionManager
                           回退: AudioManager.dispatchMediaKeyEvent

ArtworkCache ──► ArtworkFetcher ──► 网易云 song/detail (高清封面, 绕开 MediaSession)
                      ▲── CoverAspect.paramFor ◄── CoverOverride ◄── CoverLabScreen (debug)

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

## 滑动手势

点击类之外的第二个维度。加它的动机是：原有五个手势全是 tap 家族，彼此只靠「几根手指」
和「双击 vs 长按+点」区分，而盲操下这两个维度都容易出错（手指数自己数不清，
tap 节奏受走动影响）。**方向是身体记得住的**，区分度最高。

`MOVE` 原本只用来**否决**手势，现在也能产出手势——这是本次改动的实质。

### 死区：`swipeMinDistanceDp` 必须 > `moveToleranceDp`

两个阈值之间是死区：位移超过 `moveToleranceDp` 时点击类已被否决，
但不到 `swipeMinDistanceDp` 又不构成滑动，于是**什么都不触发**。

这是刻意的。若两者相等，手抖到恰好越过容差就会立刻判成滑动，把「想双击但手不稳」
变成一次误触发。盲操下宁可不触发也不要触发错。`GestureRecognizerTest`
有测试拦着这条不变式。

### 判定条件，以及为什么是「都超过」而非「平均超过」

1. 本批**恰好**两根手指（`batchPeakFingers == 2`）——要求等于而非 ≥，
   否则三指滑动会被降级识别成两指滑动
2. 两指竖直位移**同向**且**都**超过 `swipeMinDistanceDp`
3. 两指横向位移都不超过 `swipeMaxCrossDp`（保证是竖直滑动而非斜划）

条件 2 取「都超过」：一根划够、另一根几乎没动更像握持时的单指误划，不该算双指滑动。
两指反向（一上一下）是缩放之类的动作，也不产出。

### `lastPositions` 抬起时不删除

滑动判定发生在**最后一根手指抬起**的那一刻。先抬起的那根若已被移除，
就只剩一根手指的位移可算，「两指同向」这个核心条件无从验证。
整批结束时统一清，`onDown` 开批时也清一次（上一批的残留会污染位移计算）。

### 滑动判定必须排在双击判定之前

一次两指滑动同样满足「两指按下又抬起」。若先走双击分支，它会被记为 `lastTap`，
与下一次滑动凑成一次「两指双击」。产出滑动后要把 `lastTapFingers` / `lastTapEndMs`
清掉，否则连续两次滑动会额外触发一次双击。

### 真机注入多指手势的办法

`adb shell input` 只能单指，`getevent` 在三星上被 One UI 挡住（`-pl` 能列设备，
`-lt` 一行事件都抓不到，且无报错）。但 `sendevent` 可以**注入**——这台屏是
type B 多点协议（有 `ABS_MT_SLOT`），按槽位写就能模拟两根手指：

```sh
DEV=/dev/input/event3
sendevent $DEV 3 47 0      # ABS_MT_SLOT = 0
sendevent $DEV 3 57 100    # ABS_MT_TRACKING_ID，建立接触点；-1 为销毁
sendevent $DEV 3 53 $X     # ABS_MT_POSITION_X，原始量程 0..4095
sendevent $DEV 3 54 $Y     # ABS_MT_POSITION_Y
sendevent $DEV 0 0 0       # SYN_REPORT
```

坐标要从屏幕像素换算到原始量程（本机 1080×2400 → 0..4095）。
脚本要整个放在设备端跑：每条 `adb shell` 往返几十毫秒，
分多次调用会把双击窗口（350ms）撑爆，表现为「双击测不出来」而误判成回归。

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

### 手势绑定是二级菜单

设置页一级只列动作（`NavRow`，行数恒等于动作数），点进去才是 `GestureBindingScreen`
的手势多选页。早先是动作 × 手势**全平铺**，行数随两者相乘增长——
加一个动作和两个手势就从 10 行涨到 21 行，是平方级恶化。

一级页的摘要直接列出已绑手势名，让「哪个动作还没绑」一眼可见；
这对默认不绑的播放/暂停尤其重要。

二级页把手势分成「点击类」「滑动类」两组：两类的触发方式完全不同
（要划够距离 vs 点住不动），混在一起列会让用户以为是同一类操作的变体。

抢占语义沿用平铺版——被别的动作占用的手势照样可勾，只是多一行
「当前属于「X」，勾选将移交」的提示。互斥由 `ConfigStore.addBinding` 保证。

导航沿用实验室那套布尔量 `if/else`，但状态是**可空的 `ActionType`** 而非布尔量：
这一页必须知道在给哪个动作配手势。判断要排在 `showSettings` 之前，否则会被
设置页那一支拦截。

### `NudgeConfig.DEFAULT.bindings` 要列全每个 `ActionType`，包括空集

读取侧（`ConfigStore.config`、`ProfileCodec.decode`）都按 `ActionType.entries`
**全量**构造 map，所以那里「缺 key」与「空集」等价——但对 `equals` **不等价**。
`DEFAULT` 里漏写某个动作，round-trip 出来的 config 会多一个空集键，
与 `DEFAULT` 判不相等，`ProfileCodecTest` 的四个 round-trip 用例会一起挂。

加动作时若默认不绑任何手势，要显式写 `ActionType.XXX to emptySet()`，
不能靠「不写」来表达。同理 `ProfileCodecTest` 里自己构造 bindings 的用例
也要给出每个动作的条目。

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

## 两种显示模式

播放界面有两种外壳，由 `NudgeConfig.displayMode` 选择，入口在设置页
「显示模式」分组。

- **简洁模式**（`SIMPLE`，默认）：顶栏 + 一块圆角卡片，歌词画在卡片里。
  这是早先唯一的形态。
- **专辑封面模式**（`ALBUM`）：整屏专辑封面，没有卡片。

简洁模式保留下来**不只是为了兼容**：它不显示大封面，工作场合不会一眼
被看出在放歌。这是个真实需求，别把它当成过渡态删掉。默认值取 `SIMPLE`
也是因此——默认切成封面模式等于替用户做了一个会暴露的决定。
`NudgeConfigTest` 有测试拦着这个方向。

### `displayMode` 与 `lyricsEnabled` 正交

前者管「壳」，后者管歌词显隐，四种组合都成立。

不能合并成一个三选一的枚举（简洁 / 封面 / 歌词）：`ActionType.TOGGLE_LYRICS`
那个动作切的是**歌词**，不是布局，合并后它就没有对应的语义了。

### 封面模式的背景是**同一张图的多档模糊叠加**

`ui/AlbumBackdrop.kt`。四档模糊（0 / 6 / 18 / 44dp）**都是同一张图、
同一套排版**，只有半径不同，再用竖向渐变蒙版按屏幕位置把它们揉在一起。

「中间清晰、上下模糊」没法靠单个 `blur` 修饰符做到——`BlurNode` 恒
`clip=true` 且裁到自己那层的排版矩形，一张图不可能只糊一部分。

#### 走过的弯路：「方形清晰封面 + 外围模糊铺底」不行

第一版是两张图：中间一张原比例的方形清晰封面，底下一张模糊铺底，
交界处靠羽化融合。**实测仍然割裂**，而且羽化参数怎么调都救不回来。

原因是那**两张图的尺度不同**（一张原比例、一张放大裁切），羽化只能让
边界变软，变不掉「里面是一张方形图片、外面是另一层背景」这个事实：
交界处两侧的纹理对不上，人眼对这种不连续极其敏感。

改成多档模糊之后，各层**像素一一对应**，过渡处只是同一个像素在
「清晰版」和「模糊版」之间插值，因此不可能出现纹理错位，也就没有
边界可言。这是个结构性的差别，不是参数问题——**别再回头去调羽化**。

参照物是 Apple Music 的锁屏：衣服、头发的纹理是顺着往外延伸的，
没有任何一处能指出「边界在这」。

#### 清晰那一档按**图自身比例**摆，不能 Crop 铺满竖屏

方图 `ContentScale.Crop` 填满 1080×2400 要放大 6.6 倍，只能看到中间一条
竖缝——实测就是「一张脸加半个肩膀」，整张专辑封面根本看不全。
Apple Music 那种观感里，**清晰的那一段是完整的封面**，往外才是它自己的延伸。

所以各档都按「宽度铺满、**高度按图自身宽高比**」摆放，只有最糊的兜底层
纵向拉满全屏（它只提供色块与纹理，拉伸看不出来，但能保证上下最远端
不露出背景色）。

高度跟随比例而非写死方形，是因为默认已改成竖屏比例（见下）：
4:5 的图请求回来是 1080×1350，塞进方框会被压扁。

##### 不能给高度设上限

试过夹一个 `0.72`（「别让清晰区吃掉整屏」），**竖图上下沿立刻露出两道硬横边**
——实测单行亮度跳变 28.5 / 19.1，而正常时只有 0.8~3。

原因是蒙版的渐变跨度按「本层边缘」算，夹掉高度之后图被 `FillBounds`
压进更矮的框里，渐变却仍按原比例铺，于是**还没收干净就到了边缘**。
要限制清晰区大小只能动 `reach` 的系数（那是蒙版内部的量），
不能动本层的排版高度。

#### 清晰区半径必须**明显小于**封面自身的半高

各档「完全不透明」的竖向半径最宽只取到封面半高的 0.46 倍，剩下的
全是渐变过渡的余量。按半高取值的话过渡段被挤成 0，**方形封面的上下沿
直接露出两道硬横边**，正是这套方案要消灭的东西。

同理，蒙版的过渡段要一直铺到本层的上下边缘（`span` 取「从不透明区
边界到本层边缘」的全部距离），只要在边缘处还没衰减到全透明，
方形的边就会露出来。

#### 蒙版的比例基准是**屏幕**，绘制坐标是**本层**

`fadeFrom` / `fadeTo` 按占屏幕高度传入，而 `drawWithContent` 的坐标是
本层自己的（方形层只有屏幕的一部分高），必须换算：

```kotlin
val from = (fadeFrom - topRatio) / heightRatio
```

不换算的话蒙版会整体偏上、且跨度被放大。

#### 渐变色标必须严格递增，且别用 `coerceIn` 去夹

清晰区贴着屏幕边缘时，几个色标夹回 `[0,1]` 后会撞到一起，`Brush` 直接抛。

**别写成 `v.coerceIn(last + eps, 上界)`**：`last` 顶到上界时下界会超过上界，
`coerceIn` 抛的是 `Cannot coerce value to an empty range`——真机上就是
一进封面模式立刻崩。正确写法是先各自夹好、再逐个抬到比前一个大一点：

```kotlin
last = maxOf(v.coerceIn(0f, 1f), last + eps)
```

### 模式切换只 crossfade，不动 blur 半径

歌词开关时，除兜底层外的各档 alpha 一起淡出、压暗层加浓（300ms）。
**不对 `blur` 半径做动画**：半径每变一次都要重新生成模糊，全屏尺寸下
每帧重算会和歌词的逐行动画、手势识别抢同一帧的预算。

`AlbumArt` 那个暂停模糊确实在动半径，但那是 52dp 的小图，全屏是另一回事。

#### 歌词态要淡出**全部**清晰档，不能只淡出第 0 档

中间那两档（6dp / 18dp）仍然保留着可辨认的结构。只淡出全清晰那一档的话，
**歌词会压在一张认得出五官的脸上**，背景在跟文字抢注意力。
歌词态的口径是「只看到色彩氛围，认不出封面细节」。

### 浅色封面下的可读性靠**分区**遮罩

不能假设封面是深色的（实测苏打绿「迟到千年」那张几乎全白，白字直接糊掉）。

但把全屏遮罩加浓到浅色封面也能读的程度，深色封面就会被压成一团黑，
封面模式的意义就没了。所以按区域给：**文字只出现在顶栏那一条**，
就只在那里额外叠一道自上而下由浓转无的渐变，中间的封面主体保持通透。

没做「采样封面亮度动态决定黑字白字」：封面上下亮度不均时仍会局部读不清，
且会出现同一首歌文字颜色跳变。分区遮罩是静态的，不存在这两个问题。

顶栏渐变的高度取顶栏的 1.35 倍，让渐变的尾巴落在顶栏下沿**之外**——
正好在下沿收干净的话，会显出一条能看见的横边。

### 封面模式恒为暗底白字，不跟随主题

底色完全由封面决定，跟着明暗主题走没有意义。`themeMode` 因此只影响
设置页与简洁模式，这一点在设置页的「主题」分组下明说了一次——
不说明的话，用户在封面模式下切主题会以为是开关坏了。

说明挂在**分组下而不是每个 `OptionRow` 的 hint 里**：三个选项各印一遍
同样的话，反而像是在描述三个不同的选项。

`LyricsOverlay` / `LyricsScroller` 因此多了一个 `textColor` 参数，
默认 null 表示跟随主题（简洁模式与实验室走这条），封面模式显式传白色。

### 顶栏两个模式共用同一个组件

`ui/PlayerHeader.kt`，位置、尺寸、间距完全一致，差异收敛到 `onDark`
一个参数。切模式时这块必须**纹丝不动**，才像「同一个播放器换了内容」
而不是跳转到了另一个页面。

封面模式传 `drawBackdrop = false`：整屏已经是封面了，顶栏再自己铺一层
模糊封面会在信息区下沿戳出一道能看见的暗边。

收藏红（`LIKE_RED`）**不跟着 `onDark` 变白**：它是状态指示而非装饰，
变白会让「已收藏」和「未收藏」失去区分。

### 手势区范围两个模式完全一致

恒为**顶栏以下**那块，触摸修饰符也是同一份（`touchModifier`）。
封面模式下虽然视觉上没有边界了，但触摸区并没有扩张到整屏。

顶栏要留给设置按钮：整屏接管会让落在按钮上的那根手指被识别器吞掉
半个多指手势。分别写两份修饰符同样不行——改了一处忘了另一处就会出现
「换个模式手势就不灵」。

### 封面的竖直位置由 `SHARP_CENTER` 定，取 0.52

**略低于**屏幕正中。方图时代取的是 0.46（略高于正中）——方图只占屏高 45%，
上方空得多，不往上提会显得坠底。

换成竖屏比例后这个值必须改：4:5 占屏高 56%，同样的中心会把清晰区
顶进顶栏，**封面自身的文字与顶栏的歌名叠在一起**（实测「滚石30周年」
那张，专辑的美术字正好压在标题行上，两行字互相盖住，截图一眼可见）。
往下挪之后顶栏那一条留给文字，清晰区完整落在它下方。

各档的清晰区都以这个中心向上下展开。

背景本身要铺满整个窗口（含顶栏背后），否则顶上会留一条突兀的背景色。

## 高清封面：MediaSession 只有 363，绕开它去查网易云

### MediaSession 这条路到头了

**真机取证**（网易云 / One UI 5.1，加临时日志打 `MediaMetadata` 实况）：

- 三个 bitmap key（`ALBUM_ART` / `ART` / `DISPLAY_ICON`）**全是 363×363**，
  同一张图，换几首歌都一样
- 三个 URI key（`ALBUM_ART_URI` / `ART_URI` / `DISPLAY_ICON_URI`）**全是 null**

所以经 MediaSession 拿不到更高清的封面，**别再在 `MediaMetadata` 里找**。
铺满 1080px 宽是 3 倍上采样，封面模式下糊得肉眼可见。

### 改为用歌曲 id 查 `song/detail` 拿 `picUrl`

`media/ArtworkFetcher.kt`。`METADATA_KEY_MEDIA_ID` 就是网易云的真实歌曲 id
（与歌词同源，依据见 `LyricsFetcher` 的注释），所以能直接查，
**无需按歌名搜索匹配**。实测原图 **640~1700 见方**，按歌不同，
全部远超 MediaSession 那张 363。

**不做文本模糊匹配**（iTunes / Deezer / Cover Art Archive）：实测 iTunes
搜「陈奕迅 异梦」返回的是 Unconditional 和 The Album，全不对。用 id 查是精确的，
文本搜索是猜的——盲操下匹配错了会显示另一张专辑的封面，而用户不一定察觉。

没去读网易云的私有缓存拿原图：要么依赖 root，要么依赖它的内部目录结构，
两者都会在它改版后静默失效，不该进产品。也没引 Coil/Glide，一个 GET 不值得。

#### `?param=WxH` 做的是**居中裁切**，不是拉伸

逐像素比对确认：与居中裁切假设的平均通道差 5.6，与纯拉伸假设 34.3，相差 6 倍。
所以非方比例是可用的——裁的是画面而不是把人脸压扁。

##### 方形原图求竖图，裁的是**左右**不是上下

这条一开始想反了，得记牢。直觉上「要竖图就切掉上下」，但原图是方的：
1080×1350 比 1080×1080 **还高**，方图不可能靠切上下变得更高。
实际是把图放大到够高，再切掉两侧。

逐像素验证（1500 方图请求 `1080y1350`，结果缩回同高后是 864×1080）：
与「居中裁左右」假设的 RMSE 是 **0.0057**，与「不裁」是 **0.164**，差 29 倍。

后果是 4:5 会**吃掉原图横向的 20%**，而专辑封面的标题、艺人名
往往就排在左右边缘——实测陈奕迅「梦想天空分外蓝」那张，
左侧一整列「梦想天空分外蓝 / 唱：陈奕迅 / 林夕」全缺了左半边。

#### 默认取 **4:5 竖**

竖图明显更满：方图清晰区只占屏高 45%，4:5 占 56%。这是用户拍板的取舍——
**知道它会切掉左右 20%（含封面上的字）仍然选了满**。要改回完整就是
把 `DEFAULT_COVER_ASPECT` 换成 `SQUARE`，排版会自动跟上。

不取更竖的 2:3 / 9:16：它们的长边先顶到原图上限，**超限就整个回落成方图**
（1500 的原图上请求 `1080y1620`，拿回来的是 1500×1500），等于选了也没用。
只有 4:5 在常见的 1000~1500 原图上还能真的生效。

已知这些封面类型上观感不佳，是比例本身的代价不是缺陷：

- **极简/大留白**（如余佳运「我想」，黑底 + 偏置的小图标）：铺满竖屏后
  是一大片纯黑加一个孤零零的元素，像没加载出来。
- **文字贴边**：如上，左右的字会被切。

另外四档仍留在封面实验室（`ui/CoverLabScreen.kt`）里可试。

#### 长边超过原图会**静默回落成方图**

实测（原图 1500 的那首）：请求 `1080y1620` 返回的是 **1500×1500**——
既不是请求的比例，也没有任何错误提示；同图 `1080y1080` 则正常返回。

准确的语义是「**从不上采样**」：请求超过原图时，接口给回原图本身，
于是非方比例**连比例一起丢掉**。方形档不受影响（超限也只是拿到原图），
真正会坏的只有非方档。所以请求前必须按原图长边等比降级，
这就是 `media/CoverAspect.kt` 里 `paramFor` 做的事——**唯一值得单测的逻辑**，
也正是最容易算错的（错了界面上看不出来）。

原图边长**按歌不同**（实测 640 / 800 / 1500 / 1681 / 1700），
所以降级必须运行时算，不能写死一个安全值——写死等于对多数歌放弃清晰度。

#### 原图尺寸只能自己探，`song/detail` 里**没有**

**真机取证**：`song/detail` 返回的 album 对象只有
`picId` / `picUrl` / `pic` 等字段，**没有 `picWidth`/`picHeight`**
（完整字段表见 `ArtworkFetcher.probeSourceEdge` 的注释）。
早先按它取值，恒为 0 而落到写死的保守值，结果是所有歌都被压到那个值
以下——白丢清晰度，且实验室里报的源图尺寸是假的。这个缺陷在界面上
看不出来（图照样显示，只是小一号），是逐像素对比才发现的。

改为读**原图自己的文件头**：带 `Range: bytes=0-4095` 只取前 4KB，
配 `BitmapFactory.Options.inJustDecodeBounds` 解出尺寸而不解码像素。
实测 CDN 认这个头（返回 206 + `content-range: bytes 0-4095/2829285`），
4KB 足够覆盖 JPEG 的 SOF 段——比下整张原图（0.8~2.8MB）便宜两三个数量级。

所以 `get()` 要同时接受 200 与 **206**，只认 200 的话探测恒失败。

### 高清图**不替换** `artwork`，是并列的第二个字段

`TrackInfo.hiResArtwork`。363 那张是随播放状态同步拿到的，要继续当占位
立即显示；渲染侧取 `hiResArtwork ?: artwork`。等高清图到位再显示的话，
切歌瞬间封面会空一下，比糊一点更难看。

轮询那段（`MainActivity` 每秒重建 `TrackInfo`）必须**把 `hiResArtwork` 带过去**，
且只在 `mediaId` 相同时带：直接赋值会把它每秒抹掉一次，表现为封面在
高清与 363 之间反复闪。

只在**封面模式**下拉：简洁模式只有 52dp 的小图，363 绰绰有余。

### 缓存只留一张

`media/ArtworkCache.kt`，键是 `mediaId + aspect`。高清图下载下来只有
100~150KB，但 **1274² 的 ARGB_8888 解码后是 6.5MB**，攒十首就是 65MB。
切歌时把上一张的强引用丢掉。

**但不主动 `recycle()`**：切歌那一刻界面上画的仍是上一张（新的还没拉到），
回收掉会让正在合成的那一帧抛 `trying to use a recycled bitmap`；
而封面模式下每层模糊都持有它的引用，「还有没有人在画」精确判断不了。
这里要的是「不攒着」而非「立刻释放」——真正会 OOM 的是无上限的 LRU，
不是晚一个 GC 周期。

与 `LyricsRepository` 不做缓存的口径不同，是因为代价不对称：歌词几 KB，
重拉无所谓；封面是一次网络往返加一次大图解码，而播放状态每秒轮询一次。

失败**不写缓存**，下次重组会再试——短暂断网恢复后能自愈。

### 非网易云播放器必须能安全失败

`skipTargetController` 会回落到任意正在播放的会话，此时 `mediaId` 不是
网易云 id。`ArtworkFetcher.fetch` 因此先查「非纯数字直接返回 null」，
同 `LyricsRepository.load` 的口径——不浪费一次必然失败的请求。

### 封面恒为正方形，但 `BlurLayer` 仍用 `FillBounds`

没遇到过非方的原图。用 `FillBounds` 而非 `Crop`：真出现非方封面时
宁可轻微拉伸，也好过把两侧裁掉。

### 封面实验室（仅 debug 包）

设置页「显示模式」分组下 → `ui/CoverLabScreen.kt`。用真实的 `AlbumBackdrop`
渲染预览（同歌词实验室「用同一套 composable」的口径），五个比例档位单选。

**每档必须标注实际会拿到的尺寸**：静默回落在界面上看不出来，
不标的话调参时只会看到「怎么选哪档都一样」，却不知道是被接口吞了。
源图边长由 `ArtworkFetcher.Result.sourceEdge` 带回，不为此另查一次接口。

标灰的口径是「**铺不满屏幕宽度**」（仍要上采样），不是「这一档坏了」——
`paramFor` 永远保住比例，没有哪一档会退化成方图。早先拿
「长边 < 屏宽」当判据，结果原图只有 800 的歌**五档全灰**，
这个信号就失去了区分度。

比例存在 `ui/CoverOverride.kt`，**只存内存不落盘**，理由同另两个实验室。
**复用同一个 LAB 角标**，不新增——角标要回答的是「现在跑的是不是实验室
参数」，这个问题对三者是同一个。

## 暂停态靠封面表达，不靠文字

暂停时**封面模糊 + 压暗 + 叠一个暂停图标**（`AlbumArt` 的 `isPaused`）。
早先是在时长旁边写一行「已暂停 · 3:41」，混在同色号同字号的小字里，
抬眼一瞥根本分不出来——而封面是视线本来就会落到的地方。

改了之后时长那处**只留总时长**，不再重复说暂停。两处都说同一件事，
反而把时长这个信息稀释掉了。

几个实现约束：

- `blur` 要排在 `fillMaxSize` 之后、外层 `clip(shape)` 之内。`BlurNode` 恒
  `clip=true` 且裁到自己那层的排版矩形，位置不对光晕会在圆角处被硬切。
  （半径为 0 时也照样裁，所以这里用 `animateDpAsState` 从 0 起步是安全的，
  但若封面本身要保持锐利就不能挂 `blur`。）
- 压暗那层不能省：浅色封面下白图标对比度不够。
- `track == null`（未检测到播放）时**不算暂停**，否则一进应用就顶着一个暂停图标。
  判断是 `track != null && !track.isPlaying`。
- `blur` 需要 API 31+，低版本静默降级为不模糊，只剩图标 + 压暗。
  图标本身已经够表达暂停，可以接受。

## 歌词展示

对齐 Apple Music 的观感，`ui/LyricsOverlay.kt`。**纯展示，绝不接触摸**——
盲操下可交互的歌词会与手势语义冲突。

动画参数全部集中在 `ui/LyricsAnimSpec.kt`（纯 Kotlin，可 JVM 单测），
`LyricsOverlay` 只负责取数据，渲染与动画在 `LyricsScroller` 里——
拆开是为了让实验室能用假数据驱动**同一套** composable。

### 滚动是逐行弹簧，不是整列平移

每行持有自己的 `Animatable` 去追同一个目标位移。别改回「整列一个
`translationY` + 单条补间」：那样所有行同时同速移动，就是 Apple Music
与普通歌词控件最直观的差别。

错峰与阻尼是**同一套机制的两个侧面**，所以没有独立的 delay 参数：
刚度梯度既产生相位差（一行先顶上来、后面几行被依次拖上来），
又让软的那端阻尼比更低（过冲回弹）。加第二套动画去单独控制其中一个，
两条曲线会互相打架。

`key(index)` 绑绝对行号同时决定每行 Animatable 的身份。绑错会让某行的
弹簧状态被下一行接手，位移从别人的当前值继续跑，表现为换行时随机抽搐。

#### 窗口滑动之后，位移得靠 `shiftAnim` 补，不能只靠 `targetOffsetY`

只渲染当前行附近的窗口（`windowStart..windowEnd`），而 `targetOffsetY` 是
**相对窗口**算的（`topOffsetPx` 从 `windowStart` 数起）。这带来一个只在
歌曲中途才暴露的缺陷：

- 歌曲开头 `windowStart` 被 `coerceAtLeast(0)` 钉在 0，上方行数逐行增加，
  `targetOffsetY` 每换一行减少一个行高 —— 弹簧正常被驱动，观感正确。
- 一旦 `anchorIndex > rowsAbove`，`windowStart` 就跟着当前行一起前进，
  上方行数**恒为** `rowsAbove`，`targetOffsetY` 冻结成常数。换行带来的
  位移全部由「Column 重新排版」完成，而排版是瞬时的、没有动画 ——
  表现就是「高亮行直接闪现到上一行」。

所以要补一段反向位移：`windowStart` 每前进 n 行，就补 +n 个行高，
再按缓动曲线趋近 0，视觉上等价于整列平滑滚动了 n 行。
歌曲开头这一项恒为 0，走原路径。

补偿必须**叠加**而不是覆盖：快歌连续换行时上一次还没走完，
直接赋值会抹掉残余位移，反而跳动。

排查这类问题别只看歌曲开头 —— 前 5、6 行一切正常，正是这个缺陷的特征。

##### 补偿要在**组合期**算，且由**父级**统一算

这两条各对应一种「所有字闪一下、像重新 fix position」的观感缺陷，
都是真机实测才发现的：

1. **不能只放在 `LaunchedEffect` 里。** effect 在组合与布局提交**之后**才跑，
   帧序会变成「窗口变 → 整列瞬间上跳一行并画出去 → 下一帧才按回来 →
   再开始动画」。那一帧的错位肉眼看得见。所以 `pendingShift` 在组合期
   同步累加，当帧就参与 `translationY`。
2. **不能每行各自算。** 每行的 `lastWindowStart` 是 `remember` 出来的，
   而**新进窗口的行**初值就等于当前 `windowStart`，于是它拿不到补偿，
   一出现就在终点位置上，旁边的行却还在动——整列对不齐。
   提到 `LyricsScroller` 里算，整列共用一个补偿量，新行也从同一起点开始。

清零由父级在 `Column` **之后**的 `LaunchedEffect` 里做：组合自上而下，
必须等所有行都读过再清。交给各行清的话，第一行清掉之后后面的行就读不到。

**接手用的 effect 必须 key 在 `shiftEpoch`（单调递增的代号）上，
不能 key 在 `pendingShift` 上。** 后者会让「清零」这个动作本身触发
effect 重启，把正在跑的 `animateTo` 取消掉，`shiftAnim` 停在半路回不到 0；
下一次换行再叠一层，位移逐行累积——**表现为整列越来越往下沉，
当前行离开锚点行、容器顶上空出一大片**。

这个缺陷截图一眼可见（当前行跑到屏幕下半部、上方大片空白），
但要唱十几行才攒得出来，改完 `pendingShift` 相关逻辑后
应当隔一分钟各截一张图比对锚点位置。

量化标准：逐帧追踪整列位移，**单帧跳变不应超过一个行高的 10%**。
缺陷版实测一帧跳 17px（行高 43px，40%），修好后最大 3px（7%）。

#### 梯度基准是**屏幕位置**，不是「距当前行的距离」

拖尾强度沿屏幕**自上而下递增**：第 1 行最干脆（懒惰度 0.1），
最下面的行最拖（0.95）。

依据是列表往**上**走：最上面那行是这趟位移里走得最久、最先该落定的；
越靠下的行越是「被前面的行拖着走」，起步越慢。

梯度是**纯粹的屏幕位置函数，与 `anchorRow` 无关**。当前行只是恰好落在
曲线的某一点上，挪动锚点会让它取到不同的快慢，但不改变曲线本身。
`interpolate` 里不该出现 `anchorRow`。

基准取**目标位置**（动画结束后落在第几行）而非实时位置：后者会让曲线
随自身动画状态变化，构成非线性反馈。目标位置是静态量，每次换行只重算一次。

##### 位移是**单调逼近**，不是弹簧

这是最后定下来的模型，前面绕了很多弯路才明白需求本身就不是弹簧。

弹簧（`spring(dampingRatio, stiffness)`）是 **PID 式**的：快速拉到目标位置，
然后来回震荡收敛，越软的行震得越厉害。而实际要的是**永不越过上一行的位置**，
只是各行趋近的快慢不同——「趋近于 0」而非「震荡收敛到 0」。

震荡在盲操场景里尤其糟：焦点行晃一下会被读成「歌词跳了」。

所以整块动画没有弹簧，全部走
`tween(settleTweenMs, cubic-bezier(ease, 0, 0.25, 1))`：

- 第一个控制点的 y 恒为 **0**，x 就是「懒惰度」。x 越大曲线在起点附近越平，
  起步越慢，「被拖着走」越明显。
- 第二个控制点固定 `(0.25, 1)`，让所有行同时收尾且收得很软。
- 因为 y 从 0 单调升到 1，**进度不会超过 1**，位移只逼近不越过。
  `LyricsAnimSpecTest` 直接复算这条曲线来断言单调性。

**时长对所有行相同**（`settleTweenMs`，同时开始同时结束），错峰只靠曲线差异。
若下面的行时长也更长，快歌连续换行时它们会追不上，位移累积起来越滚越偏。

`offsetAnim` 与 `shiftAnim` 两条路径用**同一个** `scrollSpec`，
否则唱到第 6 行（窗口开始滑动）时手感会突然变一下。

##### 这块栽过四次，每次的错法都不一样

1. **用 `abs(index - currentIndex)` 取无向距离**，梯度以当前行为中心向两侧
   对称扩散 → 拖尾在上下两个方向同时出现。
2. **方向对了但带宽不够 + 低阻尼端不可见**（`40→260 / 0.58→1`）→
   实测是「整列线性滚动」：刚度比值只有 6.5 倍，相邻行只差二十几，
   肉眼把它们合成了一个刚体；阻尼在整个阅读区又都 ≥0.68（近临界，不回弹）。
3. **方向整个写反**（顶软底硬）→ 最上面那行晃得最厉害，而它本该最稳。
   当时的理由是「被拖拽的只能是后面的行」，这话没错，但**「后面的行」
   指的是下方的行**，却给它们配了最硬的弹簧，自相矛盾。
4. **模型选错**：一直在弹簧的参数空间里找解。把阻尼提到 1.0（临界）
   消掉了震荡，但**消不掉弹簧前重后轻的速度分布**——弹簧从静止释放时
   恢复力在 t=0 最大，实测 k=191/ζ=1 的速度峰值在 **71ms**，
   那时已走完三成路程。观感仍是「先窜一下再慢慢爬」，即「往上拱一下」。
   根治办法是换掉整个模型，不是继续调参数。

跨度也不是越大越好，但太窄同样不行：太窄（3）梯度在第 3 行就跑完、
往下全是同一档，退化成「上面几行动、下面一坨一起动」；太开则相邻行
差太小。现在取 7，大致覆盖可视区。

**共同点是这四次在代码里都看不出问题，端点数字也都「有梯度」。**
所以 `LyricsAnimSpecTest` 不止断言方向性，还断言位移单调不越过、
相邻行在 ¼ 时刻的进度差 ≥0.2、当前行不能太拖、梯度与锚点解耦。
实验室里的**逐行梯度表**（`GradientTable`）是同一件事的可视化版本——
它采样的是**这一行实际会用的那条曲线**，不另算近似值，否则调参就是瞎调。

#### 当前行锚定在第 4 行，不是恒定居中

`anchorRow = 3`。居中不符合阅读的实际重心：注意力在「下一句是什么」，
正下方的预读区比正上方已唱过的行重要得多。

上方留三行做上下文。早先取 2 实测仍偏上——预读区是够了，但焦点贴着
容器顶边，上方那点上下文被边缘淡出吃掉大半，看着像「当前行被顶在天花板上」。

锚点用 `LINE_HEIGHT` 的整数倍而不是上方各行的实测高度累加：一旦上方
出现折行，按实测算会把当前行顶下去半行，盲操下焦点位置飘忽比精确对齐更糟。
下方各行仍按实测高度自然排布。

锚点上移后渲染窗口**不能再上下对称**（`rowsAbove` / `rowsBelow`）：
对称会一头渲染过量、一头不够，表现为当前行下方空出一片。

### 字号统一，焦点只由清晰度建立

当前行与其余行**同字号**，区分全靠透明度 + 模糊。早先当前行大一档、
靠 scale 放大，衍生出一串补偿逻辑（排版宽度反向收窄、当前行行高补足、
scale 必须排在 blur 之前），统一字号后这些全部不需要了。

模糊**第 1 行即起步**，缓升到最远 9dp。封顶要守住「最远处仍认得出字」
——早先 12dp 那版第 5 行开外糊成色块，层次其实止步于前四行。

**峰值与跨度要配着调**（`maxBlurDp` / `blurRampLines`）：决定观感的是
曲线斜率而不只是峰值。只抬峰值不拉跨度，紧邻当前行的一两行会跟着糊掉，
"预读下一句"就没了。

##### `blur` 必须排在 `padding` 之前

`BlurNode` 恒 `clip=true`，裁切边界是它自己那一层的排版矩形。排在
`padding` 之后时那个矩形已经被 `SIDE_PADDING` 内缩过，模糊光晕就在距
边缘 `SIDE_PADDING` 处被硬切一刀。

居中对齐时短句离边界远，看不出来；**靠左对齐时每行行首都贴着这条边界**，
远处那些糊得厉害的行左边像被竖着裁掉一块。挪到 `padding` 外层后，
光晕有整行宽度可以铺开。

（相关但不同的一条：半径为 0 时也照样裁，所以当前行不挂 `blur`。）

#### 清晰度要**等位移走完再变**，靠 `fadeDelayMs` 而不是靠时长

淡入淡出带一个 220ms 的**延迟**（`fadeDelayMs`），位移则立刻响应。
两件事同时进行时是「一边往上滚一边对焦」，挤在一起显得急；
先滑到位、再换焦点才顺。

早先的口径正相反（fade 短于位移，先建立焦点再收尾位移），那是推断
不是实测——真机上看就是上面这个问题。想改时序要调**延迟**而不是时长：
只拉长时长，渐变仍然从换行那一刻就开始，起不到排队的作用。

延迟要**略小于**当前行的位移落定时间（约 290ms），让两段稍有交叠。
完全排队会有个能察觉的停顿，反而不连贯。

`animateFloatAsState` / `animateDpAsState` 的 `tween` 都要带同一个
`delayMillis`——alpha 与 blur 是同一件事（建立焦点）的两个侧面，
错开会让字先变清晰再去掉模糊，像对焦对了两次。

真机录屏逐帧量过这条时序：0–134ms 位移走完，134–267ms 平台期，
267ms 后清晰度才开始变，约 600ms 全部落定。位移段没有来回震荡。

清晰度（模糊、透明度）默认仍是**上下对称**的，`upperFadeScale = 1`。
位移梯度这次已经改成有向的，若清晰度同时也改成有向，出了问题分不清
是哪个变量在起作用——留给实验室去试。

### 歌词动画实验室（仅 debug 包）

设置页「歌词」分组下的入口 → `ui/LyricsLabScreen.kt`。上半屏用内置假歌词
跑真实的 `LyricsScroller`，下半屏是参数滑块，改一下立刻生效。

假歌词而非真实播放：调参需要**可复现**的素材，真实播放要等切歌、等副歌，
试一组参数就得等半分钟。样本里有一句长到必然折行的，因为折行是动画里
最容易露馅的场景（行高不一，位移累加一旦算错就飘）。

改动经 `LyricsAnimOverride` 共享给主界面，**返回后真实歌词立刻跟着变**。
这条通路是必要的：预览框只有 340dp 高，能看到的行数远少于真实全屏，
而梯度的观感与可见行数强相关，只在框里调容易看走眼。

主界面右下角有个 **LAB 角标**（`LyricsAnimOverride.isActive`），正在跑实验室
参数时才显示。没有它就分不清「刚才那下观感变化是参数生效了，还是这首歌
本来就长这样」——调参全靠肉眼比对，这个不确定性会让整个取景器不可信。

`LyricsAnimOverride` 的几条约束：

- 用 `mutableStateOf` 而非普通 `var`——读它的是 composable，
  普通字段改了不触发重组，返回主界面要等下次换行才偶然生效，
  调参时会被误判成「这个参数没效果」。
- `current` / `isActive` 在 **release 恒为默认值 / false**，与实验室入口本身
  被 `BuildConfig.DEBUG` 挡掉是同一个口径。
- 同步写在**一个** `LaunchedEffect(spec, touched)` 里，滑块则统一走
  `updateSpec`，不在十几个 `onChange` 里各自 `spec.copy(...)`——
  漏一个就会出现「这一项调了主界面不动」的静默不一致。
- `touched` 标记区分「进来看一眼」和「真的动过参数」：前者不该点亮角标，
  否则是假信号。它同时是那个 effect 的 key——「恢复默认」要
  `clear()` + `touched = false` 成对执行，只 key `spec` 的话那次 clear
  会被 effect 立刻 set 回去，角标灭不掉。
- `clear()` 与 `set(DEFAULT)` 渲染结果一样但**角标状态不同**，
  所以两者必须分开，不能为了省一条状态而合并。

参数**只存内存，杀进程即回默认**，不落盘也不进 `NudgeConfig`。歌词动画的
好坏没有「因人而异」的成分，做成用户可持久化的配置只会让线上出现一堆
没人能复现的观感问题；不落盘也就不存在「用户留下一个自己看不到、
也改不回的状态」，不必像 `sensitivity` 那样再为 release 侧写一套忽略存量值
的逻辑。所以它仍然是取景器：调好之后点「打印当前参数」，把可直接粘贴的
构造调用抄回 `LyricsAnimSpec.DEFAULT`，否则重启一次就没了。
不用剪贴板是因为真机调参时手边未必有键盘，且剪贴板在分屏／后台限制下
时灵时不灵。

## 应用内更新

设置页手动触发，不做启动自动检查——这是刻意的，盲操工具不该在启动时弹更新提示。

版本比较**只做字符串不等判断**，不解析版本号大小：`/releases/latest` 就代表官方认定的当前版本，
本地与之不一致即非最新。已知副作用是本地 debug 包（versionName 回落为 `1.0`）总会提示有更新，
手动入口下可以接受。

`REQUEST_INSTALL_PACKAGES` 权限在 Android 8+ 还需要用户在系统设置里单独授予，
`UpdateInstaller.canInstall()` 先查再跳，否则拉安装器会被静默拦下。

## 振动反馈

盲操下振动是确认操作结果的**唯一**渠道，所以每种反馈必须可区分。
早先全是单震或近似单震，只有时长差别（50 / 30-80-30 / 20 / 200ms），
实测基本分不出来——尤其切歌与已收藏，除了长短没有任何别的差异。

### 这台机器只有振幅控制，别去找更好的 API

真机取证（SM-G9810 / Android 13，`dumpsys vibrator_manager`）：

```
mCapabilities=[AMPLITUDE_CONTROL], mSupportedPrimitives=[],
mSupportedEffects=[], mCompositionSizeMax=0, mPwleSizeMax=0
```

于是这些全部不可用，**别再试**：

- `startComposition()` + `PRIMITIVE_QUICK_RISE` —— 官方做「蓄力→迸发」正是用它，
  但 `mSupportedPrimitives` 是空的。
- `createPredefined(EFFECT_HEAVY_CLICK)` —— `mSupportedEffects` 空，
  会**静默回退**成通用一震，毫无区分度（静默是这条最坑的地方）。
- PWLE（频率曲线）—— `mPwleSizeMax=0`。

唯一可用的高表达力接口是 `createWaveform(timings, amplitudes, -1)`。
所以 `HapticSpec` 做的事就是把参数化的包络离散成那两个数组。

### 区分度靠「形状」，不靠数值

每种反馈占一个**节奏形状**，差异是类别而非程度：

| 反馈 | 形状 | 签名 |
|---|---|---|
| 切歌 | 单记重击 42ms | 1 记 |
| 收藏成功 | 加速脉冲列 + 迸发 | 7 记 / 加速 / 有迸发 |
| 已收藏 | 三记轻快短击 | 3 记 / 匀速 |
| 播放/暂停 | 两记等距中性击 | 2 记 / 匀速 |
| 失败 | 减速渐弱列 | 3 记 / 减速 |

盲操下「几记」「越来越快还是越来越慢」不需要对照就能认出来，
而**振幅的绝对值没有对照根本分不出来**，所以不拿它当区分维度。
`HapticSpecTest` 里「波形的形状两两不同」按
`(脉冲数, 有无迸发, 节奏走向)` 三元组断言——它当初正是抓出了
已收藏与播放/暂停撞形状（都是「2 记匀速」）的问题，那时两者只差振幅，
而那恰恰是被判定为不可靠的维度。加新反馈时这条会继续拦着。

### 累积感靠间隔压缩，不靠振幅渐强

直觉上「火箭发射」该用一条从 0 平滑爬到满幅的连续曲线。但这台是弱马达，
连续渐强的低振幅段人手几乎感知不到，实际会退化成「停一会儿然后震一下」——
正是要修的「一段持续振动」的亲戚。

脉冲的**起停边沿**才是最强的触觉信号，所以蓄力主要靠间隔从 90ms 压到 16ms，
振幅递增只是辅助。

两个配套约束：

- **蓄力段刻意不爬满**（`endAmp = 0.62`），顶上那截留给迸发。爬满了迸发就没有落差。
- **迸发前留 50ms 静默**。此时节奏已压到最密，紧接着一记重击会跟最后几个脉冲
  黏成一团，冲击力全在那段空白的落差上。

`minAmp` 是马达**起振阈值**的地板：LRA/ERM 振幅太低时根本没转起来，
若渐强的起点低于阈值，前几记完全摸不到，表现为「从中间突然开始震」，
蓄力的前半段等于白做。

#### 间隔的进度基准与脉冲**不同**

间隔比脉冲少一个，所以它有自己的 `i/(gapCount-1)`。早先两者共用
`i/(count-1)`，最后一段间隔只取到 `(count-2)/(count-1)`，**永远到不了
`endGapMs`**——节奏压缩在最该收紧的地方戛然而止。曲率越大缺口越明显
（`gapCurve=1.9` 时 `0.8^1.9≈0.66`，最后一段间隔差了四倍）。
这个缺陷在代码里看不出来，端点参数也「看着没问题」，是单测抓出来的。

### 用 `USAGE_MEDIA` 而非默认的 TOUCH

`mVibrationIntensities` 里 `TOUCH=(MEDIUM_LOW)` 而 `MEDIA=(HIGH)`，
系统按 usage 缩放振幅。挂 TOUCH 等于自己把天花板压低一档，
而迸发最需要的就是上限。

不用 `NOTIFICATION`（同为 HIGH）：这是用户主动操作的即时反馈而非通知，
且某些 ROM 下勿扰模式会把 NOTIFICATION 整个静音——那会让盲操下
唯一的反馈渠道消失。

### 真机验证振动的办法

`dumpsys` 能把实际发给马达的包络完整打出来，不必靠手感猜：

```bash
adb shell am broadcast -a com.nudge.app.MEDIA \
  -n com.nudge.app/.action.MediaCommandReceiver --es command like
adb shell dumpsys vibrator_manager | ag -u 'opPkg: com.nudge.app' | tail -1
```

两个坑：

1. **必须带 `-n` 指定组件**。不带的话 Android 8+ 会拦下隐式广播
   （logcat 里是 `Background execution not allowed`），表现为「广播发了没反应」，
   而 `am broadcast` 仍然返回 `result=0`，看不出失败。
2. **`command` 的值是小写 wire name**（`next`/`like`/`play_pause`），
   传 `LIKE` 会被 `MediaCommand.parse` 解成 null 而静默返回。

输出里 `segments=[Step{amplitude=..., duration=...}]` 就是实际包络。
收藏那条应能看到间隔 90→87→77→62→42→16 单调收紧、振幅 0.28→0.62 爬升、
50ms 静默、最后 `amplitude=1.0` 的迸发。

### 振动实验室（仅 debug 包）

设置页「反馈」分组 → `ui/HapticLabScreen.kt`。每种反馈各占一块，
每块有试听按钮、**包络柱状图**和该形状用得上的滑块
（单脉冲时不显示间隔与曲率——摆出来只会让人以为调了有用）。

包络图是必要的：振动是纯触觉的，手的记忆很短，试到第三块时已经想不起
第一块什么手感了。图让「这条波形长什么样」在按下去之前就可见，
与歌词实验室那张梯度表同一个用途。图直接画 `render()` 的产物而非另算近似值。

横轴有 200ms 的最小跨度：单脉冲占自己总时长的 100%，按比例画就是一整块实心蓝，
既看不出「只有一记」也看不出「很短」——而那恰恰是切歌这条的全部特征。

参数**只存内存**（`HapticOverride`），杀进程即回默认，理由同
`LyricsAnimOverride`，另加一条：振动强度受机型与系统设置影响极大，
用户存下来的一组值换台机器完全是另一个手感。

`HapticOverride.specFor` **不是 `@Composable`**（与 `LyricsAnimOverride.current`
的关键区别）：读它的是 `ActionDispatcher`，在普通函数里调。标成 composable
会让实验室调的参数只在实验室里听得到，失去意义。

LAB 角标与歌词实验室**共用一个**：它要回答的是「现在跑的是不是实验室参数」，
这个问题对两者是同一个。

## 媒体控制的适用范围

- **下一首**：对任意播放器有效。目标优先网易云，无网易云会话时取第一个 PLAYING 的会话。
- **收藏**：**仅网易云**。走 custom action 动态查找（匹配 `STAR` 或 name 含 `like`），**不要硬编码 action id**，以适应网易云改版。
- **播放/暂停**：toggle 语义，默认不绑手势（手势池已够用，绑哪个交给用户）。
- **显示歌词**：**唯一不经播放器的动作**，只改本机的 `lyricsEnabled`。默认不绑手势。

### 「显示歌词」为什么不走 MediaControlRepository

它是本机 UI 配置的 toggle，不是媒体命令。`ActionDispatcher.dispatch` 在
调 `repository.execute` **之前**就分流走，`MediaControlRepository` 里两处
`when` 对它都是 `error("歌词开关不经播放器")`——和收藏那条 `error("收藏使用独立入口")`
同一个口径：能走到那里就说明分流漏了，应当立刻崩而不是静默发一个媒体键。

仍然产出 `ActionResult`（`LyricsToggled(shown)`）是为了让振动反馈的选择
保持单一口径（`HapticPalette.idFor` 按结果选波形），否则这一个动作就得在
dispatcher 里另起一条反馈支路。

`shown` 带的是切换**之后**的状态，因为盲操下歌词是纯视觉的——用户看不见
屏幕就不知道自己切成了哪一边，所以开/关必须是**两条方向相反的波形**
（`LYRICS_ON` 间隔收紧、`LYRICS_OFF` 间隔拉开），这是整套反馈里唯一
必须区分方向的一对。

`toggleLyrics` 用 `runBlocking` 读改写 DataStore，理由同 `ProfileCommandReceiver`：
调用方之一是广播接收器，`onReceive` 返回后进程可能立即被回收。
这里「先读后写」是安全的，与收藏那条 toggle 缺陷不同——读的是本应用自己的
DataStore，不存在外部异步更新的窗口。

设置页「歌词」分组里的勾选框与手势绑定里的「显示歌词」是**同一个开关**，
两处同名，所以勾选框的 hint 里点明了可以绑手势——不说明关系的话，
用户会以为手势绑的是另一项设置。

真机实测网易云的 `actions` 位掩码**不含** `ACTION_SET_RATING`，所以收藏只能走 custom action。设计文档 §5.2 里的 `setRating` 写法是早期方案，以代码为准。

### 播放/暂停必须读状态后调 play()/pause()，不能发 PLAY_PAUSE 键码

真机实测（网易云 / One UI 5.1）：用 `controller.dispatchMediaButtonEvent` 发一对
`KEYCODE_MEDIA_PLAY_PAUSE` 的 ACTION_DOWN + ACTION_UP，会被播放器按**连按两次播放键**
计数，而连按两次在 Android 媒体按键约定里是「下一首」——于是「播放/暂停」手势的
实际效果是切歌。

这个缺陷极难从现象定位：手势判定链路**完全正确**（日志里是
`TWO_FINGER_SWIPE_DOWN -> PLAY_PAUSE`），DataStore 里的绑定也对，
只有最后一层把命令翻译成播放器动作时才出错。排查时不要一路怀疑手势识别，
先用 `description=` 看歌名变没变，一次就能把范围缩到这一层：

```bash
adb shell dumpsys media_session | ag -u -o 'description=[^,]*|state=(PLAYING|PAUSED)'
```

正确写法是读 `playbackState` 后显式调 `transportControls.play()` / `pause()`。
读状态在这里是安全的（playbackState 由播放器持续回推，触发时读到的就是当前真实状态），
不必像收藏那条 toggle 缺陷那样担心「读到尚未更新的状态」。读不到状态时按「未在播放」
处理并调 `play()`——盲操下用户更可能是想恢复播放。

注意这**只**适用于 `MediaController.dispatchMediaButtonEvent`。零权限回退路径用的
`AudioManager.dispatchMediaKeyEvent` 仍然必须成对发 DOWN + UP，那是单次按下的正确表达。

## 测试

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
```

155 个单元测试，主体在 `GestureRecognizer`——正例（七种手势 × 三档灵敏度）、边界（阈值临界、滑动死区）、负例（斜滑、两指反向、单指滑动、三指降级、指数不符、超时）。**动手势逻辑必须补相应测试**，尤其是防误触的负例。

预设部分由 `ProfileCodecTest` 覆盖 round-trip 与宽容解码，`ProfileSlotTest` 覆盖槽位号解析。

歌词动画由 `LyricsAnimSpecTest` 覆盖：梯度的**方向性**（拖尾自上而下递增）、
**位移单调逼近不越过**、相邻行的进度差下限、当前行不能太拖、
**淡入淡出等位移走完才开始**、梯度与锚点解耦
（见「梯度基准是屏幕位置」一节）。断言的是这些结构性约束而非具体数值——
数值随观感调，但违反其中任何一条都会让动画退化（线性滚动，或最上面那行
晃得最厉害），而这在代码里看不出来，端点数字也看不出来。

振动包络由 `HapticSpecTest` 覆盖：形状的**方向性**（加速列间隔单调收紧、
减速列单调拉开）、**振幅地板**挡住起振阈值以下的脉冲、迸发前有静默且
**落差足够**、蓄力段刻意不爬满、**波形的形状两两不同**、歌词开关两条方向相反、
高频反馈足够短。同样断言结构性约束而非具体数值——振动没法自动化测
（只能上手摸），这一层是唯一的防回归手段。

高清封面的请求尺寸由 `CoverAspectTest` 覆盖：不超限时按目标宽度取、
超限时等比降级到源图边长、**降级后比例保持**（五档 × 五种实测源图边长全跑一遍）、
源图极小时边长不为 0。这是整条封面链路里最容易算错的一环——
**长边超限时接口静默回落成方图，界面上看不出来**。

`ArtworkFetcherGuardTest` 覆盖非网易云播放器那条：形如 `dQw4w9WgXcQ`、
`spotify:track:abc` 的 mediaId 必须在发请求**之前**就返回 null。
它在 JVM 单测里能跑通本身就是证据——真发了请求会撞上 Android 的
`Bitmap` 桩实现而失败。这条比真机更可靠：要复现得另找一个播放器正在播。

渲染本身没有自动化测试，靠实验室肉眼看。**但实验室的假歌词只有 10 行、
循环播放，`windowStart` 很快就卡在末尾**，测不出「窗口滑动后位移停摆」
那类缺陷（见上）。这种要在真实歌曲的**中段**看 —— 歌曲开头前五六行
一切正常正是它的特征。

`org.json` 在 JVM 单测里只有会抛「not mocked」的桩实现，所以 `build.gradle.kts` 里额外引了
`org.json:json` 作为 testImplementation 覆盖掉它。**不要改用 `returnDefaultValues = true`**——
那会让所有未 mock 的 Android 调用静默返回 null，把真实失败一并掩盖掉（`ReleaseInfo.parse`
的解析失败正是被它掩盖过一次）。

涉及媒体控制、震动、shortcut 的部分没有自动化测试（Activity 与 ShortcutManager
都是 Android 框架），需要真机验证。关键回归项：

- 对**已收藏**的歌重复执行收藏手势，断言 `hasHeart` 保持 true 不变（防 toggle 缺陷回归）
- 把某动作的手势全部取消勾选 → 存成预设 → 改回有绑定 → 加载该预设 →
  断言该动作仍显示「未绑定」（防 `loadProfile` 跳过写入的缺陷回归）
- 把播放/暂停绑到任一手势，在**播放中**触发，断言**歌名不变**且状态转为暂停
  （防「PLAY_PAUSE 被当成连按两次播放键而切歌」的缺陷回归）。
  只看状态不够——切歌后状态仍是 PLAYING，必须同时比对歌名：

  ```bash
  adb shell dumpsys media_session | ag -u -o 'description=[^,]*|state=(PLAYING|PAUSED)'
  ```
- 歌词滚动在**歌曲中段**（至少第 6 行以后）仍是平滑位移而非瞬间替换，
  且换行起始那一两帧没有整列的突跳。只看开头会漏掉：`windowStart`
  被钉在 0 时缺陷不显现，见「窗口滑动之后」那两节。

  肉眼分辨「闪一下」很吃力，可以录屏逐帧量——把画面缩成窄条、
  按行方差做互相关求整列位移，看单帧增量：

  ```bash
  adb shell screenrecord --time-limit 16 --bit-rate 16000000 /sdcard/x.mp4
  ```

  正常时单帧跳变 ≤ 行高的 10%，缺陷版会出现 40% 的一帧突跳。
- 歌词动画实验室的三态角标（debug 包，肉眼看主界面右下角）：新进程无角标 →
  实验室拖任一滑块后返回，出现 `LAB` → 点「恢复默认」后返回，角标消失。
  中间那步同时也是「实验室参数真的作用到主界面」的唯一验证手段——
  `LyricsAnimOverride` 全链路都是 Compose 状态，没法单测。
- 五种振动反馈的包络。不必靠手感，`dumpsys` 能把实际发给马达的
  `Step{amplitude, duration}` 序列完整打出来（命令与两个坑见「振动反馈」一节）。
  断言各自的形状签名：切歌 1 记、收藏 7 记加速 + 迸发、已收藏 3 记匀速、
  播放/暂停 2 记匀速、失败 3 记减速。
- 「显示歌词」手势的两个方向各触发一次，断言歌词真的显隐、**曲目信息与播放状态不变**
  （它不该碰播放器），且两次的振动包络方向相反：

  ```bash
  adb shell am broadcast -a com.nudge.app.MEDIA \
    -n com.nudge.app/.action.MediaCommandReceiver --es command toggle_lyrics
  adb shell dumpsys vibrator_manager | ag -u 'opPkg: com.nudge.app' | tail -1
  ```

  打开应是「振幅递增 + 间隔 40ms（收紧）」，关闭应是「振幅递减 + 间隔 90ms（拉开）」。
  只看歌词有没有消失不够——两条波形若写成一样，盲操下就完全分不出切到了哪一边。
- 振动实验室的覆盖**真的作用到手势反馈**（而非只在试听按钮上）：
  把「下一首」的脉冲个数拖到 12 记 → 返回 → 触发真实切歌 →
  断言 `dumpsys` 里是 12 记而非默认的 1 记 → 点「恢复默认」→ 断言回到 1 记。
  `HapticOverride` 是进程内状态，没法单测，这是唯一的验证手段。
- 切到**专辑封面模式**后触发手势，断言仍能切歌（防「视觉层挡住触摸」回归）。
  封面模式下内容区叠了三层图像与遮罩，任何一层误挂 pointer 修饰符都会
  吞掉手势，而这在代码里看不出来：

  ```bash
  adb shell dumpsys media_session | ag -u -o 'description=[^,]*' | head -1
  adb shell 'input tap 540 1400; input tap 540 1400'   # 双击＝下一首
  adb shell dumpsys media_session | ag -u -o 'description=[^,]*' | head -1
  ```

  注意手势是否真的生效要看**歌名变没变**，别只看有没有振动——
  `dumpsys vibrator_manager` 能确认识别器跑通了，但确认不了动作发出去没有。
- 封面模式下**看不出方形边界**：整张封面清晰可见，往上下连续糊出去，
  没有任何一条能指出边界的横线。肉眼分辨羽化与硬边很吃力，
  逐像素量纵向亮度梯度，硬边会是一条跨度极小的大跳变。
  注意封面自身的构图（如拼接封面的中缝）也会产生大梯度，
  要看**位置**是否落在封面的上下沿。
- 封面模式**整张专辑封面看得全**，不是只有中间一条竖缝
  （见「清晰那一档按图自身比例摆」）。
- 歌词态下**认不出封面细节**，只剩色彩氛围。只淡出全清晰那一档的话，
  中间两档仍会留下可辨认的五官，要专门看这条。
- 封面模式换一张**浅色封面**（如苏打绿「迟到千年」），断言顶栏的
  歌名、歌手、进度条仍然读得清。只在深色封面上看会漏掉这条。
- 高清封面这条链路的四项（见「高清封面」一节）：

  1. 封面模式下切歌，断言封面明显比之前锐利。肉眼差别足够大，
     不必逐像素量高频能量。
  2. **飞行模式**下切歌，断言仍显示 363 那张、不崩、不卡——这是降级链，
     无网络时 `ArtworkFetcher` 应静默返回 null。
  3. 用**非网易云**播放器（YouTube）播放，断言不崩且显示原封面。
     此时 `mediaId` 不是数字 id，应在发请求之前就被挡掉。
  4. 连切十首歌，断言 Native/Java heap 不持续上涨（防高清 bitmap 泄漏——
     1274² 的 ARGB_8888 一张就是 6.5MB）：

     ```bash
     adb shell dumpsys meminfo com.nudge.app | ag -u 'Native Heap|Java Heap'
     ```
- 封面实验室（debug 包）：逐档切比例，断言界面标注的尺寸与实际解码出来的
  bitmap 尺寸一致，超限的档位确实标灰。**这条是那个「静默回落成方图」
  行为的唯一可见化手段**，不看就不知道某一档其实没生效。
  调过比例后返回主界面断言 LAB 角标亮起，「恢复默认」后熄灭。
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

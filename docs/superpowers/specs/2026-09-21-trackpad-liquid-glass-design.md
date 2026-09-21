# Trackpad 质感升级：Liquid Glass 三方案并行探索

## 背景

当前 `TrackpadScreen` 的视觉是「纯色背景 + 不透明圆角卡片」：

- 外层 `Column` 铺 `colorScheme.background`（浅色 `#F7F7F8` / 深色 `#101114`）
- 触摸区是一块 `RoundedCornerShape(24.dp)` 的 `colorScheme.surface` 实色块
- 封面模糊（`BACKDROP_BLUR = 28.dp`）只铺在 `TopBar` 背后
- 触摸反馈是 `Color.Gray.copy(alpha = 0.35f)` 的实心圆，半径写死 48px
- 手势命中反馈是整块白色闪一下（`flashAlpha`，250ms 衰减）

问题在于：**玻璃底下没有可折射的内容**。触摸区是不透明的，背后是纯色，
任何「磨砂 / 半透明 / 高光」的处理都只会读成「稍微发灰的卡片」，而不是玻璃。

## 目标

给触摸区做出接近 Apple Liquid Glass 的质感。因为视觉方案的好坏**无法靠
代码审查或单元测试判断，只能上真机看**，所以本次不预先选定单一方案，
而是**并行实现三个 MVP 变体，装到同一台设备上并排对比**，由人工决定去留。

三个变体分别探索「玻璃背后放什么」这个核心问题的三种答案。

## 三个变体

### 变体 A — 全屏封面铺底

把 `TopBar` 里那层模糊封面扩展到整屏，触摸区变成浮在其上的半透明磨砂玻璃。

- 玻璃感最强：封面提供了真实可折射的内容
- 复用已加载的 `track.artwork`，无新依赖
- 无封面时自动退化为当前的纯色观感

### 变体 B — 氛围渐变光斑

从封面提取主色，用几团缓慢漂移的径向渐变色块作背景，触摸区玻璃浮其上。

- 更「活」，且不受封面构图影响（封面若是纯色大块，A 的折射会很平）
- 需要取色：**手写降采样求主色，不引入 AndroidX Palette 依赖**
  （本项目依赖克制，取主色几十行可解决）
- 风险：触摸面下有持续动画，需确认不影响手势响应

### 变体 C — 仅卡片材质强化

背景保持纯色，只在卡片本身做文章：镜面高光边、内阴影、噪点、半透明。

- 改动面最小，性能开销最低
- 作为对照组存在：如果 A/B 的收益撑不起复杂度，C 可能是性价比最优解

## 硬约束（三个变体共同遵守）

这几条来自项目既有设计约束（见 `CLAUDE.md`），不是本次新增的偏好。

1. **不得修改 `GestureRecognizer`**，及其「纯 Kotlin、不依赖 Android 类」的性质
2. **不得改动触摸链路的接线方式**：触摸区 `Box` 上的 `pointerInteropFilter`
   保持原样，视觉效果只能通过 `drawBehind` / `drawWithContent` / `graphicsLayer`
   / 背景层 composable 实现，不得插入新的 pointer 修饰符或拦截触摸
3. **`Modifier.blur` 必须做 API 31+ 判断**，低版本走降级路径。minSdk 是 26，
   项目现有两处 blur（`TopBar` 的封面铺底、`LyricsOverlay` 的景深）都是这么做的
4. **歌词层可读性不能退化**：`LyricsOverlay` 画在触摸区最底层，任何新增的
   背景层都必须保证歌词仍有足够对比度
5. 深色 / 浅色两种主题下都要成立（`ThemeMode` 支持 SYSTEM/LIGHT/DARK）

## 交付与验收

### 并排安装

三个变体各自在 `app/build.gradle.kts` 新增 debug buildType 的
`applicationIdSuffix`（`.glassa` / `.glassb` / `.glassc`），使三个 APK 能同时
安装在同一台设备上对比。同时设置对应的 `resValue` 应用名后缀以便在桌面区分。

**已知代价**：不同 applicationId 是三个独立应用，**每个都需要用户在系统设置里
单独授予通知使用权**，否则看不到歌曲信息和歌词，玻璃底下无内容可折射，对比失去意义。
这是一次性的手动成本，已与用户确认接受。

此改动**仅作用于 debug buildType**，release 发布流程不受影响。

### 验收标准

每个变体需满足：

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug` 编译通过
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test` 既有 72 个单测全绿
  （视觉改动不应触碰手势逻辑，测试全绿即为「没碰不该碰的东西」的证据）
- 成功安装到目标设备（三星 SM-G9810，Android 13 / API 33）
- 提交一份自查说明：实现了什么、做了哪些取舍、有哪些已知不足

**视觉效果本身由人工在真机上判定**，agent 不得声称视觉效果「良好」——
它看不到屏幕。

## 实施方式

三个变体在**独立 git worktree** 中并行实现，互不干扰。三者都会修改
`TrackpadScreen.kt` 和 `app/build.gradle.kts`，同一工作区内并行必然冲突。

对比完成后，由用户决定：采用其一、取各家之长合并，或全部放弃。
未采用的 worktree 直接删除。

## 不做的事

- 不做视觉效果的自动化测试（截图对比测试的搭建成本远超本次收益）
- 不改动 `LyricsOverlay` 的排版逻辑（那是上一轮刚调优过的，只作为背景内容存在）
- 不改动 release 构建与发布流程

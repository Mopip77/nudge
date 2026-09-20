# nudge —— 手势盲操音乐控制 App 设计文档

日期：2026-09-20
状态：已验证核心技术假设，待实现

## 1. 目标

一个 Android 前台应用，把整个屏幕当作触控板，用不易误触的多指复合手势控制音乐播放。核心场景是**盲操**——不看屏幕也能可靠地切歌和收藏。

**首版范围**：两个动作——「下一首」和「网易云收藏（红心）」。

目标设备：三星 SM-G9810，Android 13 (API 33)。
目标音乐应用：网易云音乐 9.5.95。

## 2. 关键技术验证结论

以下结论均在真机实测得出（非推测），是整个设计的基础。

### 2.1 网易云 MediaSession 实测数据

```
package=com.netease.cloudmusic
rating type=1  (RATING_HEART)
actions=822    (PAUSE|PLAY|SKIP_PREV|SKIP_NEXT|SEEK_TO|SET_RATING)
customActions:
  action='com.netease.cloudmusic.STAR'                      name='like'
  action='com.netease.cloudmusic.LYRIC_ACTION_FROM_NOTI_BUTTON'  name='lyric'
  action='ucar.media.action.COLLECT'                        name='收藏'（收藏到歌单，非红心）
  action='ucar.media.action.PLAY_MODE'                      name='播放模式'
  action='ucar.media.action.PLAY_SPEED'                     name='播放倍速'
metadata keys (11):
  TITLE / ARTIST / ALBUM / DURATION / MEDIA_ID(网易云内部歌曲 id)
  USER_RATING = isRated=true hasHeart=<bool> style=1   ← 当前收藏状态可读
  ALBUM_ART / ART / DISPLAY_ICON / DISPLAY_TITLE / DISPLAY_SUBTITLE
```

### 2.2 验证结果表

| 验证项 | 结果 | 说明 |
|---|---|---|
| 下一首 | ✅ 生效 | `media_session dispatch next` 实测切歌成功 |
| 收藏（点亮红心） | ✅ 生效 | `setRating(newHeartRating(true))` 实测 `hasHeart` 由 false 变 true |
| **`setRating` 幂等性** | ❌ **是 toggle** | 连续三次调用 false→true→false→true，严格交替。网易云忽略传入布尔值，只当作切换信号 |
| 当前收藏状态可读 | ✅ | `METADATA_KEY_USER_RATING` 的 `hasHeart()` |
| 权限 | 通知使用权 | `MediaSessionManager.getActiveSessions()` 要求调用方是已启用的 NotificationListenerService |

### 2.3 由验证结论导出的两个设计约束

1. **收藏必须「先读后写」**。因为 `setRating` 是 toggle，直接调用会把已收藏的歌取消掉——这在盲操下是不可接受的数据损失。必须先读 `USER_RATING.hasHeart()`，仅在未收藏时才调用。
2. **nudge 自身绝不注册 MediaSession**。否则会抢走媒体按键，导致 `dispatchMediaKeyEvent` 回退路径失效（这是社区实测中最常见的坑）。

## 3. 架构

```
┌─────────────────────────────────────────┐
│  UI 层 (Jetpack Compose)                 │
│  ┌───────────────┐  ┌─────────────────┐ │
│  │ TrackpadScreen│  │  SettingsScreen │ │
│  └───────┬───────┘  └────────┬────────┘ │
└──────────┼───────────────────┼──────────┘
           │ MotionEvent       │ 读写配置
┌──────────▼────────┐  ┌───────▼─────────┐
│ GestureRecognizer │  │  ConfigStore    │
│ (纯 Kotlin 状态机) │  │  (DataStore)    │
└──────────┬────────┘  └─────────────────┘
           │ Gesture
┌──────────▼────────┐
│ ActionDispatcher  │──► Vibrator (震动反馈)
└──────────┬────────┘
           │
┌──────────▼────────────────────────────┐
│  MediaControlRepository                │
│  NotificationListenerService           │
│    → MediaSessionManager               │
│    → MediaController(网易云)            │
│  回退: AudioManager.dispatchMediaKey   │
└────────────────────────────────────────┘
```

### 模块职责

| 模块 | 职责 | 依赖 |
|---|---|---|
| `GestureRecognizer` | 输入 MotionEvent 序列 + 灵敏度参数，输出 Gesture 枚举。**纯 Kotlin，不依赖任何 Android UI 类**，可在 JVM 上单元测试 | 无 |
| `ConfigStore` | 持久化手势↔动作绑定表、灵敏度档位、主题模式 | DataStore |
| `ActionDispatcher` | 查绑定表，调用对应动作，触发震动反馈 | ConfigStore, MediaControlRepository, Vibrator |
| `MediaControlRepository` | 封装媒体控制，对外暴露 `skipNext()` / `like()` / `nowPlaying: Flow<TrackInfo?>` | NotificationListenerService |
| `TrackpadScreen` | 全屏触控区，采集原始 MotionEvent，显示播放信息与收藏状态 | GestureRecognizer, MediaControlRepository |
| `SettingsScreen` | 绑定配置、灵敏度、主题、权限引导 | ConfigStore |

`GestureRecognizer` 是唯一有复杂逻辑的模块，也是测试重点。把它做成纯函数式状态机，是为了让它能脱离设备快速测试——多指手势的边界条件很多，靠真机手动试完全不现实。

## 4. 手势识别

### 4.1 支持的手势

| 手势 | 判定条件 |
|---|---|
| 双击 | 1 指，两次完整 down-up，间隔 < `doubleTapWindow` |
| 两指双击 | 2 指同时按下，两次，间隔 < `doubleTapWindow` |
| 三指双击 | 3 指同时按下，两次 |
| 两指长按 + 一指单击 | 2 指按住超过 `longPressMs`，保持按住期间第 3 指 down-up |
| 三指长按 + 一指单击 | 3 指按住超过 `longPressMs`，保持按住期间第 4 指 down-up |

「同时按下 N 指」的判定：N 根手指的 down 事件全部落在 `multiTouchSlop` 时间窗内。

**「长按 + 单击」的触发时机**：在第 N+1 指抬起的瞬间立即触发动作，**不等待长按的那 N 指抬起**。这样用户按住两指后可以连续单击第三指触发多次动作（每次触发之间仍受冷却期约束）。长按的手指抬起时，手势序列结束，不再产生额外触发。

### 4.2 灵敏度档位

三档，映射到一组参数：

| 参数 | 宽松 | 标准 | 严格 |
|---|---|---|---|
| `doubleTapWindow` 双击间隔上限 | 500ms | 350ms | 250ms |
| `multiTouchSlop` 多指同时性窗口 | 150ms | 100ms | 60ms |
| `longPressMs` 长按阈值 | 350ms | 500ms | 700ms |
| `moveTolerance` 移动容差 | 40dp | 24dp | 12dp |

### 4.3 防误触机制

- 任一手指移动超过 `moveTolerance` → 判定为滑动，取消当前手势序列
- 手指数量不匹配 → 立即失败，不做"降级匹配"
- 手势完成后进入短暂冷却期（300ms），避免连击误触发

## 5. 动作实现

### 5.1 下一首

1. **主路径**：`MediaSessionManager.getActiveSessions()` → `transportControls.skipToNext()`
   - **目标选择规则**：优先选网易云；网易云无活跃会话时，退而选列表中第一个处于 PLAYING 状态的会话。这样「下一首」对任意音乐应用都可用（不限于网易云），而「收藏」仅对网易云有效
   - 优点：精确指定目标应用，语义无歧义
2. **回退**：未授予通知使用权时 → `AudioManager.dispatchMediaKeyEvent()`，成对发送 ACTION_DOWN + ACTION_UP，keycode 87
   - 零权限，但作用于系统当前最高优先级会话，无法指定目标

### 5.2 收藏（只点亮，永不取消）

```kotlin
fun like(): LikeResult {
    val controller = neteaseController() ?: return LikeResult.NoSession
    val rating = controller.metadata?.getRating(METADATA_KEY_USER_RATING)

    if (rating?.hasHeart() == true) {
        return LikeResult.AlreadyLiked      // 幂等：已收藏则不操作
    }
    controller.transportControls.setRating(Rating.newHeartRating(true))
    return LikeResult.Liked
}
```

**这是本设计最关键的一段逻辑**。因为 `setRating` 实测为 toggle，若不加 `hasHeart()` 判断，对已收藏歌曲执行手势会**取消收藏**——盲操下用户无法察觉，属于静默数据损失。

备选路径 `sendCustomAction("com.netease.cloudmusic.STAR")` 保留为兜底（当 `setRating` 在未来版本失效时）。action id 在运行时从 `playbackState.customActions` 中按 name 匹配 `like` 动态查找，**不硬编码**，以适应网易云改版。

## 6. 反馈机制

盲操场景下，震动是用户确认操作结果的唯一渠道，因此不同结果必须可区分。

| 事件 | 震动模式 |
|---|---|
| 下一首成功 | 单次短震 50ms |
| 收藏成功（新点亮） | 双震 30-80-30 |
| 已收藏（未操作） | 单次极短震 20ms |
| 动作失败 / 无会话 | 长震 200ms |
| 手指按下 | 无震动，仅视觉涟漪 |

## 7. UI

### 7.1 主界面（Trackpad）

- **顶部**：当前播放的歌名 / 歌手（来自 MediaMetadata）；右侧显示**红心状态图标**（来自 `USER_RATING.hasHeart()`，实心=已收藏，空心=未收藏）。无播放时显示"未检测到播放"
- **中部**：大片留白触控区，手指按下显示涟漪；手势成功触发时整屏短暂闪一下
- **底部**：当前绑定摘要小字（如"两指双击 → 下一首"）
- **右上角**：设置入口

### 7.2 设置页

- **动作绑定**：「下一首」「收藏」各选一个手势，同一手势不可重复绑定
- **灵敏度**：宽松 / 标准 / 严格
- **主题**：跟随系统 / 白天 / 夜间
- **权限状态**：通知使用权是否已授予，附跳转系统设置的按钮

### 7.3 默认配置

- 两指双击 → 下一首
- 三指双击 → 收藏
- 灵敏度：标准
- 主题：跟随系统

## 8. 技术栈

- Kotlin + Jetpack Compose
- DataStore（配置持久化）
- minSdk 26 / targetSdk 34 / compileSdk 34
- 手势采集使用 `pointerInteropFilter` 获取原始 MotionEvent，**不使用 Compose 高层手势 API**——「N 指长按 + 一指单击」这类组合手势没有任何现成库支持，必须手写状态机

### 构建环境

- JDK 17（`/opt/homebrew/opt/openjdk@17`），需显式设置 `JAVA_HOME`，系统默认 JDK 为 8
- Gradle 8.2（brew），AGP 8.1.4
- 无 Android Studio，纯命令行构建

## 9. 测试策略

### 9.1 单元测试（重点）

`GestureRecognizer` 用合成 MotionEvent 序列测试，覆盖：

- **正例**：五种手势各自在三档灵敏度下的标准触发
- **边界**：双击间隔恰好等于阈值、多指同时性窗口边缘、长按时长临界
- **负例（防误触）**：单指滑动、多指滑动、按下后移动超容差、指数不足、指数超出、双击间隔超时、长按时长不足

### 9.2 集成验证（真机）

- 下一首：观察 `dumpsys media_session` 中 metadata 变化
- 收藏：观察 `USER_RATING.hasHeart` 变化
- **幂等性回归**：对已收藏歌曲重复执行收藏手势，断言 `hasHeart` 保持 true 不变（这是防止 toggle 缺陷回归的关键测试）
- 权限撤销后的降级行为

## 10. 已知风险

| 风险 | 缓解措施 |
|---|---|
| 网易云改版导致 action id 或 rating 行为变化 | custom action 动态查找而非硬编码；收藏有 `setRating` 与 `sendCustomAction` 两条路径 |
| 用户撤销通知使用权后 controller 失效 | 在 `onNotificationPosted` 中重建 controller；下一首降级到 `dispatchMediaKeyEvent` |
| 三星 One UI 后台限制 | 本应用为前台运行，影响有限 |
| 灵敏度默认值可能不适合所有用户 | 提供三档可调；若实际使用中发现默认档位不合适，调整映射表即可 |

## 11. 不做的事（YAGNI）

- 上一首 / 播放暂停 / 音量控制——系统已有便捷入口，无盲操痛点
- 悬浮窗全局手势——与其他应用的触摸事件不可调和
- 支持网易云之外的音乐应用收藏——各家实现不同，需逐个逆向
- HTTP `/like` API 方案——上游仓库已归档，且需登录态 cookie，成本高于收益
- 手势参数的逐项自定义——三档灵敏度已覆盖绝大多数需求

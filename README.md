<div align="center">

# nudge

**把整个屏幕当作触控板，不看屏幕也能切歌和收藏。**

[![Release](https://img.shields.io/github/v/release/Mopip77/nudge?style=flat-square)](https://github.com/Mopip77/nudge/releases)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Mopip77/nudge/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)

</div>

---

nudge 是一个 Android 音乐控制 app，核心场景是**盲操**——手机在兜里、握在手上或屏幕朝下时，用不易误触的多指复合手势控制播放，全程不需要看屏幕，靠震动确认结果。

<div align="center">
  <img src="docs/images/screenshot-lyrics.png" width="300" alt="实时歌词展示" />
  <img src="docs/images/screenshot-settings.png" width="300" alt="手势设置" />
</div>

## 特性

- **盲操手势** — 多指复合手势，为「看不见屏幕」设计，日常握持不会误触
- **震动反馈** — 每种结果的震动模式不同，不看屏幕也知道发生了什么
- **实时歌词** — Apple Music 观感的滚动歌词，景深模糊 + 边缘淡出，可关闭
- **三档灵敏度** — 在「容易触发」和「不易误触」之间按手感选择
- **手势可改绑** — 五种手势自由绑定到动作，冲突时有提示

首版支持两个动作：**下一首** 和 **网易云收藏（红心）**。

## 安装

到 [Releases](https://github.com/Mopip77/nudge/releases) 下载最新的 `nudge-<版本>.apk` 安装。

要求 Android 8.0（API 26）及以上。

装好后可在**设置 → 关于 → 检查更新**里直接升级，不必再回来手动下载。

## 使用

1. 打开 app，按提示授予**通知使用权**（设置 → 通知 → 通知使用权 → 允许 nudge）。
   没有这个权限时「下一首」会降级为发送媒体按键（仍可用，但无法指定目标应用），「收藏」完全不可用。
2. 播放音乐，回到 nudge 主界面。
3. 在屏幕任意位置做手势。

### 默认手势

| 手势 | 动作 |
|---|---|
| 两指双击 | 下一首 |
| 三指双击 | 收藏 |

可在设置页改绑，同一手势不能同时绑给两个动作。可选手势还有：单指双击、两指长按 + 一指单击、三指长按 + 一指单击。

> 「长按 + 一指单击」的真实语义是「先放上 N 根手指作为底座，再用另一根手指点一下」。底座不需要刻意按住很久，只要处于按下状态即可。按住底座可以连续点击触发多次。

### 震动反馈

盲操下看不到屏幕，震动是确认结果的唯一渠道，因此每种结果的震动都不同：

| 结果 | 震动 |
|---|---|
| 切歌成功 | 单次短震（50ms） |
| 收藏成功 | 双震 |
| 本来就已收藏 | 极短震（20ms） |
| 失败 / 无播放会话 | 长震（200ms） |

### 灵敏度

三档（宽松 / 标准 / 严格），调整双击间隔、多指同时性窗口、移动容差等阈值。越严格越难误触，也越难触发。默认标准档。

### 检查更新

设置页底部「关于」里点**检查更新**，会查询本仓库的最新 Release。有新版时显示版本号和更新日志，点「立即更新」在应用内下载 APK 并拉起系统安装器。

只在手动点击时检查，启动时不会打扰。首次更新需要在系统弹出的页面里给 nudge 开启「安装未知应用」权限。

## 兼容性

| 功能 | 支持范围 |
|---|---|
| 下一首 | 任意音乐应用。优先控制网易云，网易云无活跃会话时控制第一个正在播放的应用 |
| 收藏 | **仅网易云音乐**。各家播放器的收藏实现不同，需要逐个适配 |
| 歌词 | 来自网易云公开接口，纯音乐或无歌词时不显示 |

在三星 SM-G9810 / Android 13 + 网易云 9.5.95 上实测通过。

## 开发

### 环境

- JDK 17（项目要求，注意系统默认 JDK 可能是别的版本）
- 无需预装 Gradle，用仓库内的 wrapper 即可
- Android SDK（`local.properties` 里配 `sdk.dir`）

### 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # 按你的实际路径改

./gradlew assembleDebug    # debug 包
./gradlew test             # 单元测试
```

`assembleRelease` 在本地不带签名配置时会产出 unsigned APK（装不上），正式包由 CI 出。

### 架构

```
TrackpadScreen / SettingsScreen  (Compose)
        │ MotionEvent
GestureRecognizer  ──► Gesture         ConfigStore (DataStore)
        │
ActionDispatcher  ──► Vibrator
        │
MediaControlRepository ──► NotificationListenerService → MediaSessionManager
                           回退: AudioManager.dispatchMediaKeyEvent
```

`GestureRecognizer` 是纯 Kotlin 状态机，不依赖任何 Android 类，因此能在 JVM 上直接单元测试——多指手势的边界条件太多，靠真机手测不现实。

### 发布

推一个 `v` 开头的 tag 即可，GitHub Actions 会自动构建签名 APK 并发布到 Release：

```bash
git tag v1.2.3
git push origin v1.2.3
```

`versionName` 取 tag 去掉 `v` 前缀，`versionCode` 取 workflow 的运行序号。

签名依赖四个仓库 Secret：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。缺失时 CI 会直接失败，不会发出未签名的包。

> ⚠️ keystore 必须离线备份。丢失后无法再为 `com.nudge.app` 发布能覆盖升级的新版本。

## 设计文档

`docs/superpowers/specs/` 下有完整设计文档，记录了网易云 MediaSession 的真机实测数据和由此导出的设计约束。

## 致谢

歌词字体使用 [Noto Sans SC](https://fonts.google.com/noto/specimen/Noto+Sans+SC)（SIL Open Font License 1.1）。

## License

[MIT](LICENSE) © Mopip77

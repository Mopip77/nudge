# 锁屏专辑封面壁纸

把当前播放曲目的高清封面，烘焙成一张全屏图写进**锁屏壁纸**，
做出「满屏沉浸式封面」的观感；停止播放后恢复原壁纸。

## 为什么是这个方案（先说清楚它的天花板）

真机取证得出的硬结论：**无 root 下改不了锁屏的布局，只能改背景。**

- 锁屏由 SystemUI 的 `NotificationShade` 窗口绘制，`mBaseLayer=171000`；
  第三方 app 能拿到的最高层 `TYPE_APPLICATION_OVERLAY` 只有 **21000**。
  差 15 万层，悬浮窗不是「被挡住」而是根本画在锁屏底下。
- 三星的锁屏媒体卡片是 `FaceWidgetMusicPage`（`AODService_v80.apk`），
  有插件框架，但 `com.samsung.android.app.aodservice.permission.SERVICEBOX_REMOTEVIEWS`
  是 `prot=signature|privileged`——只有三星平台签名的 app（Good Lock）能用，
  **root 也绕不过**（签名校验不看 uid）。
- Good Lock 全家桶碰不到媒体控件；Galaxy Themes 改不了其布局；
  AOD 的音乐组件是纯文字的（`aod_layout_music_info_component.xml` 里
  **没有封面 ImageView**）。

所以本方案**不追求**「大封面独占一块 + 播放条在下方」那种分层效果——
那需要改 SystemUI。本方案只做**背景层**：封面铺满，系统的时钟、通知、
媒体卡片照常压在上面，且**全部保持可操作**。

这个形态用户已经用 MusWall 实地验证过并接受。

### 相对 MusWall 的实质优势

MusWall 读的是 MediaSession 里那张 **363×363** 的位图，拉到 1440 宽是 4 倍上采样。
而 nudge 已有 `ArtworkFetcher` 走网易云 `song/detail` 拿 **640~1700 见方**的原图
（见 CLAUDE.md「高清封面」一节）。同一个形态，源图差一个数量级。

另加 `AlbumBackdrop` 那套调好的「多档模糊 + 渐变蒙版」——MusWall 只有简单模糊。

## 范围

**做**：锁屏壁纸。**不做**：桌面壁纸（用户明确不需要）。

## 架构

```
NudgeNotificationListener（已有）
        │ 曲目变化
        ▼
LockWallpaperService ──► ArtworkFetcher（已有，高清封面）
        │                      └── 失败回落 MediaSession 的 363 位图
        ▼
BackdropBaker ──► 离屏渲染成 1 张全屏 bitmap
        │              ▲── LockWallpaperConfig（中心高度 / 模糊 / 压暗…）
        ▼
WallpaperManager.setBitmap(bmp, null, true, FLAG_LOCK)
        │
        ▼
WallpaperRestorer ──► 停止播放 N 秒后恢复
```

配置入口：设置页 →「锁屏壁纸」二级菜单（`LockWallpaperScreen`），
**带实时预览**。

## 各部分设计

### 1. 触发与恢复

监听沿用已有的 `NudgeNotificationListener`，不新增监听器。

- **切歌** → 重新烘焙并写入
- **暂停超过 `restoreDelaySec`** → 恢复原壁纸（默认 **5 秒**，可配置）
- **恢复播放** → 重新写入

5 秒沿用 MusWall 的默认值（用户认可），但做成可配置：
切歌间隙、来电、短暂暂停都会触发一次壁纸闪动，容忍度因人而异。

**去抖**：连续切歌（用户快速跳过）时不要每首都写一次壁纸——
`setBitmap` 是重的系统调用。合并 `writeDebounceMs`（默认 800ms）内的多次变化。

### 2. 恢复原壁纸：三种情形，不能只存一张图

真机取证：测试机 `/data/system/users/0/` 下**只有 `wallpaper_first`，
没有 `wallpaper_lock`**——即**没有设过独立锁屏壁纸**，锁屏继承桌面壁纸。

所以「恢复」有三种语义，混为一谈会造成不可逆的破坏：

| 原状态 | 恢复动作 |
|---|---|
| 设过独立锁屏壁纸 | 写回备份的原图 |
| **没设过**（继承桌面） | `clear(FLAG_LOCK)` 回到继承态 |
| 备份失败 | 写用户指定的「恢复图」 |

**第二种最关键**：此时写一张备份图回去是**错的**——那会把「继承桌面」
变成「固定一张图」，用户之后改桌面壁纸锁屏不再跟随，且他不会知道是
nudge 干的。必须用 `clear(FLAG_LOCK)`。

`getWallpaperFile(FLAG_LOCK)` 在未设独立锁屏壁纸时**返回 null**——
这正是区分前两种情形的依据，不是错误。

**开启功能时**做一次备份并记录属于哪种情形，存进 DataStore。
备份拿不到时（部分 One UI 版本对第三方保护壁纸文件），
**要求用户显式指定一张恢复图**才允许开启——不能让用户在不知情的
情况下丢掉原壁纸。

**用户指定恢复图是兜底而非默认**：能自动备份就自动备份。
（MusWall 的「预设恢复壁纸」等于跳过了自动备份这步，我们比它做得细。）

### 3. 离屏烘焙：本方案唯一的新技术点

`AlbumBackdrop` 现在是**实时 composable**，用 `Modifier.blur()`
（GPU 的 `RenderEffect`），**只在活的组合里成立，拿不到 bitmap**。
壁纸要的是一张静态图，所以需要一条离屏路径。

`BackdropBaker` 用 `android.graphics` 直接画，不经 Compose：

- 多档模糊用 `RenderEffect.createBlurEffect` + `RenderNode`（API 31+）
  离屏渲染；**API < 31 回落**为 `RenderScript` 或降级到单档模糊
  （minSdk 26，不能假定 31+）
- 渐变蒙版用 `LinearGradient` + `PorterDuff.DST_IN`，与 Compose 那版
  同构

**必须与 `AlbumBackdrop` 保持同一套几何**（`CoverAspect.coverHeightRatio`、
清晰区半径系数、蒙版色标递增规则）。几何逻辑抽到纯 Kotlin 供两边共用，
**不要各写一份**——CLAUDE.md 里那些踩过的坑（高度只跟目标比例走、
清晰区半径必须明显小于半高、色标严格递增不能 `coerceIn`）在烘焙侧
一条都不会自动成立。

**渲染尺寸取「有效分辨率」而非物理分辨率。** 测试机
`Physical size: 1440x3200` 但 `Override size: 1080x2400`——
按物理尺寸烘焙会多花 1.7 倍内存且被系统缩放。取
`WindowManager` 的当前 metrics。

**内存**：1080×2400 的 ARGB_8888 是 10MB，多档模糊要同时持有几张。
烘焙完立即释放中间层；沿用 `ArtworkCache` 的口径——**不攒着，
但也不急着 `recycle()`**（正在写入的那张可能还被系统读着）。

### 4. 配置项与实时预览

用户的核心诉求：**视觉中心会因人而异**（时钟大小、有没有放组件），
所以清晰区位置必须可调，且要能立刻看到效果。

| 配置项 | 说明 | 默认 |
|---|---|---|
| `enabled` | 总开关 | false |
| `centerY` | 清晰区中心占屏高比例 | 0.52（现 `SHARP_CENTER`）|
| `sharpReach` | 清晰区半径系数 | 同现值 |
| `maxBlurDp` | 最远档模糊半径 | 44dp |
| `scrimAlpha` | 整体压暗 | 0.30 |
| `coverAspect` | 裁切比例，复用 `CoverAspect` | 3:4 |
| `restoreDelaySec` | 暂停多久后恢复 | 5 |
| `fallbackToLowRes` | 非网易云时用 363 位图 | true |

`SHARP_CENTER` 等常量从 `private const val` 改为**有默认值的配置**。
`AlbumBackdrop` 继续用默认值（主界面观感不变），烘焙侧读配置。

**预览**：`LockWallpaperScreen` 里放一块按屏幕比例缩小的预览框，
直接显示 `BackdropBaker` 烘焙出的 bitmap（**不是**用 `AlbumBackdrop`
实时渲染）——预览必须是**成品本身**，否则调好了写进去不一样。
这是歌词/封面实验室「用同一套渲染」口径的延续，但这里更严格：
预览的就是最终字节。

预览框上叠一个**时钟与媒体卡片的位置示意**（半透明线框，按 One UI
的实际位置），否则用户无法判断清晰区会不会被卡片压住。

### 5. 非网易云的回落

`ArtworkFetcher.fetch` 对非纯数字 mediaId 直接返回 null（已有行为）。
此时用 MediaSession 的 363 位图烘焙，受 `fallbackToLowRes` 控制（默认开）。

363 拉到 1080 宽是 3 倍上采样，会糊——但多档模糊本身就在糊，
清晰区那一档受影响，整体仍可接受。关掉则该曲目不换壁纸，保持恢复态。

### 6. 配置存储：不进 profile

`LockWallpaperConfig` 独立于 `NudgeConfig`，**不进 `ProfileCodec`**
（用户明确要求）。锁屏壁纸是设备级的环境设定，与「手势/灵敏度」
这类操作习惯不是一回事；切预设不该改壁纸行为。

这也避开了 CLAUDE.md「加配置项要同时改五处」的连锁修改。

## 风险与未决

1. **后台存活**：One UI 会杀后台。需引导用户加电池优化白名单
   （MusWall 也有这个要求）。这是本方案最大的**体验**风险。
2. **`setBitmap` 的开销与闪烁**：切歌频繁时表现如何，**必须真机实测**
   （连切十首，看延迟、闪烁、耗电）。这是最大的**技术**未知，
   实测不过关就得加大去抖或放弃。
3. **S24 Ultra（One UI 7/8）行为未知**：测试机是 One UI 5.1。
   One UI 7 重做了锁屏（Now Bar），壁纸的暗化/裁切可能不同。
   **主力机上必须重测恢复逻辑**。
4. **三星的锁屏壁纸暗化**：测试机 `darkmode dim:0.188`，深色封面会更闷。
   可在烘焙时反向补偿，但补偿量因版本而异，先实测再定。

## 测试

纯逻辑部分走 JVM 单测（沿用 `CoverAspectTest` 的口径）：

- **恢复语义的三分支**：设过 / 没设过（→ `clear`）/ 备份失败（→ 用户图）。
  这是最容易出静默破坏的地方，必须有测试拦着
  「没设过时不能写备份图」这个方向。
- **烘焙几何与 `AlbumBackdrop` 一致**：同一组参数下，
  两边算出的 `coverHeightRatio`、清晰区上下沿、蒙版色标应完全相同。
  抽成纯函数后可直接断言。
- **色标严格递增**：清晰区贴边时不能撞标（真机上会直接崩）。
- **去抖**：窗口内多次变化只写一次。

真机验证项（`WallpaperManager` 无法单测）：

- 开启 → 切歌 → 壁纸跟着换且**明显比 MusWall 清晰**
- 暂停 5 秒 → 恢复原壁纸；恢复播放 → 再次写入
- **关闭功能 → 壁纸回到原样**。分别在「设过独立锁屏壁纸」与
  「没设过」两种情形下各测一次——后者要断言锁屏**仍跟随桌面壁纸**
  （改一次桌面壁纸看锁屏是否跟着变），而不是被固定成一张图
- 飞行模式下切歌 → 回落 363 或不换，不崩
- 非网易云（YouTube）→ 按 `fallbackToLowRes` 行事，不崩
- 连切十首 → 内存不持续上涨（`dumpsys meminfo`）
- 杀进程 / 重启 → 壁纸停在最后一张（可接受），重新播放能恢复工作

## 明确不做

- **桌面壁纸**：用户不需要。
- **「点击展开」**：点媒体卡片走的是播放器自己的 `setSessionActivity`
  （真机可见 `launchIntent=PendingIntent{...com.netease.cloudmusic}`），
  nudge 拦不住。
- **自己画时钟/通知**：那是 B 方案（全屏 Activity）的事，已否决。
  自己画通知还会绕过系统的锁屏隐私策略。

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

- **切歌** → 重新烘焙并写入（受 `deferWhileScreenOff` 约束，见 1.1a）
- **暂停超过 `restoreDelaySec`** → 恢复原壁纸（默认 **5 秒**，可配置）
- **恢复播放** → 重新写入
- **亮屏 / 熄屏** → 熄屏时攒住待写状态，亮屏时补写最后一次

监听屏幕状态用 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`，
**必须动态注册**（这两个 action 从 Android 8 起不接受静态声明）。

5 秒沿用 MusWall 的默认值（用户认可），但做成可配置：
切歌间隙、来电、短暂暂停都会触发一次壁纸闪动，容忍度因人而异。

### 1.1 写入开销：链路、代价与四条优化

`setBitmap` 不是一次内存写。真机取证的存储形态：

```
/data/system/users/0/wallpaper_lock_images/wallpaper_first  3658177 字节，文件头 8950 4e47 = PNG
/data/system/users/0/wallpaper_colors_info/lock_phone.xml   取色缓存
```

**系统把壁纸存成 PNG（无损）**，所以每次写入的完整链路是：

| 环节 | 开销 | 能否避免 |
|---|---|---|
| 1. 烘焙全屏图（多档模糊） | GPU，几十 ms | 部分（见下 d） |
| 2. **PNG 编码 + 落盘** | **最重**，几 MB | ❌ 服务端行为 |
| 3. IPC 传给 `WallpaperManagerService` | 中等，走 fd | ❌ |
| 4. 解码 + `WallpaperColors` 取色 | 中等 | ❌ |
| 5. SystemUI 重新加载并重绘锁屏 | **闪烁来源** | ❌ |

专辑封面是照片类内容，PNG 几乎压不动（实测 1080×2400 的照片般数据，
7.8MB 原始像素压完仍有 7.5MB，与设备上那个 3.6MB 的文件同量级）。
所以**每次切歌 = 一次几 MB 的无损编码 + 磁盘写 + 解码 + 取色 + 锁屏重绘**。
它"重"不是因为 API 慢，是这条链路干的事本来就多。

四条优化，按收益排序：

- **a. 熄屏时不写**（收益最大）。正常听歌时屏幕大多是黑的，用户根本
  看不到锁屏，写了白写。攒住最后一次状态，等**亮屏或锁屏事件**再写。
  这条同时消掉了绝大部分「写入时重绘」的可见性。
- **b. 去抖** `writeDebounceMs`（默认 800ms）。快速连切十首只在停下来时
  写一次，把最坏情况从 10 次降到 1 次。
- **c. 同图不写**。比对 `mediaId + 配置指纹`，一样就跳过——
  暂停/恢复、进度更新都会触发监听，但图没变。
- **d. 可选降分辨率**。壁纸本就是模糊背景，按 0.7 倍尺寸烘焙再让系统
  缩放，编码量降一半。**观感影响待实测**，默认关。

**「闪烁」要分成两种**，成因和对策都不同：

- **写入时的重绘**：换壁纸那一瞬锁屏重新加载。**避不掉**（第 5 步由
  SystemUI 控制，没有平滑过渡的 API），但配合 a 后用户很少撞上。
- **恢复时的来回闪**：暂停 5 秒恢复 → 恢复播放又写回。短暂暂停
  （切歌间隙、来电）会闪两次。**这个能避**：调大 `restoreDelaySec`，
  或选「只在停止播放/熄屏后恢复」。

### 2. 恢复原壁纸：三种情形，不能只存一张图

真机取证：测试机上锁屏壁纸的落盘是
`/data/system/users/0/wallpaper_lock_images/wallpaper_first`，
而 `dumpsys wallpaper` 里 `Lock Wallpaper` 一节是
`mWallpaperComponent=null`——**静态图而非动态壁纸**，可备份。

但**不能假定它一定存在**：用户没设过独立锁屏壁纸时锁屏继承桌面壁纸，
此时 `getWallpaperFile(FLAG_LOCK)` 返回 null。判定依据以该 API 为准，
不要去读私有目录（第三方无权，且路径随版本变）。

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
| `deferWhileScreenOff` | 熄屏时攒住不写（见 1.1a） | true |
| `renderScale` | 烘焙缩放（见 1.1d） | 1.0 |

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
2. **`setBitmap` 的单次耗时**：链路已查清（见 1.1），但**具体数值没测出来**
   ——要测得先有一个真的调 `setBitmap` 的 app。
   **实现的第一个里程碑就是「能写进去 + 打出耗时」**，
   拿到数据再定 `writeDebounceMs` 和要不要开 `renderScale`。
   若单次超过 1 秒或有可感卡顿，回头重新权衡这个功能值不值得做。
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
- **同图不写**：`mediaId` 与配置指纹都没变时不产生写入。
- **熄屏攒住**：熄屏期间的多次变化只在亮屏后写一次，且写的是**最后**
  那首歌而不是第一首。

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

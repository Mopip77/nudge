# 配置预设（Profile）设计

## 目标

把当前全部配置存成命名预设，最多三个，可随时加载。另外导出一个广播入口，
让三星「模式与日常安排」等自动化工具能在切换场景时自动切换预设。

## 一、预设的内容与模型

### 存完整配置快照

一个预设就是一份完整的 `NudgeConfig`——手势绑定、灵敏度、主题、歌词开关、屏幕固定，
五项全存。不做「只存手势相关」的裁剪。

场景本身是跨维度的：「夜跑」既要宽松灵敏度、又要夜间主题、还要关歌词省流量。
按维度裁剪会让预设只能解决半个问题。

### 三个固定槽位，名字可改

槽位号固定 `1..3`，删除后不重排——槽位号是广播协议的对外标识（见 §四），
重排会让已配置好的自动化指向别的预设。

```kotlin
/** 一个预设槽位。空槽的 name 与 config 均为 null。 */
data class ProfileSlot(val index: Int, val name: String?, val config: NudgeConfig?)
```

名字由用户自定，是**唯一**的辨识手段——设置页不显示配置内容摘要，
所以命名是否达意直接决定三个月后还认不认得出来。保存时默认填「预设 N」，可直接确认。

### 不记录「当前预设」

加载是一次性覆写，之后改配置就是改当前配置，与来源预设再无关系。
三个槽位没有「选中」态，「覆盖」是唯一的写回路径，且带二次确认。

这是刻意的取舍，理由和收藏那条约束同源：**宁可让用户多点一次，也不要悄悄改掉他的数据**。
若记录 activeProfile 并在改配置时自动写回，盲操下用户改了灵敏度就会静默污染存档，
和 `like()` 直接调 toggle 把已收藏的歌取消掉是同一类缺陷。

## 二、持久化

### 一个预设序列化成一个 JSON 串

三个 `stringPreferencesKey("profile_1"/"2"/"3")`，值是 JSON。不扁平展开成十几个 key。

理由是可扩展性：`NudgeConfig` 还在长，JSON 方案加字段只需改 encode/decode 各一行，
扁平展开要同时动 key 表、读、写三处。`org.json` 项目里已在用（`ReleaseInfo.parse`），无新依赖。

```json
{
  "name": "夜跑",
  "bindings": { "NEXT_TRACK": "TWO_FINGER_DOUBLE_TAP", "LIKE": "" },
  "sensitivity": "RELAXED",
  "themeMode": "DARK",
  "lyricsEnabled": false,
  "screenPinningEnabled": true
}
```

`bindings` 的值沿用 `encodeGestures`／`decodeGestures` 的逗号分隔格式，不另造一套。

`name` 与配置各项在 JSON 里是同级字段，编解码的单位是「名字 + 配置」整体：
`encode(name, config) -> String` 与 `decode(String) -> Pair<String, NudgeConfig>?`
（整串非法或为空时返回 null，表示空槽）。`ProfileSlot` 把两者拆成独立字段只是
为了让界面层拿到 `index` 后能直接渲染，不影响存储布局。

### 解码必须宽容

缺字段、枚举名不认识、手势名不认识、整个 JSON 非法——全部回落到 `NudgeConfig.DEFAULT`
的对应值，不抛异常。这和 `decodeGestures` 「未知名字直接丢弃」、`ConfigStore` 读取侧
「读不到就回落默认」是同一口径，保证枚举重命名后读旧数据不会崩。

### 编解码放在纯 Kotlin 里

`ProfileCodec` 不依赖任何 Android 类，和 `GestureRecognizer` 一样能直接 JVM 单测。
预设的正确性靠序列化的 round-trip 保证，这部分必须可自动化测试。

注意 `org.json` 在 JVM 单测里是会抛「not mocked」的桩实现，项目已额外引了
`org.json:json` 作 testImplementation 覆盖掉它，所以这条路是通的。

## 三、加载的写入语义

**`loadProfile` 必须把五项配置全部写进 DataStore，包括值等于默认值的项。**

不能做「等于默认就不写」的优化。`bindings` 的读取侧口径是「没写过 key 才回落默认，
写过空串表示用户主动清空」——若加载一个空绑定的预设时跳过写入，读出来会变成
默认绑定而不是空集，用户存的「这个动作不绑任何手势」被静默改掉。

这条要写成代码注释，它不是显而易见的。

## 四、外部切换入口

### 三星「模式与日常安排」无法直接调用第三方动作

M&R 的操作列表是固定的内置类别，没有公开给开发者注册自定义动作的 API。
它对第三方应用只有「打开应用」（含应用配对分屏）。反方向（应用 → M&R）有隐藏
content provider，Tasker 在用，但那不是这里需要的方向。

所以链路必须经一层中转：**M&R → Tasker / MacroDroid → 广播 → nudge**。

### 只做 exported BroadcastReceiver

不做 deep link Activity。两个理由：

1. **广播不拉前台。** deep link 会把 nudge 弹到前台，而「开车时 M&R 切到驾驶模式」
   这种场景下突然弹出全屏触摸板是危险的。
2. **M&R「打开应用」能否携带自定义 extra 取决于 One UI 版本**，不确定能成。
   广播这条路是确定可用的。

真机验证完 M&R 的实际能力后若确实需要，再补 Activity 入口——两者可共用同一段切换逻辑。

### 协议

照 `MediaCommandReceiver` 的口径，同一套风格，用户只需学一次。

```
action: com.nudge.app.PROFILE
extra  slot: "1" | "2" | "3"
```

```bash
adb shell am broadcast -a com.nudge.app.PROFILE --es slot 2 \
  -n com.nudge.app/.config.ProfileCommandReceiver
```

**只支持槽位号，不支持按名字切。** 名字是用户可改的中文串，写进 Tasker/M&R 配置后
改个名就断了；槽位号固定，稳定。

**空槽位忽略并打日志**，不崩不弹提示，和现有 Receiver 对未知命令的处理一致。

**切换后不震动不弹 Toast。** 这是自动化触发的，用户可能根本没看手机。
只打 Log 供排查，和「盲操工具不该在启动时弹更新提示」是同一种克制。

Manifest 静态注册，让界面未打开时也能接收：

```xml
<receiver android:name=".config.ProfileCommandReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.nudge.app.PROFILE" />
    </intent-filter>
</receiver>
```

## 五、界面

「预设」区块放在设置页**最上面**（「权限」之后、手势绑定之前）。
加载预设会改掉下面所有区块的显示，放在最前符合「因在前、果在后」的阅读顺序。

空槽：

```
预设
  槽位 1（空）                      [保存当前配置]
```

已保存：

```
  夜跑                              [加载] [覆盖] [删除]
```

不显示配置内容摘要。三项摘要（灵敏度·主题·歌词）信息量低，而手势绑定展开后太长，
折中出来的摘要两头不靠。让名字承担全部辨识职责，反而促使用户起个有意义的名字。

三个 `AlertDialog`：

- **保存到空槽** —— 名字输入框，默认「预设 N」
- **覆盖已有** —— 「覆盖『夜跑』？当前配置将替换它，原内容无法恢复。」+ 名字输入框，默认保留原名
- **删除** —— 「删除『夜跑』？」

**加载不确认。** 它不销毁任何数据——当前配置随时能改回来，且用户点加载本就是为了看效果。
加载后给一个 Snackbar「已加载『夜跑』」；此处用户正在看屏幕，不需要震动反馈。

## 六、组件划分

```
NudgeConfig                  已有。预设就是它的快照
     │
ProfileCodec                 新增。纯 Kotlin，encode/decode JSON，无 Android 依赖
     │
ConfigStore                  扩展。profiles: Flow<List<ProfileSlot>>
     │                              saveProfile / loadProfile / deleteProfile
     ├── SettingsScreen       扩展。「预设」区块 + 三个 AlertDialog
     └── ProfileCommandReceiver  新增。广播入口，照 MediaCommandReceiver 写
```

## 七、测试

`ProfileCodecTest`（纯 JVM）：

- **round-trip**：`DEFAULT`、空 bindings、多手势绑定同一动作 —— encode→decode 后相等
- **宽容解码**：缺字段、未知枚举名、未知手势名、非法 JSON —— 回落 DEFAULT 对应值，不抛异常

`ProfileCommandReceiver` 的 slot 解析（纯函数部分）：

- `"1"/"2"/"3"` 解析正确；`null`、`"0"`、`"4"`、`"abc"` 一律返回 null 而非抛异常

### 需真机验证

DataStore 读写、界面交互、广播入口无法自动化。关键回归项：

- **空绑定预设的往返**：保存一个某动作无绑定的预设 → 改配置 → 加载它 → 断言该动作
  绑定仍为空集（防 §三 那个「跳过写入」的坑回归）
- **广播切换**：用上面的 adb 命令切到各槽位，含空槽位（应无反应且不崩）

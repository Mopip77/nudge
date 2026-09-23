package com.nudge.app.media

/** 广播协议独立于手势绑定，新增远程命令不会改变已有手势配置。 */
enum class MediaCommand(val wireName: String) {
    NEXT("next"),
    PREVIOUS("previous"),
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("play_pause"),
    LIKE("like"),

    /**
     * 切换歌词显示。**不经播放器**，只改本机配置。
     *
     * 放在 MediaCommand 里是为了让广播协议保持单一入口——外部工具
     * （HA / adb / Tasker）已经在用 `--es command <name>` 这套，
     * 为一个动作另开一个 receiver 会让协议分叉。
     */
    TOGGLE_LYRICS("toggle_lyrics");

    companion object {
        fun parse(value: String?): MediaCommand? = entries.firstOrNull { it.wireName == value }
    }
}

package com.nudge.app.media

/** 广播协议独立于手势绑定，新增远程命令不会改变已有手势配置。 */
enum class MediaCommand(val wireName: String) {
    NEXT("next"),
    PREVIOUS("previous"),
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("play_pause"),
    LIKE("like");

    companion object {
        fun parse(value: String?): MediaCommand? = entries.firstOrNull { it.wireName == value }
    }
}

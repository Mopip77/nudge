package com.nudge.app.action

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nudge.app.media.MediaCommand
import com.nudge.app.media.MediaControlRepository

/**
 * Home Assistant Companion 的 command_broadcast_intent 入口。
 * 静态注册让界面未打开时也能接收；仅允许协议中的固定动作，不接受任意媒体 action。
 */
class MediaCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MEDIA) return

        try {
            val action = MediaCommand.parse(intent.getStringExtra(EXTRA_COMMAND))
            if (action == null) {
                Log.w(TAG, "忽略未知或缺失的媒体命令")
                return
            }
            val result = ActionDispatcher(context, MediaControlRepository(context)).dispatch(action)
            // 广播发送成功不代表播放器执行成功，保留结果供本机排查。
            Log.i(TAG, "command=${intent.getStringExtra(EXTRA_COMMAND)}, result=$result")
        } catch (e: Exception) {
            // 会话可能在收到广播后消失，外部参数也可能类型错误，不能让进程崩溃。
            Log.e(TAG, "媒体命令执行失败", e)
        }
    }

    companion object {
        const val ACTION_MEDIA = "com.nudge.app.MEDIA"
        const val EXTRA_COMMAND = "command"
        private const val TAG = "NudgeMediaCommand"
    }
}

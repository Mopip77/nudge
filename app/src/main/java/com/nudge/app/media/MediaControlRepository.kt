package com.nudge.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.KeyEvent

/**
 * 媒体控制封装。
 *
 * 重要约束：本应用绝不注册自己的 MediaSession，否则会抢走媒体按键，
 * 导致 dispatchMediaKeyEvent 回退路径失效。
 */
class MediaControlRepository(private val context: Context) {

    private val sessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent =
        ComponentName(context, NudgeNotificationListener::class.java)

    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }

    private fun sessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    /** 网易云的会话，收藏功能仅对它有效。 */
    private fun neteaseController(): MediaController? =
        sessions().firstOrNull { it.packageName == NETEASE_PACKAGE }

    /**
     * 切歌的目标：优先网易云；网易云无会话时退而选第一个正在播放的会话。
     * 这样「下一首」对任意音乐应用都可用。
     */
    private fun skipTargetController(): MediaController? =
        neteaseController()
            ?: sessions().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    fun currentTrack(): TrackInfo? {
        val controller = skipTargetController() ?: return null
        val md = controller.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        return TrackInfo(title = title, artist = artist, isLiked = readIsLiked(controller))
    }

    private fun readIsLiked(controller: MediaController): Boolean =
        controller.metadata
            ?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            ?.hasHeart() == true

    fun skipNext(): ActionResult {
        val controller = skipTargetController()
        if (controller != null) {
            controller.transportControls.skipToNext()
            return ActionResult.Skipped
        }
        // 回退：无通知使用权时用媒体按键，零权限但无法指定目标
        return if (dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)) {
            ActionResult.Skipped
        } else {
            ActionResult.NoSession
        }
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            // 必须成对发送 DOWN + UP，只发 DOWN 很多播放器不响应
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 收藏当前歌曲，语义为「只点亮，永不取消」。
     *
     * 网易云的 setRating 实测为 toggle（忽略传入的布尔值，只当切换信号），
     * 因此必须先读 USER_RATING 判断当前状态，已收藏时不做任何操作。
     * 否则盲操下会静默取消用户已有的收藏。
     */
    fun like(): ActionResult {
        val controller = neteaseController() ?: return ActionResult.NoSession

        if (readIsLiked(controller)) return ActionResult.AlreadyLiked

        val actions = controller.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_SET_RATING != 0L) {
            controller.transportControls.setRating(Rating.newHeartRating(true))
            return ActionResult.Liked
        }

        // 兜底：动态查找 like custom action，不硬编码 id 以适应网易云改版
        val likeAction = controller.playbackState?.customActions?.firstOrNull {
            it.action.contains("STAR", ignoreCase = true) ||
                it.name.toString().contains("like", ignoreCase = true)
        }
        if (likeAction != null) {
            controller.transportControls.sendCustomAction(likeAction.action, null)
            return ActionResult.Liked
        }

        return ActionResult.Failed("网易云未暴露收藏能力")
    }

    companion object {
        const val NETEASE_PACKAGE = "com.netease.cloudmusic"

        fun openNotificationSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

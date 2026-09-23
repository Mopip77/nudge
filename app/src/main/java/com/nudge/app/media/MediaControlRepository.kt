package com.nudge.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
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
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val state = controller.playbackState

        return TrackInfo(
            title = title,
            artist = artist,
            isLiked = readIsLiked(controller),
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            artwork = readArtwork(md),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position ?: 0,
            positionUpdateTimeMs = state?.lastPositionUpdateTime ?: 0,
            // 部分播放器暂停时上报 speed=0，这里只在播放中取用，避免进度推算被清零
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
        )
    }

    /**
     * 读取封面。网易云三个 key 指向同一张图，按优先级取第一个非空的。
     *
     * 位图较大（实测 363x363 ARGB_8888 约 515KB），调用方只读不改，不要回写。
     */
    private fun readArtwork(md: MediaMetadata): Bitmap? =
        ARTWORK_KEYS.firstNotNullOfOrNull { key ->
            runCatching { md.getBitmap(key) }.getOrNull()
        }

    private fun readIsLiked(controller: MediaController): Boolean =
        controller.metadata
            ?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            ?.hasHeart() == true

    fun skipNext(): ActionResult = execute(MediaCommand.NEXT)

    fun execute(command: MediaCommand): ActionResult {
        if (command == MediaCommand.LIKE) return like()
        val keyCode = when (command) {
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.LIKE -> error("收藏使用独立入口")
        }
        val result = if (command == MediaCommand.NEXT || command == MediaCommand.PREVIOUS) {
            ActionResult.Skipped
        } else {
            ActionResult.PlaybackCommandSent
        }
        // 恢复其他播放器时要能找到暂停的会话，同时维持网易云、正在播放会话的优先级。
        val controller = skipTargetController() ?: if (
            command == MediaCommand.PLAY || command == MediaCommand.PLAY_PAUSE
        ) sessions().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED } else null
        if (controller != null) {
            when (command) {
                MediaCommand.NEXT -> controller.transportControls.skipToNext()
                MediaCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
                MediaCommand.PLAY -> controller.transportControls.play()
                MediaCommand.PAUSE -> controller.transportControls.pause()
                MediaCommand.PLAY_PAUSE -> {
                    // 必须读状态后调 play()/pause()，不能发 KEYCODE_MEDIA_PLAY_PAUSE。
                    //
                    // 真机实测（网易云 / One UI 5.1）：dispatchMediaButtonEvent 发一对
                    // ACTION_DOWN + ACTION_UP，会被播放器按「连按两次播放键」计数，
                    // 而连按两次在 Android 媒体按键约定里是**下一首**——于是「播放/暂停」
                    // 手势的实际效果是切歌。日志里能看到手势判定完全正确
                    // （TWO_FINGER_SWIPE_DOWN -> PLAY_PAUSE），缺陷只在这一层。
                    //
                    // 这里读状态做分支是安全的：playbackState 由播放器持续回推，
                    // 手势触发时读到的就是当前真实状态。读不到状态时按「未在播放」
                    // 处理并调 play()，因为盲操下用户更可能是想恢复播放。
                    val playing =
                        controller.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (playing) controller.transportControls.pause()
                    else controller.transportControls.play()
                }
                MediaCommand.LIKE -> error("收藏使用独立入口")
            }
            return result
        }
        // 回退：无通知使用权时用媒体按键，零权限但无法指定目标
        return if (dispatchMediaKey(keyCode)) {
            result
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
     * 网易云的收藏入口实测为 toggle（只当切换信号，不接受目标状态），
     * 因此必须先读 USER_RATING 判断当前状态，已收藏时不做任何操作。
     * 否则盲操下会静默取消用户已有的收藏。
     *
     * 注意 USER_RATING 对未收藏的歌也报 isRated=true，只有 hasHeart 有区分度。
     */
    fun like(): ActionResult {
        val controller = neteaseController() ?: return ActionResult.NoSession

        if (readIsLiked(controller)) return ActionResult.AlreadyLiked

        // 真机实测网易云的 actions 位掩码不含 ACTION_SET_RATING（822 =
        // PAUSE|PLAY|SKIP_TO_PREVIOUS|SKIP_TO_NEXT|SEEK_TO|PLAY_PAUSE），
        // 收藏只能走 custom action。动态查找而非硬编码 id，以适应改版。
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

        /** 封面 key 的优先级。网易云三者指向同一张图，其他播放器未必都给。 */
        private val ARTWORK_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )

        fun openNotificationSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

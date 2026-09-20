package com.nudge.app.media

import android.service.notification.NotificationListenerService

/**
 * 仅用于换取 MediaSessionManager.getActiveSessions() 所需的通知使用权。
 *
 * 不处理任何通知内容——之所以需要这个服务，是因为 Android 把「读取活跃媒体会话」
 * 的能力与通知使用权绑定，普通应用没有别的途径。
 */
class NudgeNotificationListener : NotificationListenerService()

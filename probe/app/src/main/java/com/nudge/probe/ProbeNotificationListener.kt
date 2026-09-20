package com.nudge.probe

import android.service.notification.NotificationListenerService

/**
 * 仅用于换取 MediaSessionManager.getActiveSessions() 所需的通知使用权。
 * 不处理任何通知。
 */
class ProbeNotificationListener : NotificationListenerService()

package com.nudge.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nudge.app.MainActivity
import com.nudge.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网易云在前台时，在屏幕一侧挂一个跳转到 nudge 的气泡。
 *
 * ## 为什么必须是前台服务
 *
 * 这件事整个发生在**别的应用在前台**的时候，nudge 自己不可见。One UI 对
 * 后台进程的限制很严，普通后台服务撑不过几分钟——表现为「用了一会儿气泡
 * 自己就没了」。代价是通知栏常驻一条，与锁屏壁纸服务是同一个取舍。
 *
 * 类型取 `specialUse`：不播放、不传数据，只是观察前台应用并画一个悬浮窗。
 * **Android 14 起不声明 type 就是启动即崩**，而 A13 上完全正常——
 * 这条坑见 CLAUDE.md 锁屏壁纸一节，两个服务踩的是同一个。
 *
 * ## 为什么用独立的通知渠道
 *
 * 与锁屏壁纸共用一个渠道的话，用户在系统设置里关掉那个渠道会把两个功能的
 * 通知一起静音，而前台服务通知被关掉后功能仍在跑——他会失去「这个东西
 * 正在后台工作」的唯一线索。
 */
class OverlayBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var monitor: ForegroundAppMonitor
    private lateinit var store: OverlayStore
    private lateinit var bubble: BubbleWindow

    @Volatile
    private var cfg: OverlayConfig = OverlayConfig.DEFAULT

    @Volatile
    private var screenOn: Boolean = true

    /** 上一次的显隐判定。前台未知时 `BubblePolicy` 要沿用它。 */
    @Volatile
    private var visible: Boolean = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        monitor = ForegroundAppMonitor(this)
        store = OverlayStore(this)
        bubble = BubbleWindow(this).apply {
            onClick = { launchNudge() }
            onMoved = { edge, y -> store.savePositionBlocking(edge, y) }
        }

        startForegroundCompat()

        // ACTION_SCREEN_ON/OFF 必须动态注册，Android 8 起不接受静态声明。
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

        scope.launch {
            store.config.collect { newCfg ->
                val sizeOrLookChanged = newCfg.sizeDp != cfg.sizeDp ||
                    newCfg.alpha != cfg.alpha ||
                    newCfg.edge != cfg.edge ||
                    newCfg.yRatio != cfg.yRatio
                cfg = newCfg
                if (!newCfg.enabled) {
                    stopSelf()
                    return@collect
                }
                // 就地更新而不重建：重建会让气泡闪一下，而用户正在设置页
                // 拖滑块，闪动会被当成参数没生效。
                if (sizeOrLookChanged) withContext(Dispatchers.Main) { bubble.update(newCfg) }
            }
        }

        scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val fg = if (screenOn) monitor.currentForegroundPackage() else null
            val want = BubblePolicy.visible(
                BubbleInputs(
                    enabled = cfg.enabled,
                    foregroundPackage = fg,
                    screenOn = screenOn,
                    previouslyVisible = visible,
                ),
            )
            if (want != visible) {
                visible = want
                // WindowManager 的增删必须在主线程。
                withContext(Dispatchers.Main) {
                    if (want) bubble.show(cfg) else bubble.hide()
                }
                // 显隐时机没法单测（WindowManager 是框架类），真机验证靠这条。
                // 带上前台包名：气泡该出没出的问题九成是前台解析的问题。
                Log.i(TAG, "气泡${if (want) "显示" else "隐藏"} 前台=$fg 挂载=${bubble.isShowing}")
            }
            delay(POLL_MS)
        }
    }

    /**
     * 拉起 nudge 主界面。
     *
     * 从服务里 `startActivity` 在 Android 10+ 受后台启动限制，但**持有
     * `SYSTEM_ALERT_WINDOW` 的应用是豁免的**——而那正是画气泡的前提，
     * 所以这条路径成立。`NEW_TASK` 是从非 Activity 上下文启动的必备 flag。
     */
    private fun launchNudge() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "快速跳转",
                    // LOW：前台服务通知必须存在，但这条不该发声或弹横幅。
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("快速跳转已开启")
            .setContentText("网易云音乐在前台时显示跳转按钮")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        // 服务停掉必须把窗口摘掉，否则气泡会留在屏幕上且再也没人管它
        // ——点击回调所在的服务已经没了，表现为「一个点不动的图标赖在屏幕上」。
        bubble.hide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OverlayBubble"

        /**
         * 开关开着就把服务拉起来。理由同 `LockWallpaperService.resumeIfEnabled`：
         * One UI 会杀后台，重启手机后服务就没了，而用户那边表现为「气泡不见了」
         * 且没有任何提示，打开应用是最自然的恢复时机。
         *
         * 权限被中途收回时**不拉起**：没有 `SYSTEM_ALERT_WINDOW` 时服务能起来
         * 但 `addView` 必失败，结果是通知栏挂着一条「已开启」而屏幕上什么都没有。
         */
        fun resumeIfEnabled(context: Context) {
            if (!OverlayPermissions.allGranted(context)) return
            val enabled = runCatching {
                kotlinx.coroutines.runBlocking { OverlayStore(context).currentConfig().enabled }
            }.getOrDefault(false)
            if (enabled) start(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayBubbleService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayBubbleService::class.java)) }
        }

        private const val CHANNEL_ID = "overlay_bubble"
        private const val NOTIF_ID = 1002

        /**
         * 1 秒。与 `MainActivity` 重建 `TrackInfo` 的节奏一致，`queryEvents`
         * 只读系统内存里的事件表，不碰网络也不碰磁盘。
         *
         * 熄屏时这条循环仍在跑但**不查询**（见 [pollLoop]），所以后台功耗
         * 与熄屏时长无关——口径同锁屏壁纸的「熄屏不写」。
         */
        private const val POLL_MS = 1_000L
    }
}

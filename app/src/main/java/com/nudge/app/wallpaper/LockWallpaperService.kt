package com.nudge.app.wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nudge.app.media.ArtworkCache
import com.nudge.app.media.MediaControlRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 把当前播放曲目的封面烘焙成锁屏壁纸。
 *
 * ## 为什么是前台服务
 *
 * 这件事必须在**应用不在前台时**继续工作——用户听歌时手机是锁着的，
 * nudge 的界面根本不可见。One UI 对后台的限制又很严，普通后台服务
 * 撑不过几分钟。所以只能是前台服务，代价是通知栏常驻一条。
 *
 * ## 为什么轮询而不是监听回调
 *
 * `MediaControlRepository` 现有的读取口径就是 `getActiveSessions()` 快照
 * （`MainActivity` 也是每秒重建 `TrackInfo`）。改成注册
 * `OnActiveSessionsChangedListener` 是更优雅，但那会引入一套新的生命周期，
 * 而轮询间隔取 [POLL_MS] 时开销可以忽略（只读内存里的 session 状态，
 * 不碰网络、不碰磁盘）。真正贵的那步（烘焙+写入）由 [WritePolicy] 把关。
 */
class LockWallpaperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: MediaControlRepository
    private lateinit var writer: LockWallpaperWriter
    private lateinit var store: LockWallpaperStore
    private val policy = WritePolicy()

    /** 当前配置。由 DataStore 推送更新。 */
    @Volatile
    private var cfg: LockWallpaperConfig = LockWallpaperConfig.DEFAULT

    @Volatile
    private var screenOn: Boolean = true

    /** 壁纸当前是不是我们写的封面。恢复/写入后更新，决定要不要执行恢复。 */
    @Volatile
    private var coverShowing: Boolean = false

    /** 暂停开始的时刻；null 表示不在暂停计时中。 */
    @Volatile
    private var pausedSinceMs: Long? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // 补写熄屏期间攒下的最后一项。
                    scope.launch { flushPending() }
                }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = MediaControlRepository(this)
        writer = LockWallpaperWriter(this)
        store = LockWallpaperStore(this)

        startForegroundCompat()

        // ACTION_SCREEN_ON/OFF **必须动态注册**——Android 8 起不接受静态声明。
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

        scope.launch {
            store.config.collect { newCfg ->
                val pixelsChanged = newCfg.renderFingerprint != cfg.renderFingerprint
                cfg = newCfg
                // 成品像素变了，同一首歌也得重写，否则用户调了参数看不到变化。
                if (pixelsChanged) policy.invalidate()
                if (!newCfg.enabled) stopSelf()
            }
        }

        scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            runCatching { tick() }.onFailure { Log.w(TAG, "tick 失败", it) }
            delay(POLL_MS)
        }
    }

    private suspend fun tick() {
        if (!cfg.enabled) return
        val track = repo.currentTrack()

        // 没有会话 / 停止播放 → 走恢复计时
        if (track == null || !track.isPlaying) {
            maybeRestore()
            return
        }
        pausedSinceMs = null

        val key = "${track.mediaId}|${cfg.renderFingerprint}"
        val decision = policy.request(
            WritePolicy.Request(key, System.currentTimeMillis()),
            screenOn = screenOn,
            deferWhileScreenOff = cfg.deferWhileScreenOff,
        )
        if (decision is WritePolicy.Decision.Write) {
            bakeAndWrite(track.mediaId, track.artwork)
        }
    }

    /** 亮屏时补写熄屏期间攒下的那一张。 */
    private suspend fun flushPending() {
        if (!cfg.enabled) return
        val d = policy.onScreenOn(System.currentTimeMillis())
        if (d !is WritePolicy.Decision.Write) return
        val track = repo.currentTrack() ?: return
        bakeAndWrite(track.mediaId, track.artwork)
    }

    /**
     * 取图 → 烘焙 → 写入。
     *
     * 高清图走 [ArtworkCache]（与主界面同一份缓存，切歌时已经拉过的
     * 不必重拉）；拿不到时按配置回落到 MediaSession 那张 363。
     */
    private suspend fun bakeAndWrite(mediaId: String, lowRes: Bitmap?) {
        // 全屏尺寸而非 displayMetrics——后者扣掉了系统栏，壁纸会被拉伸。
        val (w, h) = BackdropBaker.screenSize(this)
        val density = resources.displayMetrics.density

        val hiRes = ArtworkCache.load(mediaId, w)?.bitmap
        val cover = hiRes ?: lowRes.takeIf { cfg.fallbackToLowRes } ?: return

        val baked = runCatching {
            BackdropBaker.bake(cover, w, h, density, cfg)
        }.getOrElse {
            Log.w(TAG, "烘焙失败", it)
            return
        }

        val ms = writer.write(baked)
        // 里程碑 1 的观测点：真机上这条日志给出单次写入的真实耗时。
        Log.i(TAG, "写入锁屏壁纸 mediaId=$mediaId hiRes=${hiRes != null} 耗时=${ms}ms")
        if (ms != null) coverShowing = true

        // 烘焙产物用完即弃：全屏 ARGB_8888 一张 10MB 量级，攒着必 OOM。
        // 与 ArtworkCache 里那张源图不同——那张还要给主界面用。
        baked.recycle()
    }

    /** 暂停超过 `restoreDelaySec` 后恢复原壁纸。 */
    private fun maybeRestore() {
        if (!coverShowing) return
        val since = pausedSinceMs ?: System.currentTimeMillis().also { pausedSinceMs = it }
        if (System.currentTimeMillis() - since < cfg.restoreDelaySec * 1000L) return

        val kind = store.originalKind()
        if (writer.restore(kind, store.userSuppliedBitmap())) {
            coverShowing = false
            // 必须清掉「上次写的是谁」：壁纸已经不是那张图了，
            // 同一首歌恢复播放时要能重新写回去。
            policy.onRestored()
            Log.i(TAG, "已恢复原壁纸 kind=$kind")
        }
        pausedSinceMs = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        // 服务停止时把壁纸还回去——否则封面会永久留在锁屏上。
        // 这是用户最容易骂人的地方：关掉功能/杀掉应用之后壁纸不该还是封面。
        if (coverShowing) {
            runCatching { writer.restore(store.originalKind(), store.userSuppliedBitmap()) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * 进前台。**Android 14 起必须带 type**，否则抛
     * `MissingForegroundServiceTypeException` 直接崩。
     *
     * 这个坑在 Android 13 上完全测不出来——13 允许不带 type 的前台服务，
     * 我据此错误地得出「targetSdk 34 下不需要声明」的结论，
     * 结果在 One UI 8.5 / Android 16 的真机上一启用就闪退。
     * **要验证前台服务，必须在 A14+ 的机器上验。**
     *
     * 类型取 `specialUse`：我们不播放任何东西（`mediaPlayback` 不成立），
     * 也不传输数据（`dataSync` 不成立），只是跟着别人的播放状态改壁纸。
     */
    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        // 字符串直接写在代码里——本项目没有 strings.xml，全是内联中文。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "锁屏封面壁纸",
                // LOW：这条通知只是前台服务的凭证，不该响也不该弹。
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("锁屏封面壁纸运行中")
            .setContentText("播放时把专辑封面设为锁屏壁纸")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LockWallpaper"
        private const val CHANNEL_ID = "lock_wallpaper"
        private const val NOTIF_ID = 42

        /**
         * 轮询间隔。取 2 秒：切歌后最多 2 秒壁纸跟上，肉眼可接受；
         * 而这一步只读内存里的 session 快照，开销可忽略。
         */
        private const val POLL_MS = 2000L

        fun start(context: Context) {
            val i = Intent(context, LockWallpaperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockWallpaperService::class.java))
        }

        /**
         * 配置里开着就把服务拉起来。应用启动时调，用于从「被系统杀掉」
         * 或「重启手机」中恢复——那两种情况下用户只会看到壁纸不再跟着
         * 切歌，没有任何提示。
         *
         * `runBlocking` 读一次 DataStore：在 `onCreate` 里必须同步拿到结果，
         * 异步的话这一帧过后 Activity 可能已经走完初始化。读本地盘是毫秒级。
         */
        fun resumeIfEnabled(context: Context) {
            val enabled = runCatching {
                runBlocking { LockWallpaperStore(context).currentConfig().enabled }
            }.getOrDefault(false)
            if (enabled) start(context)
        }
    }
}

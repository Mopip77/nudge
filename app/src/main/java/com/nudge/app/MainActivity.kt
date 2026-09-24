package com.nudge.app

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import com.nudge.app.action.ActionDispatcher
import com.nudge.app.config.ConfigStore
import com.nudge.app.config.DisplayMode
import com.nudge.app.config.NudgeConfig
import com.nudge.app.config.PROFILE_SLOT_COUNT
import com.nudge.app.config.ProfileShortcuts
import com.nudge.app.config.ProfileSlot
import com.nudge.app.lyrics.LyricsRepository
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.media.ActionResult
import com.nudge.app.media.ArtworkCache
import com.nudge.app.media.MediaControlRepository
import com.nudge.app.media.TrackInfo
import com.nudge.app.ui.CoverLabScreen
import com.nudge.app.ui.LockWallpaperScreen
import com.nudge.app.ui.CoverOverride
import com.nudge.app.ui.HapticLabScreen
import com.nudge.app.ui.LyricsLabScreen
import com.nudge.app.config.ActionType
import com.nudge.app.ui.GestureBindingScreen
import com.nudge.app.ui.SettingsScreen
import com.nudge.app.ui.TrackpadScreen
import com.nudge.app.ui.clearSystemGestureExclusion
import com.nudge.app.ui.enterImmersiveMode
import com.nudge.app.ui.excludeFromSystemGestures
import com.nudge.app.ui.exitImmersiveMode
import com.nudge.app.update.ApkDownloader
import com.nudge.app.update.UpdateChecker
import com.nudge.app.update.UpdateInstaller
import com.nudge.app.update.UpdateState
import com.nudge.app.ui.theme.NudgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 动作执行后延迟多久再读取播放状态。
 *
 * setRating / skipToNext 都是 oneway 异步调用，返回时目标应用尚未处理完，
 * 立即读会拿到操作前的旧值。
 */
private const val POST_ACTION_REFRESH_DELAY_MS = 250L

/**
 * 两次返回键在此窗口内按下才真的退出。
 *
 * 盲操下用户看不到「再按一次退出」的提示，这个窗口的作用不是给人读提示，
 * 而是让「口袋里蹭到一次返回」不足以退出——真要退出的人会连按。
 */
private const val DOUBLE_BACK_WINDOW_MS = 2000L

/**
 * 把 [previous] 里已经拉到的高清封面接到这个新读出来的曲目上（同一首歌才接）。
 *
 * **每一处用 `repository.currentTrack()` 的结果覆写 `track` 的地方都必须走这里。**
 * 那个方法读的是 MediaSession，只认识 363 的那张，`hiResArtwork` 恒为 null；
 * 直接赋值就会把异步拉到的高清图抹掉。
 *
 * 抹掉之后**不会自愈**：轮询那段同样按「同一首歌就保留」的口径接力，
 * 于是接力的是 null，而拉取 effect 的 key 是 mediaId、不会重跑——
 * 表现为封面退回低清并一直停在那里，直到切歌。
 *
 * 这个 bug 最早就是这么漏出来的：轮询那处做了保留，手势后刷新那处忘了，
 * 于是「按一下暂停，封面就糊了」。抽成函数而不是复制第二遍判断，
 * 就是为了让「又多一个刷新点」时不必重新想一遍这件事。
 */
private fun TrackInfo?.keepHiResFrom(previous: TrackInfo?): TrackInfo? =
    this?.copy(
        hiResArtwork = previous?.takeIf { it.mediaId == mediaId }?.hiResArtwork
    )

class MainActivity : ComponentActivity() {

    private lateinit var repository: MediaControlRepository
    private lateinit var dispatcher: ActionDispatcher
    private lateinit var configStore: ConfigStore

    /**
     * 防误触模式的当前值，供 [onWindowFocusChanged] 读取。
     *
     * 它是 Activity 回调，拿不到 Compose 里的 config，只能靠这个字段传递；
     * 写入方是 setContent 内的 LaunchedEffect。初值 null 表示配置尚未就绪——
     * 此时不施加任何一层，避免关掉防误触的用户每次启动都被首帧的默认值
     * 先固定一下屏幕再解开。
     */
    private var antiMistouchEnabled: Boolean? = null

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = MediaControlRepository(this)
        dispatcher = ActionDispatcher(this, repository)
        configStore = ConfigStore(this)

        // 盲操场景下屏幕熄灭就没法操作了
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 这里不再无条件进沉浸式：三层防误触统一由配置开关控制，
        // 而配置来自 DataStore 的 Flow，要等下面的 LaunchedEffect 拿到真实值。

        setContent {
            val config by configStore.config.collectAsState(initial = NudgeConfig.DEFAULT)
            // 初值是三个空槽而非 emptyList()，否则首帧区块空白、随后才跳出三行
            val profiles by configStore.profiles.collectAsState(
                initial = (1..PROFILE_SLOT_COUNT).map { ProfileSlot(it, null, null) }
            )
            val scope = rememberCoroutineScope()
            var showSettings by remember { mutableStateOf(false) }
            // 实验室是设置页的下一层，所以是独立的布尔量而不是与 showSettings
            // 互斥的枚举：从实验室返回要退回设置页，而不是一路退回主界面。
            var showLyricsLab by remember { mutableStateOf(false) }
            var showHapticLab by remember { mutableStateOf(false) }
            var showCoverLab by remember { mutableStateOf(false) }
            var showLockWallpaper by remember { mutableStateOf(false) }
            // 手势绑定二级页。用可空的 ActionType 而非布尔量：这一页必须知道
            // 是在给哪个动作配手势，null 即「不在这一页」。
            var bindingAction by remember { mutableStateOf<ActionType?>(null) }
            var track by remember { mutableStateOf<TrackInfo?>(null) }
            var hasPermission by remember { mutableStateOf(repository.hasNotificationAccess()) }
            var lyricsState by remember { mutableStateOf<LyricsState>(LyricsState.Idle) }
            var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

            // 轮询播放状态。MediaController 回调需要绑定/解绑生命周期管理，
            // 而本应用是前台短时使用，1 秒轮询更简单且开销可忽略。
            //
            // 两个查询都是到系统服务的同步跨进程调用（getActiveSessions 走 binder，
            // hasNotificationAccess 查 ContentProvider），必须放到 IO 线程——
            // LaunchedEffect 默认调度器是主线程，直接调会在高负载时阻塞触摸响应。
            LaunchedEffect(Unit) {
                while (true) {
                    val permission = withContext(Dispatchers.IO) {
                        repository.hasNotificationAccess()
                    }
                    val current = withContext(Dispatchers.IO) { repository.currentTrack() }
                    hasPermission = permission
                    track = current.keepHiResFrom(track)
                    delay(1000)
                }
            }

            // 三层防误触统一跟随配置开关。
            //
            // 不直接用上面的 config：它首帧是 DEFAULT（防误触开），真实值稍后才到，
            // 关掉防误触的用户每次启动都会被先固定一下屏幕再解开。这里单独收一份
            // 初值为 null 的流，配置真正就绪后才施加。
            val antiMistouch by configStore.config
                .map { it.antiMistouchEnabled }
                .distinctUntilChanged()
                .collectAsState(initial = null)
            LaunchedEffect(antiMistouch) {
                val enabled = antiMistouch ?: return@LaunchedEffect
                antiMistouchEnabled = enabled
                applyAntiMistouch(enabled)
            }

            // 歌曲变化时重新拉歌词。以 mediaId 为 key，切歌会自动取消上一次
            // 未完成的请求，避免旧歌词错配到新歌上。
            //
            // lyricsEnabled 也作为 key：关闭时不仅隐藏 UI，还要连网络请求一起省掉；
            // 重新打开时该 effect 重启，立刻补拉当前歌曲的歌词而不必等切歌。
            val mediaId = track?.mediaId
            LaunchedEffect(mediaId, config.lyricsEnabled) {
                lyricsState = if (mediaId.isNullOrBlank() || !config.lyricsEnabled) {
                    LyricsState.Idle
                } else {
                    LyricsState.Loading
                    LyricsRepository.load(mediaId)
                }
            }

            // 高清封面。同上：以 mediaId 为 key，切歌自动取消上一次未完成的
            // 请求，避免旧封面错配到新歌上。
            //
            // **仅封面模式才拉**：简洁模式只有 52dp 的小图，363 那张绰绰有余，
            // 为它跑一次网络往返加一次 6MB 的解码没有意义。
            //
            // **比例不在 key 里**：请求恒为方图，比例是渲染侧的事。
            // 早先 coverAspect 也是 key，于是实验室里每换一档都白重拉一次网络，
            // 而换比例现在应当立即生效、零网络。
            val albumMode = config.displayMode == DisplayMode.ALBUM
            val screenWidthPx = resources.displayMetrics.widthPixels
            LaunchedEffect(mediaId, albumMode) {
                if (mediaId.isNullOrBlank() || !albumMode) return@LaunchedEffect
                val hiRes = ArtworkCache.load(mediaId, screenWidthPx)
                    ?: return@LaunchedEffect
                // 拉完期间可能已经切歌，此时这张图是旧歌的，丢掉。
                // effect 的取消不保证能在 track 被改之前生效，故再比一次。
                if (track?.mediaId == mediaId) {
                    track = track?.copy(hiResArtwork = hiRes.bitmap)
                }
            }

            NudgeTheme(themeMode = config.themeMode) {
                Surface {
                    val editingAction = bindingAction
                    if (showLyricsLab) {
                        BackHandler { showLyricsLab = false }
                        LyricsLabScreen(onBack = { showLyricsLab = false })
                    } else if (showHapticLab) {
                        BackHandler { showHapticLab = false }
                        HapticLabScreen(onBack = { showHapticLab = false })
                    } else if (showCoverLab) {
                        BackHandler { showCoverLab = false }
                        CoverLabScreen(
                            track = track,
                            onBack = { showCoverLab = false },
                        )
                    } else if (showLockWallpaper) {
                        BackHandler { showLockWallpaper = false }
                        LockWallpaperScreen(
                            track = track,
                            onBack = { showLockWallpaper = false },
                        )
                    } else if (editingAction != null) {
                        // 与实验室同理：这是设置页的下一层，返回要退回设置页。
                        // 判断放在 showSettings 之前，否则会被设置页那一支拦截。
                        BackHandler { bindingAction = null }
                        GestureBindingScreen(
                            action = editingAction,
                            config = config,
                            onBindingAdd = { action, gesture ->
                                scope.launch { configStore.addBinding(action, gesture) }
                            },
                            onBindingRemove = { action, gesture ->
                                scope.launch { configStore.removeBinding(action, gesture) }
                            },
                            onBack = { bindingAction = null },
                        )
                    } else if (showSettings) {
                        // 全 app 只有一个 Activity，也没用 navigation，设置页是靠
                        // showSettings 布尔量 if/else 切出来的——系统返回栈里始终只有
                        // MainActivity 一项，没有「上一页」，返回键默认行为是 finish
                        // 整个 Activity。必须自己接管，才能退回主界面而不是退出 app。
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            config = config,
                            profiles = profiles,
                            hasPermission = hasPermission,
                            onOpenGestureBinding = { bindingAction = it },
                            onSensitivityChange = {
                                scope.launch { configStore.setSensitivity(it) }
                            },
                            onThemeChange = {
                                scope.launch { configStore.setThemeMode(it) }
                            },
                            onLyricsEnabledChange = {
                                scope.launch { configStore.setLyricsEnabled(it) }
                            },
                            onLyricsAlignmentChange = {
                                scope.launch { configStore.setLyricsAlignment(it) }
                            },
                            onDisplayModeChange = {
                                scope.launch { configStore.setDisplayMode(it) }
                            },
                            onAntiMistouchChange = {
                                scope.launch { configStore.setAntiMistouchEnabled(it) }
                            },
                            onProfileSave = { index, name ->
                                scope.launch {
                                    configStore.saveProfile(index, name, config)
                                    syncProfileShortcuts()
                                }
                            },
                            onProfileLoad = { index ->
                                scope.launch {
                                    val loaded = configStore.loadProfile(index)
                                    if (loaded != null) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "已加载「${loaded.name}」",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            onProfileDelete = { index ->
                                scope.launch {
                                    configStore.deleteProfile(index)
                                    syncProfileShortcuts()
                                }
                            },
                            onRequestPermission = {
                                MediaControlRepository.openNotificationSettings(this@MainActivity)
                            },
                            currentVersion = BuildConfig.VERSION_NAME,
                            updateState = updateState,
                            onCheckUpdate = {
                                updateState = UpdateState.Checking
                                scope.launch {
                                    val latest = withContext(Dispatchers.IO) {
                                        UpdateChecker.fetchLatest()
                                    }
                                    updateState = when {
                                        latest == null -> UpdateState.CheckFailed
                                        UpdateChecker.hasUpdate(BuildConfig.VERSION_NAME, latest) ->
                                            UpdateState.Available(latest)
                                        else -> UpdateState.UpToDate
                                    }
                                }
                            },
                            onDownloadUpdate = { release ->
                                updateState = UpdateState.Downloading(release, 0, release.apkSize)
                                scope.launch {
                                    val file = withContext(Dispatchers.IO) {
                                        ApkDownloader.download(
                                            this@MainActivity,
                                            release,
                                        ) { downloaded ->
                                            // 下载循环在 IO 线程，回调里切回主线程改状态
                                            scope.launch(Dispatchers.Main) {
                                                updateState = UpdateState.Downloading(
                                                    release,
                                                    downloaded,
                                                    release.apkSize,
                                                )
                                            }
                                        }
                                    }
                                    updateState = if (file == null) {
                                        UpdateState.DownloadFailed(release)
                                    } else {
                                        startInstall(file)
                                        UpdateState.Downloaded(release)
                                    }
                                }
                            },
                            onInstallUpdate = { release ->
                                // 「重新安装」走的是已下载好的包，不必重新下载
                                startInstall(
                                    ApkDownloader.apkFile(this@MainActivity, release.versionName)
                                )
                            },
                            onOpenLyricsLab = { showLyricsLab = true },
                            onOpenHapticLab = { showHapticLab = true },
                            onOpenCoverLab = { showCoverLab = true },
                            onOpenLockWallpaper = { showLockWallpaper = true },
                            onBack = { showSettings = false },
                        )
                    } else {
                        // 主界面的返回键要连按两次才退出。设置页不加这层——
                        // 那里是明视操作，且「返回」只是退回主界面，误触没有代价。
                        //
                        // 用 enabled 参数而不是在回调里判断：关掉防误触时这个 handler
                        // 整个不拦截，返回键走系统默认直接退出，语义比「拦下来再手动
                        // finish」更准。配置未就绪（null）时按默认值开着。
                        var lastBackMs by remember { mutableStateOf(0L) }
                        BackHandler(enabled = antiMistouch ?: NudgeConfig.DEFAULT.antiMistouchEnabled) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastBackMs < DOUBLE_BACK_WINDOW_MS) {
                                finish()
                            } else {
                                lastBackMs = now
                                Toast.makeText(
                                    this@MainActivity,
                                    "再按一次返回退出",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        TrackpadScreen(
                            track = track,
                            config = config,
                            hasPermission = hasPermission,
                            lyricsState = lyricsState,
                            onGesture = { gesture ->
                                // 本回调由 pointerInteropFilter 在触摸事件分发路径上同步调用，
                                // 而 dispatch 内部是跨进程的媒体控制调用，必须切到 IO 线程，
                                // 否则每次手势都会在 UI 线程上阻塞，盲操时表现为触摸卡顿。
                                scope.launch(Dispatchers.IO) {
                                    val result = dispatcher.dispatch(gesture, config)
                                    if (result == null) return@launch

                                    // 歌词开关不经播放器，曲目信息不可能变。走下面那段
                                    // 刷新只会白等 600ms 再做一次跨进程查询；而界面由
                                    // config 流驱动，DataStore 一写就自动重组了。
                                    if (result is ActionResult.LyricsToggled) return@launch

                                    // 收藏成功时乐观点亮红心：setRating 是异步的，
                                    // 等网易云回推 metadata 要几百毫秒，盲操场景下
                                    // 先按已知结果更新，随后的刷新会校正。
                                    if (result is ActionResult.Liked) {
                                        withContext(Dispatchers.Main) {
                                            track = track?.copy(isLiked = true)
                                        }
                                    }

                                    // 留出时间让目标应用处理完并回推新的 metadata。
                                    // 立即读会拿到操作前的旧值，反而把正确的显示覆盖掉。
                                    delay(POST_ACTION_REFRESH_DELAY_MS)
                                    val refreshed = repository.currentTrack()
                                    withContext(Dispatchers.Main) {
                                        track = refreshed.keepHiResFrom(track)
                                    }
                                }
                            },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    /**
     * 按当前开关重新施加（或撤销）窗口层的防误触。
     *
     * 必须在这里做而不是只在配置变化时做：
     * - 沉浸式粘性在切走再切回后会丢失，导航栏退回默认行为；
     * - 排除区要的是 decorView 的实际尺寸，onCreate 时还没测量完，拿到的是 0。
     *
     * 配置未就绪时什么都不做，理由见 [antiMistouchEnabled]。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        applyWindowAntiMistouch(antiMistouchEnabled ?: return)
    }

    /**
     * 施加三层防误触。开关关闭时逐层撤销，完全恢复系统默认。
     */
    private fun applyAntiMistouch(enabled: Boolean) {
        applyWindowAntiMistouch(enabled)
        applyScreenPinning(enabled)
    }

    /** 第 1 层：沉浸式粘性 + 全屏手势排除区。两者配套，见 AntiMistouch.kt。 */
    private fun applyWindowAntiMistouch(enabled: Boolean) {
        if (enabled) {
            enterImmersiveMode()
            window.decorView.excludeFromSystemGestures()
        } else {
            exitImmersiveMode()
            window.decorView.clearSystemGestureExclusion()
        }
    }

    /**
     * 让动态 shortcut 与槽位状态一致。存/删预设后调用。
     *
     * 从 DataStore 重新读一遍而不用 Compose 里的 profiles：那是写入前的快照，
     * Flow 还没把新值推过来，直接用会同步出旧状态。
     */
    private suspend fun syncProfileShortcuts() {
        val slots = configStore.profiles.first()
        ProfileShortcuts.sync(this, slots)
    }

    /**
     * 第 3 层：屏幕固定。
     *
     * 非 device owner 时 `startLockTask()` 退化为屏幕固定：系统弹框征求同意，
     * 用户长按返回+概览可以退出。这正是我们要的强度——挡住误触，但不锁死用户。
     * 真正的 kiosk 需要 DPC 白名单，那要 device owner 权限，普通应用不该做。
     */
    private fun applyScreenPinning(enabled: Boolean) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pinned = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

        // 已是目标状态就别重复调用：startLockTask 在已固定时无效果，
        // 但 stopLockTask 在未固定时会抛异常。
        if (enabled == pinned) return
        runCatching {
            if (enabled) startLockTask() else stopLockTask()
        }
    }

    /**
     * 拉起系统安装器，没有「安装未知应用」授权时先把用户送去设置页。
     *
     * 不做「授权后自动继续安装」：APK 已经下载完了，用户授权后退回来点一下
     * 「重新安装」即可，为此挂一个 ActivityResult 回调不值当。
     */
    private fun startInstall(apk: File) {
        if (!apk.exists()) return
        if (UpdateInstaller.canInstall(this)) {
            UpdateInstaller.install(this, apk)
        } else {
            UpdateInstaller.openInstallPermissionSettings(this)
        }
    }
}

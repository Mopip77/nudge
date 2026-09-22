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
import com.nudge.app.config.NudgeConfig
import com.nudge.app.config.PROFILE_SLOT_COUNT
import com.nudge.app.config.ProfileShortcuts
import com.nudge.app.config.ProfileSlot
import com.nudge.app.lyrics.LyricsRepository
import com.nudge.app.lyrics.LyricsState
import com.nudge.app.media.ActionResult
import com.nudge.app.media.MediaControlRepository
import com.nudge.app.media.TrackInfo
import com.nudge.app.ui.SettingsScreen
import com.nudge.app.ui.TrackpadScreen
import com.nudge.app.ui.enterImmersiveMode
import com.nudge.app.ui.excludeFromSystemGestures
import com.nudge.app.update.ApkDownloader
import com.nudge.app.update.UpdateChecker
import com.nudge.app.update.UpdateInstaller
import com.nudge.app.update.UpdateState
import com.nudge.app.ui.theme.NudgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

class MainActivity : ComponentActivity() {

    private lateinit var repository: MediaControlRepository
    private lateinit var dispatcher: ActionDispatcher
    private lateinit var configStore: ConfigStore

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = MediaControlRepository(this)
        dispatcher = ActionDispatcher(this, repository)
        configStore = ConfigStore(this)

        // 盲操场景下屏幕熄灭就没法操作了
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式粘性：边缘滑动只能召出系统栏，要再滑一次才真的导航。
        // 同时它是下面全屏手势排除区生效的前提（见 AntiMistouch.kt）。
        enterImmersiveMode()

        setContent {
            val config by configStore.config.collectAsState(initial = NudgeConfig.DEFAULT)
            // 初值是三个空槽而非 emptyList()，否则首帧区块空白、随后才跳出三行
            val profiles by configStore.profiles.collectAsState(
                initial = (1..PROFILE_SLOT_COUNT).map { ProfileSlot(it, null, null) }
            )
            val scope = rememberCoroutineScope()
            var showSettings by remember { mutableStateOf(false) }
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
                    track = current
                    delay(1000)
                }
            }

            // 屏幕固定跟随配置开关。放在 setContent 里而非 onResume，是因为
            // config 来自 DataStore 的 Flow，首帧拿到的是 DEFAULT，真实值稍后才到。
            LaunchedEffect(config.screenPinningEnabled) {
                applyScreenPinning(config.screenPinningEnabled)
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

            NudgeTheme(themeMode = config.themeMode) {
                Surface {
                    if (showSettings) {
                        // 全 app 只有一个 Activity，也没用 navigation，设置页是靠
                        // showSettings 布尔量 if/else 切出来的——系统返回栈里始终只有
                        // MainActivity 一项，没有「上一页」，返回键默认行为是 finish
                        // 整个 Activity。必须自己接管，才能退回主界面而不是退出 app。
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            config = config,
                            profiles = profiles,
                            hasPermission = hasPermission,
                            onBindingAdd = { action, gesture ->
                                scope.launch { configStore.addBinding(action, gesture) }
                            },
                            onBindingRemove = { action, gesture ->
                                scope.launch { configStore.removeBinding(action, gesture) }
                            },
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
                            onScreenPinningChange = {
                                scope.launch { configStore.setScreenPinningEnabled(it) }
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
                            onBack = { showSettings = false },
                        )
                    } else {
                        // 主界面的返回键要连按两次才退出。设置页不加这层——
                        // 那里是明视操作，且「返回」只是退回主界面，误触没有代价。
                        var lastBackMs by remember { mutableStateOf(0L) }
                        BackHandler {
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
                                    withContext(Dispatchers.Main) { track = refreshed }
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
     * 重新施加沉浸式，并把整块窗口登记为手势排除区。
     *
     * 两件事都必须在这里做而不是只在 onCreate：
     * - 沉浸式粘性在切走再切回后会丢失，导航栏退回默认行为；
     * - 排除区要的是 decorView 的实际尺寸，onCreate 时还没测量完，拿到的是 0。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        enterImmersiveMode()
        window.decorView.excludeFromSystemGestures()
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
     * 按配置开关屏幕固定。
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

package com.nudge.app

import android.os.Bundle
import android.view.WindowManager
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
import com.nudge.app.media.ActionResult
import com.nudge.app.media.MediaControlRepository
import com.nudge.app.media.TrackInfo
import com.nudge.app.ui.SettingsScreen
import com.nudge.app.ui.TrackpadScreen
import com.nudge.app.ui.theme.NudgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 动作执行后延迟多久再读取播放状态。
 *
 * setRating / skipToNext 都是 oneway 异步调用，返回时目标应用尚未处理完，
 * 立即读会拿到操作前的旧值。
 */
private const val POST_ACTION_REFRESH_DELAY_MS = 250L

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

        setContent {
            val config by configStore.config.collectAsState(initial = NudgeConfig.DEFAULT)
            val scope = rememberCoroutineScope()
            var showSettings by remember { mutableStateOf(false) }
            var track by remember { mutableStateOf<TrackInfo?>(null) }
            var hasPermission by remember { mutableStateOf(repository.hasNotificationAccess()) }

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
                            onRequestPermission = {
                                MediaControlRepository.openNotificationSettings(this@MainActivity)
                            },
                            onBack = { showSettings = false },
                        )
                    } else {
                        TrackpadScreen(
                            track = track,
                            config = config,
                            hasPermission = hasPermission,
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
}

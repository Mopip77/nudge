package com.nudge.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import com.nudge.app.media.MediaControlRepository
import com.nudge.app.media.TrackInfo
import com.nudge.app.ui.SettingsScreen
import com.nudge.app.ui.TrackpadScreen
import com.nudge.app.ui.theme.NudgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            LaunchedEffect(Unit) {
                while (true) {
                    hasPermission = repository.hasNotificationAccess()
                    track = repository.currentTrack()
                    delay(1000)
                }
            }

            NudgeTheme(themeMode = config.themeMode) {
                Surface {
                    if (showSettings) {
                        SettingsScreen(
                            config = config,
                            hasPermission = hasPermission,
                            onBindingChange = { action, gesture ->
                                scope.launch { configStore.setBinding(action, gesture) }
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
                                val result = dispatcher.dispatch(gesture, config)
                                if (result != null) {
                                    // 动作执行后立即刷新，让红心状态尽快反映变化
                                    track = repository.currentTrack()
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

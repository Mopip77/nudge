package com.nudge.app.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 防误触的窗口层处理。
 *
 * 盲操场景里误触的代价是**不对称**的：误触发一次「下一首」只是烦人，
 * 但误滑退出应用后用户看不见屏幕、根本不知道自己已经退出，后续所有手势
 * 都打在别的应用上。所以这里优先防「意外退出」，而不是防「手势识别错」。
 *
 * 本文件的四个函数成两对，由「防误触模式」开关统一切换（见 MainActivity）。
 * 关闭时完全恢复系统默认，而不是只松一半——语义要么是「防误触的 app」，
 * 要么是「普通全屏 app」，中间态只会让人猜不透当前到底拦不拦返回手势。
 */

/**
 * 进入沉浸式粘性模式：隐藏状态栏与导航栏，且系统栏只能被临时召出。
 *
 * 用 [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE] 而不是
 * `BEHAVIOR_SHOW_BARS_BY_SWIPE`：前者才是「二次触发」语义——系统栏隐藏时
 * home / back 手势被禁用，第一次边缘滑动只把栏召出来，第二次滑才真正导航。
 * 后者一次滑动就永久恢复系统栏，起不到防误触作用。
 *
 * 必须在每次获得焦点时重新调用（见 MainActivity 的 onWindowFocusChanged）：
 * 切到别的应用再切回来，粘性行为会丢失，导航栏退回默认表现。
 */
fun Activity.enterImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/**
 * 退出沉浸式：恢复系统栏，窗口不再铺到物理边缘。
 *
 * `setDecorFitsSystemWindows(true)` 必须跟 `show()` 一起做：只 show 不改 fit，
 * 系统栏会浮在内容之上遮住顶部；只改 fit 不 show，栏仍然是隐藏的。
 */
fun Activity.exitImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(window, true)
    WindowInsetsControllerCompat(window, window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
}

/**
 * 把整个 view 声明为系统手势排除区，使返回手势在其范围内失效。
 *
 * 有两个前提，缺一不可：
 *
 * 1. **API 29+**，低版本此 API 不存在，靠 ViewCompat 内部的版本判断兜底成空操作。
 * 2. **必须配合沉浸式粘性模式**。系统默认每条边只认最底部 200dp 的排除区，
 *    全屏范围会被截断；而官方对该限制的唯一豁免就是「导航栏处于粘性隐藏状态」。
 *    单独调本函数而不进沉浸式，在全屏触摸区上基本无效。
 *
 * 底部的 home / 快速切换手势**无法排除**，系统不提供接口。这其实是必要的：
 * 它是用户唯一可靠的逃生通道，否则粘性沉浸 + 全边排除会把人锁死在应用里。
 */
fun View.excludeFromSystemGestures() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    ViewCompat.setSystemGestureExclusionRects(this, listOf(Rect(0, 0, width, height)))
}

/** 撤销 [excludeFromSystemGestures]，返回手势在全屏范围内恢复正常。 */
fun View.clearSystemGestureExclusion() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    ViewCompat.setSystemGestureExclusionRects(this, emptyList())
}

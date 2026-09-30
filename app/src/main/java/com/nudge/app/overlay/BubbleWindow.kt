package com.nudge.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.nudge.app.R
import kotlin.math.abs

/**
 * 悬浮气泡的窗口管理。
 *
 * ## 为什么是 `TYPE_APPLICATION_OVERLAY`
 *
 * 真机取证：网易云的窗口是 `ty=BASE_APPLICATION`，而本类型的层级是
 * 21000，稳稳盖在普通应用之上。（顺带一条已知的天花板：锁屏由 SystemUI
 * 在 171000 绘制，同样的窗口**画在锁屏底下**，见 CLAUDE.md 的锁屏壁纸一节。
 * 所以气泡只在普通应用上方成立，别指望它出现在锁屏上。）
 *
 * ## 为什么不用 ComposeView
 *
 * 项目其余 UI 全是 Compose，这里刻意用原生 View。`ComposeView` 挂在
 * `WindowManager` 上需要自备 `ViewTreeLifecycleOwner` / `SavedStateRegistry`，
 * 否则一 attach 就崩；而这个视图只是一个圆 + 一个图标，不值得为它
 * 引入一套生命周期宿主。
 *
 * ## 拖动期间**不动窗口**，只改子 view 的 translation
 *
 * 这是拖动手感的关键。真机逐帧实测（120Hz 屏，一帧 8.3ms）：
 *
 * | 做法 | 中位帧间隔 | p95 | 掉帧 |
 * |---|---|---|---|
 * | 每个 MOVE 挪窗口 | 16.6ms | 24.9ms | **157/239** |
 * | 只改 translation | 8.3ms | 8.3ms | 4/239 |
 *
 * 挪窗口只能跑到**一半**的帧率，一半的帧是丢的，手感就是拖动跟不上手指。
 * 窗口几何变更要经 WindowManagerService 与 SurfaceFlinger，而 translation
 * 只是更新一个 RenderNode 属性，不触发 measure/layout，也不出进程。
 *
 * **别用单次调用耗时来判断这件事**：`updateViewLayout` 在客户端只花 24us
 * （单向 binder，量到的只是入队），看起来毫无问题——我正是被这个数误导过。
 * 代价全在 WMS 那一侧，只有量帧间隔才看得出来。
 *
 * 于是窗口有两种尺寸：静止时**恰好**是气泡大小（只挡住那 48dp 的触摸），
 * 拖动时一次性放大到全屏（让 translation 有地方可走）。整个拖动过程中
 * 窗口纹丝不动，只在进入和退出拖动时各变一次。
 *
 * 代价是拖动期间全屏窗口会挡住下层应用的触摸。可以接受——用户正在拖气泡，
 * 本来就不会同时想点网易云，松手立刻恢复成小窗口。
 */
class BubbleWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 窗口根容器。拖动时它会被放大到全屏，[bubble] 靠 translation 定位。 */
    private var root: FrameLayout? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 气泡在**屏幕**坐标系里的左上角。窗口尺寸变化不影响它的语义。 */
    private var bubbleX = 0
    private var bubbleY = 0
    private var bubbleSize = 0

    /** 松手后回调最终位置，由服务落盘。 */
    var onMoved: ((BubbleEdge, Float) -> Unit)? = null
    var onClick: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    fun show(cfg: OverlayConfig) {
        if (root != null) {
            update(cfg)
            return
        }
        bubbleSize = (cfg.sizeDp * context.resources.displayMetrics.density).toInt()
        bubbleX = edgeX(cfg.edge, bubbleSize)
        bubbleY = (screenHeight() * cfg.yRatio).toInt()

        val b = createBubble(cfg, bubbleSize)
        val container = FrameLayout(context).apply {
            // 拖动时气泡靠 translation 走到窗口各处，不能被容器裁掉。
            clipChildren = false
            addView(b, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        }
        val p = createParams()
        applyIdleGeometry(p)

        runCatching { wm.addView(container, p) }
            .onFailure { return }   // 权限被中途收回时 addView 会抛，静默放弃
        root = container
        bubble = b
        params = p
        attachTouch(b)
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null
        bubble = null
        params = null
    }

    /** 配置变化时就地更新，不重建窗口——重建会让气泡闪一下。 */
    fun update(cfg: OverlayConfig) {
        val b = bubble ?: return
        val p = params ?: return
        bubbleSize = (cfg.sizeDp * context.resources.displayMetrics.density).toInt()
        bubbleX = edgeX(cfg.edge, bubbleSize)
        bubbleY = (screenHeight() * cfg.yRatio).toInt()
        b.alpha = cfg.alpha
        b.layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        applyIdleGeometry(p)
        runCatching { wm.updateViewLayout(root, p) }
    }

    /**
     * 静止态：窗口恰好是气泡大小，摆在气泡该在的位置，translation 归零。
     *
     * 窗口只占 48dp 是刻意的——全屏窗口会把下层应用的触摸全部吃掉，
     * 而气泡平时只该挡住自己那一小块。
     */
    private fun applyIdleGeometry(p: WindowManager.LayoutParams) {
        p.width = bubbleSize
        p.height = bubbleSize
        p.x = bubbleX
        p.y = bubbleY
        bubble?.translationX = 0f
        bubble?.translationY = 0f
    }

    /**
     * 拖动态：窗口铺满屏幕，气泡靠 translation 定位到原来的视觉位置。
     *
     * 放大与 translation 的设置必须**在同一次 updateViewLayout 之前**完成，
     * 否则会有一帧气泡跳到窗口左上角——那是肉眼可见的闪动。
     */
    private fun applyDragGeometry(p: WindowManager.LayoutParams) {
        p.width = screenWidth()
        p.height = screenHeight()
        p.x = 0
        p.y = 0
        bubble?.translationX = bubbleX.toFloat()
        bubble?.translationY = bubbleY.toFloat()
    }

    private fun createBubble(cfg: OverlayConfig, size: Int): View =
        ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round))
            // 底下垫一个半透明深色圆：浅色壁纸／浅色歌单页上，图标自己的
            // 浅色边缘会与背景糊在一起看不出这是个可点的东西。
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 0, 0, 0))
            }
            val pad = size / 6
            setPadding(pad, pad, pad, pad)
            alpha = cfg.alpha
            contentDescription = "打开 nudge"
        }

    private fun createParams() = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // FLAG_NOT_FOCUSABLE：不抢输入焦点，否则网易云的搜索框会失焦、
        // 返回键也会先被我们这个窗口吃掉。气泡只要能收到触摸，不需要焦点。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // FLAG_LAYOUT_IN_SCREEN 是必须的，**少了它 x/y 会整体偏移**。
            //
            // 真机实测：不加时 `TOP|START` 的原点是**内容区**而非屏幕
            // （dumpsys 里 `parent=[0,78][1080,2355]`），请求 y=432 实际
            // 落在 510——正好差一个状态栏高度 78px。表现是设置页的
            // 「竖向位置」比标称值偏下，且拖动位置存盘后下次显示又往下挪一截
            // （落盘的是屏幕系，读回来当内容区系用，每次都多偏 78px）。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * 触摸处理：位移超过 touch slop 算拖动，否则算点击。
     *
     * 用系统的 `scaledTouchSlop` 而不是自己定一个值——它随屏幕密度与 ROM
     * 变化，写死会在高密度屏上让轻微手抖被当成拖动，于是「点不动」。
     * （同一个口径在 `GestureRecognizer` 里也用着：判定阈值都以 dp 为准。）
     */
    private fun attachTouch(b: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        b.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = bubbleX
                    startY = bubbleY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        // 进入拖动：窗口放大到全屏，**整个拖动过程只有这一次**
                        // 窗口变更。之后每帧只改 translation。
                        applyDragGeometry(p)
                        runCatching { wm.updateViewLayout(root, p) }
                    }
                    if (dragging) {
                        // 这里是热路径，每个触摸事件都会走到。只更新 RenderNode
                        // 属性，不碰 WindowManager、不触发 measure/layout。
                        bubbleX = startX + dx.toInt()
                        bubbleY = startY + dy.toInt()
                        b.translationX = bubbleX.toFloat()
                        b.translationY = bubbleY.toFloat()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // 吸附到最近的一边：自由落点会让气泡停在屏幕中间，
                        // 那里最挡事。竖向位置保留用户放的高度。
                        val edge = if (bubbleX + bubbleSize / 2 < screenWidth() / 2) {
                            BubbleEdge.LEFT
                        } else {
                            BubbleEdge.RIGHT
                        }
                        val yRatio = (bubbleY.toFloat() / screenHeight())
                            .coerceIn(OverlayConfig.Y_RATIO_RANGE)
                        bubbleX = edgeX(edge, bubbleSize)
                        bubbleY = (screenHeight() * yRatio).toInt()
                        // 退出拖动：窗口缩回气泡大小，translation 归零。
                        applyIdleGeometry(p)
                        runCatching { wm.updateViewLayout(root, p) }
                        // ACTION_CANCEL 也落盘：手势被系统打断（来电、别的窗口
                        // 抢走输入）时气泡已经挪到了新位置，不存反而更怪。
                        onMoved?.invoke(edge, yRatio)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        onClick?.invoke()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 贴边时留一点缝，完全贴死会与系统的边缘手势区重叠。 */
    private fun edgeX(edge: BubbleEdge, size: Int): Int {
        val margin = (EDGE_MARGIN_DP * context.resources.displayMetrics.density).toInt()
        return when (edge) {
            BubbleEdge.LEFT -> margin
            BubbleEdge.RIGHT -> screenWidth() - size - margin
        }
    }

    /**
     * 用 `maximumWindowMetrics` 而非 `displayMetrics`。
     *
     * 后者是**应用窗口**的尺寸（真机实测 1080×2277，比屏幕少 123px），
     * 而气泡是 overlay、坐标系是整个屏幕。用错会让「贴右边」差出一截、
     * 「贴底部」到不了底。这条坑与 `BackdropBaker` 的输出尺寸是同一个。
     */
    private fun screenWidth(): Int = screenBounds().first

    private fun screenHeight(): Int = screenBounds().second

    /** API 30 以下没有 `maximumWindowMetrics`，回落到 `displayMetrics`。 */
    private fun screenBounds(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = context.resources.displayMetrics
            m.widthPixels to m.heightPixels
        }

    private companion object {
        const val EDGE_MARGIN_DP = 4
    }
}

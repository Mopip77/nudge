package com.nudge.app.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 锁屏壁纸的 adb 入口，**仅供真机验证**。
 *
 * 这个功能的关键指标（单次写入耗时、恢复是否把继承态写成了固定图、
 * 熄屏攒住有没有生效）都没法单测——`WallpaperManager` 是框架类。
 * 而靠界面点按去测又没法读出耗时。所以留一个广播入口，
 * 与 `MediaCommandReceiver` / `ProfileCommandReceiver` 同一个口径。
 *
 * ```sh
 * adb shell am broadcast -a com.nudge.app.LOCKWP \
 *   -n com.nudge.app/.wallpaper.LockWallpaperDebugReceiver --es cmd on
 * ```
 *
 * 命令：`on` 开启并启动服务 / `off` 关闭并恢复原壁纸 /
 * `bake` 立刻烘焙写入一次（绕开去抖，用于测耗时）/ `restore` 立刻恢复。
 */
class LockWallpaperDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val store = LockWallpaperStore(context)
        val writer = LockWallpaperWriter(context)

        // runBlocking 而非异步：onReceive 返回后进程可能立即被回收，
        // 异步写 DataStore 会来不及。理由同 ProfileCommandReceiver。
        runBlocking {
            when (cmd) {
                "on" -> {
                    // 探测必须在第一次写入**之前**——我们写过一次之后，
                    // 系统就认为「设过独立锁屏壁纸」，再探测只会拿到
                    // 被自己污染的结果。
                    val kind = writer.detectOriginalKind()
                    store.saveOriginalKind(kind)
                    val canEnable = RestorePlan.canEnable(kind, store.userSuppliedBitmap() != null)
                    Log.i(TAG, "原壁纸形态=$kind 可否开启=$canEnable")
                    if (!canEnable) {
                        Log.w(TAG, "设过独立锁屏壁纸且没有恢复图，拒绝开启（开了就回不去）")
                        return@runBlocking
                    }
                    store.save(currentConfig(store).copy(enabled = true))
                    LockWallpaperService.start(context)
                }

                "off" -> {
                    store.save(currentConfig(store).copy(enabled = false))
                    LockWallpaperService.stop(context)
                    val kind = store.originalKind()
                    val ok = writer.restore(kind, store.userSuppliedBitmap())
                    Log.i(TAG, "关闭并恢复 kind=$kind 成功=$ok")
                }

                "restore" -> {
                    val kind = store.originalKind()
                    val ok = writer.restore(kind, store.userSuppliedBitmap())
                    Log.i(TAG, "恢复 kind=$kind 成功=$ok")
                }

                // `--ef scale 0.7` 可临时覆盖烘焙缩放，用于对比不同分辨率
                // 下的写入耗时（渲染分辨率是唯一能压缩写入开销的旋钮）。
                "bake" -> bakeOnce(context, store, writer, intent.getFloatExtra("scale", -1f))

                // `--es path /sdcard/x.png` 把一张图存为「恢复图」。
                // 正式入口是设置页的 Photo Picker，这里是 adb 侧的等价物。
                "setrestore" -> {
                    val path = intent.getStringExtra("path")
                    val bmp = path?.let { android.graphics.BitmapFactory.decodeFile(it) }
                    if (bmp == null) {
                        Log.w(TAG, "恢复图读取失败 path=$path")
                    } else {
                        store.saveUserSupplied(bmp)
                        Log.i(TAG, "已保存恢复图 ${bmp.width}x${bmp.height}")
                    }
                }

                else -> Log.w(TAG, "未知命令 $cmd")
            }
        }
    }

    /** 绕开去抖直接烘焙写入一次，用于测量单次耗时。 */
    private suspend fun bakeOnce(
        context: Context,
        store: LockWallpaperStore,
        writer: LockWallpaperWriter,
        scaleOverride: Float = -1f,
    ) {
        val cfg = currentConfig(store).let {
            if (scaleOverride > 0f) it.copy(renderScale = scaleOverride) else it
        }
        val repo = com.nudge.app.media.MediaControlRepository(context)
        val track = repo.currentTrack()
        if (track == null) {
            Log.w(TAG, "没有正在播放的会话")
            return
        }
        val (sw, sh) = BackdropBaker.screenSize(context)
        val density = context.resources.displayMetrics.density
        val t0 = System.currentTimeMillis()
        val hiRes = com.nudge.app.media.ArtworkCache.load(track.mediaId, sw)?.bitmap
        val tFetch = System.currentTimeMillis() - t0

        val cover = hiRes ?: track.artwork
        if (cover == null) {
            Log.w(TAG, "没有封面")
            return
        }

        val t1 = System.currentTimeMillis()
        val baked = BackdropBaker.bake(cover, sw, sh, density, cfg)
        val tBake = System.currentTimeMillis() - t1

        val outW = baked.width
        val outH = baked.height
        val tWrite = writer.write(baked)
        baked.recycle()

        // 里程碑 1 的观测点：三段耗时分开报，才知道瓶颈在取图、烘焙还是写入。
        Log.i(
            TAG,
            "耗时 取图=${tFetch}ms 烘焙=${tBake}ms 写入=${tWrite}ms " +
                "源图=${cover.width}x${cover.height} 高清=${hiRes != null} " +
                "输出=${outW}x$outH scale=${cfg.renderScale}",
        )
    }

    private suspend fun currentConfig(store: LockWallpaperStore): LockWallpaperConfig =
        store.config.first()

    private companion object {
        const val TAG = "LockWallpaper"
    }
}

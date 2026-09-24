package com.nudge.app.wallpaper

/**
 * 「这次要不要真的去写壁纸」的决策，纯 Kotlin 无 Android 依赖、无副作用。
 *
 * 抽出来单测是因为每次写入的代价很高（一次几 MB 的 PNG 无损编码 + 落盘 +
 * 解码取色 + 锁屏重绘，见设计文档 1.1），而这里的三条优化——去抖、
 * 熄屏攒住、同图不写——**全是时序逻辑，真机上很难复现和断言**：
 * 要造出「熄屏期间连切三首歌」这种场景只能靠手速。
 *
 * 决策与执行分离：本类只回答「写不写、写哪张」，不碰 `WallpaperManager`。
 */
class WritePolicy(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    /** 上一次**真正写进系统**的内容标识，用于「同图不写」。 */
    private var lastWrittenKey: String? = null

    /**
     * 上一次真正写入的时刻，用于去抖。
     *
     * 用可空而不是 `Long.MIN_VALUE` 当哨兵：`nowMs - Long.MIN_VALUE` 会
     * **溢出成负数**，于是第一次写入被误判成「在去抖窗口内」而跳过——
     * 表现为开启功能后第一首歌不换壁纸。单测抓出来的。
     */
    private var lastWriteAtMs: Long? = null

    /** 熄屏期间攒住的待写项；亮屏时补写**最后**一个而不是第一个。 */
    private var pending: Request? = null

    /** 一次写入意图。[key] = mediaId + 配置指纹，决定「是不是同一张图」。 */
    data class Request(val key: String, val nowMs: Long)

    sealed interface Decision {
        /** 立刻写。 */
        data class Write(val key: String) : Decision
        /** 同图，跳过。 */
        data object SkipSameImage : Decision
        /** 去抖窗口内，跳过（调用方可稍后重试）。 */
        data object SkipDebounced : Decision
        /** 熄屏中，已攒住。 */
        data object Deferred : Decision
    }

    /**
     * 请求写入。
     *
     * @param screenOn 屏幕是否亮着
     * @param deferWhileScreenOff 熄屏时是否攒住（配置项）
     */
    fun request(req: Request, screenOn: Boolean, deferWhileScreenOff: Boolean): Decision {
        // 同图不写要排在最前：暂停/恢复、进度更新都会触发监听，但图没变。
        // 放在去抖之后的话，同一张图会在去抖窗口过后被重复写一次。
        if (req.key == lastWrittenKey) return Decision.SkipSameImage

        if (deferWhileScreenOff && !screenOn) {
            // 覆盖而非追加：熄屏期间连切五首，亮屏后只该看到第五首。
            pending = req
            return Decision.Deferred
        }

        val last = lastWriteAtMs
        if (last != null && req.nowMs - last < debounceMs) return Decision.SkipDebounced

        commit(req)
        return Decision.Write(req.key)
    }

    /**
     * 亮屏时调用，补写熄屏期间攒下的最后一项。
     *
     * **不走去抖**：熄屏期间攒了多久都算等过了，此时正是该写的时刻。
     * 同图仍然要跳过——熄屏前后是同一首歌时不必重写。
     */
    fun onScreenOn(nowMs: Long): Decision {
        val p = pending ?: return Decision.SkipDebounced
        pending = null
        if (p.key == lastWrittenKey) return Decision.SkipSameImage
        commit(p.copy(nowMs = nowMs))
        return Decision.Write(p.key)
    }

    /**
     * 恢复原壁纸后调用。
     *
     * 必须清掉 [lastWrittenKey]：壁纸已经不是那张图了，下次同一首歌
     * 再播放时**必须重写**。不清的话会被「同图不写」挡掉，
     * 表现为「暂停恢复原壁纸后，继续播放同一首歌壁纸回不来」。
     */
    fun onRestored() {
        lastWrittenKey = null
        pending = null
    }

    /** 配置变更后调用：成品像素变了，同一首歌也得重写。 */
    fun invalidate() {
        lastWrittenKey = null
    }

    private fun commit(req: Request) {
        lastWrittenKey = req.key
        lastWriteAtMs = req.nowMs
    }

    companion object {
        /**
         * 去抖窗口。用户快速连按「下一首」时，把 N 次写入合并成 1 次。
         * 800ms 是「连按的间隔」与「正常切歌后想尽快看到新封面」的折中。
         */
        const val DEFAULT_DEBOUNCE_MS = 800L
    }
}

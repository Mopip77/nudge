package com.nudge.app.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 高清封面的单张缓存：**只留当前曲目那一张**。
 *
 * 不做 LRU、不留历史：高清图下载下来只有 100~150KB，但 1274² 的
 * ARGB_8888 解码后是 **6.5MB**，攒上十首就是 65MB。而封面模式一次只显示
 * 一张，多留的那些永远不会再被看到——留着纯粹是等 OOM。
 *
 * **不主动 `recycle()`**：切歌那一刻界面上画的仍是上一张（新的还没拉到），
 * 回收掉会让正在合成的那一帧直接抛 "Canvas: trying to use a recycled bitmap"。
 * 而封面模式下每层模糊都持有它的引用，精确判断「还有没有人在画」
 * 是做不到的。丢掉强引用交给 GC 就够了——这里的目的是「不攒着」，
 * 而不是「立刻释放」。真正会 OOM 的是无上限的 LRU，不是晚一个 GC 周期。
 *
 * 单例、进程内。与 `LyricsRepository` 不做缓存的口径不同，是因为这里的
 * 代价不对称：歌词是几 KB 的文本，重拉无所谓；封面是一次网络往返加一次
 * 大图解码，每秒一次的播放状态轮询要是每次都重拉，流量和卡顿都受不了。
 */
object ArtworkCache {

    private data class Key(val mediaId: String, val aspect: CoverAspect)

    private var key: Key? = null
    private var cached: ArtworkFetcher.Result? = null

    /**
     * 取当前曲目的高清封面，没有就现拉。**挂起函数，内部切 IO 线程。**
     *
     * 拉取失败返回 null，调用方继续用 MediaSession 那张 363 的图。
     * 失败**不写缓存**，下次重组会再试一次——短暂断网后恢复就能自愈。
     */
    suspend fun load(
        mediaId: String,
        aspect: CoverAspect,
        targetWidth: Int,
    ): ArtworkFetcher.Result? {
        val want = Key(mediaId, aspect)
        synchronized(this) {
            if (key == want) return cached
        }

        val fetched = withContext(Dispatchers.IO) {
            ArtworkFetcher.fetch(mediaId, aspect, targetWidth)
        } ?: return null

        synchronized(this) {
            key = want
            cached = fetched
        }
        // **总是把自己拉到的这张还给调用方**，哪怕这期间缓存槽已经被另一次
        // load 换成了别的歌。调用方拿到的必须是它请求的那一张——
        // 「这张是不是当前该显示的」由调用方自己判断（`MainActivity` 比对
        // mediaId，实验室本就只关心自己那次请求）。
        //
        // 早先这里写成「缓存槽已被别人占了就返回 null」，结果是：主界面先
        // 缓存了当前歌，实验室再请求同一首时走不到上面的命中分支就被判成
        // 失败，界面上显示「高清封面：未拉到」，而预览里明明是高清图。
        return fetched
    }

    /** 丢掉缓存。实验室的「清缓存重拉」用，让下一次 [load] 重新走网络。 */
    fun clear() {
        synchronized(this) {
            cached = null
            key = null
        }
    }
}

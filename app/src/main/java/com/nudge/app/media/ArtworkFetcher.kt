package com.nudge.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从网易云拉高清专辑封面。
 *
 * ## 为什么要绕过 MediaSession
 *
 * 经 MediaSession 拿到的封面恒为 **363×363**（真机实测：三个 bitmap key
 * 同图、三个 URI key 全 null，见 `CLAUDE.md`），铺满 1080px 宽是 3 倍上采样，
 * 封面模式下糊得肉眼可见。这是数据源的上限，渲染侧无解。
 *
 * 而 `METADATA_KEY_MEDIA_ID` 就是网易云的真实歌曲 id（同 [com.nudge.app.lyrics.LyricsFetcher]
 * 的依据），所以能直接查 `song/detail` 拿 `picUrl`，实测原图 1274~3000 见方，
 * 全部远超 363。
 *
 * **用 id 查而不是按歌名搜**：文本匹配是猜的。实测 iTunes 搜「陈奕迅 异梦」
 * 返回的是完全不相干的两张专辑，而盲操下匹配错了会显示另一张专辑的封面，
 * 用户不一定察觉。id 查是精确的。
 *
 * ## 恒请求方图
 *
 * 比例是渲染侧的事（见 [CoverAspect]）。这里只负责拿一张**尽可能清晰的
 * 方图**，因为方图请求永远不会触发接口那个「长边超限就连比例一起丢掉」
 * 的静默回落，而且与 MediaSession 那张 363 方图形状一致——两者在渲染侧
 * 走同一套裁切，替换时几何恒等。
 *
 * 形状照搬 [com.nudge.app.lyrics.LyricsFetcher]：`HttpURLConnection`、同样的头、
 * 5 秒超时、任何失败返回 null。不引 OkHttp/Coil——总共就这一个请求。
 */
object ArtworkFetcher {

    private const val TIMEOUT_MS = 5000

    /**
     * 拉一张高清封面，**恒为方图**。**阻塞调用，必须在 IO 线程执行。**
     *
     * 任何失败返回 null，调用方降级为继续用 MediaSession 那张 363 的图——
     * 无网络、非网易云播放器、接口改版都走这条，不打扰盲操。
     *
     * 不接受比例参数：比例是**渲染侧**的事（见 [CoverAspect]）。
     * 这样低清那张方图与这里拉到的方图在渲染时走同一套裁切，
     * 替换时几何恒等，只有清晰度变化。
     *
     * @param targetWidth 渲染需要的宽度，通常是屏幕宽度
     */
    fun fetch(songId: String, targetWidth: Int): Result? {
        // 非网易云播放器（skipTargetController 会回落到任意正在播放的会话）
        // 的 mediaId 不是数字 id，查了没意义，不浪费一次请求。
        if (songId.isBlank() || !songId.all { it.isDigit() }) return null

        val picUrl = fetchPicUrl(songId) ?: return null
        val shortEdge = probeSourceShortEdge(picUrl)
        val edge = squareEdgeFor(shortEdge, targetWidth)
        val bitmap = decode("$picUrl?param=${edge}y$edge") ?: return null
        return Result(bitmap, shortEdge)
    }

    /**
     * 拉取结果。带上 [sourceShortEdge] 是给封面实验室显示用的
     * （「这首歌的原图有多大」决定了能拿到多清晰的图）。
     */
    data class Result(val bitmap: Bitmap, val sourceShortEdge: Int)

    private fun fetchPicUrl(songId: String): String? {
        val body = get("https://music.163.com/api/song/detail?ids=%5B$songId%5D") {
            it.bufferedReader().readText()
        } ?: return null

        return runCatching {
            JSONObject(body)
                .getJSONArray("songs")
                .getJSONObject(0)
                .getJSONObject("album")
                .optString("picUrl")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 探测原图**短边**。
     *
     * 求方图时能裁出的最大方块由短边决定，按长边算会超出去。
     * **原图不一定是方的**——实测歌曲 28643004 的原图是 852×1136（3:4 竖）。
     * 早先这里取的是长边，注释里也写着「网易云的原图恒为方形」，是错的。
     *
     * `song/detail` 的 album 对象里**没有** `picWidth`/`picHeight`
     * （真机取证：整个 album 只有 picId / picUrl / pic 等字段，没有尺寸）。
     * 早先按它取值，恒为 0 而回落到一个写死的保守值，结果是所有歌都被
     * 压到那个值以下——白白丢掉清晰度，且实验室里报的源图尺寸是假的。
     *
     * 所以改成读**原图自己的文件头**：带 `Range: bytes=0-4095` 只取前 4KB，
     * 配 `inJustDecodeBounds` 解出尺寸而不解码像素。实测 CDN 认这个头
     * （返回 206 + `content-range`），4KB 足够覆盖 JPEG 的 SOF 段——
     * 比下整张原图（实测 0.8~2.8MB）便宜两三个数量级。
     *
     * 探测失败（CDN 不认 Range、头被截断）时回落到 [FALLBACK_EDGE]。
     * 现在探测失败**不再有几何后果**：请求恒为方图，算小了只是拿到一张
     * 小些的图。早先它还要防「超限触发静默回落丢掉比例」，责任重得多。
     */
    private fun probeSourceShortEdge(picUrl: String): Int {
        val bounds = get(picUrl, range = "bytes=0-$PROBE_BYTES") { stream ->
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeStream(stream, null, this)
            }
        } ?: return FALLBACK_EDGE

        val edge = minOf(bounds.outWidth, bounds.outHeight)
        return if (edge > 0) edge else FALLBACK_EDGE
    }

    private fun decode(url: String): Bitmap? = get(url) { BitmapFactory.decodeStream(it) }

    /** 一次 GET。头与超时同 `LyricsFetcher`——缺 Referer 时接口可能拒绝返回。 */
    private fun <T> get(
        url: String,
        range: String? = null,
        read: (java.io.InputStream) -> T,
    ): T? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://music.163.com")
                if (range != null) setRequestProperty("Range", range)
            }
            // 带 Range 时 CDN 回的是 206，不带时是 200，两者都算成功。
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK &&
                code != HttpURLConnection.HTTP_PARTIAL
            ) return null
            connection.inputStream.use(read)
        } catch (e: Exception) {
            // 无网络、超时、JSON 结构变化、图片解码失败都走这里。
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 探测原图尺寸时取的字节数。够覆盖 JPEG 的 SOF 段，又远小于整张图。 */
    private const val PROBE_BYTES = 4095

    /** 探测失败时的保守假设，取实测见过的较小原图边长。 */
    // 实测原图边长按歌不同（348 / 553 / 640 / 800 / 1500 / 3000 / 6000 都见过），
    // 取 640 兜底。请求恒为方图，所以超了也只是拿回原图本身、不丢比例，
    // 这个值算保守只会让这条罕见的降级路径少几个像素。
    private const val FALLBACK_EDGE = 640
}

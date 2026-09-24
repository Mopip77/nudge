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
 * 形状照搬 [com.nudge.app.lyrics.LyricsFetcher]：`HttpURLConnection`、同样的头、
 * 5 秒超时、任何失败返回 null。不引 OkHttp/Coil——总共就这一个请求。
 */
object ArtworkFetcher {

    private const val TIMEOUT_MS = 5000

    /**
     * 拉一张高清封面。**阻塞调用，必须在 IO 线程执行。**
     *
     * 任何失败返回 null，调用方降级为继续用 MediaSession 那张 363 的图——
     * 无网络、非网易云播放器、接口改版都走这条，不打扰盲操。
     *
     * @param targetWidth 渲染需要的宽度，通常是屏幕宽度
     */
    fun fetch(songId: String, aspect: CoverAspect, targetWidth: Int): Result? {
        // 非网易云播放器（skipTargetController 会回落到任意正在播放的会话）
        // 的 mediaId 不是数字 id，查了没意义，不浪费一次请求。
        if (songId.isBlank() || !songId.all { it.isDigit() }) return null

        val picUrl = fetchPicUrl(songId) ?: return null
        val edge = probeSourceEdge(picUrl)
        val param = paramFor(aspect, edge, targetWidth)
        val bitmap = decode("$picUrl?param=${param.query}") ?: return null
        return Result(bitmap, edge)
    }

    /**
     * 拉取结果。带上 [sourceEdge] 是给封面实验室用的：
     * 各档「会不会因超限而被静默回落成方图」只能按源图边长算，
     * 而那个数字只有这次请求知道。
     */
    data class Result(val bitmap: Bitmap, val sourceEdge: Int)

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
     * 探测原图长边。**必须探，不能省。**
     *
     * 请求长边超过原图时接口会**静默回落成方图**（实测：1500 的源上请求
     * `1080y1620` 返回的是 1500×1500，既不是请求的比例也没有错误提示），
     * 所以非方比例必须先知道原图多大才能算出安全的请求尺寸。
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
     * 探测失败（CDN 不认 Range、头被截断）时回落到 [FALLBACK_EDGE]：
     * 宁可请求得保守一点拿到一张小些的图，也好过超限触发静默回落。
     */
    private fun probeSourceEdge(picUrl: String): Int {
        val bounds = get(picUrl, range = "bytes=0-$PROBE_BYTES") { stream ->
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeStream(stream, null, this)
            }
        } ?: return FALLBACK_EDGE

        // 非方原图没遇到过，但按长边限幅才是安全的方向。
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
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
    // 取 640 而非更大的值：实测见过 640 的原图（老专辑），按它兜底才不会超限。
    // 方形档位不受影响（方形永远不会超限，见 paramFor），只有非方档位会被
    // 这个保守值压小一点——探测失败本就是个罕见的降级路径。
    private const val FALLBACK_EDGE = 640
}

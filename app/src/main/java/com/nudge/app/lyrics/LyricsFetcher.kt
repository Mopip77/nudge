package com.nudge.app.lyrics

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从网易云拉取歌词原文。
 *
 * 之所以能直接用歌曲 id 查（而不必按歌名搜索匹配），是因为真机实测
 * MediaMetadata 的 METADATA_KEY_MEDIA_ID 就是网易云的真实歌曲 id
 * （已核对 song/detail 返回的歌名、歌手、时长逐项一致）。
 * 这让歌词与音频天然同源，不存在版本不符导致的时间轴偏移。
 *
 * 用 HttpURLConnection 而非 OkHttp：只有这一个请求，不值得为它引入依赖。
 */
object LyricsFetcher {

    private const val TIMEOUT_MS = 5000

    /**
     * 接口返回的两套歌词数据。
     *
     * [tlyric] 是中文译文，**可空**——大量歌曲没有译文（中文歌本来就不需要，
     * 英文歌也不是都有），此时接口返回的 `tlyric.version` 为 0、`lyric` 为空串。
     *
     * [yrc] 是**逐字**歌词，[ytlrc] 是它配套的译文，两者同样可空：真机抽样
     * 12 首里只有 5 首有 `yrc`。
     *
     * **`(lrc, tlyric)` 与 `(yrc, ytlrc)` 是两套各自自洽、互不同轴的数据**，
     * 取用时必须整对选择，绝不能交叉组合（依据见 `YrcParser` 的注释）。
     * 这也是它们在同一个数据类里却分成两对的原因。
     */
    data class RawLyrics(
        val lrc: String,
        val tlyric: String?,
        val yrc: String? = null,
        val ytlrc: String? = null,
    )

    /** 阻塞调用，必须在 IO 线程执行。任何失败返回 null，调用方降级为无歌词。 */
    fun fetchLrc(songId: String): RawLyrics? {
        // yv / yrv 才会带出逐字歌词（yrc）与它配套的译文（ytlrc）。
        // 实测加这两个参数是**纯增量**：lrc / tlyric 两个字段不受影响，
        // 行级路径零风险。早先只要 lv/kv/tv，所以一直拿不到逐字数据。
        val url = "https://music.163.com/api/song/lyric" +
            "?id=$songId&lv=1&kv=1&tv=-1&yv=1&ytv=-1&yrv=-1"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // 缺 Referer 时接口可能拒绝返回
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://music.163.com")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val lrc = json.optJSONObject("lrc")
                ?.optString("lyric")
                ?.takeIf { it.isNotBlank() }
                ?: return null

            // 译文一直在响应里（请求参数的 tv=-1 就是在要它），早先只是没读。
            // 没有译文时接口返回 version=0 + 空 lyric，takeIf 会滤成 null，
            // 渲染侧据此退化为纯原文——这条降级路径是常态，不是异常。
            val tlyric = json.optJSONObject("tlyric")
                ?.optString("lyric")
                ?.takeIf { it.isNotBlank() }

            // 逐字歌词约四成的歌才有，取不到是常态，由上层回落到行级。
            val yrc = json.optJSONObject("yrc")
                ?.optString("lyric")
                ?.takeIf { it.isNotBlank() }
            val ytlrc = json.optJSONObject("ytlrc")
                ?.optString("lyric")
                ?.takeIf { it.isNotBlank() }

            RawLyrics(lrc, tlyric, yrc, ytlrc)
        } catch (e: Exception) {
            // 无网络、超时、JSON 结构变化都走这里。静默失败，不打扰盲操。
            null
        } finally {
            connection?.disconnect()
        }
    }
}

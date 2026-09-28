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
     * 接口返回的两份 LRC。[tlyric] 是中文译文，**可空**——大量歌曲没有译文
     * （中文歌本来就不需要，英文歌也不是都有），此时接口返回的
     * `tlyric.version` 为 0、`lyric` 为空串。
     */
    data class RawLyrics(val lrc: String, val tlyric: String?)

    /** 阻塞调用，必须在 IO 线程执行。任何失败返回 null，调用方降级为无歌词。 */
    fun fetchLrc(songId: String): RawLyrics? {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
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

            RawLyrics(lrc, tlyric)
        } catch (e: Exception) {
            // 无网络、超时、JSON 结构变化都走这里。静默失败，不打扰盲操。
            null
        } finally {
            connection?.disconnect()
        }
    }
}

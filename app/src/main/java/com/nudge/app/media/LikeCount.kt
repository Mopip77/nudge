package com.nudge.app.media

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从网易云拉一首歌的红心数（收藏人数）。
 *
 * 接口是 `song/red/count?songId=`，与封面、歌词一样按 `METADATA_KEY_MEDIA_ID`
 * 精确查询。实测返回 `{"data":{"count":231523,"countDesc":"23w+"}}`。
 *
 * **只取 `count`，不用 `countDesc`**：后者粒度太粗（实测 162 万与 2252 万
 * 都报成「100w+」），且是小写 `w`。展示格式由 [formatLikeCount] 统一决定。
 *
 * 形状照搬 [ArtworkFetcher]：`HttpURLConnection`、同样的头、5 秒超时、
 * 任何失败返回 null。红心数只是装饰信息，拉不到就不显示，不打扰盲操。
 */
object LikeCountFetcher {

    private const val TIMEOUT_MS = 5000

    /** **阻塞调用，必须在 IO 线程执行。** 失败或非网易云播放器返回 null。 */
    fun fetch(songId: String): Long? {
        // 同 ArtworkFetcher：非网易云播放器的 mediaId 不是数字 id，不浪费一次请求。
        if (songId.isBlank() || !songId.all { it.isDigit() }) return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://music.163.com/api/song/red/count?songId=$songId")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://music.163.com")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
                .getJSONObject("data")
                .optLong("count", -1)
                .takeIf { it >= 0 }
        } catch (e: Exception) {
            // 无网络、超时、JSON 结构变化都走这里。
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * 红心数的紧凑写法：千用 `K`、万用 `W`。
 *
 * - `< 1000` 原样：`999`
 * - `< 1万` 用 K，一位小数：`1.2K`、`9.9K`
 * - `< 100万` 用 W，一位小数：`1.2W`、`23.1W`
 * - 再往上只留整数：`162W`、`2252W`——小数位在这个量级已经没有信息量，
 *   却会让角标宽出一截去挤设置按钮
 *
 * 小数**截断而非四舍五入**：9999 四舍五入是「10.0K」，既越过了 K 的区间
 * 又比真实值大；截断成「9.9K」才不会报出一个还没达到的数。
 * 小数位为 0 时省略（`10W` 而非 `10.0W`）。
 */
fun formatLikeCount(count: Long): String {
    if (count < 1_000) return count.coerceAtLeast(0).toString()
    val (unit, suffix) = if (count < 10_000) 1_000L to "K" else 10_000L to "W"
    val tenths = count * 10 / unit
    return if (tenths < 1_000 && tenths % 10 != 0L) {
        "${tenths / 10}.${tenths % 10}$suffix"
    } else {
        "${count / unit}$suffix"
    }
}

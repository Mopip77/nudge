package com.nudge.app.lyrics

/**
 * 一行歌词。[timeMs] 是这行开始演唱的时刻（相对歌曲开头）。
 *
 * 刻意不含 Android 依赖：解析与查找的边界条件多，必须能在 JVM 上单测。
 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 当前时刻应高亮的行索引，无则 -1。
 *
 * 前奏期间（位置早于首行）返回 -1 而非 0，避免第一行在没唱之前就亮着。
 * 列表已按时间升序（[LrcParser] 保证），用二分查找而非线性扫描——
 * 这个函数在滚动时会被高频调用。
 */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    if (isEmpty() || positionMs < first().timeMs) return -1

    var low = 0
    var high = size - 1
    var result = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

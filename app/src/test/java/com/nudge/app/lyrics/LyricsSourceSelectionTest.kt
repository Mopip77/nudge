package com.nudge.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两套歌词数据的**择一**逻辑。
 *
 * 这是本次改动里最容易写错、且错了在界面上看不出是数据问题的一环：
 * `yrc` 与 `lrc` 不在同一条时间轴上（真机实测偏差非常数，单曲内范围
 * 可达 [−523, +2000]ms），混用的表现是扫光跑在字的前面或后面，
 * 看着像动画没调好。所以这里穷举各种组合，钉住「绝不跨轴」。
 */
class LyricsSourceSelectionTest {

    /** 行起 11460ms——与下面 lrc 样本的 12000ms 刻意不同，用来暴露混用。 */
    private val yrc =
        "[11460,900](11460,210,0)I (11670,180,0)do (11850,510,0)stay"

    /** 与 yrc **同轴**的译文（11460）。 */
    private val ytlrc = "[00:11.460]我留下"

    /** 与 lrc 同轴、与 yrc **不同轴**的译文（12000）。 */
    private val tlyric = "[00:12.000]这是 lrc 那一轴的译文"

    private val lrc = "[00:12.000]I do stay"

    @Test
    fun `有 yrc 时用 yrc 那一轴_且带字级时间表`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = lrc, tlyric = tlyric, yrc = yrc, ytlrc = ytlrc)
        )

        assertEquals(1, lines.size)
        // 时间戳必须来自 yrc（11460），不是 lrc（12000）
        assertEquals(11460L, lines[0].timeMs)
        assertNotNull(lines[0].words)
    }

    /**
     * **最关键的一条**：用了 yrc 时，译文只能来自 ytlrc，绝不能是 tlyric。
     *
     * tlyric 的时间戳（12000）与 yrc 的行（11460）对不上，按精确匹配挂不上去，
     * 所以一旦混用，表现是译文**整体消失**或挂到别的句子上。
     */
    @Test
    fun `用 yrc 时译文取自 ytlrc_绝不取 tlyric`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = lrc, tlyric = tlyric, yrc = yrc, ytlrc = ytlrc)
        )

        assertEquals("我留下", lines[0].translation)
    }

    /**
     * 反向钉死：即便 ytlrc 缺失、而 tlyric 存在，也不许拿 tlyric 来顶。
     * 宁可没有译文，也不要挂一份错位的。
     */
    @Test
    fun `ytlrc 缺失时不拿 tlyric 顶替`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = lrc, tlyric = tlyric, yrc = yrc, ytlrc = null)
        )

        assertEquals(11460L, lines[0].timeMs)
        assertNull("跨轴的译文绝不能挂上来", lines[0].translation)
    }

    @Test
    fun `无 yrc 时回落到 lrc_行为与加逐字支持之前一致`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = lrc, tlyric = tlyric, yrc = null, ytlrc = null)
        )

        assertEquals(1, lines.size)
        assertEquals(12000L, lines[0].timeMs)
        assertEquals("这是 lrc 那一轴的译文", lines[0].translation)
        assertNull("没有 yrc 就不该有字级时间表", lines[0].words)
    }

    @Test
    fun `yrc 为空串视同没有`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = lrc, tlyric = tlyric, yrc = "", ytlrc = null)
        )

        assertEquals(12000L, lines[0].timeMs)
        assertNull(lines[0].words)
    }

    /**
     * yrc 只有元信息行（解析后为空）时要继续回落到 lrc，
     * 而不是判成「这首歌没有歌词」——lrc 那份可能是好的。
     */
    @Test
    fun `yrc 只有元信息时回落到 lrc`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(
                lrc = lrc,
                tlyric = tlyric,
                yrc = "[0,0](0,0,0) 作词 : 某人",
                ytlrc = null,
            )
        )

        assertEquals(1, lines.size)
        assertEquals(12000L, lines[0].timeMs)
        assertNull(lines[0].words)
    }

    /** 两套都没有内容时返回空列表，由上层判成 Unavailable。 */
    @Test
    fun `两套都为空时返回空列表`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = "", tlyric = null, yrc = null, ytlrc = null)
        )

        assertTrue(lines.isEmpty())
    }

    /**
     * 走 yrc 那一轴时，行文本必须由字级块拼接而成，
     * 与字级时间表严格对应——扫光的字符区间是按这个拼接结果算的，
     * 两者对不上会让光停在错误的位置。
     */
    @Test
    fun `行文本与字级时间表严格对应`() {
        val lines = LyricsRepository.parseBestAvailable(
            LyricsFetcher.RawLyrics(lrc = lrc, tlyric = null, yrc = yrc, ytlrc = null)
        )

        val words = requireNotNull(lines[0].words)
        assertEquals(lines[0].text, words.joinToString("") { it.text })
    }
}

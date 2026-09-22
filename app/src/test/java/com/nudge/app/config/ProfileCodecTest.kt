package com.nudge.app.config

import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCodecTest {

    @Test
    fun `默认配置 round-trip 后相等`() {
        val original = StoredProfile("预设 1", NudgeConfig.DEFAULT)
        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    /**
     * 空绑定是「用户主动清空」，不是「没配过」。它必须能原样存取，
     * 否则加载预设后该动作会被静默恢复成默认绑定。
     */
    @Test
    fun `空绑定 round-trip 后仍为空集`() {
        val config = NudgeConfig.DEFAULT.copy(
            bindings = mapOf(
                ActionType.NEXT_TRACK to setOf(Gesture.DOUBLE_TAP),
                ActionType.LIKE to emptySet(),
            )
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(StoredProfile("夜跑", config)))
        assertEquals(emptySet<Gesture>(), decoded?.config?.bindings?.get(ActionType.LIKE))
    }

    @Test
    fun `一个动作绑多个手势 round-trip 后保持`() {
        val config = NudgeConfig.DEFAULT.copy(
            bindings = mapOf(
                ActionType.NEXT_TRACK to setOf(
                    Gesture.DOUBLE_TAP,
                    Gesture.TWO_FINGER_DOUBLE_TAP,
                    Gesture.THREE_FINGER_HOLD_TAP,
                ),
                ActionType.LIKE to setOf(Gesture.THREE_FINGER_DOUBLE_TAP),
            )
        )
        val original = StoredProfile("多绑", config)
        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    @Test
    fun `非默认的标量字段 round-trip 后保持`() {
        val config = NudgeConfig.DEFAULT.copy(
            sensitivity = Sensitivity.STRICT,
            themeMode = ThemeMode.DARK,
            lyricsEnabled = false,
            lyricsAlignment = LyricsAlignment.START,
            screenPinningEnabled = true,
        )
        val original = StoredProfile("严格夜间", config)
        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    // —— 以下为宽容解码：枚举重命名或数据损坏后不能崩 ——

    @Test
    fun `缺失字段回落默认值`() {
        val decoded = ProfileCodec.decode("""{"name":"只有名字"}""")
        assertEquals("只有名字", decoded?.name)
        assertEquals(NudgeConfig.DEFAULT, decoded?.config)
    }

    @Test
    fun `未知灵敏度名回落默认`() {
        val decoded = ProfileCodec.decode("""{"name":"x","sensitivity":"TURBO"}""")
        assertEquals(NudgeConfig.DEFAULT.sensitivity, decoded?.config?.sensitivity)
    }

    @Test
    fun `未知主题名回落默认`() {
        val decoded = ProfileCodec.decode("""{"name":"x","themeMode":"NEON"}""")
        assertEquals(NudgeConfig.DEFAULT.themeMode, decoded?.config?.themeMode)
    }

    @Test
    fun `未知歌词对齐名回落默认`() {
        val decoded = ProfileCodec.decode("""{"name":"x","lyricsAlignment":"JUSTIFY"}""")
        assertEquals(NudgeConfig.DEFAULT.lyricsAlignment, decoded?.config?.lyricsAlignment)
    }

    /** 未知手势名逐个丢弃，已认识的仍保留——半坏的数据不该整条作废。 */
    @Test
    fun `未知手势名被丢弃而保留已知手势`() {
        val decoded = ProfileCodec.decode(
            """{"name":"x","bindings":{"NEXT_TRACK":"DOUBLE_TAP,FOUR_FINGER_SWIPE"}}"""
        )
        assertEquals(
            setOf(Gesture.DOUBLE_TAP),
            decoded?.config?.bindings?.get(ActionType.NEXT_TRACK),
        )
    }

    /** bindings 里没出现的动作要回落默认，而不是变成空集。 */
    @Test
    fun `bindings 缺某动作时该动作回落默认绑定`() {
        val decoded = ProfileCodec.decode(
            """{"name":"x","bindings":{"NEXT_TRACK":"DOUBLE_TAP"}}"""
        )
        assertEquals(
            NudgeConfig.DEFAULT.bindings[ActionType.LIKE],
            decoded?.config?.bindings?.get(ActionType.LIKE),
        )
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(ProfileCodec.decode("{ 这不是 json"))
    }

    @Test
    fun `空槽位的 null 与空串返回 null`() {
        assertNull(ProfileCodec.decode(null))
        assertNull(ProfileCodec.decode(""))
        assertNull(ProfileCodec.decode("   "))
    }

    /** 名字缺失或为空的记录视为无效槽位——没有名字就无法辨识，等于空槽。 */
    @Test
    fun `缺名字或空名字返回 null`() {
        assertNull(ProfileCodec.decode("""{"sensitivity":"STRICT"}"""))
        assertNull(ProfileCodec.decode("""{"name":"  "}"""))
    }
}

package com.nudge.app.config

import com.nudge.app.gesture.Gesture
import com.nudge.app.gesture.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeConfigTest {

    private fun configOf(bindings: Map<ActionType, Set<Gesture>>) = NudgeConfig(
        bindings = bindings,
        sensitivity = Sensitivity.STANDARD,
        themeMode = ThemeMode.SYSTEM,
        lyricsEnabled = true,
        lyricsAlignment = LyricsAlignment.CENTER,
        displayMode = DisplayMode.SIMPLE,
        antiMistouchEnabled = true,
    )

    @Test
    fun `一个动作绑多个手势时 每个手势都反查回该动作`() {
        val config = configOf(
            mapOf(
                ActionType.NEXT_TRACK to setOf(
                    Gesture.DOUBLE_TAP,
                    Gesture.TWO_FINGER_DOUBLE_TAP,
                ),
            )
        )
        assertEquals(ActionType.NEXT_TRACK, config.gestureToAction(Gesture.DOUBLE_TAP))
        assertEquals(ActionType.NEXT_TRACK, config.gestureToAction(Gesture.TWO_FINGER_DOUBLE_TAP))
    }

    @Test
    fun `手势被另一动作抢占后 原动作不再包含它`() {
        // 模拟 addBinding 的抢占结果：同一手势只出现在一个动作的集合里
        val config = configOf(
            mapOf(
                ActionType.NEXT_TRACK to setOf(Gesture.TWO_FINGER_DOUBLE_TAP),
                ActionType.LIKE to setOf(Gesture.THREE_FINGER_DOUBLE_TAP),
            )
        )
        val owners = ActionType.entries.filter { Gesture.TWO_FINGER_DOUBLE_TAP in config.bindings[it].orEmpty() }
        assertEquals(listOf(ActionType.NEXT_TRACK), owners)
        assertEquals(ActionType.NEXT_TRACK, config.gestureToAction(Gesture.TWO_FINGER_DOUBLE_TAP))
    }

    @Test
    fun `动作手势集为空时 原手势反查返回 null`() {
        val config = configOf(
            mapOf(
                ActionType.NEXT_TRACK to emptySet(),
                ActionType.LIKE to setOf(Gesture.THREE_FINGER_DOUBLE_TAP),
            )
        )
        assertNull(config.gestureToAction(Gesture.TWO_FINGER_DOUBLE_TAP))
    }

    @Test
    fun `未绑定的手势反查返回 null`() {
        assertNull(NudgeConfig.DEFAULT.gestureToAction(Gesture.DOUBLE_TAP))
    }

    @Test
    fun `默认配置的两个手势各自反查正确`() {
        assertEquals(
            ActionType.NEXT_TRACK,
            NudgeConfig.DEFAULT.gestureToAction(Gesture.TWO_FINGER_DOUBLE_TAP),
        )
        assertEquals(
            ActionType.LIKE,
            NudgeConfig.DEFAULT.gestureToAction(Gesture.THREE_FINGER_DOUBLE_TAP),
        )
    }

    @Test
    fun `每个动作在默认配置里都有条目`() {
        // 读取侧（ConfigStore.config、ProfileCodec.decode）按 ActionType.entries
        // 全量构造 map，那里「缺 key」与「空集」等价——但对 equals 不等价。
        // DEFAULT 漏写某个动作，round-trip 出来的 config 会多一个空集键而
        // 与 DEFAULT 判不相等，ProfileCodecTest 的四个用例会一起挂。
        // 加动作时若默认不绑，要显式写 emptySet() 而不是不写。
        ActionType.entries.forEach { action ->
            assertTrue(
                "DEFAULT.bindings 缺少 $action",
                NudgeConfig.DEFAULT.bindings.containsKey(action),
            )
        }
    }

    @Test
    fun `歌词开关默认不绑手势`() {
        // 与播放/暂停同理：手势池已够用，且它误触代价极低（纯视觉、
        // 再做一次就回来），不值得默认占一个好记的手势位。
        assertEquals(
            emptySet<Gesture>(),
            NudgeConfig.DEFAULT.bindings[ActionType.TOGGLE_LYRICS],
        )
    }

    @Test
    fun `防误触模式默认开启`() {
        // 这个开关一次管三层，其中沉浸式与双击返回在合并前是硬编码默认生效的。
        // 若改成默认关，那两层会从「默认开」退化成「默认关」，对盲操这个
        // 核心场景是功能倒退。改默认值前先想清楚这点。
        assertTrue(NudgeConfig.DEFAULT.antiMistouchEnabled)
    }

    @Test
    fun `显示模式默认为简洁模式`() {
        // 与防误触那条相反：这里默认值要守住「升级后观感不变」。
        // 封面模式把整屏变成专辑封面，而简洁模式不显示大封面，
        // 在工作场合不会一眼被看出在放歌——这是它保留下来的理由，
        // 默认切成封面模式等于替用户做了一个会暴露的决定。
        assertEquals(DisplayMode.SIMPLE, NudgeConfig.DEFAULT.displayMode)
    }

    @Test
    fun `旧的单值存储格式解析成单元素集合`() {
        assertEquals(
            setOf(Gesture.TWO_FINGER_DOUBLE_TAP),
            decodeGestures("TWO_FINGER_DOUBLE_TAP"),
        )
    }

    @Test
    fun `逗号分隔格式能往返编解码`() {
        val gestures = setOf(Gesture.DOUBLE_TAP, Gesture.TWO_FINGER_DOUBLE_TAP)
        assertEquals(gestures, decodeGestures(encodeGestures(gestures)))
    }

    @Test
    fun `空串解析成空集`() {
        assertEquals(emptySet<Gesture>(), decodeGestures(""))
        assertEquals("", encodeGestures(emptySet()))
    }

    @Test
    fun `未知枚举名被丢弃而不是崩溃`() {
        assertEquals(
            setOf(Gesture.TWO_FINGER_DOUBLE_TAP),
            decodeGestures("TWO_FINGER_DOUBLE_TAP,GONE_IN_A_RENAME"),
        )
    }
}

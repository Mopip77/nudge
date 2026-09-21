package com.nudge.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSlotTest {

    @Test
    fun `名字与配置俱在时不是空槽`() {
        val slot = ProfileSlot(1, "夜跑", NudgeConfig.DEFAULT)
        assertFalse(slot.isEmpty)
    }

    @Test
    fun `名字与配置为 null 时是空槽`() {
        assertTrue(ProfileSlot(2, null, null).isEmpty)
    }

    @Test
    fun `合法槽位号解析正确`() {
        assertEquals(1, parseSlotIndex("1"))
        assertEquals(2, parseSlotIndex("2"))
        assertEquals(3, parseSlotIndex("3"))
    }

    /** 外部工具传什么都有可能，越界与非数字一律返回 null 而非抛异常。 */
    @Test
    fun `越界或非法槽位号返回 null`() {
        assertNull(parseSlotIndex("0"))
        assertNull(parseSlotIndex("4"))
        assertNull(parseSlotIndex("-1"))
        assertNull(parseSlotIndex("abc"))
        assertNull(parseSlotIndex(""))
        assertNull(parseSlotIndex(null))
        assertNull(parseSlotIndex("1.0"))
        assertNull(parseSlotIndex("99999999999999999999"))
    }

    @Test
    fun `槽位号两侧空白被容忍`() {
        assertEquals(2, parseSlotIndex(" 2 "))
    }
}

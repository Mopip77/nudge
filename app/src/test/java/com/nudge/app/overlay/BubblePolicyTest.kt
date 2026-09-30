package com.nudge.app.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePolicyTest {

    private fun inputs(
        enabled: Boolean = true,
        fg: String? = "com.netease.cloudmusic",
        screenOn: Boolean = true,
        previouslyVisible: Boolean = false,
    ) = BubbleInputs(enabled, fg, screenOn, previouslyVisible)

    @Test
    fun `网易云在前台时显示`() {
        assertTrue(BubblePolicy.visible(inputs()))
    }

    @Test
    fun `别的应用在前台时不显示`() {
        assertFalse(BubblePolicy.visible(inputs(fg = "com.tencent.mm")))
    }

    @Test
    fun `nudge 自己在前台时不显示`() {
        // 在自己界面上挂一个「打开 nudge」是无意义的。
        assertFalse(BubblePolicy.visible(inputs(fg = BubblePolicy.SELF_PACKAGE)))
    }

    @Test
    fun `关闭时不显示`() {
        assertFalse(BubblePolicy.visible(inputs(enabled = false)))
    }

    @Test
    fun `熄屏时不显示`() {
        assertFalse(BubblePolicy.visible(inputs(screenOn = false)))
    }

    @Test
    fun `前台未知时沿用上一次的判定`() {
        // 这是这块最容易写错的一条：查询窗口内没有 RESUMED 事件只说明
        // 用户一直没切应用，**那正是最该显示气泡的时候**。
        // 写成「未知即隐藏」的表现是：盯着网易云看几分钟，气泡自己消失了。
        assertTrue(BubblePolicy.visible(inputs(fg = null, previouslyVisible = true)))
        assertFalse(BubblePolicy.visible(inputs(fg = null, previouslyVisible = false)))
    }

    @Test
    fun `前台未知但关闭或熄屏时仍然不显示`() {
        // 「沿用上一次」只对前台归属这一个维度成立，不能让它盖过开关与熄屏。
        assertFalse(
            BubblePolicy.visible(inputs(enabled = false, fg = null, previouslyVisible = true))
        )
        assertFalse(
            BubblePolicy.visible(inputs(screenOn = false, fg = null, previouslyVisible = true))
        )
    }

    @Test
    fun `目标集合不含 nudge 自己`() {
        // 把 SELF_PACKAGE 误加进 TARGET_PACKAGES 会让气泡盖在自己界面上，
        // 而那块区域整个是触摸板，气泡会吞掉一根手指。
        assertFalse(BubblePolicy.SELF_PACKAGE in BubblePolicy.TARGET_PACKAGES)
    }
}

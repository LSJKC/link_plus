package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音乐岛绘制和触摸共用几何，圆角外不命中 */
class MusicIslandTest {
    /**
     * 矩形顶部居中，缩放受内容区宽高共同约束，窄竖屏不出界
     */
    @Test fun boxesAreTopCenteredAndScaleWithContent() {
        // 参考画布 1:1：缩放基准是内容区而非元素自身（回归：曾把胶囊高度当分母放大 2 倍）
        assertEquals(1f, MusicIsland.scale(1408, 476), 0f)
        val compact = MusicIsland.boxFor(MusicIsland.State.COMPACT, 1408, 476)
        val expanded = MusicIsland.boxFor(MusicIsland.State.EXPANDED, 1408, 476)
        assertEquals(400f, compact.width, 0.5f)
        assertEquals(56f, compact.height, 0.5f)
        assertEquals(600f, expanded.width, 0.5f)
        assertEquals(168f, expanded.height, 0.5f)
        assertEquals(704f, (compact.left + compact.right) / 2f, 0.5f)
        assertEquals(704f, (expanded.left + expanded.right) / 2f, 0.5f)
        assertEquals(compact.top, expanded.top, 0.01f)
        assertTrue(expanded.height > compact.height)
        assertTrue(expanded.right <= 1408f)
        // 隐藏态锚在胶囊矩形上，显隐动画不位移
        assertEquals(compact, MusicIsland.boxFor(MusicIsland.State.HIDDEN, 1408, 476))
        // 窄内容按宽度收缩，不出界
        val narrow = MusicIsland.boxFor(MusicIsland.State.EXPANDED, 648, 1400)
        assertTrue(narrow.right <= 648f && narrow.left >= 0f)
    }

    /**
     * 圆角外不算命中，与悬浮面板同一判定
     */
    @Test fun boxContainsExcludesRoundedCorners() {
        val box = MusicIsland.Box(0f, 0f, 100f, 40f)
        assertTrue(box.contains(50f, 20f, 20f))
        // 左端角落：矩形内但在圆角外
        assertFalse(box.contains(1f, 1f, 20f))
        assertFalse(box.contains(99f, 39f, 20f))
        assertFalse(box.contains(50f, -1f, 20f))
        assertTrue(box.contains(21f, 1f, 20f))
    }

    /**
     * 按钮只在各自圆域内命中，圆心来自绘制同一组比例
     */
    @Test fun expandedButtonsHitOnlyWithinTheirCircles() {
        val box = MusicIsland.boxFor(MusicIsland.State.EXPANDED, 1408, 476)
        val playX = box.left + 0.5f * box.width
        val playY = box.top + 0.8333f * box.height
        assertEquals(MusicIsland.BUTTON_PLAY, MusicIsland.buttonAt(box, playX, playY))
        val prevX = box.left + 0.4147f * box.width
        assertEquals(MusicIsland.BUTTON_PREV, MusicIsland.buttonAt(box, prevX, playY))
        val nextX = box.left + 0.5853f * box.width
        assertEquals(MusicIsland.BUTTON_NEXT, MusicIsland.buttonAt(box, nextX, playY))
        // 远离按钮的封面区域不命中任何按钮
        assertNull(MusicIsland.buttonAt(box, box.left + 0.15f * box.width, box.top + 0.2f * box.height))
        // 相邻按钮圆域不重叠：两圆心正中归最近圆外（半径不足以覆盖中点）
        val midPrevPlay = (prevX + playX) / 2f
        assertNull(MusicIsland.buttonAt(box, midPrevPlay, playY))
    }

    /**
     * 可见态之间：矩形插值 + 交叉淡入，进度 0/1 落到单层静态
     */
    @Test fun visibleTransitionMorphsBoxAndCrossFades() {
        val mid = MusicIsland.spec(MusicIsland.State.COMPACT, MusicIsland.State.EXPANDED, 0.5f, 1408, 476)
        val compact = MusicIsland.boxFor(MusicIsland.State.COMPACT, 1408, 476)
        val expanded = MusicIsland.boxFor(MusicIsland.State.EXPANDED, 1408, 476)
        assertEquals((compact.height + expanded.height) / 2f, mid.box.height, 2f)
        assertTrue(mid.primaryExpanded)
        assertTrue(mid.primaryAlpha in 0.4f..0.6f)
        assertEquals(1f - mid.primaryAlpha, mid.outgoingAlpha, 0.001f)
        assertFalse(mid.outgoingExpanded)
        val done = MusicIsland.spec(MusicIsland.State.COMPACT, MusicIsland.State.EXPANDED, 1f, 1408, 476)
        assertEquals(expanded, done.box)
        assertEquals(1f, done.primaryAlpha, 0f)
        assertEquals(0f, done.outgoingAlpha, 0f)
    }

    /**
     * 与隐藏态之间只做淡入淡出，矩形停在可见态不位移
     */
    @Test fun hiddenTransitionsOnlyFadeInPlace() {
        val fadeIn = MusicIsland.spec(MusicIsland.State.HIDDEN, MusicIsland.State.COMPACT, 0.25f, 1408, 476)
        val compact = MusicIsland.boxFor(MusicIsland.State.COMPACT, 1408, 476)
        assertEquals(compact, fadeIn.box)
        assertTrue(fadeIn.primaryAlpha > 0f && fadeIn.primaryAlpha < 1f)
        assertEquals(0f, fadeIn.outgoingAlpha, 0f)
        val fadeOut = MusicIsland.spec(MusicIsland.State.EXPANDED, MusicIsland.State.HIDDEN, 0.25f, 1408, 476)
        assertEquals(MusicIsland.boxFor(MusicIsland.State.EXPANDED, 1408, 476), fadeOut.box)
        assertTrue(fadeOut.primaryAlpha < 1f)
        assertEquals(0f, fadeOut.outgoingAlpha, 0f)
        val hidden = MusicIsland.spec(MusicIsland.State.HIDDEN, MusicIsland.State.HIDDEN, 1f, 1408, 476)
        assertEquals(0f, hidden.primaryAlpha, 0f)
        assertEquals(0f, hidden.outgoingAlpha, 0f)
    }

    /**
     * 缓出曲线单调且端点固定；时间格式 m:ss
     */
    @Test fun easeAndFormatTime() {
        assertEquals(0f, MusicIsland.ease(0f), 0f)
        assertEquals(1f, MusicIsland.ease(1f), 0f)
        assertTrue(MusicIsland.ease(0.5f) == 0.5f)
        assertTrue(MusicIsland.ease(0.25f) < 0.5f)
        assertEquals("0:05", MusicIsland.formatTime(5_000))
        assertEquals("1:05", MusicIsland.formatTime(65_000))
        assertNotNull(MusicIsland.spec(MusicIsland.State.COMPACT, MusicIsland.State.COMPACT, 1f, 1408, 476))
    }
}

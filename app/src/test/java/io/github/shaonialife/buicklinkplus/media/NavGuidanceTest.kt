package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 导航文案没有明确转向词时返回 null */
class NavGuidanceTest {
    /**
     * 距离 + 右转的标准引导文案
     */
    @Test fun parsesDistanceAndRightTurn() {
        val step = NavGuidance.parse(null, "500米后右转")
        assertEquals(NavGuidance.Direction.RIGHT, step?.direction)
        assertEquals("500米", step?.distance)
        assertNull(step?.road)
    }

    /**
     * 公里距离与道路名提取
     */
    @Test fun parsesKilometerDistanceAndRoad() {
        val step = NavGuidance.parse("1.2公里后左转进入中山路", null)
        assertEquals(NavGuidance.Direction.LEFT, step?.direction)
        assertEquals("1.2公里", step?.distance)
        assertEquals("中山路", step?.road)
    }

    /**
     * 靠右/稍向右/右后转归入轻微右转，掉头优先于左右
     */
    @Test fun parsesBearingAndUTurn() {
        assertEquals(NavGuidance.Direction.SLIGHT_RIGHT, NavGuidance.parse(null, "300米后靠右行驶")?.direction)
        assertEquals(NavGuidance.Direction.SLIGHT_LEFT, NavGuidance.parse(null, "稍向左转")?.direction)
        assertEquals(NavGuidance.Direction.STRAIGHT, NavGuidance.parse(null, "200米后直行进入环城路")?.direction)
        assertEquals(NavGuidance.Direction.U_TURN, NavGuidance.parse(null, "前方掉头")?.direction)
        assertEquals(NavGuidance.Direction.ARRIVE, NavGuidance.parse(null, "已到达目的地")?.direction)
    }

    /**
     * 无明确动作关键字不发布指引，空文案同样
     */
    @Test fun rejectsTextWithoutAction() {
        assertNull(NavGuidance.parse(null, "前方路段拥堵"))
        assertNull(NavGuidance.parse(null, null))
        assertNull(NavGuidance.parse("", " "))
    }

    /**
     * 剩余里程/时间仅在含「剩余/预计」的文案提取，供行程组件显示
     */
    @Test fun parsesRemainingTripInfo() {
        val step = NavGuidance.parse(null, "500米后右转进入中山路，剩余 23 公里，预计 35 分钟")
        assertEquals("23 公里 · 35 分钟", step?.remaining)
        val onlyDistance = NavGuidance.parse(null, "直行，剩余 2.4 公里")
        assertEquals("2.4 公里", onlyDistance?.remaining)
        val noRemaining = NavGuidance.parse(null, "500米后右转")
        assertNull(noRemaining?.remaining)
    }
}

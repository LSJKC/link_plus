package io.github.shaonialife.buicklinkplus.render

import io.github.shaonialife.buicklinkplus.input.scaleTouchCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeDisplayGeometryTest {
    /**
     * 系统把应用屏旋转后，坐标按实际逻辑宽高映射且端点不越界
     */
    @Test fun rotatedDisplayUsesActualLogicalCoordinates() {
        val actual = requireNotNull(RuntimeDisplayGeometry.from(intArrayOf(714, 1930, 1)))
        assertEquals(713, scaleTouchCoordinate(1929, 1930, actual.width))
        assertEquals(1927, scaleTouchCoordinate(713, 714, actual.height))
        assertEquals(0, scaleTouchCoordinate(0, 1930, actual.width))
    }

    /**
     * 服务失败和无效结果不能污染原显示尺寸
     */
    @Test fun invalidGeometryRemainsUnknown() {
        assertNull(RuntimeDisplayGeometry.from(null))
        assertNull(RuntimeDisplayGeometry.from(intArrayOf()))
        assertNull(RuntimeDisplayGeometry.from(intArrayOf(0, 714, 0)))
        assertNull(RuntimeDisplayGeometry.from(intArrayOf(1930, 714, 9)))
    }
}

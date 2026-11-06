package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationReadbackTest {
    /** 接受、相反回读和设备不支持是不同状态 */
    @Test fun acceptanceIsNotReadback() {
        assertTrue(OrientationReadback.describe(true, true, null, false).contains("未知"))
        assertTrue(OrientationReadback.describe(true, true, false, false).contains("不一致"))
        assertTrue(OrientationReadback.describe(false, true, null, true).contains("不支持"))
        assertTrue(OrientationReadback.describe(false, true, true, false).contains("已确认"))
    }

    /**
     * 系统与厂商字段格式均能读取，其他属性的布尔值不能冒充方向策略
     */
    @Test fun explicitFieldsSupportVendorFormatting() {
        assertTrue(OrientationReadback.parse("ignoreOrientationRequest true for displayId=125") == true)
        assertFalse(OrientationReadback.parse("mIgnoreOrientationRequest=false") != false)
        assertTrue(OrientationReadback.parse("ignoreOrientationRequest: TRUE") == true)
        assertNull(OrientationReadback.parse("rotation=true permission=false"))
    }

    /**
     * 失败输出和冲突回读均保持未知，不能按默认成功处理
     */
    @Test fun conflictsAndCommandErrorsRemainUnknown() {
        assertNull(OrientationReadback.parse("Error: unknown command"))
        assertNull(OrientationReadback.parse("ignoreOrientationRequest true\nmIgnoreOrientationRequest=false"))
    }
}

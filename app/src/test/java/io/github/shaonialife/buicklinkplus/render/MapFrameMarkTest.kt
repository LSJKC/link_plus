package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧帧、通知和心跳不满足切屏条件 */
class MapFrameMarkTest {
    /**
     * 帧号增加但生产者时间戳未变，仍是旧图像
     */
    @Test fun repeatedTextureDoesNotConfirmSwitch() {
        assertFalse(MapFrameMark(12, 100).newerThan(MapFrameMark(11, 100)))
        assertFalse(MapFrameMark(12, 99).newerThan(MapFrameMark(11, 100)))
    }

    /**
     * 时间戳变化也必须对应成功消费后的新帧号
     */
    @Test fun bothConsumptionAndProducerMustAdvance() {
        assertFalse(MapFrameMark(11, 101).newerThan(MapFrameMark(11, 100)))
        assertTrue(MapFrameMark(12, 101).newerThan(MapFrameMark(11, 100)))
    }
}

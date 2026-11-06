package io.github.shaonialife.buicklinkplus.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查按 HID 描述符区分 5 字节单指和 12 字节多槽报告 */
class TouchReportsTest {
    /** 奥迪触摸屏描述符（Digitizer 页，单指，X/Y 16bit）。 */
    private val audiTouchDescriptor =
        byteArrayOf(
            0x05,
            0x0d,
            0x09,
            0x04,
            0xa1.toByte(),
            0x01,
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x05,
            0x0d,
            0x09,
            0x33,
            0x15,
            0x00,
            0x25,
            0x01,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x75,
            0x07,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x01,
            0x05,
            0x01,
            0x09,
            0x30,
            0x15,
            0x00,
            0x26,
            0x84.toByte(),
            0x05,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x09,
            0x31,
            0x15,
            0x00,
            0x26,
            0x8e.toByte(),
            0x02,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            0xc0.toByte(),
        )

    /** DiPlay 双指描述符（两个 Finger Collection）。 */
    private val diplayTouchDescriptor =
        byteArrayOf(
            0x05,
            0x0d,
            0x09,
            0x04,
            0xa1.toByte(),
            0x01,
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x09,
            0x38,
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x15,
            0x00,
            0x25,
            0x01,
            0x09,
            0x33,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x95.toByte(),
            0x07,
            0x81.toByte(),
            0x03,
            0x05,
            0x01,
            0x26,
            0x84.toByte(),
            0x05,
            0x09,
            0x30,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x26,
            0x8e.toByte(),
            0x02,
            0x09,
            0x31,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            // 第二个手指 Collection
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x09,
            0x38,
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x15,
            0x00,
            0x25,
            0x01,
            0x09,
            0x33,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x95.toByte(),
            0x07,
            0x81.toByte(),
            0x03,
            0x05,
            0x01,
            0x26,
            0x84.toByte(),
            0x05,
            0x09,
            0x30,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x26,
            0x8e.toByte(),
            0x02,
            0x09,
            0x31,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            0xc0.toByte(),
        )

    @Test
    fun audiSingleTouchDescriptorDetected() {
        assertEquals(
            TouchReports.Format.SINGLE_ABSOLUTE,
            TouchReports.formatFromDescriptor(audiTouchDescriptor),
        )
    }

    @Test
    fun diplayMultitouchDescriptorDetected() {
        assertEquals(
            TouchReports.Format.MULTI_SLOT,
            TouchReports.formatFromDescriptor(diplayTouchDescriptor),
        )
    }

    @Test
    fun nonDigitizerDescriptorIsNotTouch() {
        // Consumer Control（媒体键）不是触摸设备
        val consumer =
            byteArrayOf(
                0x05,
                0x0c,
                0x09,
                0x01,
                0xa1.toByte(),
                0x01,
                0x15,
                0x00,
                0x25,
                0x08,
                0x05,
                0x0c,
                0x0a,
                0xb0.toByte(),
                0x00,
                0x75,
                0x08,
                0x95.toByte(),
                0x01,
                0x81.toByte(),
                0x00,
                0xc0.toByte(),
            )
        assertNull(TouchReports.formatFromDescriptor(consumer))
    }

    @Test
    fun audiSingleTouchReportParses() {
        // [tip=1][X=0x0200 LE][Y=0x0100 LE]
        val report = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01)
        val contacts =
            TouchReports.parse(
                report,
                TouchReports.formatFromDescriptor(audiTouchDescriptor),
            )!!
        assertEquals(1, contacts.size)
        assertTrue(contacts[0].down)
        assertEquals(0x0200, contacts[0].xPixels)
        assertEquals(0x0100, contacts[0].yPixels)
    }

    @Test
    fun diplayMultiSlotReportParses() {
        // 槽0 按下 (100,200)、槽1 抬起 (0,0)
        val report =
            byteArrayOf(
                0x00,
                0x01,
                100.toByte(),
                0,
                200.toByte(),
                0,
                0x01,
                0x00,
                0,
                0,
                0,
                0,
            )
        val contacts =
            TouchReports.parse(
                report,
                TouchReports.formatFromDescriptor(diplayTouchDescriptor),
            )!!
        assertEquals(2, contacts.size)
        assertTrue(contacts[0].down)
        assertEquals(100, contacts[0].xPixels)
        assertEquals(200, contacts[0].yPixels)
        assertEquals(false, contacts[1].down)
    }

    @Test
    fun lengthFallbackWhenDescriptorMissing() {
        // 描述符不可得：5B → 单指；12B → 多槽；其它 → 不解析
        assertEquals(1, TouchReports.parse(byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01), null)!!.size)
        assertEquals(2, TouchReports.parse(ByteArray(12), null)!!.size)
        assertNull(TouchReports.parse(byteArrayOf(0x00, 0x00, 0x00, 0x00), null))
    }

    /** 用户日志中的完整描述符，逻辑范围与显示屏尺寸明显不同 */
    private val buickDescriptor =
        hex(
            "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 " +
                "75 07 95 01 81 01 05 01 09 30 15 00 26 0a 0c 75 10 95 01 81 02 " +
                "09 31 15 00 26 2e 03 75 10 95 01 81 02 c0 c0",
        )

    /** 四角、中心及越界值均映射到显示像素范围，释放与槽号不能改变 */
    @Test
    fun realDescriptorMapsLogicalCoordinatesToDisplay() {
        val device = TouchReports.Device(buickDescriptor)
        assertEquals(TouchReports.LogicalRange(0, 3082), device.coordinateRange!!.x)
        assertEquals(TouchReports.LogicalRange(0, 814), device.coordinateRange.y)
        for ((raw, expected) in listOf(
            (0 to 0) to (0 to 0),
            (3082 to 814) to (1971 to 519),
            (0 to 814) to (0 to 519),
            (3082 to 0) to (1971 to 0),
            (1541 to 407) to (986 to 260),
            (65535 to 65535) to (1971 to 519),
        )) {
            val mapped = device.parse(report(raw.first, raw.second, down = false), 1972, 520)!!.single()
            assertEquals(expected, mapped.xPixels to mapped.yPixels)
            assertEquals(false, mapped.down)
            assertEquals(0, mapped.slot)
        }
    }

    /** 复现日志点击，换算后再交给现有流缩放，避免把逻辑值当画面像素 */
    @Test
    fun loggedTapMapsOnceBeforeStreamAndApplicationScaling() {
        val mapped = TouchReports.Device(buickDescriptor).parse(report(1581, 588), 1972, 520)!!.single()
        assertEquals(1011 to 375, mapped.xPixels to mapped.yPixels)
        // 流尺寸为视图一倍、应用屏超采样为一点五倍，沿用原路由换算
        assertEquals(1335, ((mapped.xPixels - 121) * 1.5f).toInt())
        assertEquals(562, (mapped.yPixels * 1.5f).toInt())
        assertTrue(mapped.down)
    }

    /** 旧设备按各自范围换算，双槽必须保留相同的坐标范围 */
    @Test
    fun existingDescriptorsRetainTheirOwnRanges() {
        for (descriptor in listOf(audiTouchDescriptor, diplayTouchDescriptor)) {
            val ranges = TouchReports.coordinateRangeFromDescriptor(descriptor)!!
            assertEquals(TouchReports.LogicalRange(0, 1412), ranges.x)
            assertEquals(TouchReports.LogicalRange(0, 654), ranges.y)
        }
        val mapped =
            TouchReports.Device(diplayTouchDescriptor).parse(
                hex("00 01 84 05 8e 02 01 00 00 00 00 00"),
                1412,
                654,
            )!!
        assertEquals(1411 to 653, mapped[0].xPixels to mapped[0].yPixels)
        assertEquals(1, mapped[1].slot)
        assertEquals(false, mapped[1].down)
    }

    /** 明确的电话描述符不能因报告长度碰巧是五字节而触发触摸 */
    @Test
    fun nonTouchDescriptorNeverFallsBackToLength() {
        val telephone = TouchReports.Device(hex("05 0b 09 01 a1 01 75 10 95 02 81 02 c0"))
        assertNull(telephone.parse(report(100, 200), 1972, 520))
        assertNull(telephone.coordinateRange)
        val fallback = TouchReports.Device(null).parse(report(100, 200), 1972, 520)!!.single()
        assertEquals(100 to 200, fallback.xPixels to fallback.yPixels)
    }

    /** 屏幕绑定按协议 UUID 比较，副屏输入不能误注入主屏 */
    @Test
    fun deviceDisplayBindingIsRespected() {
        val device = TouchReports.Device(buickDescriptor, "e5f7a68d-7b0f-4305-984b-974f677a150F")
        assertTrue(device.targetsDisplay("E5F7A68D-7B0F-4305-984B-974F677A150F"))
        assertTrue(!device.targetsDisplay("其他屏幕"))
        assertTrue(TouchReports.Device(null).targetsDisplay("主屏"))
    }

    /** 同一输入项的双轴、局部范围用法、全局栈和长项都应正确处理 */
    @Test
    fun descriptorSupportsSharedAxesPushPopAndLongItems() {
        val descriptor =
            hex(
                "05 0d a1 01 05 01 15 00 26 ff ff 75 10 95 02 a4 " +
                    "05 0c 15 00 25 01 09 01 81 02 b4 fe 02 99 01 02 19 30 29 31 81 02 c0",
            )
        val range = TouchReports.coordinateRangeFromDescriptor(descriptor)!!
        assertEquals(TouchReports.LogicalRange(0, 65535), range.x)
        assertEquals(range.x, range.y)
        val mapped = TouchReports.toDisplayPixels(TouchReports.Contact(1, 65535, 0, true), range, 1920, 1080)
        assertEquals(TouchReports.Contact(1, 1919, 0, true), mapped)
    }

    /** 逻辑最小值为负数时，最大值及报告坐标按有符号数解释 */
    @Test
    fun signedLogicalRangesDecodeTwosComplement() {
        val range =
            TouchReports.coordinateRangeFromDescriptor(
                hex("05 0d a1 01 05 01 16 00 80 26 ff 7f 75 10 95 02 09 30 09 31 81 02 c0"),
            )!!
        assertEquals(TouchReports.LogicalRange(-32768, 32767), range.x)
        val mapped = TouchReports.toDisplayPixels(TouchReports.Contact(0, 32768, 32767, false), range, 100, 100)
        assertEquals(0 to 99, mapped.xPixels to mapped.yPixels)
    }

    /** 截断、栈下溢、相对轴、常量输入及冲突范围不能产生坐标映射 */
    @Test
    fun malformedOrNonAbsoluteDescriptorsHaveNoRange() {
        val prefix = "05 0d 05 01 15 00 26 ff 7f 75 10 95 02 09 30 09 31 "
        for (descriptor in listOf(
            hex("05 0d 26 ff"),
            hex("b4"),
            hex("fe 05 99 00"),
            hex(prefix + "81 06"),
            hex(prefix + "81 03"),
            hex(prefix + "81 00"),
            hex(prefix + "81 02 26 ff 3f 95 01 09 30 81 02"),
        )) {
            assertNull(TouchReports.coordinateRangeFromDescriptor(descriptor))
        }
    }

    /** 原点释放不能当空闲丢弃，多槽设备的其他槽也不能打断当前单指手势 */
    @Test
    fun trackerPreservesOriginReleaseAndIgnoresOtherSlots() {
        val tracker = TouchReports.ContactTracker()
        assertTrue(!tracker.accept(TouchReports.Contact(1, 0, 0, false)))
        assertTrue(tracker.accept(TouchReports.Contact(0, 100, 200, true)))
        assertTrue(!tracker.accept(TouchReports.Contact(1, 0, 0, false)))
        assertTrue(!tracker.accept(TouchReports.Contact(1, 200, 300, true)))
        assertTrue(tracker.accept(TouchReports.Contact(0, 0, 0, false)))
        assertTrue(tracker.accept(TouchReports.Contact(1, 0, 0, true)))
        tracker.reset()
        assertTrue(!tracker.accept(TouchReports.Contact(1, 0, 0, false)))
        assertTrue(tracker.accept(TouchReports.Contact(0, 0, 0, true)))
    }

    /** 按真实单指报告的字节顺序构造测试输入 */
    private fun report(
        x: Int,
        y: Int,
        down: Boolean = true,
    ): ByteArray = byteArrayOf(if (down) 1 else 0, x.toByte(), (x ushr 8).toByte(), y.toByte(), (y ushr 8).toByte())

    /** 测试描述符以十六进制文本保存，便于与原始日志逐字节核对 */
    private fun hex(value: String): ByteArray = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}

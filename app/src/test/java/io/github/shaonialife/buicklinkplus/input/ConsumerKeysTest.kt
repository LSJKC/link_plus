package io.github.shaonialife.buicklinkplus.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 检查 Array 报告按 Usage 下标解析媒体键 */
class ConsumerKeysTest {
    /** 描述符/报文原文按字节直书，便于与日志十六进制逐字节比对。 */
    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** 奥迪 /info hidDevices[0].hidDescriptor 原文（46B）。 */
    private val audiDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 08 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a b3 00 0a b4 00 0a b5 00 0a b6 " +
                "00 0a b9 00 0a bc 00 75 08 95 01 81 00 c0",
        )

    @Test
    fun descriptorParsedAsArrayWithNineUsages() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)
        assertNotNull("应能解析出 Consumer 映射", map)
        assertEquals(true, map!!.isArray)
        assertEquals(9, map.usages.size)
        // 顺序必须与描述符声明一致（下标语义依赖它）
        assertEquals(
            listOf(0x0000, 0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC),
            map.usages,
        )
    }

    @Test
    fun reportValueIsArrayIndexNotUsageId() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        // 抓包值：下一首为 0x05，上一首为 0x06
        assertEquals(0xB5, map.usageFor(0x05))
        assertEquals(0xB6, map.usageFor(0x06))
        // 教训：若把 0x05 直接当 Usage ID 会得到「未知」——这两个断言锁定正确语义
        assertEquals(0, ConsumerKeys.keyCodeFor(0x05))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        // 播放/暂停 = 下标 1/2
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        // 0 与越界 = 无按键
        assertNull(map.usageFor(0))
        assertNull(map.usageFor(20))
    }

    @Test
    fun nonConsumerDevicesAreNotMediaKeys() {
        // 电话报告可能与媒体键同长，仍应按 Telephony 页排除
        val telephonyDescriptor =
            hex(
                "05 0b 09 07 a1 01 15 00 25 11 05 0b 09 00 09 20 09 21 09 26 09 2f 09 b0 09 b1 09 b2 09 b3 75 08 95 " +
                    "01 81 00 c0",
            )
        assertNull(ConsumerKeys.parseDescriptor(telephonyDescriptor))
    }

    @Test
    fun diplayMediaDescriptorUsesIndexSemantics() {
        // DiPlay（测试台）media 描述符：Usage 数组 6 项，报告值同为下标
        val diplayMedia =
            hex(
                "05 0c 09 01 a1 01 15 00 25 06 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a cd 00 0a b5 00 0a b6 00 0a 9e " +
                    "02 02 75 08 95 01 81 00 c0",
            )
        val map = ConsumerKeys.parseDescriptor(diplayMedia)!!
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usageFor(4)!!))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(map.usageFor(5)!!))
    }

    @Test
    fun usageMapsToAndroidMediaKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, ConsumerKeys.keyCodeFor(0xB0))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, ConsumerKeys.keyCodeFor(0xB1))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, ConsumerKeys.keyCodeFor(0xB3))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, ConsumerKeys.keyCodeFor(0xB4))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(0xB6))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, ConsumerKeys.keyCodeFor(0xB9))
        // Repeat 无对应 Android 键 → 0（调用方忽略）
        assertEquals(0, ConsumerKeys.keyCodeFor(0xBC))
    }

    @Test
    fun descriptorWithoutConsumerPageReturnsNull() {
        // 奥迪触摸屏（uuid=1）描述符：Generic Desktop / Digitizer，无 Consumer 页
        val touchDescriptor =
            hex(
                "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 c0 c0",
            )
        assertNull(ConsumerKeys.parseDescriptor(touchDescriptor))
    }

    @Test
    fun allDescriptorUsagesNamed() {
        listOf(0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC).forEach { usage ->
            assertFalse(
                "usage=0x%02x 应可读".format(usage),
                ConsumerKeys.nameOf(usage).startsWith("未知"),
            )
        }
    }

    // ---- 本田 HFT（Alpine 8A250-30A）：位图（Variable）报告 ----

    /** 5 个 Usage 各占 1bit 的 HID 描述符，末尾补齐 3bit */
    private val hondaDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 01 75 01 95 05 09 b0 09 b1 09 b5 09 b6 09 b9 81 02 75 03 95 01 81 03 c0",
        )

    @Test
    fun hondaDescriptorIsBitmapWithFiveUsages() {
        val map = ConsumerKeys.parseDescriptor(hondaDescriptor)!!
        assertFalse("`81 02` 是位图报告，不是 Array", map.isArray)
        assertEquals(listOf(0xB0, 0xB1, 0xB5, 0xB6, 0xB9), map.usages)
    }

    @Test
    fun hondaBitmapReportsDecodeToMediaKeys() {
        val map = ConsumerKeys.parseDescriptor(hondaDescriptor)!!
        // 抓包值：按住下一首发送 0x04，松开发送 0x00
        assertEquals(listOf(0xB5), map.usagesFor(byteArrayOf(0x04)))
        assertEquals(listOf(0xB6), map.usagesFor(byteArrayOf(0x08)))
        assertEquals(listOf(0xB0), map.usagesFor(byteArrayOf(0x01)))
        assertEquals(listOf(0xB1), map.usagesFor(byteArrayOf(0x02)))
        assertEquals(listOf(0xB9), map.usagesFor(byteArrayOf(0x10)))
        assertEquals(emptyList<Int>(), map.usagesFor(byteArrayOf(0x00)))
        // 0x04 是 Usage 数组下标，不能直接当作 Usage ID
        assertEquals(0, ConsumerKeys.keyCodeFor(0x04))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usagesFor(byteArrayOf(0x04))[0]))
    }

    @Test
    fun audiArrayReportStillWorksThroughUsagesFor() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        assertEquals(listOf(0xB5), map.usagesFor(byteArrayOf(0x05)))
        assertEquals(listOf(0xB6), map.usagesFor(byteArrayOf(0x06)))
        assertEquals(emptyList<Int>(), map.usagesFor(byteArrayOf(0x00)))
    }
}

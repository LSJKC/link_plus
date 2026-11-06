package io.github.shaonialife.buicklinkplus.input

import android.view.KeyEvent

/**
 * 按设备 HID 描述符将 Consumer Control 报告转成媒体键
 * Array 报告值是 Usage 下标；描述符不可用时按 Usage ID 直接读取
 */
object ConsumerKeys {
    /** Consumer 页面（Generic Desktop 之外最常用的多媒体页）。 */
    private const val USAGE_PAGE_CONSUMER = 0x0C

    /**
     * 一台 HID 设备的媒体键映射
     *
     * @param usages 按描述符顺序排列的 Usage ID
     * @param isArray true 时报告值为下标，false 时为 Usage ID
     */
    class KeyMap(
        val usages: List<Int>,
        val isArray: Boolean,
    ) {
        /** 报告值 → Usage ID；0 或越界返回 null（0 = 无按键/释放）。 */
        fun usageFor(reportValue: Int): Int? {
            if (reportValue == 0) return null
            return if (isArray) {
                usages.getOrNull(reportValue)?.takeIf { it != 0 }
            } else {
                reportValue
            }
        }

        /** 解析本次按下的 Usage：Array 值按下标取用，Variable 报告按位展开 */
        fun usagesFor(report: ByteArray): List<Int> {
            if (report.isEmpty()) return emptyList()
            if (isArray) return listOfNotNull(usageFor(report[0].toInt() and 0xFF))
            val pressed = ArrayList<Int>(usages.size)
            usages.forEachIndexed { index, usage ->
                val byteIndex = index / 8
                if (byteIndex >= report.size || usage == 0) return@forEachIndexed
                val bit = (report[byteIndex].toInt() shr (index % 8)) and 0x01
                if (bit == 1) pressed += usage
            }
            return pressed
        }
    }

    /**
     * 解析第一个 Consumer Application Collection 的 Usage 列表，没有 Consumer 页时返回 null
     * 支持 0、1、2、4 字节短条目
     */
    fun parseDescriptor(descriptor: ByteArray): KeyMap? {
        var index = 0
        var page = 0
        var inConsumerApp = false
        var sawConsumerPage = false
        val usages = ArrayList<Int>()
        var isArray = true

        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size = prefix and 0x03
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            val dataBytes =
                when (size) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            if (index + 1 + dataBytes > descriptor.size) break
            var value = 0
            for (offset in 0 until dataBytes) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            index += 1 + dataBytes

            when {
                // 全局项：Usage Page
                type == 1 && tag == 0x0 -> {
                    page = value
                    if (value == USAGE_PAGE_CONSUMER) sawConsumerPage = true
                }
                // 全局项：Logical Maximum（Array 报告里用于界定下标范围）
                type == 1 && tag == 0x2 -> Unit
                // 局部项：Usage（1 或 2 字节）——收进当前 Consumer Application 的数组
                type == 2 && tag == 0x0 -> {
                    if (inConsumerApp && sawConsumerPage) usages += value
                }
                // 主项：Collection / EndCollection
                type == 0 && tag == 0xA -> {
                    // 仅当当前页是 Consumer 且处于顶层 Application 时开启收集
                    if (!inConsumerApp && page == USAGE_PAGE_CONSUMER) inConsumerApp = true
                }
                type == 0 && tag == 0xC -> {
                    if (inConsumerApp) return KeyMap(usages, isArray)
                }
                // 主项：Input——bit1=0 表示 Array（值是下标），=1 表示 Variable（值是位图）。
                // bit0=1 是 Constant（描述符尾部的补齐位，如 `75 03 95 01 81 03`），
                // 不参与按键语义，不能让它覆盖前面的真实报告格式。
                type == 0 && tag == 0x8 -> {
                    if ((value and 0x01) == 0) isArray = (value and 0x02) == 0
                }
            }
        }
        if (sawConsumerPage && usages.isNotEmpty()) return KeyMap(usages, isArray)
        return null
    }

    /** Usage ID → Android 媒体键；无对应键返回 0（如 Repeat）。 */
    fun keyCodeFor(usage: Int): Int =
        when (usage) {
            0xB0 -> KeyEvent.KEYCODE_MEDIA_PLAY
            0xB1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
            0xB3 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            0xB4 -> KeyEvent.KEYCODE_MEDIA_REWIND
            0xB5 -> KeyEvent.KEYCODE_MEDIA_NEXT
            0xB6 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            0xB9 -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> 0
        }

    /** 可读名称（日志用）。 */
    fun nameOf(usage: Int): String =
        when (usage) {
            0xB0 -> "播放"
            0xB1 -> "暂停"
            0xB3 -> "快进"
            0xB4 -> "快退"
            0xB5 -> "下一首"
            0xB6 -> "上一首"
            0xB9 -> "停止"
            0xBC -> "循环"
            else -> "未知(0x%02x)".format(usage)
        }
}

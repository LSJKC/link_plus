package io.github.shaonialife.buicklinkplus.input

/**
 * 按 HID 描述符解析触摸，不依赖车机品牌或 uuid
 * 多槽格式每槽 6B：槽号、按下标志、X/Y u16LE；单指格式为 5B：tip 和 X/Y u16LE
 * 描述符缺失时按长度回退：5B 为单指，12B 及以上为多槽
 */
object TouchReports {
    const val TOUCH_UUID_HEX = "2a2a2a2a"
    const val KNOB_UUID_HEX = "2a2a2a2b"
    private const val SLOTS = 2
    private const val BYTES_PER_FINGER = 6

    data class Contact(
        val slot: Int,
        val xPixels: Int,
        val yPixels: Int,
        val down: Boolean,
    )

    /** 触摸报文格式（由描述符或报文长度判定）。 */
    enum class Format { MULTI_SLOT, SINGLE_ABSOLUTE }

    /** 从 HID 描述符分别读取 X/Y 逻辑范围 */
    data class LogicalRange(
        val min: Int,
        val max: Int,
    )

    data class CoordinateRange(
        val x: LogicalRange,
        val y: LogicalRange,
    )

    /** 按设备缓存描述符，不把非触摸设备按长度误判为触摸 */
    class Device(
        descriptor: ByteArray?,
        val displayUuid: String? = null,
    ) {
        val format = descriptor?.let(::formatFromDescriptor)
        val coordinateRange = descriptor?.let(::coordinateRangeFromDescriptor)
        private val descriptorPresent = descriptor != null

        /** 未绑定显示屏的旧设备沿用主屏，明确绑定其他显示屏的输入不能注入主屏 */
        fun targetsDisplay(uuid: String?): Boolean = displayUuid == null || uuid == null || displayUuid.equals(uuid, ignoreCase = true)

        /** 描述符缺失才允许长度回退，坐标只在入口转换一次 */
        fun parse(
            report: ByteArray,
            width: Int,
            height: Int,
        ): List<Contact>? {
            if (descriptorPresent && format == null) return null
            return TouchReports.parse(report, format)?.map { toDisplayPixels(it, coordinateRange, width, height) }
        }
    }

    /** 跟踪活动槽，忽略空闲槽，保留原点抬起 */
    class ContactTracker {
        private var activeSlot: Int? = null

        /** 首次按下选定活动槽，只有该槽的抬起才能结束手势 */
        fun accept(contact: Contact): Boolean {
            if (activeSlot == null) {
                if (!contact.down) return false
                activeSlot = contact.slot
            }
            if (contact.slot != activeSlot) return false
            if (!contact.down) activeSlot = null
            return true
        }

        /** 外层会话结束时清理槽位，防止影响下次连接 */
        fun reset() {
            activeSlot = null
        }
    }

    /** 只读取绝对 X/Y 输入字段的逻辑范围，跳过填充、相对轴及无关页面 */
    fun coordinateRangeFromDescriptor(descriptor: ByteArray): CoordinateRange? {
        data class Globals(
            var page: Int = 0,
            var minimum: Int = 0,
            var maximum: Int = 0,
            var reportCount: Int = 0,
        )
        var globals = Globals()
        val stack = ArrayDeque<Globals>()
        val usages = ArrayList<Int>()
        var usageMinimum: Int? = null
        var digitizer = false
        var x: LogicalRange? = null
        var y: LogicalRange? = null
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index++].toInt() and 255
            if (prefix == 0xFE) {
                if (index + 2 > descriptor.size) return null
                val length = descriptor[index].toInt() and 255
                index += 2 + length
                if (index > descriptor.size) return null
                continue
            }
            val size =
                when (prefix and 3) {
                    3 -> 4
                    else -> prefix and 3
                }
            if (index + size > descriptor.size) return null
            var value = 0
            repeat(size) { offset -> value = value or ((descriptor[index + offset].toInt() and 255) shl (8 * offset)) }
            val signed = if (size in 1..3) (value shl (32 - size * 8)) shr (32 - size * 8) else value
            index += size
            val type = (prefix shr 2) and 3
            val tag = prefix shr 4
            when (type) {
                1 ->
                    when (tag) {
                        0 -> {
                            globals.page = value
                            if (value == 0x0D) digitizer = true
                        }
                        1 -> globals.minimum = signed
                        2 -> globals.maximum = if (globals.minimum < 0) signed else value
                        9 -> globals.reportCount = value
                        10 -> stack.addLast(globals.copy())
                        11 -> globals = stack.removeLastOrNull() ?: return null
                    }
                2 ->
                    when (tag) {
                        0 -> usages.add(value)
                        1 -> usageMinimum = value
                        2 ->
                            usageMinimum?.let { minimum ->
                                if (value >= minimum && value - minimum <= 255) usages.addAll(minimum..value)
                            }
                    }
                0 -> {
                    if (tag == 8 && value and 7 == 2 && globals.maximum > globals.minimum) {
                        for (usage in usages.take(globals.reportCount.coerceIn(0, 256))) {
                            val page = if (usage ushr 16 != 0) usage ushr 16 else globals.page
                            val axis = usage and 0xFFFF
                            if (page != 1 || axis !in 0x30..0x31) continue
                            val range = LogicalRange(globals.minimum, globals.maximum)
                            if (axis == 0x30) {
                                if (x != null && x != range) return null
                                x = range
                            } else {
                                if (y != null && y != range) return null
                                y = range
                            }
                        }
                    }
                    usages.clear()
                    usageMinimum = null
                }
            }
        }
        return if (digitizer && x != null && y != null) CoordinateRange(x, y) else null
    }

    /** 先从 HID 逻辑域映射到对应显示屏像素域，再由路由器进行流缩放和分屏换算 */
    fun toDisplayPixels(
        contact: Contact,
        range: CoordinateRange?,
        width: Int,
        height: Int,
    ): Contact {
        if (range == null || width <= 0 || height <= 0) return contact

        fun pixel(
            raw: Int,
            logical: LogicalRange,
            size: Int,
        ): Int {
            val span = logical.max.toLong() - logical.min
            if (span <= 0L) return raw
            // 当前支持的报文均为十六位坐标，有符号逻辑域要先恢复补码
            val value = if (logical.min < 0 && raw in 32768..65535) raw.toShort().toInt() else raw
            val offset = value.coerceIn(logical.min, logical.max).toLong() - logical.min
            return ((offset * (size - 1) + span / 2) / span).toInt()
        }
        return contact.copy(xPixels = pixel(contact.xPixels, range.x, width), yPixels = pixel(contact.yPixels, range.y, height))
    }

    /** 识别 Digitizer 页中的 16bit X/Y 输入，按手指 Collection 区分格式；非触摸设备返回 null */
    fun formatFromDescriptor(descriptor: ByteArray): Format? {
        var page = 0
        var sawDigitizer = false
        var has16BitXy = false
        // 单指/多槽都含 Contact ID(0x22) 的 Finger Collection；以「X 的 ReportSize=16」
        // 作为两种格式共有的识别点，再用描述符长度与 bytes-per-finger 差异区分。
        var index = 0
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
                type == 1 && tag == 0x0 -> if (value == 0x0D) sawDigitizer = true
                // Report Size（全局项 tag=7）＝16 → X/Y 为 16bit（两种格式都是）
                type == 1 && tag == 0x7 -> if (value == 16) has16BitXy = true
            }
        }
        if (!sawDigitizer) return null
        if (!has16BitXy) return null
        // 奥迪单指描述符 62B、DiPlay 双指描述符更长（含两个 Finger Collection）。
        // 单一手指 Collection 的判定：Contact Identifier(0x09 0x22) 出现次数。
        val fingerCollections = countOccurrences(descriptor, byteArrayOf(0x09, 0x22))
        return if (fingerCollections <= 1) Format.SINGLE_ABSOLUTE else Format.MULTI_SLOT
    }

    private fun countOccurrences(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        var count = 0
        var index = 0
        while (index <= haystack.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }

    /**
     * 解析触摸报告
     *
     * @param format 描述符确定的格式，null 时按报文长度回退
     */
    fun parse(
        report: ByteArray,
        format: Format? = null,
    ): List<Contact>? {
        val resolved =
            format ?: when {
                report.size == 5 -> Format.SINGLE_ABSOLUTE
                report.size >= SLOTS * BYTES_PER_FINGER -> Format.MULTI_SLOT
                else -> return null
            }
        return when (resolved) {
            Format.SINGLE_ABSOLUTE -> parseSingleTouch(report)
            Format.MULTI_SLOT -> parseMultiSlot(report)
        }
    }

    /** 兼容旧调用点：按 uuid 选择解析器（描述符不可得时的回退路径）。 */
    fun parseByUuid(
        uuid: String?,
        report: ByteArray,
    ): List<Contact>? =
        when (uuid) {
            TOUCH_UUID_HEX -> parseMultiSlot(report)
            // 单指设备（各车机 uuid 不同）：用报文长度自证
            else -> parse(report, format = null)
        }

    /** 多槽格式解析（DiPlay 测试台：12 字节 2 槽）。 */
    fun parseMultiSlot(report: ByteArray): List<Contact> {
        if (report.size < SLOTS * BYTES_PER_FINGER) return emptyList()
        return (0 until SLOTS).mapNotNull { slot ->
            val offset = slot * BYTES_PER_FINGER
            Contact(
                slot = slot,
                down = report[offset + 1].toInt() and 0xFF == 1,
                xPixels = readU16Le(report, offset + 2),
                yPixels = readU16Le(report, offset + 4),
            )
        }
    }

    /** 单指绝对坐标：5 字节 [tip 1bit][X u16LE][Y u16LE]。 */
    fun parseSingleTouch(report: ByteArray): List<Contact> {
        if (report.size < 5) return emptyList()
        return listOf(
            Contact(
                slot = 0,
                down = report[0].toInt() and 0x01 != 0,
                xPixels = readU16Le(report, 1),
                yPixels = readU16Le(report, 3),
            ),
        )
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}

package io.github.shaonialife.buicklinkplus.video

/** 直接将起始码区间写入长度前缀缓冲，减少 NAL 复制 */
internal object VideoNalUnits {
    /**
     * 将混合三字节和四字节起始码转换为四字节大端长度，保留无起始码的单个 NAL
     */
    fun lengthPrefixed(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 2 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                val prefix =
                    when {
                        bytes[index + 2] == 1.toByte() -> 3
                        index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                        else -> 0
                    }
                if (prefix > 0) {
                    starts += index
                    index += prefix
                    continue
                }
            }
            index++
        }
        // 无起始码时将完整输入视作一个 NAL
        if (starts.isEmpty()) starts += -1
        var size = 0
        for (unit in starts.indices) {
            val start = starts[unit]
            val bodyStart = if (start < 0) 0 else start + if (bytes[start + 2] == 1.toByte()) 3 else 4
            val end = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (end > bodyStart) size += 4 + end - bodyStart
        }
        val packed = ByteArray(size)
        var offset = 0
        for (unit in starts.indices) {
            val start = starts[unit]
            val bodyStart = if (start < 0) 0 else start + if (bytes[start + 2] == 1.toByte()) 3 else 4
            val end = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            val length = end - bodyStart
            if (length <= 0) continue
            packed[offset] = (length ushr 24).toByte()
            packed[offset + 1] = (length ushr 16).toByte()
            packed[offset + 2] = (length ushr 8).toByte()
            packed[offset + 3] = length.toByte()
            bytes.copyInto(packed, offset + 4, bodyStart, end)
            offset += 4 + length
        }
        return packed
    }
}

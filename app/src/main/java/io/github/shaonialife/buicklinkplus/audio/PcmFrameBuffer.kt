package io.github.shaonialife.buicklinkplus.audio

/** 把 PCM 短读拼成完整帧，不补零，也不丢尾部数据 */
internal class PcmFrameBuffer(
    size: Int,
) {
    val bytes = ByteArray(size)
    var offset = 0
        private set
    val remaining: Int get() = bytes.size - offset

    /**
     * 只有填满一帧才复位游标，读取错误和暂时无数据不改变已收采样
     */
    fun accept(count: Int): Boolean {
        if (count <= 0) return false
        require(count <= remaining)
        offset += count
        if (offset < bytes.size) return false
        offset = 0
        return true
    }
}

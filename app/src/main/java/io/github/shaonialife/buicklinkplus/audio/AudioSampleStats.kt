package io.github.shaonialife.buicklinkplus.audio

/** 按小端 PCM 统计内容，静音不直接视为采集故障 */
internal data class AudioSampleStats(
    val peak: Int,
    val nonZeroPercent: Int,
) {
    companion object {
        /** 统计完整的 16bit 采样，负峰值按绝对值计算 */
        fun inspect(
            pcm: ByteArray,
            length: Int,
        ): AudioSampleStats {
            val limit = length.coerceIn(0, pcm.size) / 2 * 2
            var peak = 0
            var nonZero = 0
            for (index in 0 until limit step 2) {
                val raw = (pcm[index].toInt() and 255) or ((pcm[index + 1].toInt() and 255) shl 8)
                val value = if (raw > 32767) 65536 - raw else raw
                peak = maxOf(peak, value)
                if (value > 0) nonZero++
            }
            return AudioSampleStats(peak, nonZero * 100 / (limit / 2).coerceAtLeast(1))
        }
    }
}

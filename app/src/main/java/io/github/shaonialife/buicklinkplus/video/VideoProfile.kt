package io.github.shaonialife.buicklinkplus.video

/** 编码配置与布局密度分离，配置回退时整体通知显示链路 */
data class VideoProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
) {
    companion object {
        /** 最小编码尺寸按同一倍率抬升，避免分别钳制宽高后拉伸文字和地图 */
        fun scaledSize(
            width: Int,
            height: Int,
            percent: Int,
        ): Pair<Int, Int> {
            require(width > 0 && height > 0 && percent > 0)
            val scale = maxOf(percent / 100.0, 320.0 / width, 180.0 / height)
            val scaledWidth = (width * scale).toInt().coerceAtLeast(320) / 2 * 2
            val scaledHeight = (height * scale).toInt().coerceAtLeast(180) / 2 * 2
            return scaledWidth to scaledHeight
        }

        fun limitFps(
            requested: Int,
            receiver: Int,
            encoder: Int = 120,
        ): Int = minOf(requested.coerceIn(1, 120), receiver.takeIf { it > 0 } ?: 30, encoder.coerceAtLeast(1))

        /**
         * 自动码率按像素和帧率计算并限制为 4 至 12Mbps，显式选择的码率保持原值
         */
        fun bitrate(
            width: Int,
            height: Int,
            fps: Int,
            mbps: Int,
        ): Int =
            if (mbps in listOf(8, 12, 16, 20)) {
                mbps * 1_000_000
            } else {
                (width.toLong() * height * 8 * fps / 30).coerceIn(4_000_000L, 12_000_000L).toInt()
            }

        fun legacyBitrate(
            width: Int,
            height: Int,
            fps: Int,
        ): Int =
            ((width.toLong() * height * 6).coerceIn(2_000_000L, 10_000_000L) * if (fps >= 60) 1.5 else 1.0).toInt().coerceAtMost(15_000_000)
    }
}

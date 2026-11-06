package io.github.shaonialife.buicklinkplus.render

/** 已消费图像的帧记录，不包含通知和合成心跳 */
data class MapFrameMark(
    val sequence: Long,
    val timestamp: Long,
) {
    /** 帧号和生产者时间戳都前进后，才确认目标新帧 */
    fun newerThan(previous: MapFrameMark): Boolean = sequence > previous.sequence && timestamp > previous.timestamp
}

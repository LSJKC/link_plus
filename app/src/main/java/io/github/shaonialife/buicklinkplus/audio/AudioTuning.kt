package io.github.shaonialife.buicklinkplus.audio

/** 音频负载封装和时间戳参数，默认使用裸 AAC；发送时不做 PCM 字节换序 */
enum class AudioTuning(
    /** 是否给每帧补 ADTS 头。 */
    val adts: Boolean,
    /** SETUP 里声明的 spf（每包样本帧数）；null = 不声明。 */
    val spf: Int?,
    /** SETUP 里的 supportsHighAccuracyTimestamps。 */
    val highAccuracyTimestamps: Boolean,
) {
    /** 默认裸 AAC、spf=1024 和到达时间戳 */
    RAW_SPF(
        adts = false,
        spf = 1024,
        highAccuracyTimestamps = false,
    ),
    ;

    /** 日志用的一行说明。 */
    val detail: String
        get() = "裸 AAC 帧 + spf=$spf + 到达时间戳"
}

package io.github.shaonialife.buicklinkplus.audio

/** 音频负载编码方式：决定采集后如何打包 RTP。 */
enum class AudioCodec {
    /** 裸 PCM（大端 s16），type=100 通路。 */
    PCM,

    /** AAC-LC 裸帧（无 ADTS/AU 头），type=102 通路。 */
    AAC_LC,
}

/**
 * 音频候选档位，包含流类型、audioType、格式、采样率、延迟和编码
 * 候选可跨 PCM 和 AAC-LC，失败后换档，结果由 [AudioProfileMemory] 按车机保存
 */
data class AudioProfile(
    /** SETUP 原样使用的 audioType，如 media 或 compatibility */
    val audioType: String,
    /** 格式位：PCM 48k=1<<15，PCM 44.1k=1<<11，AAC-LC 44.1k=1<<22 */
    val formatBit: Long,
    /** 与 [formatBit] 对应的采集采样率——`AudioSender` 必须用同一值采集。 */
    val sampleRate: Int,
    /** SETUP 的 audioLatencyMs。 */
    val latencyMs: Long,
    /** RTP 流类型：100 为主音频 PCM，102 为高质量主音频 AAC-LC */
    val streamType: Long = 100L,
    /** 负载编码方式。 */
    val codec: AudioCodec = AudioCodec.PCM,
) {
    /** 档位存储键，包含流类型和编码方式 */
    val key: String get() = "$streamType|${audioType.lowercase()}|$formatBit|${codec.name}"

    /** 日志用短标签。 */
    val label: String
        get() = "t$streamType/$audioType/0x${formatBit.toString(16)}(${sampleRate}Hz,${codec.name},$latencyMs ms)"
}

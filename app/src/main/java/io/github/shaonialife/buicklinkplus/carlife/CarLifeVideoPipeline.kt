/* Adapted from CarPToCarL carlife/CarLifeHijack.kt (MIT, Copyright (c) 2026 lizhihua), commit 7b729fb:
 *   SPS/PPS 缓存、裸 IDR 前置 CSD、码流真实尺寸回调的处理顺序；
 * 以及历史参考实现的解码版本 carlife/CarLifeVideoPipeline.java（GOP 缓存按 P1A 直出需要简化为 CSD 维护）。 */
package io.github.shaonialife.buicklinkplus.carlife

/**
 * CarLife H.264 视频管线（P1A 直出，纯 Kotlin、无 Android 依赖，可在 JVM 单测）。
 *
 * 引擎把 H.264 按帧（Annex-B，含起始码）推给车机端，本类在投喂解码器前做四件事：
 *
 * 1. **NAL 切分与 CSD 缓存**：按 `00 00 01` / `00 00 00 01` 起始码切分 NAL，缓存最近一次
 *    SPS（type 7）与 PPS（type 8）的完整字节（含起始码），供后续裸关键帧复用。
 * 2. **裸 IDR 补 CSD**：CarLife 引擎只在编码器刚初始化后的首个 IDR 里带 SPS/PPS，之后周期性
 *    IDR 都是裸片。若关键帧是裸 IDR（首 NAL type 5、帧内无 SPS）且已缓存 CSD，则把
 *    `SPS + PPS` 前置到帧头再转发——否则晚创建的解码器永远配置不出来（黑屏）。
 * 3. **码流真实尺寸**：只在**帧内带 SPS** 时调用 [H264Sps.parseAnnexB] 解析宽高，做合理性
 *    校验（尺寸范围 + 宽高比），仅在变化时回调 [Listener.onStreamSize]。98311 申请值/65544
 *    回显值都可能与实际编码尺寸不一致，SPS 才是码流自带的真相。
 * 4. **最近关键帧缓存**：把*已补 CSD* 的关键帧留一份副本（上限 4MB），供解码器晚建/重建时
 *    立即投喂，不必空等引擎下一个 IDR——[lastKeyFrame] 返回防御性副本，[reset] 一并清除。
 *
 * 线程模型：[feed] 由 CarLife 视频读线程逐帧调用；[reset] 可由生命周期线程在重连时调用。
 * 内部只在缓存读写时短暂持锁，回调一律在锁外触发且不做 I/O——**绝不阻塞视频读线程**。
 */
class CarLifeVideoPipeline(
    private val listener: Listener,
) {
    /** 下游接收：一帧 Annex-B（可能已补 CSD）与码流真实尺寸变化。 */
    interface Listener {
        /**
         * 一帧 H.264（Annex-B，含起始码；裸 IDR 已按需前置缓存的 SPS/PPS）。
         *
         * @param data 帧数据，数组可能大于有效区间，用 offset/length 取用
         * @param offset 有效数据起始下标
         * @param length 有效数据长度
         * @param ptsMs 帧时间戳（毫秒，来自 CarLife 帧头，原样透传）
         * @param keyFrame 是否关键帧（由上层 CarLifeHuClient 判定后传入）
         */
        fun onFrame(
            data: ByteArray,
            offset: Int,
            length: Int,
            ptsMs: Long,
            keyFrame: Boolean,
        )

        /** 码流真实尺寸（SPS 解析值），仅在与上次不同时回调。 */
        fun onStreamSize(
            width: Int,
            height: Int,
        )
    }

    private val lock = Any()

    /** 最近一次见到的 SPS NAL（含起始码）。 */
    private var spsNal: ByteArray? = null

    /** 最近一次见到的 PPS NAL（含起始码）。 */
    private var ppsNal: ByteArray? = null

    /** SPS + PPS 的合并前缀，供裸 IDR 前置；任一缺失则为 null。 */
    private var mergedCsd: ByteArray? = null

    /**
     * 最近一次**已补 CSD** 的关键帧副本（Annex-B，含起始码）。
     *
     * 解码器晚建/重建（SPS 尺寸变化、解码线程退出）时直接投喂，立刻出画面，不必等引擎
     * 下一个 IDR。上限 [MAX_CACHED_KEYFRAME_BYTES]：异常大帧静默跳过，不占内存也不刷日志。
     */
    private var latestKeyFrame: ByteArray? = null

    /** SPS 解析出的码流真实尺寸（0 = 尚未解析到）。 */
    private var streamWidth = 0
    private var streamHeight = 0

    /** 最近一次缓存过的 SPS/PPS 是否齐全（解码器配置 csd-0/csd-1 的前提）。 */
    val hasCsd: Boolean
        get() =
            synchronized(lock) {
                spsNal != null && ppsNal != null
            }

    /** 最近一次解析出的码流真实尺寸；尚未解析到 SPS 时为 null。 */
    val streamSize: Pair<Int, Int>?
        get() =
            synchronized(lock) {
                if (streamWidth > 0 && streamHeight > 0) {
                    streamWidth to streamHeight
                } else {
                    null
                }
            }

    /** 最近一次见到的 SPS（Annex-B，含起始码）副本；没有则 null。 */
    fun sps(): ByteArray? = synchronized(lock) { spsNal?.copyOf() }

    /** 最近一次见到的 PPS（Annex-B，含起始码）副本；没有则 null。 */
    fun pps(): ByteArray? = synchronized(lock) { ppsNal?.copyOf() }

    /**
     * 最近一次关键帧（Annex-B，含起始码；裸 IDR 已前置缓存的 CSD）的防御性副本；没有则 null。
     *
     * 解码器（重新）创建成功后可以直接把这一帧投喂进去，立刻出画面，不必等引擎下一个 IDR。
     * 返回新数组，调用方随意改写不会污染缓存。
     */
    fun lastKeyFrame(): ByteArray? = synchronized(lock) { latestKeyFrame?.copyOf() }

    /**
     * 投喂一帧 CarLife H.264（Annex-B，含起始码，可带 SPS/PPS）。
     *
     * 处理顺序：NAL 切分/缓存 CSD → 裸 IDR 前置 CSD → 缓存最近关键帧 → 解析并回调尺寸
     * （仅变化时）→ 转发帧。回调在锁外触发，因此监听器可以安全地回调本类（如读取
     * [hasCsd]/[streamSize]/[lastKeyFrame]）。
     */
    fun feed(
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsMs: Long,
        keyFrame: Boolean,
    ) {
        if (length <= 0 || offset < 0 || offset + length > data.size) return

        val scan = scanNals(data, offset, length)
        val parsedSize = if (scan.sps != null) parseStreamSize(data, offset, length) else null

        var sizeEvent: Pair<Int, Int>? = null
        var frameData = data
        var frameOffset = offset
        var frameLength = length

        synchronized(lock) {
            if (scan.sps != null && !contentEquals(spsNal, scan.sps)) {
                spsNal = scan.sps
                rebuildMergedCsdLocked()
            }
            if (scan.pps != null && !contentEquals(ppsNal, scan.pps)) {
                ppsNal = scan.pps
                rebuildMergedCsdLocked()
            }
            if (parsedSize != null && isPlausible(parsedSize)) {
                if (parsedSize.first != streamWidth || parsedSize.second != streamHeight) {
                    streamWidth = parsedSize.first
                    streamHeight = parsedSize.second
                    sizeEvent = parsedSize
                }
            }
            // 裸 IDR：关键帧首部没有 SPS，但缓存里已有 CSD → 前置，保证任何时刻新建的解码器都能配置。
            if (keyFrame && scan.sps == null && scan.hasIdr) {
                val csd = mergedCsd
                if (csd != null) {
                    val merged = ByteArray(csd.size + length)
                    System.arraycopy(csd, 0, merged, 0, csd.size)
                    System.arraycopy(data, offset, merged, csd.size, length)
                    frameData = merged
                    frameOffset = 0
                    frameLength = merged.size
                }
            }
            // 缓存「已补 CSD」的关键帧：解码器晚建/重建时可直接投喂，不必空等下一个 IDR。
            if (keyFrame && scan.hasIdr && frameLength <= MAX_CACHED_KEYFRAME_BYTES) {
                latestKeyFrame = frameData.copyOfRange(frameOffset, frameOffset + frameLength)
            }
        }

        sizeEvent?.let { listener.onStreamSize(it.first, it.second) }
        listener.onFrame(frameData, frameOffset, frameLength, ptsMs, keyFrame)
    }

    /** 重连/重置：清空 CSD 缓存、最近关键帧与已上报尺寸，下一次带 SPS 的关键帧重新上报。 */
    fun reset() {
        synchronized(lock) {
            spsNal = null
            ppsNal = null
            mergedCsd = null
            latestKeyFrame = null
            streamWidth = 0
            streamHeight = 0
        }
    }

    /** 调用方需持有 [lock]。 */
    private fun rebuildMergedCsdLocked() {
        val sps = spsNal
        val pps = ppsNal
        mergedCsd = if (sps != null && pps != null) sps + pps else null
    }

    /** 只在带 SPS 的帧上做完整解析（20fps 下每秒 1~2 次，开销可忽略）。 */
    private fun parseStreamSize(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Pair<Int, Int>? = runCatching { H264Sps.parseAnnexB(data.copyOfRange(offset, offset + length)) }.getOrNull()

    /** 解析结果合理性校验：拦掉解码出错产生的荒谬值（会让车机建错解码器、直接黑屏）。 */
    private fun isPlausible(size: Pair<Int, Int>): Boolean {
        val width = size.first
        val height = size.second
        if (width < MIN_WIDTH || height < MIN_HEIGHT || width > MAX_DIMENSION || height > MAX_DIMENSION) return false
        val aspect = width.toFloat() / height
        return aspect in MIN_ASPECT..MAX_ASPECT
    }

    /**
     * 按 Annex-B 起始码切分 NAL，返回首个 SPS/PPS 的完整字节（含起始码）与是否含 IDR。
     * 只做一次线性扫描，不拆分出全部 NAL，避免每帧额外分配。
     */
    private fun scanNals(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ScanResult {
        val end = offset + length
        var index = offset
        var hasIdr = false
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        while (index + 3 <= end) {
            val startCodeLength = startCodeLength(data, index, end)
            if (startCodeLength == 0) {
                index++
                continue
            }
            val headerOffset = index + startCodeLength
            if (headerOffset >= end) break
            val nalType = data[headerOffset].toInt() and 0x1F
            when (nalType) {
                NAL_TYPE_IDR -> hasIdr = true
                NAL_TYPE_SPS, NAL_TYPE_PPS -> {
                    var next = headerOffset + 1
                    while (next + 3 <= end && startCodeLength(data, next, end) == 0) next++
                    val nalEnd = if (next + 3 <= end) next else end
                    if (nalType == NAL_TYPE_SPS && sps == null) {
                        sps = data.copyOfRange(index, nalEnd)
                    } else if (nalType == NAL_TYPE_PPS && pps == null) {
                        pps = data.copyOfRange(index, nalEnd)
                    }
                    index = if (nalEnd > index) nalEnd else index + 1
                    continue
                }
            }
            index++
        }
        return ScanResult(hasIdr, sps, pps)
    }

    /** 起始码长度（4/3）；非起始码返回 0。 */
    private fun startCodeLength(
        data: ByteArray,
        index: Int,
        end: Int,
    ): Int {
        if (index + 4 <= end &&
            data[index] == 0.toByte() &&
            data[index + 1] == 0.toByte() &&
            data[index + 2] == 0.toByte() &&
            data[index + 3] == 1.toByte()
        ) {
            return 4
        }
        if (index + 3 <= end &&
            data[index] == 0.toByte() &&
            data[index + 1] == 0.toByte() &&
            data[index + 2] == 1.toByte()
        ) {
            return 3
        }
        return 0
    }

    private class ScanResult(
        val hasIdr: Boolean,
        val sps: ByteArray?,
        val pps: ByteArray?,
    )

    private companion object {
        private const val NAL_TYPE_IDR = 5
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8

        /** 尺寸合理性阈值：CarLife 常见 1280x720 / 1412x654 / 1452x476，荒谬值一律不采信。 */
        private const val MIN_WIDTH = 320
        private const val MIN_HEIGHT = 180
        private const val MAX_DIMENSION = 8192
        private const val MIN_ASPECT = 0.3f
        private const val MAX_ASPECT = 3.5f

        /** 关键帧缓存上限：1080p 高码率 I 帧实测 < 400KB，4MB 只为兜异常大帧，超出静默跳过。 */
        private const val MAX_CACHED_KEYFRAME_BYTES = 4 * 1024 * 1024

        private fun contentEquals(
            left: ByteArray?,
            right: ByteArray?,
        ): Boolean =
            when {
                left == null -> right == null
                right == null -> false
                else -> left.contentEquals(right)
            }
    }
}

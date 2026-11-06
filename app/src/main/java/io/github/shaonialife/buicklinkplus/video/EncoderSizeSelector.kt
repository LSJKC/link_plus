package io.github.shaonialife.buicklinkplus.video

import kotlin.math.abs

/** 按编码器能力选择尺寸，尺寸不支持时不只降低码率 */
internal object EncoderSizeSelector {
    /** 请求和原生尺寸优先，其余按比例误差及分辨率损失排序 */
    fun candidates(
        requested: Pair<Int, Int>,
        native: Pair<Int, Int>,
        widthAlignment: Int,
        heightAlignment: Int,
        widthRange: IntRange = 2..16384,
        heightRange: IntRange = 2..16384,
        supported: (Int, Int) -> Boolean,
    ): List<Pair<Int, Int>> {
        val preferred = listOf(requested, native).distinct()
        val wa = widthAlignment.coerceAtLeast(2).let { if (it % 2 == 0) it else it * 2 }
        val ha = heightAlignment.coerceAtLeast(2).let { if (it % 2 == 0) it else it * 2 }
        val fallback = mutableSetOf<Pair<Int, Int>>()
        val minW = maxOf(wa, (widthRange.first + wa - 1) / wa * wa)
        val minH = maxOf(ha, (heightRange.first + ha - 1) / ha * ha)
        val maxW = minOf(widthRange.last, maxOf(requested.first, native.first, minW)) / wa * wa
        val maxH = minOf(heightRange.last, maxOf(requested.second, native.second, minH)) / ha * ha
        if (minW <= maxW && minH <= maxH) {
            // 每个对齐宽度取最邻近原比例的高度及能力边界，避免五个百分点采样漏过可用档
            for (w in minW..maxW step wa) {
                val ideal = (w.toDouble() * requested.second / requested.first / ha).toInt() * ha
                for (h in listOf(ideal - ha, ideal, ideal + ha, minH, maxH)) {
                    if (h in minH..maxH) fallback += w to h
                }
            }
        }
        val ratio = requested.first.toDouble() / requested.second
        val ordered =
            fallback.sortedBy { (w, h) ->
                abs(w.toDouble() / h / ratio - 1) * 20 + abs(1 - w.toDouble() * h / requested.first / requested.second)
            }
        return (preferred + ordered)
            .asSequence()
            .distinct()
            .filter { (w, h) ->
                w in widthRange &&
                    h in heightRange &&
                    w > 0 &&
                    h > 0 &&
                    w % wa == 0 &&
                    h % ha == 0 &&
                    runCatching { supported(w, h) }.getOrDefault(false)
            }.take(8)
            .toList()
    }
}

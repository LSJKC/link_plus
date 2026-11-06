package io.github.shaonialife.buicklinkplus.render

/** 回读系统逻辑尺寸和方向，不直接使用创建参数 */
internal data class RuntimeDisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int,
) {
    companion object {
        /**
         * 空结果及非法尺寸保持未知，兼容旧服务尚未升级的情况
         */
        fun from(values: IntArray?): RuntimeDisplayGeometry? {
            if (values == null || values.size < 3 || values[0] <= 0 || values[1] <= 0 || values[2] !in 0..3) return null
            return RuntimeDisplayGeometry(values[0], values[1], values[2])
        }
    }
}

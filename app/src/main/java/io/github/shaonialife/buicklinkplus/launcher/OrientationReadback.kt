package io.github.shaonialife.buicklinkplus.launcher

/**
 * 方向策略回读兼容不同系统格式，冲突或缺失保持未知
 */
internal object OrientationReadback {
    /** 分别记录调用结果和实际方向策略，不支持时提示降级 */
    fun describe(
        accepted: Boolean,
        expected: Boolean,
        actual: Boolean?,
        unsupported: Boolean,
    ): String =
        when {
            actual == expected -> "实际已确认"
            actual != null -> "实际与请求不一致"
            unsupported -> "设备不支持，按实际几何降级"
            accepted -> "调用已接受，实际状态未知"
            else -> "调用未确认，实际状态未知"
        }

    /**
     * 只解释明确标识的布尔字段，不能借用其他方向属性的值
     */
    fun parse(output: String): Boolean? =
        Regex("(?im)\\b(?:mIgnoreOrientationRequest|ignoreOrientationRequest)\\s*(?:=|:)?\\s*(true|false)\\b")
            .findAll(output)
            .map { it.groupValues[1].equals("true", true) }
            .toSet()
            .singleOrNull()
}

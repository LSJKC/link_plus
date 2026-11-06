package io.github.shaonialife.buicklinkplus.launcher.settings

/** 横屏平台能力与检查失败分开，未知状态允许保存偏好并在授权后重查 */
enum class OrientationCapability { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** 系统查询成功且输出完整时才判断能力，失败时保持未知 */
internal fun classifyOrientationCapability(
    sdk: Int,
    commandSucceeded: Boolean,
    output: String,
): OrientationCapability {
    if (sdk < AppOrientationCompat.MIN_SUPPORTED_SDK) return OrientationCapability.UNSUPPORTED
    if (!commandSucceeded) return OrientationCapability.UNKNOWN
    val registered =
        Regex("ChangeId\\(\\s*(\\d+)\\s*;[^\\r\\n)]*\\bname=([^;\\s)]+)")
            .findAll(output)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .toSet()
    if (registered.isEmpty()) return OrientationCapability.UNKNOWN
    return if (AppOrientationCompat.CHANGE_UNDEFINED_TO_NOSENSOR.toLong() in registered &&
        AppOrientationCompat.CHANGE_ANY_ORIENTATION.toLong() in registered
    ) {
        OrientationCapability.SUPPORTED
    } else {
        OrientationCapability.UNSUPPORTED
    }
}

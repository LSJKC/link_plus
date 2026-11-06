package io.github.shaonialife.buicklinkplus.input

/** 按实际虚拟屏尺寸映射触点，尺寸调整失败时仍与整幅缩放的画面一致 */
internal fun scaleTouchCoordinate(
    value: Int,
    visibleSize: Int,
    displaySize: Int,
): Int {
    require(visibleSize > 0 && displaySize > 0)
    return (value.coerceIn(0, visibleSize - 1).toLong() * displaySize / visibleSize)
        .toInt()
        .coerceIn(0, displaySize - 1)
}

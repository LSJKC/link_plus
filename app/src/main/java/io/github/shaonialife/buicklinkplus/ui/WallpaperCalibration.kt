package io.github.shaonialife.buicklinkplus.ui

/**
 * 壁纸在完整投屏画布中的缩放和位置，偏移单位为画布尺寸的千分之一
 */
data class WallpaperCalibration(
    val zoomPercent: Int = 100,
    val xPermille: Int = 0,
    val yPermille: Int = 0,
) {
    /**
     * 将持久化或编辑输入约束到支持范围
     */
    fun normalized(): WallpaperCalibration =
        copy(
            zoomPercent = zoomPercent.coerceIn(25, 300),
            xPermille = xPermille.coerceIn(-1000, 1000),
            yPermille = yPermille.coerceIn(-1000, 1000),
        )

    /** 预览和正式背景共用居中填充，缩小时露出黑底 */
    fun bounds(
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
    ): WallpaperBounds {
        require(sourceWidth > 0 && sourceHeight > 0 && canvasWidth > 0 && canvasHeight > 0)
        val value = normalized()
        val scale = maxOf(canvasWidth / sourceWidth, canvasHeight / sourceHeight) * value.zoomPercent / 100f
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (canvasWidth - width) / 2f + canvasWidth * value.xPermille / 1000f
        val top = (canvasHeight - height) / 2f + canvasHeight * value.yPermille / 1000f
        return WallpaperBounds(left, top, left + width, top + height)
    }
}

/**
 * 壁纸在目标画布内的绘制边界
 */
data class WallpaperBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

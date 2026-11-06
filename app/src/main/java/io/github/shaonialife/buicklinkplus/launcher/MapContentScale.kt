package io.github.shaonialife.buicklinkplus.launcher

/** 按地图容器和内容比例计算独立密度 */
object MapContentScale {
    /**
     * 短边基准与地图容器共同限密度，缩小控件以增加可视地图内容
     */
    fun density(
        width: Int,
        height: Int,
        tileWidth: Int,
        tileHeight: Int,
        percent: Int,
    ): Int {
        require(minOf(width, height, tileWidth, tileHeight) > 0)
        val base = minOf(width, height) * 200f / 476f
        val fit = minOf(tileWidth * 160f / 480f, tileHeight * 160f / if (width > height) 480f else 300f)
        return (minOf(base, fit) * percent.coerceIn(50, 100) / 100f).toInt().coerceIn(1, 640)
    }
}

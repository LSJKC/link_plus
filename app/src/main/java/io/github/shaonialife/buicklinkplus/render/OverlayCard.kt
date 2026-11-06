package io.github.shaonialife.buicklinkplus.render

import android.graphics.Bitmap

/**
 * 合成器叠加卡片，后台生成位图，GL 上传绘制
 * 矩形使用输出画面的顶部原点坐标，触摸直接复用
 */
class OverlayCard(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}

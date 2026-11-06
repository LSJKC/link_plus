package io.github.shaonialife.buicklinkplus.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import io.github.shaonialife.buicklinkplus.R

/** GL 绘图和控件共用本地矢量图标，各执行器持有独立实例 */
internal class MediaGlyph(
    context: Context,
) {
    private val resources = context.resources
    private val icons = mutableMapOf<Int, Drawable>()

    /**
     * 缓存矢量避免每次绘制重新解析资源，尺寸仍以当前画布为准
     */
    fun draw(
        canvas: Canvas,
        icon: Int,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
    ) {
        val drawable = icons.getOrPut(icon) { checkNotNull(ResourcesCompat.getDrawable(resources, icon, null)).mutate() }
        drawable.setTint(color)
        drawable.setBounds((cx - size).toInt(), (cy - size).toInt(), (cx + size).toInt(), (cy + size).toInt())
        drawable.draw(canvas)
    }

    /**
     * 上下曲方向与真实操作保持一致
     */
    fun skip(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        size: Float,
        forward: Boolean,
    ) = draw(canvas, if (forward) R.drawable.ic_media_next else R.drawable.ic_media_prev, cx, cy, size * 1.4f, paint.color)

    /**
     * 按真实播放状态绘制播放或暂停图标
     */
    fun playPause(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        size: Float,
        playing: Boolean,
    ) = draw(canvas, if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play, cx, cy, size * 1.4f, paint.color)
}

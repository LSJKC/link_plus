package io.github.shaonialife.buicklinkplus.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withRotation
import io.github.shaonialife.buicklinkplus.media.NavGuidance

/** 导航转向图标与短文案绘制（灵动岛与左侧组件共用） */
internal object NavGlyph {
    /** 「500米后右转」式短文案 */
    fun label(step: NavGuidance.Step): String {
        val word =
            when (step.direction) {
                NavGuidance.Direction.LEFT -> "左转"
                NavGuidance.Direction.RIGHT -> "右转"
                NavGuidance.Direction.SLIGHT_LEFT -> "稍向左"
                NavGuidance.Direction.SLIGHT_RIGHT -> "稍向右"
                NavGuidance.Direction.STRAIGHT -> "直行"
                NavGuidance.Direction.U_TURN -> "掉头"
                NavGuidance.Direction.ARRIVE -> "到达目的地"
            }
        if (step.direction == NavGuidance.Direction.ARRIVE) return word
        return (step.distance?.let { "${it}后" }.orEmpty()) + word
    }

    /** 转向箭头：上箭头按方向旋转；到达画终点圆点 */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        direction: NavGuidance.Direction,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (direction == NavGuidance.Direction.ARRIVE) {
            canvas.drawCircle(cx, cy, size * 0.55f, paint)
            return
        }
        val degrees =
            when (direction) {
                NavGuidance.Direction.STRAIGHT -> 0f
                NavGuidance.Direction.SLIGHT_RIGHT -> 45f
                NavGuidance.Direction.RIGHT -> 90f
                NavGuidance.Direction.U_TURN -> 180f
                NavGuidance.Direction.LEFT -> -90f
                NavGuidance.Direction.SLIGHT_LEFT -> -45f
                NavGuidance.Direction.ARRIVE -> 0f
            }
        canvas.withRotation(degrees, cx, cy) {
            val path =
                Path().apply {
                    moveTo(cx, cy - size)
                    lineTo(cx + size * 0.72f, cy + size * 0.1f)
                    lineTo(cx + size * 0.32f, cy + size * 0.1f)
                    lineTo(cx + size * 0.32f, cy + size)
                    lineTo(cx - size * 0.32f, cy + size)
                    lineTo(cx - size * 0.32f, cy + size * 0.1f)
                    lineTo(cx - size * 0.72f, cy + size * 0.1f)
                    close()
                }
            canvas.drawPath(path, paint)
        }
    }
}

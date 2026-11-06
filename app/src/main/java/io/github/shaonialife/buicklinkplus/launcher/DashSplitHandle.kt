package io.github.shaonialife.buicklinkplus.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.ui.AppearanceAware
import io.github.shaonialife.buicklinkplus.ui.UiAppearance

/**
 * 仪表盘分屏把手，拖动时只预览纹理比例，松手后再 resize
 * DOWN 时禁止父容器拦截，位移使用 rawX/rawY，避免把手移动影响坐标
 */
class DashSplitHandle(
    context: Context,
) : View(context),
    AppearanceAware {
    /**
     * 保留拖动状态并刷新指示条颜色
     */
    override fun refreshAppearance() = invalidate()

    /** true=竖屏（上下拖，拖 y）；false=横屏（左右拖，拖 x）。 */
    private var verticalSplit = false

    /** (把手中心·应用屏像素, 是否落定)；落定=true 时回调方应 commit。 */
    private var onDrag: ((Float, Boolean) -> Unit)? = null

    private var downCenter = 0f
    private var downRaw = 0f
    private var dragging = false
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()

    fun configure(
        verticalSplit: Boolean,
        onDrag: (Float, Boolean) -> Unit,
    ) {
        this.verticalSplit = verticalSplit
        this.onDrag = onDrag
    }

    /** 未拖动的抬起通知无障碍点击，拖动和取消保持原处理 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val raw = if (verticalSplit) event.rawY else event.rawX
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRaw = raw
                moved = false
                downCenter = VirtualLauncher.dashHandleCenterScreen()
                dragging = downCenter >= 0
                isPressed = true
                invalidate()
                dragging
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                if (kotlin.math.abs(raw - downRaw) > touchSlop) moved = true
                onDrag?.invoke(downCenter + (raw - downRaw), false)
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (!dragging) return false
                dragging = false
                isPressed = false
                invalidate()
                // CANCEL 同样按当前落点收尾：commit 幂等且钳制，与 UP 行为一致可预期
                onDrag?.invoke(downCenter + (raw - downRaw), true)
                if (event.actionMasked == MotionEvent.ACTION_UP && !moved) performClick()
                true
            }

            else -> false
        }
    }

    /**
     * 点击通知交给系统，不重复执行分屏落定或尺寸调整
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        if (isPressed) {
            val inset = 6f * density
            bgRect.set(inset, inset, width - inset, height - inset)
            bgPaint.color = UiAppearance.color(context, R.color.glass_soft_accent)
            canvas.drawRoundRect(bgRect, bgRect.height() / 2f, bgRect.height() / 2f, bgPaint)
        }
        dotPaint.color = UiAppearance.color(context, if (isPressed) R.color.m3_on_surface else R.color.glass_handle)
        val r = 2.5f * density
        val gap = 10f * density
        val cx = width / 2f
        val cy = height / 2f
        // 三点跟随把手方向排列：横条横排，竖条竖排
        if (verticalSplit) {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx - gap, cy, r, dotPaint)
            canvas.drawCircle(cx + gap, cy, r, dotPaint)
        } else {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx, cy - gap, r, dotPaint)
            canvas.drawCircle(cx, cy + gap, r, dotPaint)
        }
    }
}

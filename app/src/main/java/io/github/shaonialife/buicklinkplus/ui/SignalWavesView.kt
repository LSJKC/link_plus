package io.github.shaonialife.buicklinkplus.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.withRotation
import io.github.shaonialife.buicklinkplus.R

/**
 * 连接卡信号动画：空闲静止，连接中快速呼吸旋转，已连接时放慢
 * 按 210×176 设计尺寸等比缩放
 */
class SignalWavesView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs),
        AppearanceAware {
        enum class State { IDLE, CONNECTING, CONNECTED }

        var state: State = State.IDLE
            private set

        private val density = resources.displayMetrics.density

        private val orbitPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = currentColor(R.color.m3_orbit)
                strokeWidth = 1.6f * density
                pathEffect = DashPathEffect(floatArrayOf(3f * density, 9f * density), 0f)
            }
        private val arcPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        // 设计稿基准：圆心 (105,88)，三层弧半径 50/68/86，跨 -135°..-45°（12 点方向 ±45°）
        private val designWidth = 210f
        private val designHeight = 176f
        private val cx = 105f
        private val cy = 88f
        private val radii = floatArrayOf(50f, 68f, 86f)
        private val arcWidths = floatArrayOf(2.6f, 2.4f, 2.4f)

        private var animator: ValueAnimator? = null
        private var orbitRotation = 0f

        fun setState(next: State) {
            if (state == next) return
            state = next
            startAnimation()
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startAnimation()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        private fun startAnimation() {
            animator?.cancel()
            orbitRotation = 0f
            val idle = state == State.IDLE
            if (idle) {
                invalidate()
                return
            }
            val period = if (state == State.CONNECTING) 1_500L else 2_800L
            val newAnim =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = period
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        arcPhase = (a.animatedValue as Float) * 2f * Math.PI.toFloat()
                        val playMs = a.currentPlayTime
                        val orbitPeriod = if (state == State.CONNECTING) 2_600L else 9_000L
                        orbitRotation = (playMs % orbitPeriod) * 360f / orbitPeriod
                        invalidate()
                    }
                }
            animator = newAnim
            post(newAnim::start)
        }

        private var arcPhase = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 按**较短边**等比缩放：横屏下英雄卡被压矮时，只按宽度缩放会让弧线
            // 溢出视图（高度只剩 70dp、宽度仍是半屏）。取 min 后图形始终内接视图。
            val scale = minOf(width / designWidth, height / designHeight)
            val centerX = width / 2f
            // 竖直居中：设计稿圆心 88/176 恰为正中，缩放后同样居中
            val centerY = height / 2f

            canvas.withRotation(orbitRotation, centerX, centerY) {
                canvas.drawCircle(centerX, centerY, radii[2] * scale, orbitPaint)
            }

            val baseColor =
                when (state) {
                    State.IDLE -> currentColor(R.color.m3_wave_idle)
                    State.CONNECTING -> currentColor(R.color.m3_wave_connecting)
                    State.CONNECTED -> currentColor(R.color.m3_wave_connected)
                }
            for (i in radii.indices) {
                // 相位错开：连接中 0.25s/层，已连接 0.4s/层；IDLE 不做明暗变化
                val offset = if (state == State.CONNECTING) i * 0.25f else i * 0.4f
                val alpha =
                    if (state == State.IDLE) {
                        1f
                    } else {
                        0.625f + 0.375f * kotlin.math.sin(arcPhase - offset * 2f * Math.PI.toFloat())
                    }
                arcPaint.color = baseColor
                arcPaint.alpha = (alpha.coerceIn(0.15f, 1f) * 255).toInt()
                arcPaint.strokeWidth = arcWidths[i] * scale
                canvas.drawArc(
                    centerX - radii[i] * scale,
                    centerY - radii[i] * scale,
                    centerX + radii[i] * scale,
                    centerY + radii[i] * scale,
                    -135f,
                    90f,
                    false,
                    arcPaint,
                )
            }
        }

        /**
         * 配色切换保留波形动画进度，只更新画笔
         */
        override fun refreshAppearance() {
            orbitPaint.color = currentColor(R.color.m3_orbit)
            invalidate()
        }

        private fun currentColor(id: Int) = UiAppearance.color(context, id)
    }

package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RadioGroup
import androidx.core.view.isGone

/**
 * 手机设置的自适应选项组，保持单选语义，窄屏和大字体时自动换行
 */
class PhoneOptionGroup
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : RadioGroup(context, attrs) {
        private val gap: Int
            get() = (8 * resources.displayMetrics.density).toInt()

        /**
         * 按选项实际文字宽度分行测量，不为控件写死单行高度
         */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val available =
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    Int.MAX_VALUE / 4
                } else {
                    maxOf(0, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
                }
            var rowWidth = 0
            var rowHeight = 0
            var contentWidth = 0
            var contentHeight = 0
            var childState = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.isGone) continue
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
                val params = child.layoutParams as LayoutParams
                val width = child.measuredWidth + params.leftMargin + params.rightMargin
                val height = child.measuredHeight + params.topMargin + params.bottomMargin
                if (rowWidth > 0 && rowWidth + gap + width > available) {
                    contentWidth = maxOf(contentWidth, rowWidth)
                    contentHeight += rowHeight + gap
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += (if (rowWidth > 0) gap else 0) + width
                rowHeight = maxOf(rowHeight, height)
                childState = combineMeasuredStates(childState, child.measuredState)
            }
            contentWidth = maxOf(contentWidth, rowWidth)
            contentHeight += rowHeight
            setMeasuredDimension(
                resolveSizeAndState(maxOf(suggestedMinimumWidth, contentWidth + paddingLeft + paddingRight), widthMeasureSpec, childState),
                resolveSizeAndState(
                    maxOf(suggestedMinimumHeight, contentHeight + paddingTop + paddingBottom),
                    heightMeasureSpec,
                    childState shl MEASURED_HEIGHT_STATE_SHIFT,
                ),
            )
        }

        /**
         * 使用与测量相同的分行规则排布，兼容从右到左的布局方向
         */
        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            val available = maxOf(0, right - left - paddingLeft - paddingRight)
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            var rowWidth = 0
            var rowHeight = 0
            var y = paddingTop
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.isGone) continue
                val params = child.layoutParams as LayoutParams
                val width = child.measuredWidth + params.leftMargin + params.rightMargin
                val height = child.measuredHeight + params.topMargin + params.bottomMargin
                if (rowWidth > 0 && rowWidth + gap + width > available) {
                    y += rowHeight + gap
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += gap
                val x =
                    if (rtl) {
                        right - left - paddingRight - rowWidth - params.rightMargin - child.measuredWidth
                    } else {
                        paddingLeft + rowWidth +
                            params.leftMargin
                    }
                child.layout(x, y + params.topMargin, x + child.measuredWidth, y + params.topMargin + child.measuredHeight)
                rowWidth += width
                rowHeight = maxOf(rowHeight, height)
            }
        }
    }

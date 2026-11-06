package io.github.shaonialife.buicklinkplus.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import io.github.shaonialife.buicklinkplus.R

/**
 * 手机参数面板的选项样式与折叠交互，不参与参数保存及车机配置
 */
object PhonePanelUi {
    /**
     * 状态标签成对设置柔和底色与对应文字色，避免主题主色落在深色状态底上
     */
    fun styleStatusBadge(
        label: TextView,
        ready: Boolean,
    ) {
        label.setAppearanceBackground(if (ready) R.drawable.bg_badge_ok else R.drawable.bg_badge_todo)
        label.setAppearanceTextColor(if (ready) R.color.m3_ok else R.color.phone_badge_warning_text)
    }

    /**
     * 在滑条两侧加入独立触控按钮，步进操作仍由原业务保存入口处理
     */
    fun bindStepper(
        slider: SeekBar,
        step: Int = 1,
        valueText: ((Int) -> String)? = null,
        onStep: (Int) -> Unit,
    ) {
        if (slider.parent is Stepper) return
        val parent = slider.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(slider)
        val params = slider.layoutParams
        parent.removeView(slider)
        val stepper = Stepper(slider, step, valueText, onStep)
        parent.addView(stepper, index, params)
    }

    /**
     * 拖动和外部配置同步后更新边界按钮，达到上限或下限时禁止继续步进
     */
    fun refreshStepper(slider: SeekBar) {
        (slider.parent as? Stepper)?.refresh()
    }

    /** 加减按钮点击区域至少 48dp，滑块不越界 */
    private class Stepper(
        private val slider: SeekBar,
        private val step: Int,
        private val valueText: ((Int) -> String)?,
        private val onStep: (Int) -> Unit,
    ) : LinearLayout(slider.context) {
        private val minus: AppCompatImageButton
        private val plus: AppCompatImageButton
        private var value: TextView? = null

        init {
            require(step > 0)
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val density = resources.displayMetrics.density
            val target = (48 * density).toInt()
            val inset = (12 * density).toInt()
            minimumHeight = target
            slider.thumbOffset = 0
            slider.setPadding(inset, slider.paddingTop, inset, slider.paddingBottom)
            minus = button(R.drawable.ic_phone_minus, "减少${slider.contentDescription ?: "数值"}") { change(-step) }
            plus = button(R.drawable.ic_phone_plus, "增加${slider.contentDescription ?: "数值"}") { change(step) }
            addView(minus, LayoutParams(target, target))
            if (valueText == null) {
                addView(slider, LayoutParams(0, target, 1f))
            } else {
                slider.visibility = View.GONE
                addView(slider, LayoutParams(0, 0))
                value =
                    TextView(context)
                        .apply {
                            gravity = Gravity.CENTER
                            textSize = 15f
                            minimumHeight = target
                            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                            setAppearanceTextColor(R.color.m3_on_surface)
                        }.also { addView(it, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            }
            addView(plus, LayoutParams(target, target))
            refresh()
        }

        /**
         * 图标跟随窗口语义色，禁用态由颜色资源表达
         */
        private fun button(
            icon: Int,
            description: String,
            action: () -> Unit,
        ): AppCompatImageButton =
            AppCompatImageButton(context).apply {
                contentDescription = description
                setAppearanceImageResource(icon)
                setAppearanceImageTint(R.color.phone_stepper_tint)
                setAppearanceBackground(R.drawable.bg_ripple_circle)
                val padding = (15 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                setOnClickListener { action() }
            }

        /**
         * 先钳制数值，再调用保存回调，程序刷新滑条本身不会误存
         */
        private fun change(delta: Int) {
            if (!slider.isEnabled) return
            val next = (slider.progress + delta).coerceIn(slider.min, slider.max)
            if (next == slider.progress) return
            slider.progress = next
            onStep(next)
            refresh()
        }

        /**
         * 同步滑条边界与可用状态
         */
        fun refresh() {
            value?.text = valueText?.invoke(slider.progress)
            minus.isEnabled = slider.isEnabled && slider.progress > slider.min
            plus.isEnabled = slider.isEnabled && slider.progress < slider.max
        }
    }

    /**
     * 保留单选控件的读屏语义，用有独立触控区域的按钮呈现选项
     */
    fun styleOption(option: RadioButton) {
        val density = option.resources.displayMetrics.density
        option.buttonDrawable = null
        option.gravity = Gravity.CENTER
        option.textSize = 14f
        option.minimumWidth = (56 * density).toInt()
        option.minimumHeight = (48 * density).toInt()
        option.setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        option.setAppearanceTextColor(R.color.phone_option_text)
        option.setAppearanceBackground(R.drawable.bg_phone_option)
    }

    /**
     * 折叠只切换可见性，展开和收起均不修改用户已保存的数值
     */
    fun bindDisclosure(
        root: View,
        headerId: Int,
        bodyId: Int,
        arrowId: Int,
        expanded: Boolean,
    ) {
        val header = root.findViewById<View>(headerId) ?: return
        val body = root.findViewById<View>(bodyId) ?: return
        val arrow = root.findViewById<ImageView>(arrowId) ?: return

        fun show(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            arrow.rotation = if (open) 180f else 0f
            header.stateDescription = if (open) "已展开" else "已收起"
        }
        show(expanded)
        header.setOnClickListener { show(body.visibility != View.VISIBLE) }
    }
}

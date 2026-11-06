package io.github.shaonialife.buicklinkplus.ui

import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.TextViewCompat
import io.github.shaonialife.buicklinkplus.R

/** 记录控件使用的颜色资源，换主题时保留输入和滚动状态 */
object AppearanceBindings {
    private class Binding {
        var text = 0
        var hint = 0
        var background = 0
        var image = 0
        var imageTint = 0
        var buttonTint = 0
        var backgroundTint = 0
        var progressTint = 0
        var indeterminateTint = 0
        var thumbTint = 0
        var thumb = 0
        var track = 0
        var compoundTint = 0
        val compound = IntArray(4)
        var lastBackground: android.graphics.drawable.Drawable? = null
        var lastImage: android.graphics.drawable.Drawable? = null
        var lastCompound: Array<android.graphics.drawable.Drawable?>? = null
        var lastShadow: Pair<Float, Int>? = null
    }

    private val attributes =
        intArrayOf(
            android.R.attr.textColor,
            android.R.attr.textColorHint,
            android.R.attr.background,
            android.R.attr.src,
            android.R.attr.tint,
            android.R.attr.buttonTint,
            android.R.attr.backgroundTint,
            android.R.attr.progressTint,
            android.R.attr.thumbTint,
            android.R.attr.thumb,
            android.R.attr.track,
            android.R.attr.drawableTint,
            android.R.attr.drawableStart,
            android.R.attr.drawableTop,
            android.R.attr.drawableEnd,
            android.R.attr.drawableBottom,
            android.R.attr.drawableLeft,
            android.R.attr.drawableRight,
            android.R.attr.indeterminateTint,
        ).sortedArray()

    /**
     * 在布局膨胀时保存资源标识，不依赖解析颜色值反推用途
     */
    fun capture(
        view: View,
        attrs: AttributeSet,
    ) {
        val values = view.context.obtainStyledAttributes(attrs, attributes)
        val binding = binding(view)
        try {
            // 安卓属性数组必须按资源标识排序，按标识查下标避免新增字段后错位
            fun resource(attribute: Int) = values.getResourceId(attributes.binarySearch(attribute), 0)
            binding.text = resource(android.R.attr.textColor)
            binding.hint = resource(android.R.attr.textColorHint)
            if (view is TextView) {
                if (binding.text == 0) binding.text = R.color.m3_on_surface
                if (binding.hint == 0) binding.hint = R.color.m3_on_var
            }
            binding.background = resource(android.R.attr.background)
            binding.image = resource(android.R.attr.src)
            binding.imageTint = resource(android.R.attr.tint)
            binding.buttonTint = resource(android.R.attr.buttonTint)
            binding.backgroundTint = resource(android.R.attr.backgroundTint)
            binding.progressTint = resource(android.R.attr.progressTint)
            binding.thumbTint = resource(android.R.attr.thumbTint)
            binding.indeterminateTint = resource(android.R.attr.indeterminateTint)
            binding.thumb = resource(android.R.attr.thumb)
            binding.track = resource(android.R.attr.track)
            binding.compoundTint = resource(android.R.attr.drawableTint)
            val compoundAttributes =
                intArrayOf(
                    android.R.attr.drawableStart,
                    android.R.attr.drawableTop,
                    android.R.attr.drawableEnd,
                    android.R.attr.drawableBottom,
                )
            for (index in 0..3) binding.compound[index] = resource(compoundAttributes[index])
            if (binding.compound[0] == 0) binding.compound[0] = resource(android.R.attr.drawableLeft)
            if (binding.compound[2] == 0) binding.compound[2] = resource(android.R.attr.drawableRight)
            if (view is CompoundButton && binding.buttonTint == 0) binding.buttonTint = R.color.glass_control_tint
            if (view is ProgressBar && binding.progressTint == 0) binding.progressTint = R.color.m3_primary
            if (view is SeekBar && binding.thumbTint == 0) binding.thumbTint = R.color.m3_primary
        } finally {
            values.recycle()
        }
        if (view is ImageView || view is SwitchCompat) {
            val compatAttributes =
                intArrayOf(
                    androidx.appcompat.R.attr.srcCompat,
                    androidx.appcompat.R.attr.tint,
                    androidx.appcompat.R.attr.track,
                ).sortedArray()
            val compat = view.context.obtainStyledAttributes(attrs, compatAttributes)
            try {
                if (view is ImageView) {
                    binding.image = compat.getResourceId(compatAttributes.binarySearch(androidx.appcompat.R.attr.srcCompat), binding.image)
                    binding.imageTint =
                        compat.getResourceId(compatAttributes.binarySearch(androidx.appcompat.R.attr.tint), binding.imageTint)
                }
                if (view is SwitchCompat) {
                    binding.track = compat.getResourceId(compatAttributes.binarySearch(androidx.appcompat.R.attr.track), binding.track)
                }
            } finally {
                compat.recycle()
            }
        }
        binding.lastBackground = view.background
        binding.lastImage = (view as? ImageView)?.drawable
        binding.lastCompound = (view as? TextView)?.compoundDrawablesRelative
        apply(view, binding)
        if (UiAppearance.isCar(view.context)) {
            if (view is android.widget.Button && view !is CompoundButton) CarUi.styleAction(view)
            CarUi.styleInput(view)
        }
    }

    /**
     * 原位刷新窗口树，包括动态控件与自绘视图
     */
    fun refresh(root: View) {
        if (root is SeekBar && root.getTag(R.id.appearanceBinding) == null) {
            binding(root).apply {
                progressTint = R.color.m3_primary
                thumbTint = R.color.m3_primary
            }
        }
        (root.getTag(R.id.appearanceBinding) as? Binding)?.let { apply(root, it) }
        (root as? AppearanceAware)?.refreshAppearance()
        if (root is ViewGroup) for (index in 0 until root.childCount) refresh(root.getChildAt(index))
    }

    /**
     * 弹窗面板迁移到窗口背景层，清除旧布局底图但保留内边距
     */
    fun clearDialogPanel(view: View) {
        val binding = view.getTag(R.id.appearanceBinding) as? Binding ?: return
        if (binding.background == R.drawable.bg_dialog_panel) background(view, 0)
    }

    /**
     * 动态文字颜色同时登记主题资源，切换外观后保持其业务状态
     */
    fun text(
        view: TextView,
        resource: Int,
    ) {
        val binding = binding(view)
        binding.text = resource
        applyTextColors(view, binding)
    }

    /**
     * 动态背景同步登记，避免主题刷新恢复成过时的选中态
     */
    fun background(
        view: View,
        resource: Int,
    ) {
        val binding = binding(view)
        binding.background = resource
        view.background = if (resource == 0) null else UiAppearance.drawable(view.context, resource)
        binding.lastBackground = view.background
    }

    /** 圆角按钮自行设置状态背景，移除系统默认着色 */
    fun clearBackgroundTint(view: View) {
        binding(view).backgroundTint = 0
        view.backgroundTintList = null
        (view as? androidx.core.view.TintableBackgroundView)?.supportBackgroundTintList = null
    }

    /**
     * 动态图标同步登记，应用封面等位图仍由业务自行持有
     */
    fun image(
        view: ImageView,
        resource: Int,
    ) {
        val binding = binding(view)
        binding.image = resource
        view.setImageDrawable(UiAppearance.drawable(view.context, resource))
        binding.lastImage = view.drawable
    }

    /**
     * 记录动态状态着色，重载背景或图标后继续使用当前业务状态
     */
    fun tint(
        view: View,
        resource: Int,
        image: Boolean = false,
    ) {
        val binding = binding(view)
        if (image && view is ImageView) {
            binding.imageTint = resource
            view.imageTintList = UiAppearance.colors(view.context, resource)
        } else {
            binding.backgroundTint = resource
            view.backgroundTintList = UiAppearance.colors(view.context, resource)
        }
    }

    /**
     * 动态单选按钮采用统一语义色
     */
    fun buttonTint(view: CompoundButton) {
        binding(view).buttonTint = R.color.glass_control_tint
        view.buttonTintList = UiAppearance.colors(view.context, R.color.glass_control_tint)
    }

    /**
     * 动态状态点与文字旁图标在换色后仍使用原业务状态
     */
    fun compoundTint(
        view: TextView,
        resource: Int,
    ) {
        binding(view).compoundTint = resource
        TextViewCompat.setCompoundDrawableTintList(view, UiAppearance.colors(view.context, resource))
    }

    /**
     * 为没有 XML 布局的控件创建独立绑定
     */
    private fun binding(view: View): Binding =
        (view.getTag(R.id.appearanceBinding) as? Binding) ?: Binding().also { view.setTag(R.id.appearanceBinding, it) }

    /** 按原资源换色，保留业务已替换的图片和背景 */
    private fun apply(
        view: View,
        binding: Binding,
    ) {
        val context = view.context
        if (view is TextView) {
            applyTextColors(view, binding)
            val previous = binding.lastCompound
            val current = view.compoundDrawablesRelative
            if (previous != null && current.indices.all { current[it] === previous[it] } && binding.compound.any { it != 0 }) {
                val icons = binding.compound.map { if (it == 0) null else UiAppearance.drawable(context, it) }
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[0], icons[1], icons[2], icons[3])
                binding.lastCompound = view.compoundDrawablesRelative
            }
            if (binding.compoundTint != 0) {
                TextViewCompat.setCompoundDrawableTintList(view, UiAppearance.colors(context, binding.compoundTint))
            }
        }
        if (binding.background != 0 && view.background === binding.lastBackground) {
            view.background = UiAppearance.drawable(context, binding.background)
            binding.lastBackground = view.background
        }
        if (view is ImageView) {
            if (binding.image != 0 && view.drawable === binding.lastImage) {
                view.setImageDrawable(UiAppearance.drawable(context, binding.image))
                binding.lastImage = view.drawable
            }
            if (binding.imageTint != 0) view.imageTintList = UiAppearance.colors(context, binding.imageTint)
        }
        if (view is CompoundButton && binding.buttonTint != 0) view.buttonTintList = UiAppearance.colors(context, binding.buttonTint)
        if (view is Switch) {
            if (binding.thumb != 0) view.thumbDrawable = UiAppearance.drawable(context, binding.thumb)
            if (binding.track != 0) view.trackDrawable = UiAppearance.drawable(context, binding.track)
        }
        if (view is SwitchCompat) {
            if (binding.thumb != 0) view.thumbDrawable = UiAppearance.drawable(context, binding.thumb)
            if (binding.track != 0) view.trackDrawable = UiAppearance.drawable(context, binding.track)
        }
        if (binding.backgroundTint != 0) view.backgroundTintList = UiAppearance.colors(context, binding.backgroundTint)
        if (view is ProgressBar && binding.progressTint != 0) view.progressTintList = UiAppearance.colors(context, binding.progressTint)
        if (view is ProgressBar && binding.indeterminateTint != 0) {
            view.indeterminateTintList = UiAppearance.colors(context, binding.indeterminateTint)
        }
        if (view is SeekBar && binding.thumbTint != 0) view.thumbTintList = UiAppearance.colors(context, binding.thumbTint)
        if (view is android.widget.Spinner && UiAppearance.isCar(context)) CarUi.styleInput(view)
    }

    /**
     * 文字更新只刷新文字和阴影，不重复创建背景、图标及复合图形
     */
    private fun applyTextColors(
        view: TextView,
        binding: Binding,
    ) {
        val context = view.context
        if (binding.text != 0) view.setTextColor(UiAppearance.colors(context, binding.text))
        if (binding.hint != 0) view.setHintTextColor(UiAppearance.colors(context, binding.hint))
        if (UiAppearance.isCar(context)) {
            val shadow = if (CarAppearancePrefs.transparency(context) >= 50) 1.5f * view.resources.displayMetrics.density else 0f
            val style = shadow to UiAppearance.color(context, R.color.car_text_shadow)
            if (binding.lastShadow != style) {
                view.setShadowLayer(shadow, 0f, shadow / 3f, style.second)
                binding.lastShadow = style
            }
        }
    }
}

/**
 * 设置带外观绑定的动态文字颜色
 */
fun TextView.setAppearanceTextColor(resource: Int) = AppearanceBindings.text(this, resource)

/**
 * 设置带外观绑定的动态背景
 */
fun View.setAppearanceBackground(resource: Int) = AppearanceBindings.background(this, resource)

/**
 * 设置带外观绑定的动态图标
 */
fun ImageView.setAppearanceImageResource(resource: Int) = AppearanceBindings.image(this, resource)

/**
 * 设置带外观绑定的图标状态色
 */
fun ImageView.setAppearanceImageTint(resource: Int) = AppearanceBindings.tint(this, resource, image = true)

/**
 * 设置带外观绑定的背景状态色
 */
fun View.setAppearanceBackgroundTint(resource: Int) = AppearanceBindings.tint(this, resource)

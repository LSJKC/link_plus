package io.github.shaonialife.buicklinkplus.ui

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import io.github.shaonialife.buicklinkplus.R

/**
 * 车机动态操作控件的共同样式，明确配置文字与背景状态
 */
object CarUi {
    /**
     * 为动态按钮同时绑定文字、圆角背景和点击反馈
     */
    fun styleAction(view: TextView) {
        val density = view.resources.displayMetrics.density
        view.setAppearanceTextColor(R.color.car_action_text)
        view.setAppearanceBackground(R.drawable.bg_car_action)
        AppearanceBindings.clearBackgroundTint(view)
        view.stateListAnimator = null
        view.elevation = 0f
        view.textSize = 15f
        view.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        view.minimumHeight = (52 * density).toInt()
        val padding = (16 * density).toInt()
        view.setPadding(padding, (12 * density).toInt(), padding, (12 * density).toInt())
        view.isAllCaps = false
        view.isFocusable = true
        view.clipToOutline = true
    }

    /**
     * 输入和下拉面板透出窗口的统一模糊背景，保留文字、光标和系统下拉指示
     */
    fun styleInput(view: View) {
        if (view is EditText) {
            view.setAppearanceBackground(R.drawable.bg_car_input)
            AppearanceBindings.clearBackgroundTint(view)
            view.setAppearanceTextColor(R.color.m3_on_surface)
            val padding = (14 * view.resources.displayMetrics.density).toInt()
            view.setPaddingRelative(padding, padding, padding, padding)
        } else if (view is Spinner) {
            // 不替换系统自带箭头，只统一下拉窗口的磨砂面板
            view.setPopupBackgroundDrawable(UiAppearance.drawable(view.context, R.drawable.bg_dialog_panel))
        }
    }
}

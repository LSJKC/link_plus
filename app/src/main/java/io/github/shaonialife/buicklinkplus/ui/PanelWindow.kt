package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/** 统一弹窗尺寸，宽度为屏宽 92%，旋转后重算；用弱引用登记已显示窗口 */
object PanelWindow {
    /** 面板宽度占屏宽比例（与目标车机选择、免责声明一致）。 */
    const val WIDTH_FRACTION = 0.92f

    private data class Entry(
        val dialog: WeakReference<AlertDialog>,
        val activity: WeakReference<Activity>,
        /** null = WRAP_CONTENT（短弹窗贴合内容）。 */
        val heightFraction: Float?,
        /** 旋转后额外重算（例如正文滚动区按新屏高重新夹紧）。 */
        val onResize: ((Activity) -> Unit)? = null,
    )

    private val showing = mutableListOf<Entry>()

    /**
     * 设置并登记窗口尺寸
     *
     * @param heightFraction 高度占比，null 时自适应内容
     * @param onResize 窗口重算后的回调
     */
    fun apply(
        dialog: AlertDialog,
        activity: Activity,
        heightFraction: Float? = null,
        onResize: ((Activity) -> Unit)? = null,
    ) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sizeTo(dialog, activity, heightFraction)
        dialog.window?.let { GlassWindows.install(activity, it, dialog = true) }
        register(dialog, activity, heightFraction, onResize)
    }

    /** 屏幕方向/尺寸变化后重算所有仍在显示的弹窗。 */
    fun resizeShowing(activity: Activity) {
        showing.removeAll { it.dialog.get()?.isShowing != true }
        showing.forEach { entry ->
            val dialog = entry.dialog.get() ?: return@forEach
            if (entry.activity.get() !== activity) return@forEach
            sizeTo(dialog, activity, entry.heightFraction)
            entry.onResize?.invoke(activity)
        }
    }

    private fun register(
        dialog: AlertDialog,
        activity: Activity,
        heightFraction: Float?,
        onResize: ((Activity) -> Unit)?,
    ) {
        showing.removeAll { it.dialog.get() == null || it.dialog.get() == dialog }
        showing.add(Entry(WeakReference(dialog), WeakReference(activity), heightFraction, onResize))
        dialog.window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    showing.removeAll { it.dialog.get() == null || it.dialog.get() === dialog }
                    view.removeOnAttachStateChangeListener(this)
                }
            },
        )
    }

    private fun sizeTo(
        dialog: Dialog,
        activity: Activity,
        heightFraction: Float?,
    ) {
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val height =
            if (heightFraction == null) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                (metrics.heightPixels * heightFraction).toInt()
            }
        dialog.window?.setLayout(width, height)
    }
}

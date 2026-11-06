package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * 自有界面的外观宿主，通过资源绑定原位更新配色，不重建页面和连接会话
 */
open class GlassActivity : AppCompatActivity() {
    protected open val glassBackdropEnabled: Boolean
        get() = UiAppearance.isCar(this)
    private var appearanceReady = false
    private val appearanceListener = { refreshWindowAppearance() }

    /** 在 AppCompat 工厂前记录资源，控件仍由原工厂创建 */
    override fun onCreate(savedInstanceState: Bundle?) {
        val inflater = layoutInflater
        if (inflater.factory2 == null) {
            inflater.factory2 =
                object : LayoutInflater.Factory2 {
                    override fun onCreateView(
                        name: String,
                        context: Context,
                        attrs: AttributeSet,
                    ): View? = onCreateView(null, name, context, attrs)

                    override fun onCreateView(
                        parent: View?,
                        name: String,
                        context: Context,
                        attrs: AttributeSet,
                    ): View? {
                        val view = delegate.createView(parent, name, context, attrs) ?: createStandardView(name, context, attrs)
                        if (view != null) AppearanceBindings.capture(view, attrs)
                        return view
                    }
                }
        }
        super.onCreate(savedInstanceState)
        UiAppearance.addListener(appearanceListener)
    }

    /**
     * 补充普通容器和自绘视图的构造路径，布局参数仍由原布局膨胀器处理
     */
    private fun createStandardView(
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? {
        if (name == "fragment") return null
        val actualName = if (name == "view") attrs.getAttributeValue(null, "class") ?: return null else name
        val prefixes = if ('.' in actualName) listOf<String?>(null) else listOf("android.widget.", "android.view.")
        for (prefix in prefixes) {
            try {
                return LayoutInflater.from(context).createView(context, actualName, prefix, attrs)
            } catch (_: ClassNotFoundException) {
                // 未命中时继续标准前缀，未知类型交回原工厂报错
            }
        }
        return null
    }

    /**
     * 页面内容就绪后安装单一壁纸背景层
     */
    override fun onContentChanged() {
        super.onContentChanged()
        GlassWindows.install(this, window, dialog = false, backdrop = glassBackdropEnabled)
        refreshWindowAppearance()
    }

    /**
     * 子类控件初始化完成后再更新业务绘图，避免访问尚未赋值的控件
     */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        appearanceReady = true
        refreshWindowAppearance()
    }

    /**
     * 系统配置改变时原位刷新语义资源，已有控件状态继续保留
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWindowAppearance()
    }

    /**
     * 统一刷新窗口和自定义业务绘图
     */
    private fun refreshWindowAppearance() {
        if (isFinishing || isDestroyed) return
        GlassWindows.refresh(this, window, dialog = false)
        if (appearanceReady) onAppearanceChanged()
    }

    /**
     * 业务页面在此更新动态绘图，不能重启页面或重建投屏
     */
    protected open fun onAppearanceChanged() = Unit

    /** 自有页面取得焦点后再同步侧栏，避免覆盖顶层地图状态 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val settings =
            this is io.github.shaonialife.buicklinkplus.launcher.SettingsActivity ||
                this is io.github.shaonialife.buicklinkplus.PhoneSettingsActivity ||
                this is io.github.shaonialife.buicklinkplus.SettingsContentActivity
        if (settings || this is io.github.shaonialife.buicklinkplus.launcher.GridActivity) {
            io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
                .onOwnPageResumed(display?.displayId ?: -1, settings)
        }
    }

    /**
     * 释放外观监听，避免持有已经退出的页面
     */
    override fun onDestroy() {
        UiAppearance.removeListener(appearanceListener)
        super.onDestroy()
    }
}

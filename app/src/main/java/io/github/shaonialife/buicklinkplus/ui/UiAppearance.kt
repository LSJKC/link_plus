package io.github.shaonialife.buicklinkplus.ui

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.appcompat.content.res.AppCompatResources
import io.github.shaonialife.buicklinkplus.R
import java.util.concurrent.CopyOnWriteArraySet

/** 按所在显示屏选择主题，手机和车机可使用不同配色 */
object UiAppearance {
    private var application: Application? = null
    private val receiver = ReceiverAppearanceState()
    private val changeVersion =
        java.util.concurrent.atomic
            .AtomicLong()
    val version: Long get() = changeVersion.get()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 监听手机系统配置变化，刷新已显示窗口而不重建业务页面
     */
    fun initialize(app: Application) {
        if (application != null) return
        application = app
        app.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = notifyChanged()

                override fun onLowMemory() {
                    io.github.shaonialife.buicklinkplus.util.Bg
                        .run("glass-trim") { WallpaperStore.trimBackdropCache() }
                }
            },
        )
    }

    /**
     * 当前控制连接开始持有车机外观状态
     */
    fun beginReceiver(connection: Any) {
        if (receiver.begin(connection)) notifyChanged()
    }

    /**
     * 发布当前连接明确提供的日夜通告
     */
    fun updateReceiver(
        connection: Any,
        value: Any?,
    ) {
        if (receiver.update(connection, value)) notifyChanged()
    }

    /**
     * 初始及延迟信息只用于补齐本次连接尚未收到的通告
     */
    fun initializeReceiver(
        connection: Any,
        value: Any?,
    ) {
        if (receiver.initialize(connection, value)) notifyChanged()
    }

    /**
     * 当前连接结束后恢复系统外观
     */
    fun endReceiver(connection: Any) {
        if (receiver.end(connection)) notifyChanged()
    }

    /**
     * 注册窗口或离屏绘图的外观监听，调用方在销毁时对称移除
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /**
     * 移除已销毁窗口的监听
     */
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 兼容暗屏改变时只刷新窗口系统栏，不改变手机或车机的日夜通告
     */
    fun refreshWindows() = notifyChanged()

    /**
     * 根据实际显示屏识别车机窗口，兼容车机竖屏复用手机设置宿主
     */
    fun isCar(context: Context): Boolean =
        runCatching { context.display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true }.getOrDefault(false)

    /**
     * 按窗口解析明暗，系统主题从应用上下文读取
     * 车机优先使用壁纸亮度，未知时回退车机日夜或系统主题
     */
    fun isNight(
        context: Context,
        carDisplay: Boolean = isCar(context),
    ): Boolean {
        val config = (application ?: context.applicationContext).resources.configuration
        val systemNight = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (carDisplay) {
            WallpaperStore.isDarkWallpaper(context.applicationContext)?.let { return it }
        }
        return receiver.isNight(carDisplay, systemNight)
    }

    /**
     * 创建只影响当前资源读取的配置上下文，不改动系统或其他窗口
     */
    private fun themedContext(
        context: Context,
        carDisplay: Boolean = isCar(context),
    ): Context {
        val night = if (isNight(context, carDisplay)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val type = if (carDisplay) Configuration.UI_MODE_TYPE_CAR else Configuration.UI_MODE_TYPE_NORMAL
        val mask = Configuration.UI_MODE_NIGHT_MASK or Configuration.UI_MODE_TYPE_MASK
        if ((context.resources.configuration.uiMode and mask) == (night or type)) return context
        val config = Configuration(context.resources.configuration)
        config.uiMode = config.uiMode and mask.inv() or night or type
        return context.createConfigurationContext(config)
    }

    /**
     * 读取窗口对应的语义颜色
     */
    fun color(
        context: Context,
        resource: Int,
    ): Int = themedContext(context).getColor(resource)

    /**
     * 离屏绘图没有窗口上下文，明确按车机配色读取资源
     */
    fun carColor(
        context: Context,
        resource: Int,
    ): Int = themedContext(context, carDisplay = true).getColor(resource)

    /**
     * 保留选中和禁用状态的颜色列表
     */
    fun colors(
        context: Context,
        resource: Int,
    ): ColorStateList = themedContext(context).getColorStateList(resource)

    /** 从当前主题重载图标和背景，避免旧主题缓存 */
    fun drawable(
        context: Context,
        resource: Int,
    ): Drawable? =
        AppCompatResources.getDrawable(themedContext(context), resource)?.mutate()?.apply {
            if (isCar(context) &&
                resource in
                setOf(
                    R.drawable.bg_card,
                    R.drawable.bg_dialog_panel,
                    R.drawable.bg_console,
                    R.drawable.bg_glass_outline,
                    R.drawable.bg_settings_toast,
                    R.color.glass_stroke,
                )
            ) {
                // 默认磨砂外观保持原强度，向通透调整时同步消除面板与分隔描边
                alpha = (CarAppearancePrefs.opacity(context) * 255 / CarAppearancePrefs.Preset.FROSTED.opacity).coerceIn(0, 255)
            }
        }

    /**
     * 协议线程只投递界面更新，不等待主线程或在读线程处理图片
     */
    private fun notifyChanged() {
        changeVersion.incrementAndGet()
        if (application == null) return
        mainHandler.removeCallbacks(deliverChange)
        mainHandler.post(deliverChange)
    }

    private val deliverChange =
        Runnable {
            listeners.forEach { listener ->
                runCatching { listener() }.onFailure { android.util.Log.w("UiAppearance", "外观刷新失败", it) }
            }
        }
}

/**
 * 自绘控件在颜色变更时刷新画笔，保留动画和交互状态
 */
interface AppearanceAware {
    fun refreshAppearance()
}

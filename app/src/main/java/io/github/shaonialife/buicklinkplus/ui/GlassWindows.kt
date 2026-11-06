package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * 窗口背景与弹窗统一入口，每个窗口只使用一层模糊壁纸
 */
object GlassWindows {
    /**
     * 安装自有壁纸背景，不依赖虚拟屏不稳定的系统跨窗口模糊
     */
    fun install(
        activity: Activity,
        window: Window,
        dialog: Boolean,
        backdrop: Boolean = false,
    ) {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val observed = content.getTag(R.id.glassWindowObserver) == true
        if (observed && content.findViewById<View>(R.id.glassWindowBackdrop) != null) return
        content.setTag(R.id.glassWindowObserver, true)
        if (backdrop || (dialog && UiAppearance.isCar(activity))) {
            if (!dialog) content.getChildAt(0)?.setAppearanceBackground(0)
            if (dialog) clearDialogPanels(content)
            content.addView(
                GlassBackdropView(activity).apply { id = R.id.glassWindowBackdrop },
                0,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        if (dialog && !observed) {
            content.setAppearanceBackground(R.drawable.bg_dialog_panel)
            content.clipToOutline = true
            val listener = { refresh(activity, window, dialog = true) }
            content.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        UiAppearance.addListener(listener)
                        listener()
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        UiAppearance.removeListener(listener)
                    }
                },
            )
            if (content.isAttachedToWindow) UiAppearance.addListener(listener)
        }
        refresh(activity, window, dialog)
    }

    /**
     * 弹窗只保留一个背景和描边，避免原面板叠加后遮住壁纸
     */
    private fun clearDialogPanels(root: View) {
        if (root is ViewGroup) for (index in 0 until root.childCount) clearDialogPanels(root.getChildAt(index))
        AppearanceBindings.clearDialogPanel(root)
    }

    /**
     * 更新窗口系统栏、背景和全部已绑定控件
     */
    fun refresh(
        activity: Activity,
        window: Window,
        dialog: Boolean,
    ) {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        AppearanceBindings.refresh(content)
        if (dialog) {
            content.foreground =
                UiAppearance.drawable(activity, R.drawable.bg_glass_outline)
        }
        if (!dialog) {
            window.setBackgroundDrawable(UiAppearance.color(activity, R.color.m3_surface).toDrawable())
            val dimmed = !UiAppearance.isCar(activity) && ScreenDimmer.isShowing()
            val barColor = if (dimmed) android.graphics.Color.BLACK else UiAppearance.color(activity, R.color.m3_surface)
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            val light = !dimmed && !UiAppearance.isNight(activity)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}

/** 后台加载壁纸，主线程只更新图片和遮罩 */
private class GlassBackdropView(
    context: Context,
) : FrameLayout(context),
    ViewTreeObserver.OnGlobalLayoutListener {
    private val image =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            val radius = 28f * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    private val wash = View(context)
    private val requests = WallpaperRequestState()
    private val retryWallpaper = Runnable { refresh() }
    private val appearanceListener = { refresh() }
    private val wallpaperListener: (Long) -> Unit = { refresh() }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 背景不参与弹窗内容高度测量，布局结束后再填满正文确定的窗口尺寸
     */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        fun exact(spec: Int): Int {
            val size = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
            return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        }
        super.onMeasure(exact(widthMeasureSpec), exact(heightMeasureSpec))
    }

    /**
     * 仅在窗口挂载期间监听外观及壁纸
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        UiAppearance.addListener(appearanceListener)
        WallpaperStore.addListener(wallpaperListener)
        viewTreeObserver.addOnGlobalLayoutListener(this)
        refresh()
    }

    /**
     * 取消旧图片回调并释放窗口引用，共享位图由缓存管理
     */
    override fun onDetachedFromWindow() {
        requests.cancel()
        removeCallbacks(retryWallpaper)
        UiAppearance.removeListener(appearanceListener)
        WallpaperStore.removeListener(wallpaperListener)
        viewTreeObserver.removeOnGlobalLayoutListener(this)
        image.setImageDrawable(null)
        super.onDetachedFromWindow()
    }

    /** 正文确定尺寸后测量背景，位置或尺寸变化时重新裁图，相同请求复用解码结果 */
    override fun onGlobalLayout() {
        val host = parent as? ViewGroup ?: return
        if (host.width > 0 && host.height > 0 && (width != host.width || height != host.height)) {
            measure(
                MeasureSpec.makeMeasureSpec(host.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(host.height, MeasureSpec.EXACTLY),
            )
            layout(0, 0, host.width, host.height)
        }
        refresh()
    }

    /**
     * 按完整画布及实际窗口位置取壁纸，保持车机各窗口同源
     */
    private fun refresh() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        val night = UiAppearance.isNight(context)
        val car = UiAppearance.isCar(context)
        if (car) {
            val shortSide = minOf(VirtualLauncher.streamWidthPx, VirtualLauncher.streamHeightPx).coerceAtLeast(540)
            CarAppearancePrefs.applyBackdrop(image, wash, shortSide / 540f)
        } else {
            wash.setBackgroundColor(UiAppearance.color(context, R.color.glass_backdrop_wash))
        }
        val geometry = if (car) VirtualLauncher.wallpaperGeometry else null
        val area =
            geometry?.let {
                val displayId = context.display?.displayId
                val dock = displayId == VirtualLauncher.dockDisplayId
                val base =
                    WallpaperStore.panelArea(
                        geometry,
                        VirtualLauncher.dockWidthPx,
                        VirtualLauncher.dockHeightPx,
                        VirtualLauncher.dockAtBottom,
                        dock,
                        CarAppearancePrefs.panelOutlineVisible(context),
                    )
                if (displayId == VirtualLauncher.mapDisplayId && !VirtualLauncher.mapFullscreen) {
                    val margin = io.github.shaonialife.buicklinkplus.render.CARD_MARGIN_PX
                    val scale =
                        base.width() /
                            if (VirtualLauncher.dockAtBottom) {
                                geometry.content.width
                            } else {
                                geometry.content.width -
                                    VirtualLauncher.dockWidthPx
                            }
                    RectF(
                        base.left + margin * scale,
                        base.top + margin * scale,
                        base.left + (margin + VirtualLauncher.mapTileWidth) * scale,
                        base.top + (margin + VirtualLauncher.mapTileHeight) * scale,
                    )
                } else {
                    base
                }
            }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val metrics = resources.displayMetrics
        val version = WallpaperStore.version
        val key = "$version:$geometry:$area:${metrics.widthPixels}x${metrics.heightPixels}:${location.toList()}:${width}x$height:$night"
        val request = requests.begin(key, android.os.SystemClock.elapsedRealtime()) ?: return
        val targetWidth = width
        val targetHeight = height
        val displayWidth = metrics.widthPixels
        val displayHeight = metrics.heightPixels
        Bg.run("glass-wallpaper") {
            val bitmap =
                runCatching {
                    WallpaperStore.windowBackdrop(
                        context.applicationContext,
                        targetWidth,
                        targetHeight,
                        displayWidth,
                        displayHeight,
                        location[0],
                        location[1],
                        geometry,
                        area,
                        night,
                    )
                }.getOrNull()
            post {
                if (!isAttachedToWindow || !requests.owns(request)) return@post
                if (WallpaperStore.version != version) {
                    requests.cancel()
                    refresh()
                    return@post
                }
                val applied = bitmap != null && runCatching { image.setImageBitmap(bitmap) }.isSuccess
                val retry = requests.finish(request, applied, android.os.SystemClock.elapsedRealtime())
                if (!applied) VirtualLauncher.logSink?.invoke("WALLPAPER-WINDOW: 解码或应用失败，重试=$retry")
                if (retry) postDelayed(retryWallpaper, 500L)
            }
        }
    }
}
